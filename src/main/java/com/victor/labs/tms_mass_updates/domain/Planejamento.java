package com.victor.labs.tms_mass_updates.domain;

import io.hypersistence.utils.hibernate.id.Tsid;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;

@Entity
@Table(name = "planejamento")
@Getter
@Setter
@NoArgsConstructor
public class Planejamento {

    @Id
    @Tsid
    private Long id;

    @Column(name = "usuario_id", nullable = false)
    private Integer usuarioId;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", length = 20)
    private StatusPlanejamento status = StatusPlanejamento.DRAFT;

    @Column(name = "data_criacao")
    private LocalDateTime dataCriacao = LocalDateTime.now();
}
