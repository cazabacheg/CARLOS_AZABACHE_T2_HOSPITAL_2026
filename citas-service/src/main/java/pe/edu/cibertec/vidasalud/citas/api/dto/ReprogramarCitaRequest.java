package pe.edu.cibertec.vidasalud.citas.api.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import java.time.OffsetDateTime;

public record ReprogramarCitaRequest(
        @NotNull OffsetDateTime fechaHora,
        @Pattern(regexp = "^[A-Z]{3}-\\d{3}$", message = "debe tener el formato AAA-999") String profesionalCodigo) {
}
