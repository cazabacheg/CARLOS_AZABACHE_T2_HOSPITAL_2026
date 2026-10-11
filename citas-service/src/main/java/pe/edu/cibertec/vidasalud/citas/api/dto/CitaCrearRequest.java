package pe.edu.cibertec.vidasalud.citas.api.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.time.OffsetDateTime;
import java.util.UUID;
import pe.edu.cibertec.vidasalud.citas.domain.DatosNuevaCita;
import pe.edu.cibertec.vidasalud.citas.domain.Especialidad;

public record CitaCrearRequest(
        @NotNull UUID pacienteId,
        @NotNull Especialidad especialidad,
        @NotNull OffsetDateTime fechaHora,
        @NotBlank @Pattern(regexp = "^[A-Z]{3}-\\d{3}$", message = "debe tener el formato AAA-999") String profesionalCodigo,
        @Size(max = 200) @Schema(description = "Motivo administrativo (p. ej. Control). No ingresar datos clínicos.") String motivo) {

    public DatosNuevaCita aDatos() {
        return new DatosNuevaCita(pacienteId, especialidad, fechaHora, profesionalCodigo, motivo);
    }
}
