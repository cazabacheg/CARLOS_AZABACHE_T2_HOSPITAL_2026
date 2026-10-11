package pe.edu.cibertec.vidasalud.pacientes.api.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Past;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.time.LocalDate;
import pe.edu.cibertec.vidasalud.pacientes.domain.DatosPersonales;
import pe.edu.cibertec.vidasalud.pacientes.domain.TipoDocumento;

public record PacienteCrearRequest(
        @NotNull(message = "es obligatorio") TipoDocumento tipoDocumento,
        @NotBlank(message = "es obligatorio")
        @Size(min = 6, max = 20, message = "debe tener entre 6 y 20 caracteres") String numeroDocumento,
        @NotBlank(message = "es obligatorio")
        @Size(max = 80, message = "debe tener como máximo 80 caracteres") String nombres,
        @NotBlank(message = "es obligatorio")
        @Size(max = 80, message = "debe tener como máximo 80 caracteres") String apellidos,
        @NotNull(message = "es obligatoria") @Past(message = "debe ser una fecha pasada") LocalDate fechaNacimiento,
        @Pattern(regexp = "^[0-9+ ]{7,15}$",
                message = "debe tener de 7 a 15 caracteres entre dígitos, espacios o +") String telefono,
        @Email(message = "debe ser un correo válido")
        @Size(max = 120, message = "debe tener como máximo 120 caracteres") String correo,
        @Size(max = 200, message = "debe tener como máximo 200 caracteres") String direccion) {

    public DatosPersonales datosPersonales() {
        return new DatosPersonales(nombres, apellidos, fechaNacimiento, telefono, correo, direccion);
    }
}
