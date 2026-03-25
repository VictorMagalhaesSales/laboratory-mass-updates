package com.victor.labs.tms_mass_updates.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@AllArgsConstructor
@NoArgsConstructor
public class ReservaResultDTO {
    private Integer planejamentoId;
    private int documentosReservados;
    private long tempoBuscaMs;
    private long tempoInsercaoMs;
    private long tempoTotalMs;
    private boolean sucesso;
    private String mensagem;
}
