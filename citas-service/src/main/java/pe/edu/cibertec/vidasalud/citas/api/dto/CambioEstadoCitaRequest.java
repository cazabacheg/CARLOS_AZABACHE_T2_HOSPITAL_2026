package pe.edu.cibertec.vidasalud.citas.api.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import pe.edu.cibertec.vidasalud.citas.domain.EstadoCita;

public record CambioEstadoCitaRequest(@NotNull EstadoCita estado, @Size(max = 200) String motivo) {
}
