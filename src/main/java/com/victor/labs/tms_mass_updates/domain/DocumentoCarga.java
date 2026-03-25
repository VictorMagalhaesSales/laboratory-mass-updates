package com.victor.labs.tms_mass_updates.domain;

import io.hypersistence.utils.hibernate.id.Tsid;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;

@Entity
@Table(name = "documento_carga")
@Getter
@Setter
@NoArgsConstructor
public class DocumentoCarga {

    @Id
    @Tsid
    private Long id;

    @Column(name = "numero_documento", nullable = false, length = 50)
    private String numeroDocumento;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", length = 20)
    private StatusDocumento status = StatusDocumento.DISPONIVEL;

    @Column(precision = 10, scale = 2)
    private BigDecimal peso;

    @Column(precision = 15, scale = 2)
    private BigDecimal valor;

    @Column(precision = 10, scale = 2)
    private BigDecimal volume;

    @Column(length = 20)
    private String regiao;

    @Version
    @Column(name = "versao")
    private int versao;
}
