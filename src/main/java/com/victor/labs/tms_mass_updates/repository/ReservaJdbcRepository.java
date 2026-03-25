package com.victor.labs.tms_mass_updates.repository;

import io.hypersistence.tsid.TSID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
@Slf4j
@RequiredArgsConstructor
public class ReservaJdbcRepository {

    private static final int BATCH_SIZE = 100;

    private final JdbcTemplate jdbcTemplate;

    /**
     * UPDATE nativo em bulk com CAS (Compare-And-Swap).
     * Retorna a quantidade de linhas efetivamente atualizadas.
     */
    public int updateDocumentosEmBulk(List<Long> ids) {
        if (ids.isEmpty()) return 0;

        StringBuilder sql = new StringBuilder(
                "UPDATE documento_carga SET status = 'RESERVADO', versao = versao + 1 " +
                "WHERE status = 'DISPONIVEL' AND id IN (");
        for (int i = 0; i < ids.size(); i++) {
            if (i > 0) sql.append(',');
            sql.append('?');
        }
        sql.append(')');

        return jdbcTemplate.update(sql.toString(), ids.toArray());
    }

    /**
     * Batch INSERT via JdbcTemplate.batchUpdate para PlanejamentoItem.
     * Combinado com reWriteBatchedInserts=true do driver PostgreSQL, os INSERTs
     * são reescritos em multi-value statements pelo driver.
     */
    public int insertPlanejamentoItensEmBatch(Long planejamentoId, List<Long> documentoIds) {
        if (documentoIds.isEmpty()) return 0;

        String sql = "INSERT INTO planejamento_item (id, planejamento_id, documento_id) VALUES (?, ?, ?)";

        int[][] results = jdbcTemplate.batchUpdate(sql, documentoIds, BATCH_SIZE,
                (ps, documentoId) -> {
                    ps.setLong(1, TSID.Factory.getTsid().toLong());
                    ps.setLong(2, planejamentoId);
                    ps.setLong(3, documentoId);
                });

        int totalInserted = 0;
        for (int[] batchResult : results) {
            for (int r : batchResult) {
                totalInserted += Math.max(r, 0);
            }
        }
        return totalInserted;
    }
}
