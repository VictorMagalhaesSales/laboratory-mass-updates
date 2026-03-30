# Resumo rapido da POC

## O que foi testado

- reserva atomica de ate 10.000 documentos por planejamento;
- concorrencia entre usuarios tentando reservar o mesmo lote;
- comportamento do "Selecionar todos" com filtros no backend;
- impacto de performance na escrita em massa.

## Estrategias testadas

| Estrategia | Como funciona | Principal ponto |
| --- | --- | --- |
| Lock otimista | `@Version` + `saveAll()` | seguro, mas lento em lote grande |
| Lock pessimista | `SELECT ... FOR UPDATE` | protege concorrencia, mas bloqueia muitas linhas |
| Bulk update | 1 `UPDATE` nativo + validacao por linhas afetadas | muito melhor para reservar em massa |
| Bulk update + JDBC batch | `UPDATE` em bulk + `INSERT` em batch | melhor equilibrio entre seguranca e performance |

## Resultados obtidos

- nos testes com **10 e 20 threads**, apenas **1 planejamento** venceu a disputa;
- o lote de **10.000 documentos** foi reservado sem duplicidade;
- a regra de **tudo ou nada** foi preservada;
- o lock otimista puro ficou em torno de **6,5s** na medicao registrada;
- bulk update + insert via JPA caiu para algo perto de **~3s**;
- bulk update + JDBC batch chegou na faixa de **~100 a 300ms**.

## Conclusao

A estrategia escolhida foi **bulk update + JDBC batch** porque:

- manteve a seguranca de concorrencia;
- preservou o rollback do lote inteiro em caso de conflito;
- evitou o custo de milhares de `UPDATE`s individuais;
- entregou o melhor resultado de performance com complexidade ainda aceitavel.
