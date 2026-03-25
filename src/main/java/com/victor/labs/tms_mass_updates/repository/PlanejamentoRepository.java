package com.victor.labs.tms_mass_updates.repository;

import com.victor.labs.tms_mass_updates.domain.Planejamento;
import org.springframework.data.jpa.repository.JpaRepository;

public interface PlanejamentoRepository extends JpaRepository<Planejamento, Long> {
}
