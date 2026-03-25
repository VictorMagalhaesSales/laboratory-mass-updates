# Análise de Performance — API `/api/reserva/otimista`

## Situação atual

A API `POST /api/reserva/otimista` leva em média **6.5 segundos** para processar ~1000 documentos.
Esse tempo é inaceitável para cenários de concorrência em produção.

---

## Fluxo atual (passo a passo)

```
1. SELECT nativo  → documento_carga WHERE status = 'DISPONIVEL' (+ filtros)
2. Em memória     → doc.setStatus(RESERVADO) para cada documento
3. saveAll()      → documentoRepo.saveAll(documentos)  ← UPDATEs individuais via Hibernate
4. flush()        → força detecção de OptimisticLockingFailureException
5. saveAll()      → planejamentoItemRepo.saveAll(itens) ← INSERTs individuais via Hibernate
```

---

## Configuração atual (application.yaml)

```yaml
hibernate:
  jdbc:
    batch_size: 10000
  order_inserts: true
  order_updates: true

datasource:
  url: jdbc:postgresql://...?reWriteBatchedInserts=true
```

Apesar de configurada, a combinação de fatores abaixo anula ou reduz o ganho do batching.

---

## Diagnóstico: por que `saveAll` é lento?

### Problema 1 — `saveAll` gera N statements individuais

`JpaRepository.saveAll()` itera internamente chamando `save(entity)` para cada entidade.
Para cada `save()`:

- **Se a entidade é existente** (`DocumentoCarga` com `id != null`): chama `entityManager.merge()`.
  O Hibernate precisa fazer dirty-checking, gerar um UPDATE com `WHERE id = ? AND versao = ?` (por causa do `@Version`), e verificar que exatamente 1 row foi afetada.

- **Se a entidade é nova** (`PlanejamentoItem` com `@Tsid`): chama `entityManager.persist()`.
  O Hibernate gera um INSERT. Com `order_inserts=true` e `batch_size=10000`, os INSERTs são agrupados no flush, e `reWriteBatchedInserts=true` permite ao driver PostgreSQL reescrever em multi-value INSERT.

Para **1000 documentos**, isso gera:
- **1000 UPDATEs** individuais em `documento_carga` (cada um com verificação de versão)
- **1000 INSERTs** individuais em `planejamento_item`

### Problema 2 — UPDATEs com `@Version` não se beneficiam de `reWriteBatchedInserts`

O parâmetro `reWriteBatchedInserts=true` do driver PostgreSQL **só reescreve INSERTs** em multi-value statements.
Para UPDATEs, cada statement continua sendo enviado individualmente ao banco, resultando em N round-trips de rede.

Mesmo com `hibernate.jdbc.batch_size`, os UPDATEs são executados via `PreparedStatement.executeBatch()`, que para PostgreSQL significa enviar cada UPDATE separadamente (a menos que se use pipelining).

### Problema 3 — Dirty-checking de 1000+ entidades no Persistence Context

O Hibernate mantém uma cópia "snapshot" de cada entidade gerenciada. No `flush()`, ele compara campo a campo cada entidade com seu snapshot para detectar mudanças. Com 1000+ entidades no contexto, isso consome CPU e memória.

### Problema 4 — `batch_size: 10000` é excessivo

Um batch_size de 10.000 pode causar:
- Statements SQL enormes que o PostgreSQL precisa parsear de uma vez
- Alto consumo de memória no driver JDBC
- Potencial timeout ou OOM em lotes maiores

Valor recomendado: **50 a 200**.

---

## Soluções possíveis (da menos para a mais invasiva)

### 1. UPDATE nativo em bulk (como já existe no path `/bulk`)

**Impacto:** elimina os 1000 UPDATEs individuais por **1 único UPDATE**.

```sql
UPDATE documento_carga
SET status = 'RESERVADO', versao = versao + 1
WHERE id IN (:ids) AND status = 'DISPONIVEL'
```

O método `reservarEmBulk()` já implementa isso. A verificação de concorrência é feita via CAS (Compare-And-Swap): se `rowsAffected != ids.size()`, outro usuário já reservou algum documento.

**Trade-off:** perde-se a detecção granular do `@Version` do Hibernate (qual entidade conflitou), mas ganha-se em performance e a validação de concorrência continua existindo via CAS.

**Ganho estimado:** de ~3-4s para ~10-50ms nos UPDATEs.

---

### 2. Batch INSERT com JdbcTemplate para `PlanejamentoItem`

**Impacto:** substitui `saveAll()` do JPA por `JdbcTemplate.batchUpdate()`.

```java
jdbcTemplate.batchUpdate(
    "INSERT INTO planejamento_item (id, planejamento_id, documento_id) VALUES (?, ?, ?)",
    itens, BATCH_SIZE, (ps, item) -> {
        ps.setLong(1, TsidFactory.create().toLong());
        ps.setLong(2, item.getPlanejamentoId());
        ps.setLong(3, item.getDocumentoId());
    });
```

Combinado com `reWriteBatchedInserts=true` no driver, o JDBC reescreve os inserts em blocos multi-value:
```sql
INSERT INTO planejamento_item (id, planejamento_id, documento_id)
VALUES (?,?,?), (?,?,?), (?,?,?), ...  -- até BATCH_SIZE valores
```

**Ganho estimado:** de ~2-3s para ~50-200ms nos INSERTs.

---

### 3. PostgreSQL COPY API (máxima performance para INSERTs)

O comando `COPY` do PostgreSQL é a forma mais rápida de inserir dados em massa.
Através do `CopyManager` do driver PgJDBC:

```java
CopyManager copyManager = connection.unwrap(PgConnection.class).getCopyAPI();
copyManager.copyIn(
    "COPY planejamento_item (id, planejamento_id, documento_id) FROM STDIN WITH (FORMAT csv)",
    inputStream
);
```

**Ganho estimado:** de ~2-3s para ~10-50ms nos INSERTs.

**Trade-off:** mais complexo de implementar, precisa de controle manual da conexão e do stream. Ideal para volumes acima de 10.000 registros.

---

### 4. Ajustar `batch_size` para valor sensato

Reduzir de 10.000 para **50-100**. Isso:
- Reduz consumo de memória por batch
- Permite flushes intermediários menores
- Mantém boa performance sem riscos de OOM

---

### 5. Implementar `Persistable<Long>` nas entidades com `@Tsid`

Quando o Hibernate recebe uma entidade com `id != null` no `save()`, ele assume que é uma entidade existente e chama `merge()` (que pode fazer um SELECT antes do INSERT/UPDATE).

Implementando `Persistable`, a entidade controla seu próprio `isNew()`:

```java
@Entity
public class PlanejamentoItem implements Persistable<Long> {
    @Transient
    private boolean isNew = true;

    @Override
    public boolean isNew() { return isNew; }

    @PostLoad @PostPersist
    void markNotNew() { isNew = false; }
}
```

Isso faz com que `saveAll()` use `persist()` em vez de `merge()`, evitando SELECTs desnecessários.

---

### 6. Flush + Clear periódico (para volumes muito grandes)

Para lotes com milhares de entidades, limpar o persistence context periodicamente evita degradação:

```java
for (int i = 0; i < itens.size(); i++) {
    em.persist(itens.get(i));
    if (i % 100 == 0) {
        em.flush();
        em.clear();
    }
}
```

**Cuidado:** `em.clear()` desanexa TODAS as entidades do contexto. Use com cuidado se outras entidades gerenciadas precisam continuar attached.

---

## Comparativo de performance esperada (1000 documentos)

| Estratégia                          | UPDATEs (ms) | INSERTs (ms) | Total estimado |
|-------------------------------------|-------------|-------------|----------------|
| Atual (saveAll + @Version)          | ~3000-4000  | ~2000-3000  | **~6500ms**    |
| Bulk UPDATE + saveAll (path /bulk)  | ~10-50      | ~2000-3000  | **~3000ms**    |
| Bulk UPDATE + JdbcTemplate batch    | ~10-50      | ~50-200     | **~100-300ms** |
| Bulk UPDATE + COPY API              | ~10-50      | ~10-50      | **~50-150ms**  |

---

## Recomendação

A combinação de **UPDATE nativo em bulk** (já implementado) + **JdbcTemplate batch INSERT** para `PlanejamentoItem` oferece o melhor equilíbrio entre:

- **Performance:** redução de ~6.5s para ~100-300ms (20-65x mais rápido)
- **Simplicidade:** não exige dependências extras, usa APIs padrão do Spring
- **Segurança de concorrência:** CAS no UPDATE garante detecção de conflitos
- **Manutenibilidade:** código claro e testável

O novo endpoint **`POST /api/reserva/otimista-jdbc`** implementa essa estratégia.
