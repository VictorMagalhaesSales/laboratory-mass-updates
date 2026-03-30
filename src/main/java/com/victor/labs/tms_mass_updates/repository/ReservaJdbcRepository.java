package com.victor.labs.tms_mass_updates.repository;

import io.hypersistence.tsid.TSID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

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
    public int updateDocumentosEmBulk(Map<Long, Integer> idVersaoMap) {
        if (idVersaoMap.isEmpty()) return 0;

        StringBuilder values = new StringBuilder();
        List<Object> params = new ArrayList<>();
        boolean first = true;
        for (var entry : idVersaoMap.entrySet()) {
            if (!first) values.append(',');
            values.append("(?,?)");
            params.add(entry.getKey());
            params.add(entry.getValue());
            first = false;
        }

        String sql = "UPDATE documento_carga dc " +
                "SET status = 'RESERVADO', versao = dc.versao + 1 " +
                "FROM (VALUES " + values + ") AS expected(id, versao) " +
                "WHERE dc.id = expected.id " +
                "AND dc.versao = expected.versao " +
                "AND dc.status = 'DISPONIVEL'";

        return jdbcTemplate.update(sql, params.toArray());
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
