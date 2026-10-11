package pe.edu.cibertec.vidasalud.pagos.api.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record AnularPagoRequest(@NotBlank @Size(max = 200) String motivo) {
}
