package com.victor.labs.tms_mass_updates.domain;

import io.hypersistence.utils.hibernate.id.Tsid;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Entity
@Table(name = "planejamento_item")
@Getter
@Setter
@NoArgsConstructor
public class PlanejamentoItem {

    @Id
    @Tsid
    private Long id;

    @Column(name = "planejamento_id", nullable = false)
    private Long planejamentoId;

    @Column(name = "documento_id", nullable = false)
    private Long documentoId;

    public PlanejamentoItem(Long planejamentoId, Long documentoId) {
        this.planejamentoId = planejamentoId;
        this.documentoId = documentoId;
    }
}
