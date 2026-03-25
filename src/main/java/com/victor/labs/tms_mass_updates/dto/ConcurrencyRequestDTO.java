package com.victor.labs.tms_mass_updates.dto;

import lombok.Data;

import java.util.List;

@Data
public class ConcurrencyRequestDTO {
    private FiltroDTO filtro;
    private List<Long> planejamentoIds;
}
