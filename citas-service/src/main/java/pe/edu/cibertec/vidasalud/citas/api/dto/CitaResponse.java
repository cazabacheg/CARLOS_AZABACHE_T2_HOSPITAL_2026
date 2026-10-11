package pe.edu.cibertec.vidasalud.citas.api.dto;

import java.time.OffsetDateTime;
import java.util.UUID;
import pe.edu.cibertec.vidasalud.citas.domain.Cita;
import pe.edu.cibertec.vidasalud.citas.domain.Especialidad;
import pe.edu.cibertec.vidasalud.citas.domain.EstadoCita;
import pe.edu.cibertec.vidasalud.citas.domain.ZonaHospital;

public record CitaResponse(UUID id, String codigo, UUID pacienteId, Especialidad especialidad,
                           String especialidadNombre, OffsetDateTime fechaHora, String profesionalCodigo,
                           String motivo, EstadoCita estado, OffsetDateTime fechaCreacion,
                           OffsetDateTime fechaActualizacion) {

    public static CitaResponse de(Cita c) {
        return new CitaResponse(c.getId(), c.getCodigo(), c.getPacienteId(), c.getEspecialidad(),
                c.getEspecialidad().getNombre(), ZonaHospital.aLima(c.getFechaHora()), c.getProfesionalCodigo(),
                c.getMotivo(), c.getEstado(), ZonaHospital.aLima(c.getFechaCreacion()),
                ZonaHospital.aLima(c.getFechaActualizacion()));
    }
}
