package com.victor.labs.tms_mass_updates.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;

@Data
@AllArgsConstructor
@NoArgsConstructor
public class SumarioDTO {
    private long totalDocumentos;
    private BigDecimal pesoTotal;
    private BigDecimal valorTotal;
    private BigDecimal volumeTotal;
}
