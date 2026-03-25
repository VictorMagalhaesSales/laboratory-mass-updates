package com.victor.labs.tms_mass_updates.controller;

import com.victor.labs.tms_mass_updates.dto.ReservaResultDTO;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
@Slf4j
public class GlobalExceptionHandler {

    @ExceptionHandler(ObjectOptimisticLockingFailureException.class)
    public ResponseEntity<ReservaResultDTO> handleOptimisticLock(ObjectOptimisticLockingFailureException e) {
        log.warn("Conflito de lock otimista capturado no handler: {}", e.getMessage());
        ReservaResultDTO result = new ReservaResultDTO(
                null, 0, 0, 0, 0, false,
                "Conflito de concorrência: outro usuário reservou documento(s) deste lote.");
        return ResponseEntity.status(HttpStatus.CONFLICT).body(result);
    }

    @ExceptionHandler(DataIntegrityViolationException.class)
    public ResponseEntity<ReservaResultDTO> handleConstraintViolation(DataIntegrityViolationException e) {
        log.warn("Violação de constraint capturada no handler: {}", e.getMessage());
        ReservaResultDTO result = new ReservaResultDTO(
                null, 0, 0, 0, 0, false,
                "Documento(s) já reservado(s) por outro planejamento.");
        return ResponseEntity.status(HttpStatus.CONFLICT).body(result);
    }

    @ExceptionHandler(IllegalStateException.class)
    public ResponseEntity<ReservaResultDTO> handleIllegalState(IllegalStateException e) {
        log.warn("Estado inválido: {}", e.getMessage());
        ReservaResultDTO result = new ReservaResultDTO(
                null, 0, 0, 0, 0, false, e.getMessage());
        return ResponseEntity.status(HttpStatus.CONFLICT).body(result);
    }
}
