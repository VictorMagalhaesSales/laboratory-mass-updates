package com.victor.labs.tms_mass_updates.dto;

import lombok.Data;

@Data
public class ReservaRequestDTO {
    private FiltroDTO filtro;
    private Long planejamentoId;
    private Integer quantidadeEsperada;
}
