package pe.edu.cibertec.vidasalud.atenciones.domain;

import java.time.LocalDate;
import java.util.UUID;

public record FiltroAtenciones(UUID pacienteId, UUID citaId, EstadoAtencion estado,
                               AreaAtencion area, LocalDate fecha) {
}
