package com.victor.labs.tms_mass_updates.service;

import com.victor.labs.tms_mass_updates.domain.DocumentoCarga;
import com.victor.labs.tms_mass_updates.domain.PlanejamentoItem;
import com.victor.labs.tms_mass_updates.domain.StatusDocumento;
import com.victor.labs.tms_mass_updates.dto.FiltroDTO;
import com.victor.labs.tms_mass_updates.dto.ReservaResultDTO;
import com.victor.labs.tms_mass_updates.repository.DocumentoCargaQueryRepository;
import com.victor.labs.tms_mass_updates.repository.DocumentoCargaRepository;
import com.victor.labs.tms_mass_updates.repository.PlanejamentoItemRepository;
import com.victor.labs.tms_mass_updates.repository.ReservaJdbcRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.concurrent.CountDownLatch;

@Service
@Slf4j
@RequiredArgsConstructor
public class ReservaService {

    private final DocumentoCargaQueryRepository documentoQueryRepo;
    private final DocumentoCargaRepository documentoRepo;
    private final PlanejamentoItemRepository planejamentoItemRepo;
    private final ReservaJdbcRepository reservaJdbcRepo;

    @Transactional
    public ReservaResultDTO reservarComLockOtimista(FiltroDTO filtro, Long planejamentoId, Integer quantidadeEsperada) {
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

            validarQuantidadeEsperada(quantidadeEsperada, documentos.size());
            validarTodosDisponiveis(documentos);

            long inicioInsercao = System.currentTimeMillis();

            documentos.forEach(doc -> doc.setStatus(StatusDocumento.RESERVADO));
            documentoRepo.saveAll(documentos);
            documentoRepo.flush(); // Antecipar detecção de ObjectOptimisticLockingFailureException

            List<PlanejamentoItem> itens = documentos.stream()
                    .map(doc -> new PlanejamentoItem(planejamentoId, doc.getId()))
                    .toList();
            planejamentoItemRepo.saveAll(itens);

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
    public ReservaResultDTO reservarComLockPessimista(FiltroDTO filtro, Long planejamentoId, Integer quantidadeEsperada) {
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

            validarQuantidadeEsperada(quantidadeEsperada, documentos.size());
            validarTodosDisponiveis(documentos);

            long inicioInsercao = System.currentTimeMillis();

            documentos.forEach(doc -> doc.setStatus(StatusDocumento.RESERVADO));
            documentoRepo.saveAll(documentos);

            List<PlanejamentoItem> itens = documentos.stream()
                    .map(doc -> new PlanejamentoItem(planejamentoId, doc.getId()))
                    .toList();
            planejamentoItemRepo.saveAll(itens);

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

    @Transactional
    public ReservaResultDTO reservarComBulkUpdate(FiltroDTO filtro, Long planejamentoId, Integer quantidadeEsperada) {
        long inicio = System.currentTimeMillis();
        String threadName = Thread.currentThread().getName();
        log.info("[BULK][{}] Iniciando reserva para planejamento={}", threadName, planejamentoId);

        long inicioBusca = System.currentTimeMillis();
        List<DocumentoCarga> documentos = documentoQueryRepo.selecionarLoteParaReserva(filtro);
        long tempoBusca = System.currentTimeMillis() - inicioBusca;
        log.info("[BULK][{}] Busca concluída: {} documentos em {}ms", threadName, documentos.size(), tempoBusca);

        if (documentos.isEmpty()) {
            return buildResult(planejamentoId, 0, tempoBusca, 0,
                    System.currentTimeMillis() - inicio, false, "Nenhum documento disponível para os filtros informados.");
        }

        validarQuantidadeEsperada(quantidadeEsperada, documentos.size());

        List<Long> ids = documentos.stream().map(DocumentoCarga::getId).toList();

        long inicioUpdate = System.currentTimeMillis();
        int rowsAffected = documentoQueryRepo.reservarEmBulk(ids);

        if (rowsAffected != ids.size()) {
            throw new IllegalStateException(
                    "CAS falhou: esperava " + ids.size() + " atualizações, mas obteve " + rowsAffected +
                    ". " + (ids.size() - rowsAffected) + " documento(s) já reservado(s) por outro usuário.");
        }

        List<PlanejamentoItem> itens = ids.stream()
                .map(docId -> new PlanejamentoItem(planejamentoId, docId))
                .toList();
        planejamentoItemRepo.saveAll(itens);

        long tempoUpdate = System.currentTimeMillis() - inicioUpdate;
        long tempoTotal = System.currentTimeMillis() - inicio;

        log.info("[BULK][{}] Reserva concluída com sucesso: {} documentos | busca={}ms | update={}ms | total={}ms",
                threadName, rowsAffected, tempoBusca, tempoUpdate, tempoTotal);

        return buildResult(planejamentoId, rowsAffected, tempoBusca, tempoUpdate, tempoTotal, true, "Reserva realizada com sucesso (bulk CAS).");
    }

    @Transactional
    public ReservaResultDTO reservarComJdbcBatch(FiltroDTO filtro, Long planejamentoId, Integer quantidadeEsperada) {
        long inicio = System.currentTimeMillis();
        String threadName = Thread.currentThread().getName();
        log.info("[JDBC-BATCH][{}] Iniciando reserva para planejamento={}", threadName, planejamentoId);

        long inicioBusca = System.currentTimeMillis();
        List<DocumentoCarga> documentos = documentoQueryRepo.selecionarLoteParaReserva(filtro);
        long tempoBusca = System.currentTimeMillis() - inicioBusca;
        log.info("[JDBC-BATCH][{}] Busca concluída: {} documentos em {}ms", threadName, documentos.size(), tempoBusca);

        if (documentos.isEmpty()) {
            return buildResult(planejamentoId, 0, tempoBusca, 0,
                    System.currentTimeMillis() - inicio, false, "Nenhum documento disponível para os filtros informados.");
        }

        validarQuantidadeEsperada(quantidadeEsperada, documentos.size());

        List<Long> ids = documentos.stream().map(DocumentoCarga::getId).toList();

        long inicioUpdate = System.currentTimeMillis();
        int rowsAffected = reservaJdbcRepo.updateDocumentosEmBulk(ids);

        if (rowsAffected != ids.size()) {
            throw new IllegalStateException(
                    "CAS falhou: esperava " + ids.size() + " atualizações, mas obteve " + rowsAffected +
                    ". " + (ids.size() - rowsAffected) + " documento(s) já reservado(s) por outro usuário.");
        }

        reservaJdbcRepo.insertPlanejamentoItensEmBatch(planejamentoId, ids);

        long tempoUpdate = System.currentTimeMillis() - inicioUpdate;
        long tempoTotal = System.currentTimeMillis() - inicio;

        log.info("[JDBC-BATCH][{}] Reserva concluída: {} documentos | busca={}ms | update+insert={}ms | total={}ms",
                threadName, rowsAffected, tempoBusca, tempoUpdate, tempoTotal);

        return buildResult(planejamentoId, rowsAffected, tempoBusca, tempoUpdate, tempoTotal, true,
                "Reserva realizada com sucesso (JDBC batch).");
    }

    @Transactional
    public ReservaResultDTO reservarComLockOtimistaComDrift(
            FiltroDTO filtro, Long planejamentoId,
            CountDownLatch afterSelect, CountDownLatch beforeSave) {
        long inicio = System.currentTimeMillis();
        String threadName = Thread.currentThread().getName();
        log.info("[DRIFT][{}] Iniciando reserva com drift simulado para planejamento={}", threadName, planejamentoId);

        try {
            List<DocumentoCarga> documentos = documentoQueryRepo.selecionarLoteParaReserva(filtro);

            if (documentos.isEmpty()) {
                afterSelect.countDown();
                return buildResult(planejamentoId, 0, 0, 0,
                        System.currentTimeMillis() - inicio, false, "Nenhum documento disponível.");
            }

            afterSelect.countDown();
            beforeSave.await();

            documentos.forEach(doc -> doc.setStatus(StatusDocumento.RESERVADO));
            documentoRepo.saveAll(documentos);
            documentoRepo.flush(); // Antecipar detecção de ObjectOptimisticLockingFailureException

            List<PlanejamentoItem> itens = documentos.stream()
                    .map(doc -> new PlanejamentoItem(planejamentoId, doc.getId()))
                    .toList();
            planejamentoItemRepo.saveAll(itens);

            long tempoTotal = System.currentTimeMillis() - inicio;
            return buildResult(planejamentoId, documentos.size(), 0, 0, tempoTotal, true, "Reserva realizada com sucesso.");

        } catch (ObjectOptimisticLockingFailureException e) {
            long tempoTotal = System.currentTimeMillis() - inicio;
            log.warn("[DRIFT][{}] CONFLITO DE VERSÃO (drift detectado) para planejamento={} após {}ms",
                    threadName, planejamentoId, tempoTotal);
            throw e;

        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("Simulação de drift interrompida", e);
        }
    }

    private void validarQuantidadeEsperada(Integer quantidadeEsperada, int quantidadeRetornada) {
        if (quantidadeEsperada != null && quantidadeRetornada != quantidadeEsperada) {
            throw new IllegalStateException(
                    "Quantidade de documentos divergente: esperava " + quantidadeEsperada
                    + ", mas encontrou " + quantidadeRetornada
                    + ". Os dados podem ter sido alterados entre a consulta e a reserva.");
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

    private ReservaResultDTO buildResult(Long planejamentoId, int qtd, long busca, long insercao, long total, boolean sucesso, String msg) {
        return new ReservaResultDTO(planejamentoId, qtd, busca, insercao, total, sucesso, msg);
    }
}
