package com.victor.labs.tms_mass_updates.controller;

import com.victor.labs.tms_mass_updates.dto.ReservaRequestDTO;
import com.victor.labs.tms_mass_updates.dto.ReservaResultDTO;
import com.victor.labs.tms_mass_updates.service.ReservaService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/reserva")
@RequiredArgsConstructor
public class ReservaController {

    private final ReservaService reservaService;

    @PostMapping("/otimista")
    public ResponseEntity<ReservaResultDTO> reservarOtimista(@RequestBody ReservaRequestDTO request) {
        ReservaResultDTO result = reservaService.reservarComLockOtimista(
                request.getFiltro(), request.getPlanejamentoId());
        return ResponseEntity.ok(result);
    }

    @PostMapping("/pessimista")
    public ResponseEntity<ReservaResultDTO> reservarPessimista(@RequestBody ReservaRequestDTO request) {
        ReservaResultDTO result = reservaService.reservarComLockPessimista(
                request.getFiltro(), request.getPlanejamentoId());
        return ResponseEntity.ok(result);
    }
}
