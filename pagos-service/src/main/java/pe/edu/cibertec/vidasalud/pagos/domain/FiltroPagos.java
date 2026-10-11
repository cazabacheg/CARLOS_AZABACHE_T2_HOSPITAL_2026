package pe.edu.cibertec.vidasalud.pagos.domain;

import java.time.LocalDate;
import java.util.UUID;

/** Todos los campos son opcionales: los nulos no filtran. */
public record FiltroPagos(UUID pacienteId, UUID citaId, String numeroOperacion, EstadoPago estado,
                          MedioPago medioPago, LocalDate fecha) {
}
