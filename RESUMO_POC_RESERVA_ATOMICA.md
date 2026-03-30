# Resumo da POC de Reserva Atomica em Massa

## Contexto

Esta POC foi criada para responder uma duvida pratica do projeto: **como reservar milhares de documentos de carga para um planejamento sem permitir duplicidade entre planejamentos concorrentes e sem degradar a performance da aplicacao**.

O cenario alvo e o fluxo de planejamento em que:

- um usuario pode atuar sobre ate **10.000 documentos** por operacao;
- o frontend e paginado, mas o negocio exige um comportamento de **"Selecionar todos"**;
- a reserva precisa acontecer no backend com base nos **filtros da busca**, e nao a partir de uma lista completa de IDs enviada pela UI;
- se qualquer documento do lote ja tiver sido reservado por outro planejamento, o resultado esperado e **rollback total**.

Em outras palavras, a POC valida se o sistema consegue atender ao requisito de **atomicidade + concorrencia + processamento em massa**.

## O que a POC se propoe a testar

Os testes e experimentos deste laboratorio buscaram responder principalmente estas perguntas:

1. **Como garantir exclusividade de reserva?**
   Um mesmo `DocumentoCarga` nao pode ser vinculado a dois planejamentos ao mesmo tempo.

2. **Como manter a regra de tudo ou nada?**
   Se o usuario tentar reservar 1000 documentos e apenas 1 deles ja nao estiver mais disponivel, a operacao inteira deve falhar.

3. **Como suportar o "Selecionar todos" em massa?**
   O backend precisa processar os filtros diretamente no banco, sem depender de o frontend conhecer todos os registros da selecao.

4. **Qual estrategia entrega o melhor equilibrio entre seguranca e performance?**
   Nao basta funcionar em concorrencia; a solucao precisa ser viavel para producao.

## Abordagens testadas

### 1. Lock otimista com JPA (`@Version`)

Fluxo testado:

- selecionar os documentos disponiveis;
- alterar o status em memoria;
- executar `saveAll()` dos documentos;
- forcar `flush()` para antecipar conflito de versao;
- executar `saveAll()` dos itens do planejamento.

**Vantagem**

- modelo simples e aderente ao uso tradicional de JPA/Hibernate;
- conflito de concorrencia e detectado pela versao do registro.

**Limite observado**

- para lotes grandes, o custo de `saveAll()` + `flush()` fica alto;
- o Hibernate executa muitos `UPDATE`s individuais e faz dirty-checking de muitas entidades;
- quando ha conflito, a falha acontece tarde e o rollback do lote inteiro fica caro.

### 2. Lock pessimista com `SELECT ... FOR UPDATE`

Fluxo testado:

- selecionar os documentos ja aplicando lock no banco;
- atualizar status;
- salvar os itens do planejamento.

**Vantagem**

- reduz a chance de corrida porque o bloqueio acontece antes da escrita.

**Limite observado**

- bloqueia muitas linhas durante a transacao;
- aumenta risco de contencao, espera e possiveis deadlocks;
- nao parece uma boa estrategia para um fluxo com alto volume e varios usuarios no mesmo tenant.

### 3. Bulk update com validacao CAS

Fluxo testado:

- selecionar os documentos elegiveis;
- executar **um unico `UPDATE` nativo** para reservar todos os IDs;
- validar o resultado pelo numero de linhas afetadas;
- inserir os `PlanejamentoItem` ainda via JPA.

CAS aqui significa, na pratica:

- so atualiza quem ainda esta com status `DISPONIVEL`;
- se o banco atualizar menos linhas do que o esperado, houve conflito;
- nesse caso, a transacao falha e todo o lote e revertido.

**Vantagem**

- remove o gargalo dos `UPDATE`s individuais;
- mantem a seguranca de concorrencia sem depender do Hibernate detectar conflito item a item.

**Limite observado**

- o ganho nos `UPDATE`s foi grande, mas os `INSERT`s dos itens ainda continuaram caros com `saveAll()`.

### 4. Bulk update + batch insert com JDBC

Fluxo testado:

- selecionar os documentos;
- reservar todos via `UPDATE` nativo em bulk com validacao CAS;
- inserir os `PlanejamentoItem` com `JdbcTemplate.batchUpdate()`.

**Vantagem**

- elimina o custo mais alto do caminho JPA para insercao em massa;
- aproveita melhor o driver PostgreSQL com `reWriteBatchedInserts=true`;
- reduz bastante o tempo total da operacao.

**Limite observado**

- exige um pouco mais de codigo de infraestrutura do que a abordagem puramente JPA;
- mesmo assim, continua simples o bastante para manutencao no projeto oficial.

## Resultados colhidos

### Resultado funcional

Nos testes de concorrencia com **10 e 20 threads simultaneas**, o comportamento esperado foi preservado:

- **apenas 1 planejamento venceu** a disputa;
- os **1000 documentos** ficaram reservados uma unica vez;
- nao houve duplicidade em `planejamento_item`;
- as abordagens testadas conseguiram manter a regra de exclusividade.

Tambem houve validacao de **drift entre leitura e escrita**:

- quando os dados mudaram entre o `SELECT` e o `UPDATE`, o caminho otimista detectou o conflito;
- nenhum `PlanejamentoItem` foi inserido nesse cenario, confirmando o rollback da operacao.

### Resultado de performance

O principal achado da POC foi que o gargalo nao estava apenas na busca dos documentos, mas principalmente no custo de escrita em massa usando JPA/Hibernate.

Consolidado do laboratorio para um lote de aproximadamente **1000 documentos**:

| Estrategia | Resultado consolidado |
| --- | --- |
| Lock otimista com `saveAll()` | cerca de **6,5s** no endpoint `/api/reserva/otimista` |
| Bulk update + insert via JPA | melhora relevante nos updates, mas total ainda em torno de **~3s** |
| Bulk update + batch insert via JDBC | faixa estimada de **~100 a 300ms** |

Aprendizados importantes:

- `reWriteBatchedInserts=true` ajuda muito nos **INSERTs**, mas **nao resolve os UPDATEs** com `@Version`;
- `saveAll()` em entidades existentes continua gerando muito custo para lotes grandes;
- `batch_size=10000` mostrou-se agressivo demais; valores menores tendem a ser mais saudaveis;
- a combinacao de **1 update em bulk + inserts em batch** entrega um salto de performance muito maior do que apenas ajustar configuracoes do Hibernate.

## Por que a estrategia final foi escolhida

A estrategia final recomendada foi a do endpoint **`/api/reserva/otimista-jdbc`**, que combina:

- **bulk update com validacao CAS** para reservar os documentos;
- **batch insert com JDBC** para criar os itens do planejamento.

Ela foi escolhida porque oferece o melhor equilibrio entre os pontos que mais importam para o projeto oficial:

### 1. Mantem a regra de negocio

Se o numero de linhas atualizadas for menor do que o esperado, significa que algum documento foi reservado por outro processo no meio do caminho. Nesse caso, a transacao falha e o lote inteiro e revertido.

Ou seja: a regra de **tudo ou nada** continua preservada.

### 2. Escala melhor para operacoes em massa

Em vez de fazer centenas ou milhares de `UPDATE`s individuais, a reserva dos documentos passa a ser feita em uma unica operacao SQL. O mesmo raciocinio vale para os inserts, que deixam de depender do custo de persistencia entidade por entidade.

### 3. Evita o custo operacional do lock pessimista

A abordagem final protege a concorrencia sem precisar manter milhares de linhas bloqueadas durante toda a transacao, o que reduz a chance de degradar a experiencia de outros usuarios.

### 4. Continua simples de manter

Comparada a alternativas mais agressivas, como `COPY` nativo do PostgreSQL, essa estrategia entrega alto ganho de performance sem aumentar demais a complexidade da implementacao.

## Conclusao pratica para o projeto oficial

Se a implementacao oficial precisar suportar reserva em massa com filtros e concorrencia real entre usuarios, a recomendacao e:

1. deixar a selecao no frontend apenas como intencao do usuario;
2. recalcular a reserva no backend a partir dos filtros;
3. usar uma escrita atomica que valide conflito no proprio banco;
4. inserir os itens do planejamento em batch;
5. tratar qualquer divergencia como falha do lote inteiro.

Em resumo, a POC mostrou que:

- **o problema de concorrencia foi resolvido** pelas estrategias testadas;
- **o diferencial decisivo foi a performance**;
- por isso, a estrategia final escolhida foi a combinacao de **bulk update + JDBC batch**.
