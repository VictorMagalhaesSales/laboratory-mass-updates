package com.victor.labs.tms_mass_updates.dto;

import lombok.Data;

import java.math.BigDecimal;

@Data
public class FiltroDTO {
    private String regiao;
    private BigDecimal pesoMinimo;
    private BigDecimal pesoMaximo;
    private BigDecimal valorMinimo;
    private BigDecimal valorMaximo;
    private BigDecimal volumeMinimo;
    private BigDecimal volumeMaximo;
    private Integer limit;
}
