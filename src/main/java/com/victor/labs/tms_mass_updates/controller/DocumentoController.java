package com.victor.labs.tms_mass_updates.controller;

import com.victor.labs.tms_mass_updates.dto.DocumentosDisponiveisDTO;
import com.victor.labs.tms_mass_updates.dto.FiltroDTO;
import com.victor.labs.tms_mass_updates.dto.SumarioDTO;
import com.victor.labs.tms_mass_updates.repository.DocumentoCargaQueryRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/documentos")
@RequiredArgsConstructor
public class DocumentoController {

    private final DocumentoCargaQueryRepository documentoQueryRepo;

    @GetMapping("/disponiveis")
    public DocumentosDisponiveisDTO listarDisponiveis(
            @ModelAttribute FiltroDTO filtro,
            @RequestParam(defaultValue = "0") int pagina,
            @RequestParam(defaultValue = "50") int tamanhoPagina) {

        var documentos = documentoQueryRepo.buscarDisponiveis(filtro, pagina, tamanhoPagina);
        SumarioDTO sumario = documentoQueryRepo.calcularSumario(filtro);

        return new DocumentosDisponiveisDTO(documentos, sumario, pagina, tamanhoPagina);
    }
}
