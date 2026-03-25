package com.victor.labs.tms_mass_updates.service;

import com.victor.labs.tms_mass_updates.domain.DocumentoCarga;
import com.victor.labs.tms_mass_updates.domain.StatusDocumento;
import com.victor.labs.tms_mass_updates.dto.FiltroDTO;
import com.victor.labs.tms_mass_updates.dto.ReservaResultDTO;
import com.victor.labs.tms_mass_updates.repository.DocumentoCargaQueryRepository;
import com.victor.labs.tms_mass_updates.repository.DocumentoCargaRepository;
import com.victor.labs.tms_mass_updates.repository.PlanejamentoItemRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
@Slf4j
@RequiredArgsConstructor
public class ReservaService {

    private final DocumentoCargaQueryRepository documentoQueryRepo;
    private final DocumentoCargaRepository documentoRepo;
    private final PlanejamentoItemRepository planejamentoItemRepo;

    @Transactional
    public ReservaResultDTO reservarComLockOtimista(FiltroDTO filtro, Integer planejamentoId) {
        long inicio = System.currentTimeMillis();
        String threadName = Thread.currentThread().getName();
        log.info("[OTIMISTA][{}] Iniciando reserva para planejamento={}", threadName, planejamentoId);

        try {
            long inicioBusca = System.currentTimeMillis();
            List<DocumentoCarga> documentos = documentoQueryRepo.selecionarLoteParaReserva(filtro);
            long tempoBusca = System.currentTimeMillis() - inicioBusca;
            log.info("[OTIMISTA][{}] Busca concluída: {} documentos em {}ms",
                    threadName, documentos.size(), tempoBusca);

            if (documentos.isEmpty()) {
                return buildResult(planejamentoId, 0, tempoBusca, 0,
                        System.currentTimeMillis() - inicio, false, "Nenhum documento disponível para os filtros informados.");
            }

            validarTodosDisponiveis(documentos);

            long inicioInsercao = System.currentTimeMillis();

            documentos.forEach(doc -> doc.setStatus(StatusDocumento.RESERVADO));
            documentoRepo.saveAll(documentos);
            documentoRepo.flush();

            List<Integer> ids = documentos.stream().map(DocumentoCarga::getId).toList();
            planejamentoItemRepo.inserirItensEmLote(planejamentoId, ids);

            long tempoInsercao = System.currentTimeMillis() - inicioInsercao;
            long tempoTotal = System.currentTimeMillis() - inicio;

            log.info("[OTIMISTA][{}] Reserva concluída com sucesso: {} documentos | busca={}ms | insercao={}ms | total={}ms",
                    threadName, documentos.size(), tempoBusca, tempoInsercao, tempoTotal);

            return buildResult(planejamentoId, documentos.size(), tempoBusca, tempoInsercao, tempoTotal, true, "Reserva realizada com sucesso.");

        } catch (ObjectOptimisticLockingFailureException e) {
            long tempoTotal = System.currentTimeMillis() - inicio;
            log.warn("[OTIMISTA][{}] CONFLITO DE VERSÃO para planejamento={} após {}ms: {}",
                    threadName, planejamentoId, tempoTotal, e.getMessage());
            throw e;

        } catch (DataIntegrityViolationException e) {
            long tempoTotal = System.currentTimeMillis() - inicio;
            log.warn("[OTIMISTA][{}] VIOLAÇÃO DE CONSTRAINT para planejamento={} após {}ms: {}",
                    threadName, planejamentoId, tempoTotal, e.getMessage());
            throw e;
        }
    }

    @Transactional
    public ReservaResultDTO reservarComLockPessimista(FiltroDTO filtro, Integer planejamentoId) {
        long inicio = System.currentTimeMillis();
        String threadName = Thread.currentThread().getName();
        log.info("[PESSIMISTA][{}] Iniciando reserva para planejamento={}", threadName, planejamentoId);

        try {
            long inicioBusca = System.currentTimeMillis();
            List<DocumentoCarga> documentos = documentoQueryRepo.selecionarLoteComLockPessimista(filtro);
            long tempoBusca = System.currentTimeMillis() - inicioBusca;
            log.info("[PESSIMISTA][{}] Busca com lock concluída: {} documentos em {}ms",
                    threadName, documentos.size(), tempoBusca);

            if (documentos.isEmpty()) {
                return buildResult(planejamentoId, 0, tempoBusca, 0,
                        System.currentTimeMillis() - inicio, false, "Nenhum documento disponível para os filtros informados.");
            }

            validarTodosDisponiveis(documentos);

            long inicioInsercao = System.currentTimeMillis();

            documentos.forEach(doc -> doc.setStatus(StatusDocumento.RESERVADO));
            documentoRepo.saveAll(documentos);
            documentoRepo.flush();

            List<Integer> ids = documentos.stream().map(DocumentoCarga::getId).toList();
            planejamentoItemRepo.inserirItensEmLote(planejamentoId, ids);

            long tempoInsercao = System.currentTimeMillis() - inicioInsercao;
            long tempoTotal = System.currentTimeMillis() - inicio;

            log.info("[PESSIMISTA][{}] Reserva concluída com sucesso: {} documentos | busca={}ms | insercao={}ms | total={}ms",
                    threadName, documentos.size(), tempoBusca, tempoInsercao, tempoTotal);

            return buildResult(planejamentoId, documentos.size(), tempoBusca, tempoInsercao, tempoTotal, true, "Reserva realizada com sucesso.");

        } catch (DataIntegrityViolationException e) {
            long tempoTotal = System.currentTimeMillis() - inicio;
            log.warn("[PESSIMISTA][{}] VIOLAÇÃO DE CONSTRAINT para planejamento={} após {}ms: {}",
                    threadName, planejamentoId, tempoTotal, e.getMessage());
            throw e;
        }
    }

    private void validarTodosDisponiveis(List<DocumentoCarga> documentos) {
        long indisponiveis = documentos.stream()
                .filter(d -> d.getStatus() != StatusDocumento.DISPONIVEL)
                .count();
        if (indisponiveis > 0) {
            throw new IllegalStateException(
                    indisponiveis + " documento(s) não estão mais disponíveis para reserva.");
        }
    }

    private ReservaResultDTO buildResult(Integer planejamentoId, int qtd, long busca, long insercao, long total, boolean sucesso, String msg) {
        return new ReservaResultDTO(planejamentoId, qtd, busca, insercao, total, sucesso, msg);
    }
}
