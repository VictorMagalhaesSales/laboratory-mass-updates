package com.victor.labs.tms_mass_updates.service;

import com.victor.labs.tms_mass_updates.dto.FiltroDTO;
import com.victor.labs.tms_mass_updates.dto.ReservaResultDTO;
import com.victor.labs.tms_mass_updates.repository.DocumentoCargaQueryRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

@Service
@Slf4j
@RequiredArgsConstructor
public class ConcurrencyService {

    private final ReservaService reservaService;
    private final DocumentoCargaQueryRepository documentoQueryRepo;

    /**
     * Dispara N reservas concorrentes sobre o mesmo filtro usando lock otimista.
     * Cada thread usa um planejamentoId diferente.
     */
    public List<ReservaResultDTO> simularConcorrenciaOtimista(FiltroDTO filtro, List<Long> planejamentoIds) {
        return executarConcorrencia(filtro, planejamentoIds, "OTIMISTA");
    }

    /**
     * Dispara N reservas concorrentes sobre o mesmo filtro usando lock pessimista.
     */
    public List<ReservaResultDTO> simularConcorrenciaPessimista(FiltroDTO filtro, List<Long> planejamentoIds) {
        return executarConcorrencia(filtro, planejamentoIds, "PESSIMISTA");
    }

    /**
     * Dispara N reservas concorrentes sobre o mesmo filtro usando bulk UPDATE (CAS atômico via SQL).
     */
    public List<ReservaResultDTO> simularConcorrenciaBulk(FiltroDTO filtro, List<Long> planejamentoIds) {
        return executarConcorrencia(filtro, planejamentoIds, "BULK");
    }

    /**
     * Simula drift: Thread A faz SELECT (otimista, sem lock), pausa,
     * uma modificação externa commita as mesmas linhas, e Thread A tenta salvar.
     * Resultado esperado: Thread A falha com conflito de versão.
     */
    public List<ReservaResultDTO> simularDrift(FiltroDTO filtro, Long planejamentoId) {
        log.info("[LAB] Iniciando simulação de DRIFT para planejamento={}", planejamentoId);

        CountDownLatch afterSelect = new CountDownLatch(1);
        CountDownLatch beforeSave = new CountDownLatch(1);

        CompletableFuture<ReservaResultDTO> threadA = CompletableFuture.supplyAsync(() -> {
            try {
                return reservaService.reservarComLockOtimistaComDrift(
                        filtro, planejamentoId, afterSelect, beforeSave);
            } catch (Exception e) {
                return new ReservaResultDTO(planejamentoId, 0, 0, 0, 0, false,
                        e.getClass().getSimpleName() + ": " + e.getMessage());
            }
        });

        try {
            if (!afterSelect.await(10, TimeUnit.SECONDS)) {
                beforeSave.countDown();
                return List.of(new ReservaResultDTO(planejamentoId, 0, 0, 0, 0, false,
                        "Timeout: Thread A não completou o SELECT em 10s."));
            }

            long inicioExterno = System.currentTimeMillis();
            int modified = documentoQueryRepo.simularModificacaoExterna(filtro);
            long tempoExterno = System.currentTimeMillis() - inicioExterno;
            log.info("[LAB][DRIFT] Modificação externa concluída: {} documentos em {}ms", modified, tempoExterno);

            beforeSave.countDown();

            ReservaResultDTO driftResult = threadA.get(10, TimeUnit.SECONDS);

            log.info("[LAB][DRIFT] Simulação finalizada: threadA.sucesso={}", driftResult.isSucesso());

            return List.of(
                    new ReservaResultDTO(null, modified, 0, 0, tempoExterno, true,
                            "Modificação externa: " + modified + " documento(s) reservado(s) por outro usuário (simulado)."),
                    driftResult
            );

        } catch (Exception e) {
            beforeSave.countDown();
            log.error("[LAB][DRIFT] Erro na simulação: {}", e.getMessage(), e);
            return List.of(new ReservaResultDTO(planejamentoId, 0, 0, 0, 0, false,
                    "Erro na simulação de drift: " + e.getMessage()));
        }
    }

    private List<ReservaResultDTO> executarConcorrencia(FiltroDTO filtro, List<Long> planejamentoIds, String tipo) {
        int n = planejamentoIds.size();
        log.info("[LAB] Iniciando simulação {} com {} threads concorrentes", tipo, n);

        ExecutorService executor = Executors.newFixedThreadPool(n);

        try {
            @SuppressWarnings("unchecked")
            CompletableFuture<ReservaResultDTO>[] futures = new CompletableFuture[n];

            for (int i = 0; i < n; i++) {
                final Long planId = planejamentoIds.get(i);
                futures[i] = CompletableFuture.supplyAsync(() -> {
                    try {
                        return switch (tipo) {
                            case "OTIMISTA" -> reservaService.reservarComLockOtimista(filtro, planId);
                            case "PESSIMISTA" -> reservaService.reservarComLockPessimista(filtro, planId);
                            case "BULK" -> reservaService.reservarComBulkUpdate(filtro, planId);
                            default -> throw new IllegalArgumentException("Tipo desconhecido: " + tipo);
                        };
                    } catch (Exception e) {
                        log.warn("[LAB][{}] Falha no planejamento={}: {}", tipo, planId, e.getMessage());
                        return new ReservaResultDTO(planId, 0, 0, 0, 0, false, e.getClass().getSimpleName() + ": " + e.getMessage());
                    }
                }, executor);
            }

            CompletableFuture.allOf(futures).join();

            List<ReservaResultDTO> resultados = new ArrayList<>();
            for (CompletableFuture<ReservaResultDTO> f : futures) {
                resultados.add(f.join());
            }

            long sucessos = resultados.stream().filter(ReservaResultDTO::isSucesso).count();
            long falhas = resultados.stream().filter(r -> !r.isSucesso()).count();
            log.info("[LAB] Simulação {} finalizada: {} sucesso(s), {} falha(s)", tipo, sucessos, falhas);

            return resultados;

        } finally {
            executor.shutdown();
        }
    }
}
