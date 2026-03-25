package com.victor.labs.tms_mass_updates.repository;

import com.victor.labs.tms_mass_updates.domain.PlanejamentoItem;
import org.springframework.data.jpa.repository.JpaRepository;

public interface PlanejamentoItemRepository extends JpaRepository<PlanejamentoItem, Long> {

    long countByPlanejamentoId(Long planejamentoId);
}
