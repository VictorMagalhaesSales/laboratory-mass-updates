package com.victor.labs.tms_mass_updates.repository;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import jakarta.persistence.Query;
import lombok.extern.slf4j.Slf4j;
import org.hibernate.Session;
import org.springframework.stereotype.Repository;

import java.sql.PreparedStatement;
import java.util.List;

@Repository
@Slf4j
public class PlanejamentoItemRepository {

    private static final int BATCH_CHUNK_SIZE = 1000;

    @PersistenceContext
    private EntityManager em;

    public void inserirItem(Integer planejamentoId, Integer documentoId) {
        Query query = em.createNativeQuery(
                "INSERT INTO planejamento_item (planejamento_id, documento_id) VALUES (?1, ?2)");
        query.setParameter(1, planejamentoId);
        query.setParameter(2, documentoId);
        query.executeUpdate();
    }

    /**
     * Insere todos os itens via JDBC batch — com reWriteBatchedInserts=true no driver,
     * o PostgreSQL reescreve em multi-row INSERTs automaticamente.
     */
    public void inserirItensEmLote(Integer planejamentoId, List<Integer> documentoIds) {
        Session session = em.unwrap(Session.class);
        session.doWork(connection -> {
            try (PreparedStatement ps = connection.prepareStatement(
                    "INSERT INTO planejamento_item (planejamento_id, documento_id) VALUES (?, ?)")) {
                int count = 0;
                for (Integer docId : documentoIds) {
                    ps.setInt(1, planejamentoId);
                    ps.setInt(2, docId);
                    ps.addBatch();
                    if (++count % BATCH_CHUNK_SIZE == 0) {
                        ps.executeBatch();
                    }
                }
                if (count % BATCH_CHUNK_SIZE != 0) {
                    ps.executeBatch();
                }
                log.debug("Batch insert concluído: {} itens para planejamento={}", count, planejamentoId);
            }
        });
    }

    public long contarItensPorPlanejamento(Integer planejamentoId) {
        Query query = em.createNativeQuery(
                "SELECT COUNT(*) FROM planejamento_item WHERE planejamento_id = ?1");
        query.setParameter(1, planejamentoId);
        return ((Number) query.getSingleResult()).longValue();
    }
}
