package pe.edu.cibertec.vidasalud.pacientes.api.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import pe.edu.cibertec.vidasalud.pacientes.domain.EstadoPaciente;

public record CambioEstadoRequest(
        @NotNull(message = "es obligatorio") EstadoPaciente estado,
        @Size(max = 200, message = "debe tener como máximo 200 caracteres") String motivo) {
}
