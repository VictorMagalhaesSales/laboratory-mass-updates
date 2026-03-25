package com.victor.labs.tms_mass_updates.dto;

import com.victor.labs.tms_mass_updates.domain.DocumentoCarga;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Data
@AllArgsConstructor
@NoArgsConstructor
public class DocumentosDisponiveisDTO {
    private List<DocumentoCarga> documentos;
    private SumarioDTO sumario;
    private int pagina;
    private int tamanhoPagina;
}
