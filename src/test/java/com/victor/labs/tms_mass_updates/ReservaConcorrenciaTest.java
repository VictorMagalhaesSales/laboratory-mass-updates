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
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

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
        filtro.setLimit(1000);
    }

    @Test
    void deveReservarComLockOtimista_apenasUmVencedor() {
        int totalThreads = 10;
        ExecutorService executor = Executors.newFixedThreadPool(totalThreads);

        @SuppressWarnings("unchecked")
        CompletableFuture<ReservaResultDTO>[] futures = new CompletableFuture[totalThreads];

        for (int i = 0; i < totalThreads; i++) {
            final int planId = i + 1;
            futures[i] = CompletableFuture.supplyAsync(() -> {
                try {
                    return reservaService.reservarComLockOtimista(filtro, planId);
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

        validarResultadoConcorrencia(resultados);
    }

    @Test
    void deveReservarComLockPessimista_apenasUmVencedor() {
        int totalThreads = 10;
        ExecutorService executor = Executors.newFixedThreadPool(totalThreads);

        @SuppressWarnings("unchecked")
        CompletableFuture<ReservaResultDTO>[] futures = new CompletableFuture[totalThreads];

        for (int i = 0; i < totalThreads; i++) {
            final int planId = i + 1;
            futures[i] = CompletableFuture.supplyAsync(() -> {
                try {
                    return reservaService.reservarComLockPessimista(filtro, planId);
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

        validarResultadoConcorrencia(resultados);
    }

    private void validarResultadoConcorrencia(List<ReservaResultDTO> resultados) {
        long sucessos = resultados.stream().filter(ReservaResultDTO::isSucesso).count();
        long falhas = resultados.stream().filter(r -> !r.isSucesso()).count();

        System.out.println("=== RESULTADO DA SIMULAÇÃO ===");
        System.out.println("Sucessos: " + sucessos);
        System.out.println("Falhas: " + falhas);
        resultados.forEach(r -> System.out.printf(
                "  Planejamento %d: sucesso=%b, docs=%d, msg=%s%n",
                r.getPlanejamentoId(), r.isSucesso(), r.getDocumentosReservados(), r.getMensagem()));

        assertEquals(1, sucessos, "Exatamente 1 planejamento deve ter sucesso");
        assertEquals(9, falhas, "Exatamente 9 planejamentos devem falhar");

        ReservaResultDTO vencedor = resultados.stream()
                .filter(ReservaResultDTO::isSucesso).findFirst().orElseThrow();
        assertEquals(1000, vencedor.getDocumentosReservados());

        Integer vencedorId = vencedor.getPlanejamentoId();
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
