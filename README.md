# Lab: Concorrência e Reserva Atômica

## 🎯 Objetivo do Laboratório
Este lab propõe-se a testar e validar mecanismos de **controle de concorrência transacional** e **performance de processamento em massa** em cenários de alta volumetria. O foco principal é garantir que a reserva de documentos de carga para um planejamento seja **atômica** e resiliente a conflitos entre múltiplos usuários atuando simultaneamente.

## 🧠 Contexto de Domínio
No processo de planejamento considerado aqui, a seleção envolve até **10.000 documentos de carga** (Notas Fiscais/Pedidos) por operação.
*   **Problema Central:** Múltiplos planejadores podem atuar na mesma região geográfica. Se dois usuários tentarem incluir o mesmo documento em planejamentos diferentes no mesmo milissegundo, o sistema deve impedir a duplicidade.
*   **Restrição de Interface:** O frontend é paginado (exibe 50 itens), mas o requisito de negócio exige um botão **"Selecionar Todos"** que deve atuar sobre milhares de registros no banco de dados "por baixo dos panos".

## 🧪 O que este Lab valida?

### 1. Estratégias de Travamento (Locking)
*   **Optimistic Locking (Otimista):** Validação via versionamento de registros (`@Version`). Testamos se, em um lote de até 10.000 documentos, o custo de falha por colisão (rollback total) é aceitável frente à escalabilidade do banco. [Relatório Técnico de Concorrência]
*   **Pessimistic Locking (Pessimista):** Uso de `SELECT FOR UPDATE`. Validamos o impacto de travar milhares de linhas no banco de dados durante a transação e o risco de *deadlocks* ou lentidão excessiva para outros usuários do mesmo *tenant*. [Relatório Técnico de Concorrência]

### 2. Atomicidade da Reserva
*   Validação do princípio **"Tudo ou Nada"**: Se o usuário tenta reservar 500 notas e apenas 1 delas já estiver vinculada a outro planejamento, a transação inteira deve falhar (Rollback), impedindo a fragmentação da carga.

### 3. Processamento Assíncrono e Massa de Dados
*   Teste de performance da funcionalidade **"Selecionar Todos"**: O backend deve processar filtros complexos e realizar a reserva em massa sem que o frontend precise conhecer todos os IDs dos documentos, mitigando o *mismatch* entre a paginação da UI e o volume do banco.

## 📝 Resumo para LLMs
> Este repositório foca em resolver o problema de concorrência em um sistema de planejamento. O desafio técnico é garantir que a entidade `DocumentoCarga` seja vinculada a apenas um `Planejamento` de forma atômica em operações de lote.
> **Principais regras a considerar:**
> 1. A reserva **não** ocorre no clique da UI, mas na transição de etapa (Ação: "Próximo" que representa reservar os documentos para aquele planejamento).
> 2. O sistema deve suportar falha de um único item invalidando o lote todo.
