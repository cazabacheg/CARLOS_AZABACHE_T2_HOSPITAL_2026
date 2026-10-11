package pe.edu.cibertec.vidasalud.atenciones.api.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import pe.edu.cibertec.vidasalud.atenciones.domain.EstadoAtencion;

public record CambioEstadoAtencionRequest(
        @NotNull EstadoAtencion estado,
        @Schema(description = "Motivo administrativo; no ingresar datos clínicos.")
        @Size(max = 200) String motivo) {
}
