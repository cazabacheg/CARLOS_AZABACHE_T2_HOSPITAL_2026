package pe.edu.cibertec.vidasalud.pagos.api.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.util.UUID;
import pe.edu.cibertec.vidasalud.pagos.domain.MedioPago;

/** El monto solo lleva @NotNull: su rango lo valida ReglasPago para devolver MONTO_INVALIDO. */
public record PagoCrearRequest(
        @NotNull UUID pacienteId,
        UUID citaId,
        @NotBlank @Size(max = 120) String concepto,
        @NotNull BigDecimal monto,
        @NotNull MedioPago medioPago) {
}
