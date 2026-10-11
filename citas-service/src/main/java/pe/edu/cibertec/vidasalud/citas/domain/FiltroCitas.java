package pe.edu.cibertec.vidasalud.citas.domain;

import java.time.LocalDate;
import java.util.UUID;

public record FiltroCitas(UUID pacienteId, EstadoCita estado, Especialidad especialidad,
                          String profesionalCodigo, LocalDate fecha) {

    public static FiltroCitas vacio() {
        return new FiltroCitas(null, null, null, null, null);
    }
}
