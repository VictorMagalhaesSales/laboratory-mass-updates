package com.victor.labs.tms_mass_updates;

import com.victor.labs.tms_mass_updates.dto.FiltroDTO;
import com.victor.labs.tms_mass_updates.dto.ReservaResultDTO;
import com.victor.labs.tms_mass_updates.service.ReservaService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.jdbc.Sql;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
@Sql(scripts = {"classpath:test-schema.sql", "classpath:test-data.sql"},
        executionPhase = Sql.ExecutionPhase.BEFORE_TEST_METHOD)
class ReservaConcorrenciaTest {

    @Autowired
    private ReservaService reservaService;

    @Autowired
    private JdbcTemplate jdbc;

    private FiltroDTO filtro;

    @BeforeEach
    void setUp() {
        filtro = new FiltroDTO();
        filtro.setRegiao("SUL");
    }

    // ───────────────────────────────────────────────────────────────────
    // Testes existentes (10 threads)
    // ───────────────────────────────────────────────────────────────────

    @Test
    void deveReservarComLockOtimista_apenasUmVencedor() {
        List<ReservaResultDTO> resultados = executarConcorrencia(10, "OTIMISTA");
        validarResultadoConcorrencia(resultados, 10);
    }

    @Test
    void deveReservarComLockPessimista_apenasUmVencedor() {
        List<ReservaResultDTO> resultados = executarConcorrencia(10, "PESSIMISTA");
        validarResultadoConcorrencia(resultados, 10);
    }

    // ───────────────────────────────────────────────────────────────────
    // Bulk UPDATE (3a estratégia) — 10 threads
    // ───────────────────────────────────────────────────────────────────

    @Test
    void deveReservarComBulkUpdate_apenasUmVencedor() {
        List<ReservaResultDTO> resultados = executarConcorrencia(10, "BULK");
        validarResultadoConcorrencia(resultados, 10);
    }

    // ───────────────────────────────────────────────────────────────────
    // Drift: dados mudam entre SELECT e UPDATE (otimista)
    // ───────────────────────────────────────────────────────────────────

    @Test
    void deveDetectarDriftEntreSelecaoEReserva_otimista() throws Exception {
        List<Long> planIds = getPlanejamentoIds();
        CountDownLatch afterSelect = new CountDownLatch(1);
        CountDownLatch beforeSave = new CountDownLatch(1);

        CompletableFuture<ReservaResultDTO> threadA = CompletableFuture.supplyAsync(() -> {
            try {
                return reservaService.reservarComLockOtimistaComDrift(
                        filtro, planIds.get(0), afterSelect, beforeSave);
            } catch (Exception e) {
                return new ReservaResultDTO(planIds.get(0), 0, 0, 0, 0, false,
                        e.getClass().getSimpleName() + ": " + e.getMessage());
            }
        });

        assertTrue(afterSelect.await(5, TimeUnit.SECONDS),
                "Thread A deveria ter concluído o SELECT em até 5s");

        jdbc.update("UPDATE documento_carga SET status = 'RESERVADO', versao = versao + 1 WHERE regiao = 'SUL'");

        beforeSave.countDown();

        ReservaResultDTO result = threadA.get(10, TimeUnit.SECONDS);

        System.out.println("=== RESULTADO DO DRIFT ===");
        System.out.printf("  sucesso=%b, msg=%s%n", result.isSucesso(), result.getMensagem());

        assertFalse(result.isSucesso(), "Deve falhar devido ao drift entre SELECT e UPDATE");
        assertTrue(result.getMensagem().contains("OptimisticLockingFailureException"),
                "Deve reportar conflito de versão");

        Long docsReservados = jdbc.queryForObject(
                "SELECT COUNT(*) FROM documento_carga WHERE status = 'RESERVADO'", Long.class);
        assertEquals(1000L, docsReservados, "Todos os docs devem estar RESERVADO pela modificação externa");

        Long totalItens = jdbc.queryForObject("SELECT COUNT(*) FROM planejamento_item", Long.class);
        assertEquals(0L, totalItens, "Nenhum item deve ter sido inserido (rollback)");
    }

    // ───────────────────────────────────────────────────────────────────
    // Validação de quantidade esperada
    // ───────────────────────────────────────────────────────────────────

    @Test
    void deveRejeitarReserva_quandoQuantidadeDivergenteOtimista() {
        List<Long> planIds = getPlanejamentoIds();

        IllegalStateException ex = assertThrows(IllegalStateException.class,
                () -> reservaService.reservarComLockOtimista(filtro, planIds.get(0), 999));

        assertTrue(ex.getMessage().contains("Quantidade de documentos divergente"),
                "Mensagem deve indicar divergência de quantidade");
        assertTrue(ex.getMessage().contains("999"),
                "Mensagem deve conter a quantidade esperada");
        assertTrue(ex.getMessage().contains("1000"),
                "Mensagem deve conter a quantidade encontrada");

        Long docsReservados = jdbc.queryForObject(
                "SELECT COUNT(*) FROM documento_carga WHERE status = 'RESERVADO'", Long.class);
        assertEquals(0L, docsReservados, "Nenhum documento deve ter sido reservado");

        Long totalItens = jdbc.queryForObject("SELECT COUNT(*) FROM planejamento_item", Long.class);
        assertEquals(0L, totalItens, "Nenhum item deve ter sido inserido");
    }

    @Test
    void deveRejeitarReserva_quandoQuantidadeDivergentePessimista() {
        List<Long> planIds = getPlanejamentoIds();

        IllegalStateException ex = assertThrows(IllegalStateException.class,
                () -> reservaService.reservarComLockPessimista(filtro, planIds.get(0), 999));

        assertTrue(ex.getMessage().contains("Quantidade de documentos divergente"),
                "Mensagem deve indicar divergência de quantidade");
    }

    @Test
    void deveRejeitarReserva_quandoQuantidadeDivergenteBulk() {
        List<Long> planIds = getPlanejamentoIds();

        IllegalStateException ex = assertThrows(IllegalStateException.class,
                () -> reservaService.reservarComBulkUpdate(filtro, planIds.get(0), 999));

        assertTrue(ex.getMessage().contains("Quantidade de documentos divergente"),
                "Mensagem deve indicar divergência de quantidade");
    }

    @Test
    void devePermitirReserva_quandoQuantidadeEsperadaCorreta() {
        List<Long> planIds = getPlanejamentoIds();

        ReservaResultDTO result = reservaService.reservarComLockOtimista(filtro, planIds.get(0), 1000);

        assertTrue(result.isSucesso(), "Deve ter sucesso quando a quantidade bate");
        assertEquals(1000, result.getDocumentosReservados());
    }

    @Test
    void devePermitirReserva_quandoQuantidadeEsperadaNula() {
        List<Long> planIds = getPlanejamentoIds();

        ReservaResultDTO result = reservaService.reservarComLockOtimista(filtro, planIds.get(0), null);

        assertTrue(result.isSucesso(), "Deve ter sucesso quando quantidadeEsperada é null (compatibilidade)");
        assertEquals(1000, result.getDocumentosReservados());
    }

    @Test
    void deveRejeitarReserva_quandoDocumentosForamReservadosPorOutroUsuario() {
        List<Long> planIds = getPlanejamentoIds();

        jdbc.update("UPDATE documento_carga SET status = 'RESERVADO', versao = versao + 1 " +
                "WHERE regiao = 'SUL' AND id <= 5");

        IllegalStateException ex = assertThrows(IllegalStateException.class,
                () -> reservaService.reservarComBulkUpdate(filtro, planIds.get(0), 1000));

        assertTrue(ex.getMessage().contains("Quantidade de documentos divergente"),
                "Mensagem deve indicar divergência de quantidade");
    }

    // ───────────────────────────────────────────────────────────────────
    // 20 threads concorrentes (saturação do pool HikariCP)
    // ───────────────────────────────────────────────────────────────────

    @Test
    void deveReservarComLockOtimista_20ThreadsConcorrentes() {
        List<ReservaResultDTO> resultados = executarConcorrencia(20, "OTIMISTA");
        validarResultadoConcorrencia(resultados, 20);
    }

    @Test
    void deveReservarComLockPessimista_20ThreadsConcorrentes() {
        List<ReservaResultDTO> resultados = executarConcorrencia(20, "PESSIMISTA");
        validarResultadoConcorrencia(resultados, 20);
    }

    @Test
    void deveReservarComBulkUpdate_20ThreadsConcorrentes() {
        List<ReservaResultDTO> resultados = executarConcorrencia(20, "BULK");
        validarResultadoConcorrencia(resultados, 20);
    }

    // ───────────────────────────────────────────────────────────────────
    // Métodos auxiliares
    // ───────────────────────────────────────────────────────────────────

    private List<ReservaResultDTO> executarConcorrencia(int totalThreads, String tipo) {
        ExecutorService executor = Executors.newFixedThreadPool(totalThreads);
        List<Long> planIds = getPlanejamentoIds();

        assertTrue(planIds.size() >= totalThreads,
                "Precisa de pelo menos " + totalThreads + " planejamentos no test-data.sql");

        @SuppressWarnings("unchecked")
        CompletableFuture<ReservaResultDTO>[] futures = new CompletableFuture[totalThreads];

        for (int i = 0; i < totalThreads; i++) {
            final Long planId = planIds.get(i);
            futures[i] = CompletableFuture.supplyAsync(() -> {
                try {
                    return switch (tipo) {
                        case "OTIMISTA" -> reservaService.reservarComLockOtimista(filtro, planId, null);
                        case "PESSIMISTA" -> reservaService.reservarComLockPessimista(filtro, planId, null);
                        case "BULK" -> reservaService.reservarComBulkUpdate(filtro, planId, null);
                        default -> throw new IllegalArgumentException("Tipo desconhecido: " + tipo);
                    };
                } catch (Exception e) {
                    return new ReservaResultDTO(planId, 0, 0, 0, 0, false,
                            e.getClass().getSimpleName() + ": " + e.getMessage());
                }
            }, executor);
        }

        CompletableFuture.allOf(futures).join();
        executor.shutdown();

        List<ReservaResultDTO> resultados = new ArrayList<>();
        for (var f : futures) {
            resultados.add(f.join());
        }
        return resultados;
    }

    private List<Long> getPlanejamentoIds() {
        return jdbc.queryForList("SELECT id FROM planejamento ORDER BY id", Long.class);
    }

    private void validarResultadoConcorrencia(List<ReservaResultDTO> resultados, int totalThreads) {
        long sucessos = resultados.stream().filter(ReservaResultDTO::isSucesso).count();
        long falhas = resultados.stream().filter(r -> !r.isSucesso()).count();

        System.out.println("=== RESULTADO DA SIMULAÇÃO (" + totalThreads + " threads) ===");
        System.out.println("Sucessos: " + sucessos);
        System.out.println("Falhas: " + falhas);
        resultados.forEach(r -> System.out.printf(
                "  Planejamento %d: sucesso=%b, docs=%d, totalMs=%d, msg=%s%n",
                r.getPlanejamentoId(), r.isSucesso(), r.getDocumentosReservados(),
                r.getTempoTotalMs(), r.getMensagem()));

        assertEquals(1, sucessos, "Exatamente 1 planejamento deve ter sucesso");
        assertEquals(totalThreads - 1, falhas,
                "Exatamente " + (totalThreads - 1) + " planejamentos devem falhar");

        ReservaResultDTO vencedor = resultados.stream()
                .filter(ReservaResultDTO::isSucesso).findFirst().orElseThrow();
        assertEquals(1000, vencedor.getDocumentosReservados());

        Long vencedorId = vencedor.getPlanejamentoId();
        Long itensVencedor = jdbc.queryForObject(
                "SELECT COUNT(*) FROM planejamento_item WHERE planejamento_id = ?",
                Long.class, vencedorId);
        assertEquals(1000L, itensVencedor, "O vencedor deve ter 1000 itens no banco");

        Long totalItens = jdbc.queryForObject(
                "SELECT COUNT(*) FROM planejamento_item", Long.class);
        assertEquals(1000L, totalItens, "Deve haver exatamente 1000 itens no total");

        Long docsReservados = jdbc.queryForObject(
                "SELECT COUNT(*) FROM documento_carga WHERE status = 'RESERVADO'", Long.class);
        assertEquals(1000L, docsReservados, "Todos os 1000 documentos devem estar RESERVADO");

        Long docsDisponiveis = jdbc.queryForObject(
                "SELECT COUNT(*) FROM documento_carga WHERE status = 'DISPONIVEL'", Long.class);
        assertEquals(0L, docsDisponiveis, "Nenhum documento deve permanecer DISPONIVEL");

        Long duplicatas = jdbc.queryForObject(
                "SELECT COUNT(*) FROM (SELECT documento_id FROM planejamento_item " +
                "GROUP BY documento_id HAVING COUNT(*) > 1) sub", Long.class);
        assertEquals(0L, duplicatas, "Não deve haver duplicatas em planejamento_item");
    }

}
