package pe.edu.cibertec.vidasalud.atenciones.api.dto;

import java.time.OffsetDateTime;
import java.util.UUID;
import pe.edu.cibertec.vidasalud.atenciones.domain.AreaAtencion;
import pe.edu.cibertec.vidasalud.atenciones.domain.Atencion;
import pe.edu.cibertec.vidasalud.atenciones.domain.EstadoAtencion;
import pe.edu.cibertec.vidasalud.atenciones.messaging.EventosAtencion;

public record AtencionResponse(UUID id, String codigo, UUID pacienteId, UUID citaId,
                               OffsetDateTime fechaAtencion, AreaAtencion area, String areaNombre,
                               EstadoAtencion estado, OffsetDateTime fechaCreacion,
                               OffsetDateTime fechaActualizacion) {

    public static AtencionResponse de(Atencion a) {
        return new AtencionResponse(a.getId(), a.getCodigo(), a.getPacienteId(), a.getCitaId(),
                EventosAtencion.enLima(a.getFechaAtencion()), a.getArea(), a.getArea().nombre(),
                a.getEstado(), EventosAtencion.enLima(a.getFechaCreacion()),
                EventosAtencion.enLima(a.getFechaActualizacion()));
    }
}
