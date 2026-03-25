# Guia de Execucao do Laboratorio

## Pre-requisitos

- Java 17+
- Maven 3.8+
- Docker e Docker Compose
- Um terminal (bash, PowerShell ou cmd)
- (Opcional) cURL ou Postman para chamadas HTTP

---

## Etapa 1 - Subir o banco de dados

O laboratorio usa PostgreSQL 15 via Docker Compose, exposto na porta **5433** do host.

```bash
docker compose up -d
```

Aguarde alguns segundos e valide que o container esta saudavel:

```bash
docker exec tms-lab-db pg_isready -U postgres
```

Saida esperada: `localhost:5432 - accepting connections`

> **pgAdmin** fica disponivel em `http://localhost:5050` (login: `admin@admin.com` / `admin`).
> Para registrar o servidor, use o host `tms-lab-db`, porta `5432`, user/senha `postgres/postgres`.

---

## Etapa 2 - Criar as tabelas

Conecte no banco e execute o script de schema:

```bash
docker exec -i tms-lab-db psql -U postgres -d tms < src/main/resources/migrations/schema.sql
```

Valide que as tres tabelas foram criadas:

```bash
docker exec tms-lab-db psql -U postgres -d tms -c "\dt"
```

Saida esperada:

```
              List of relations
 Schema |       Name        | Type  |  Owner
--------+-------------------+-------+----------
 public | documento_carga   | table | postgres
 public | planejamento      | table | postgres
 public | planejamento_item | table | postgres
```

### Ponto importante: a constraint de unicidade

A tabela `planejamento_item` possui a constraint `unique_documento_reserva` sobre `documento_id`. Isso impede que um mesmo documento apareca em dois planejamentos diferentes, servindo como ultima barreira de consistencia no banco.

Confirme:

```bash
docker exec tms-lab-db psql -U postgres -d tms -c "\d planejamento_item"
```

Procure pela linha: `"unique_documento_reserva" UNIQUE, btree (documento_id)`

---

## Etapa 3 - Popular a massa de dados

Execute o script de inserts que gera **500.000 documentos** distribuidos entre 5 regioes:

```bash
docker exec -i tms-lab-db psql -U postgres -d tms < src/main/resources/migrations/inserts.sql
```

> Este insert usa `generate_series` e leva poucos segundos.

Valide a massa:

```bash
docker exec tms-lab-db psql -U postgres -d tms -c "
  SELECT regiao, COUNT(*) as qtd, 
         ROUND(SUM(peso)::numeric, 2) as peso_total,
         ROUND(SUM(valor)::numeric, 2) as valor_total
  FROM documento_carga 
  GROUP BY regiao 
  ORDER BY regiao;
"
```

Crie tambem os planejamentos que serao usados nos testes de reserva:

```bash
docker exec tms-lab-db psql -U postgres -d tms -c "
  INSERT INTO planejamento (usuario_id, status) 
  SELECT gs, 'DRAFT' FROM generate_series(1, 10) gs;
"
```

Confirme:

```bash
docker exec tms-lab-db psql -U postgres -d tms -c "SELECT * FROM planejamento;"
```

---

## Etapa 4 - Compilar e subir a aplicacao

```bash
mvn clean compile
mvn spring-boot:run
```

A aplicacao sobe na porta **8080**. Procure no log:

```
Started TmsMassUpdatesApplication in X seconds
```

---

## Etapa 5 - Consultar documentos disponiveis

Este endpoint simula a tela paginada do planejador, retornando documentos + somatorios.

### Sem filtro (primeiros 50):

```bash
curl -s "http://localhost:8080/api/documentos/disponiveis" | python -m json.tool
```

### Filtrando por regiao e limitando pagina:

```bash
curl -s "http://localhost:8080/api/documentos/disponiveis?regiao=SUL&tamanhoPagina=10" | python -m json.tool
```

### Com filtro de faixa de valor:

```bash
curl -s "http://localhost:8080/api/documentos/disponiveis?regiao=NORTE&valorMinimo=10000&valorMaximo=50000&tamanhoPagina=5" | python -m json.tool
```

#### O que observar na resposta:

- `sumario.totalDocumentos`: total de documentos que atendem ao filtro (nao apenas os da pagina)
- `sumario.pesoTotal`, `sumario.valorTotal`, `sumario.volumeTotal`: somatorios globais do filtro
- `pagina` e `tamanhoPagina`: controle de paginacao

---

## Etapa 6 - Testar reserva individual (sem concorrencia)

### Reserva com Lock Otimista

```bash
curl -s -X POST http://localhost:8080/api/reserva/otimista \
  -H "Content-Type: application/json" \
  -d '{
    "filtro": {
      "regiao": "NORTE",
      "limit": 100
    },
    "planejamentoId": 1
  }' | python -m json.tool
```

### Reserva com Lock Pessimista

```bash
curl -s -X POST http://localhost:8080/api/reserva/pessimista \
  -H "Content-Type: application/json" \
  -d '{
    "filtro": {
      "regiao": "LESTE",
      "limit": 100
    },
    "planejamentoId": 2
  }' | python -m json.tool
```

#### O que observar na resposta:

```json
{
  "planejamentoId": 1,
  "documentosReservados": 100,
  "tempoBuscaMs": 45,
  "tempoInsercaoMs": 320,
  "tempoTotalMs": 370,
  "sucesso": true,
  "mensagem": "Reserva realizada com sucesso."
}
```

- `tempoBuscaMs`: tempo gasto na query de selecao do lote
- `tempoInsercaoMs`: tempo gasto no update de status + insert em `planejamento_item` + flush
- `tempoTotalMs`: tempo total da transacao

#### Validar no banco:

```bash
docker exec tms-lab-db psql -U postgres -d tms -c "
  SELECT pi.planejamento_id, COUNT(*) as itens
  FROM planejamento_item pi
  GROUP BY pi.planejamento_id;
"
```

```bash
docker exec tms-lab-db psql -U postgres -d tms -c "
  SELECT status, COUNT(*) FROM documento_carga GROUP BY status;
"
```

---

## Etapa 7 - Testar concorrencia via API (cenario real)

Para simular 10 usuarios concorrentes pela API, use o script abaixo. Cada um tenta reservar os mesmos 1.000 documentos da regiao SUL:

> **Importante:** Antes de rodar, limpe os dados de testes anteriores para garantir que ha documentos disponiveis:

```bash
docker exec tms-lab-db psql -U postgres -d tms -c "
  DELETE FROM planejamento_item;
  UPDATE documento_carga SET status = 'DISPONIVEL', versao = 1;
"
```

### Script bash de concorrencia (10 chamadas paralelas):

```bash
for i in $(seq 1 10); do
  curl -s -X POST http://localhost:8080/api/reserva/otimista \
    -H "Content-Type: application/json" \
    -d "{
      \"filtro\": { \"regiao\": \"SUL\", \"limit\": 1000 },
      \"planejamentoId\": $i
    }" &
done
wait
echo "Todas as requisicoes finalizaram."
```

> Para testar com lock pessimista, substitua `/otimista` por `/pessimista` no endpoint.

### O que observar nos logs da aplicacao:

1. **Lock Otimista** - procure por:
   - `[OTIMISTA][pool-X-thread-Y] CONFLITO DE VERSAO` -- indica que o `@Version` detectou que outro thread ja alterou o documento
   - `ObjectOptimisticLockingFailureException` -- a excecao que causa o rollback total
   - Apenas **1** thread deve mostrar `Reserva concluida com sucesso`

2. **Lock Pessimista** - procure por:
   - `[PESSIMISTA][pool-X-thread-Y] Busca com lock concluida` -- cada thread trava as linhas com `FOR UPDATE`
   - Como o lock serializa o acesso, as threads executam sequencialmente
   - A primeira thread reserva; as demais encontram 0 documentos disponiveis

### Validacao final no banco:

```bash
docker exec tms-lab-db psql -U postgres -d tms -c "
  SELECT 
    (SELECT COUNT(*) FROM planejamento_item) as total_itens,
    (SELECT COUNT(DISTINCT planejamento_id) FROM planejamento_item) as planejamentos_com_itens,
    (SELECT COUNT(*) FROM documento_carga WHERE status = 'RESERVADO') as docs_reservados,
    (SELECT COUNT(*) FROM (
      SELECT documento_id FROM planejamento_item 
      GROUP BY documento_id HAVING COUNT(*) > 1
    ) dup) as duplicatas;
"
```

Resultado esperado:

```
 total_itens | planejamentos_com_itens | docs_reservados | duplicatas
-------------+-------------------------+-----------------+------------
        1000 |                       1 |            1000 |          0
```

- `total_itens = 1000`: apenas os documentos do vencedor foram vinculados
- `planejamentos_com_itens = 1`: somente 1 planejamento venceu a disputa
- `docs_reservados = 1000`: todos os documentos do lote estao marcados como RESERVADO
- `duplicatas = 0`: nenhum documento aparece em mais de um planejamento

---

## Etapa 8 - Executar os testes automatizados

Os testes de integracao usam o banco PostgreSQL do Docker Compose (mesma porta 5433). Certifique-se de que o container esta rodando.

```bash
mvn test
```

### O que os testes validam:

| Teste | Estrategia | Verificacoes |
|-------|-----------|-------------|
| `deveReservarComLockOtimista_apenasUmVencedor` | `@Version` (otimista) | 1 sucesso, 9 falhas por `ObjectOptimisticLockingFailureException`, 1000 itens no vencedor, 0 duplicatas |
| `deveReservarComLockPessimista_apenasUmVencedor` | `FOR UPDATE` (pessimista) | 1 sucesso, 9 falhas (0 documentos disponiveis apos lock), 1000 itens no vencedor, 0 duplicatas |

Cada teste recria as tabelas e popula 1.000 documentos + 10 planejamentos antes de executar, garantindo isolamento.

Saida esperada:

```
=== RESULTADO DA SIMULACAO ===
Sucessos: 1
Falhas: 9
  Planejamento 1: sucesso=false, docs=0, ...
  Planejamento 2: sucesso=true, docs=1000, msg=Reserva realizada com sucesso.
  ...

Tests run: 3, Failures: 0, Errors: 0, Skipped: 0
BUILD SUCCESS
```

---

## Etapa 9 - Limpeza e reset para novos testes

Para resetar o banco e rodar novos cenarios:

```bash
docker exec tms-lab-db psql -U postgres -d tms -c "
  DELETE FROM planejamento_item;
  UPDATE documento_carga SET status = 'DISPONIVEL', versao = 1;
"
```

Para destruir tudo e recomecar do zero:

```bash
docker compose down -v
```

---

## Resumo dos endpoints

| Metodo | Endpoint | Descricao |
|--------|----------|-----------|
| GET | `/api/documentos/disponiveis` | Lista paginada + somatorios de documentos nao reservados |
| POST | `/api/reserva/otimista` | Reserva atomica com lock otimista (`@Version`) |
| POST | `/api/reserva/pessimista` | Reserva atomica com lock pessimista (`FOR UPDATE`) |

## Resumo dos filtros disponiveis

| Parametro | Tipo | Descricao |
|-----------|------|-----------|
| `regiao` | String | NORTE, SUL, LESTE, OESTE, CENTRO |
| `pesoMinimo` / `pesoMaximo` | BigDecimal | Faixa de peso |
| `valorMinimo` / `valorMaximo` | BigDecimal | Faixa de valor |
| `volumeMinimo` / `volumeMaximo` | BigDecimal | Faixa de volume |
| `limit` | Integer | Quantidade maxima de documentos a reservar |

---

## Pontos-chave para analise

1. **Atomicidade**: se qualquer documento do lote falhar, a transacao inteira sofre rollback. Nenhum item parcial e inserido.
2. **`@Version` vs `FOR UPDATE`**: o lock otimista e mais escalavel mas gera trabalho jogado fora (a thread faz toda a operacao e so descobre o conflito no flush). O lock pessimista serializa e evita trabalho desperdicado, mas pode causar contenção se o lote for muito grande.
3. **Constraint `UNIQUE(documento_id)`**: funciona como rede de seguranca final no banco. Mesmo que a logica da aplicacao falhasse, o banco impediria a duplicidade.
4. **Logs de tempo**: compare `tempoBuscaMs` e `tempoInsercaoMs` entre as duas estrategias para entender o custo de cada abordagem com volumes diferentes.
