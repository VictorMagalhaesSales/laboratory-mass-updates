package com.victor.labs.tms_mass_updates.controller;

import com.victor.labs.tms_mass_updates.dto.ConcurrencyRequestDTO;
import com.victor.labs.tms_mass_updates.dto.ReservaResultDTO;
import com.victor.labs.tms_mass_updates.service.ConcurrencyService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/concorrencia")
@RequiredArgsConstructor
public class ConcurrencyController {

    private final ConcurrencyService concurrencyService;

    @PostMapping("/otimista")
    public ResponseEntity<List<ReservaResultDTO>> simularOtimista(@RequestBody ConcurrencyRequestDTO request) {
        List<ReservaResultDTO> resultados = concurrencyService.simularConcorrenciaOtimista(
                request.getFiltro(), request.getPlanejamentoIds());
        return ResponseEntity.ok(resultados);
    }

    @PostMapping("/pessimista")
    public ResponseEntity<List<ReservaResultDTO>> simularPessimista(@RequestBody ConcurrencyRequestDTO request) {
        List<ReservaResultDTO> resultados = concurrencyService.simularConcorrenciaPessimista(
                request.getFiltro(), request.getPlanejamentoIds());
        return ResponseEntity.ok(resultados);
    }
}
