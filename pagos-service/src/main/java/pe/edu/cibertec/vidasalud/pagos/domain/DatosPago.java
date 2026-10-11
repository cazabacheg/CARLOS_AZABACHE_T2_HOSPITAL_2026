package pe.edu.cibertec.vidasalud.pagos.domain;

import java.math.BigDecimal;
import java.util.UUID;

public record DatosPago(UUID pacienteId, UUID citaId, String concepto, BigDecimal monto, MedioPago medioPago) {
}
