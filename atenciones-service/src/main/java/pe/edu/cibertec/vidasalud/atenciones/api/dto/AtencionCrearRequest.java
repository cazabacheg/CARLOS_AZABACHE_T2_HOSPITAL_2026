package pe.edu.cibertec.vidasalud.atenciones.api.dto;

import jakarta.validation.constraints.NotNull;
import java.util.UUID;
import pe.edu.cibertec.vidasalud.atenciones.domain.AreaAtencion;

public record AtencionCrearRequest(@NotNull UUID citaId, @NotNull AreaAtencion area) {
}
