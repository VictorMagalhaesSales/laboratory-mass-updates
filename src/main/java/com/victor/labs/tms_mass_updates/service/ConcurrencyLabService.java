package com.victor.labs.tms_mass_updates.service;

import com.victor.labs.tms_mass_updates.dto.FiltroDTO;
import com.victor.labs.tms_mass_updates.dto.ReservaResultDTO;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

@Service
@Slf4j
@RequiredArgsConstructor
public class ConcurrencyLabService {

    private final ReservaService reservaService;

    /**
     * Dispara N reservas concorrentes sobre o mesmo filtro usando lock otimista.
     * Cada thread usa um planejamentoId diferente.
     */
    public List<ReservaResultDTO> simularConcorrenciaOtimista(FiltroDTO filtro, List<Integer> planejamentoIds) {
        return executarConcorrencia(filtro, planejamentoIds, "OTIMISTA");
    }

    /**
     * Dispara N reservas concorrentes sobre o mesmo filtro usando lock pessimista.
     */
    public List<ReservaResultDTO> simularConcorrenciaPessimista(FiltroDTO filtro, List<Integer> planejamentoIds) {
        return executarConcorrencia(filtro, planejamentoIds, "PESSIMISTA");
    }

    private List<ReservaResultDTO> executarConcorrencia(FiltroDTO filtro, List<Integer> planejamentoIds, String tipo) {
        int n = planejamentoIds.size();
        log.info("[LAB] Iniciando simulação {} com {} threads concorrentes", tipo, n);

        ExecutorService executor = Executors.newFixedThreadPool(n);

        try {
            @SuppressWarnings("unchecked")
            CompletableFuture<ReservaResultDTO>[] futures = new CompletableFuture[n];

            for (int i = 0; i < n; i++) {
                final Integer planId = planejamentoIds.get(i);
                futures[i] = CompletableFuture.supplyAsync(() -> {
                    try {
                        if ("OTIMISTA".equals(tipo)) {
                            return reservaService.reservarComLockOtimista(filtro, planId);
                        } else {
                            return reservaService.reservarComLockPessimista(filtro, planId);
                        }
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
