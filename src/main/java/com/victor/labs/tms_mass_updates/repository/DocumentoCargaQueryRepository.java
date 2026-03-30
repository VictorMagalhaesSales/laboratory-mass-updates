package com.victor.labs.tms_mass_updates.repository;

import com.victor.labs.tms_mass_updates.domain.DocumentoCarga;
import com.victor.labs.tms_mass_updates.dto.FiltroDTO;
import com.victor.labs.tms_mass_updates.dto.SumarioDTO;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import jakarta.persistence.Query;
import lombok.extern.slf4j.Slf4j;
import org.hibernate.Session;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@Repository
@Slf4j
public class DocumentoCargaQueryRepository {

    @PersistenceContext
    private EntityManager em;

    public List<DocumentoCarga> buscarDisponiveis(FiltroDTO filtro, int pagina, int tamanhoPagina) {
        StringBuilder sql = new StringBuilder(
                "SELECT d.* FROM documento_carga d WHERE d.status = 'DISPONIVEL'");
        List<Object> params = new ArrayList<>();
        int idx = 1;

        idx = appendFiltros(sql, params, filtro, idx);

        sql.append(" ORDER BY d.id LIMIT ?").append(idx++);
        params.add(tamanhoPagina);
        sql.append(" OFFSET ?").append(idx);
        params.add((long) pagina * tamanhoPagina);

        Query query = em.createNativeQuery(sql.toString(), DocumentoCarga.class);
        for (int i = 0; i < params.size(); i++) {
            query.setParameter(i + 1, params.get(i));
        }

        @SuppressWarnings("unchecked")
        List<DocumentoCarga> result = query.getResultList();
        return result;
    }

    public SumarioDTO calcularSumario(FiltroDTO filtro) {
        StringBuilder sql = new StringBuilder(
                "SELECT COUNT(*), COALESCE(SUM(d.peso),0), COALESCE(SUM(d.valor),0), COALESCE(SUM(d.volume),0) " +
                "FROM documento_carga d WHERE d.status = 'DISPONIVEL'");
        List<Object> params = new ArrayList<>();
        int idx = 1;
        appendFiltros(sql, params, filtro, idx);

        Query query = em.createNativeQuery(sql.toString());
        for (int i = 0; i < params.size(); i++) {
            query.setParameter(i + 1, params.get(i));
        }

        Object[] row = (Object[]) query.getSingleResult();
        return new SumarioDTO(
                ((Number) row[0]).longValue(),
                (BigDecimal) row[1],
                (BigDecimal) row[2],
                (BigDecimal) row[3]
        );
    }

    public List<DocumentoCarga> selecionarLoteParaReserva(FiltroDTO filtro) {
        StringBuilder sql = new StringBuilder(
                "SELECT d.* FROM documento_carga d WHERE d.status = 'DISPONIVEL'");
        List<Object> params = new ArrayList<>();
        int idx = 1;

        idx = appendFiltros(sql, params, filtro, idx);

        sql.append(" ORDER BY d.id");

        Query query = em.createNativeQuery(sql.toString(), DocumentoCarga.class);
        for (int i = 0; i < params.size(); i++) {
            query.setParameter(i + 1, params.get(i));
        }

        @SuppressWarnings("unchecked")
        List<DocumentoCarga> result = query.getResultList();
        return result;
    }

    public List<DocumentoCarga> selecionarLoteComLockPessimista(FiltroDTO filtro) {
        StringBuilder sql = new StringBuilder(
                "SELECT d.* FROM documento_carga d WHERE d.status = 'DISPONIVEL'");
        List<Object> params = new ArrayList<>();
        int idx = 1;

        idx = appendFiltros(sql, params, filtro, idx);

        sql.append(" ORDER BY d.id");
        sql.append(" FOR UPDATE");

        Query query = em.createNativeQuery(sql.toString(), DocumentoCarga.class);
        for (int i = 0; i < params.size(); i++) {
            query.setParameter(i + 1, params.get(i));
        }

        @SuppressWarnings("unchecked")
        List<DocumentoCarga> result = query.getResultList();
        return result;
    }

    @Transactional
    public int simularModificacaoExterna(FiltroDTO filtro) {
        StringBuilder subquery = new StringBuilder(
                "SELECT d.id FROM documento_carga d WHERE d.status = 'DISPONIVEL'");
        List<Object> params = new ArrayList<>();
        int idx = 1;

        idx = appendFiltros(subquery, params, filtro, idx);

        subquery.append(" ORDER BY d.id");

        String sql = "UPDATE documento_carga SET status = 'RESERVADO', versao = versao + 1 " +
                     "WHERE id IN (" + subquery + ")";

        Query query = em.createNativeQuery(sql);
        for (int i = 0; i < params.size(); i++) {
            query.setParameter(i + 1, params.get(i));
        }
        return query.executeUpdate();
    }

    public int reservarEmBulk(Map<Long, Integer> idVersaoMap) {
        if (idVersaoMap.isEmpty()) return 0;

        StringBuilder values = new StringBuilder();
        List<Object> params = new ArrayList<>();
        boolean first = true;
        int idx = 1;
        for (var entry : idVersaoMap.entrySet()) {
            if (!first) values.append(',');
            values.append("(?").append(idx++).append(",?").append(idx++).append(')');
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

        Query query = em.createNativeQuery(sql);
        for (int i = 0; i < params.size(); i++) {
            query.setParameter(i + 1, params.get(i));
        }
        return query.executeUpdate();
    }

    private int appendFiltros(StringBuilder sql, List<Object> params, FiltroDTO filtro, int idx) {
        if (filtro == null) return idx;

        if (filtro.getRegiao() != null && !filtro.getRegiao().isBlank()) {
            sql.append(" AND d.regiao = ?").append(idx++);
            params.add(filtro.getRegiao());
        }
        return idx;
    }
}
