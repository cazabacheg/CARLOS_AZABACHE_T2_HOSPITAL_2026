package pe.edu.cibertec.vidasalud.citas.domain;

import java.time.OffsetDateTime;
import java.util.UUID;

public record DatosNuevaCita(UUID pacienteId, Especialidad especialidad, OffsetDateTime fechaHora,
                             String profesionalCodigo, String motivo) {
}
