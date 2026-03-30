# Lab: Concorrência e Reserva Atômica

## Resumo
Este lab valida como reservar até **10.000 documentos de carga** para um `Planejamento` sem duplicidade, mesmo com múltiplos usuários concorrendo ao mesmo tempo. O foco é garantir **concorrência segura**, **atomicidade do lote** e **boa performance** no fluxo de "Selecionar todos".

## Cenário
- A reserva acontece na ação **"Próximo"**, não no clique inicial da UI.
- O frontend é paginado, mas o backend precisa reservar milhares de registros com base em filtros.
- Se **1 documento** do lote já estiver reservado em outro planejamento, a operação inteira deve falhar.

## O que foi testado

- reserva atomica de ate 10.000 documentos por planejamento;
- concorrencia entre usuarios tentando reservar o mesmo lote;
- comportamento do "Selecionar todos" com filtros no backend;
- impacto de performance na escrita em massa.

## Estrategias testadas

| Estrategia | Como funciona | Principal ponto |
| --- | --- | --- |
| Lock otimista | `@Version` + `saveAll()` | lento em lote grande |
| Lock pessimista | `SELECT ... FOR UPDATE` | impede bloqueio de planejamento com documento já reservado |
| Bulk update + JDBC batch | `UPDATE` em bulk + `INSERT` em batch | melhor performance mesmo em lotes grandes e mantendo a integridade da transacao |

## Resultados obtidos


- Concorrencia: nos testes com **10 e 20 threads**, apenas **1 planejamento** venceu a disputa
- Integridade: o lote de **10.000 documentos** foi reservado sem duplicidade
- Atomicidade: a regra de **tudo ou nada** foi preservada

| Resultado | Evidencia |
| --- | --- |
| Lock otimista com Spring Data JPA | ficou em torno de **5s** a **20s**, variando conforme o acumulo de **dead tuples** no postgres |
| Bulk update + insert via JPA | caiu para algo perto de **~3s** |
| Bulk update + JDBC batch | chegou na faixa de **~100 a 300ms** |

## Conclusao

A estrategia escolhida foi **bulk update + JDBC batch** porque:

- manteve a seguranca de concorrencia;
- preservou o rollback do lote inteiro em caso de conflito;
- evitou o custo de milhares de `UPDATE`s individuais;
- entregou o melhor resultado de performance com complexidade ainda aceitavel.