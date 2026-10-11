package pe.edu.cibertec.vidasalud.auditoria.api;

import java.time.OffsetDateTime;
import java.util.UUID;
import pe.edu.cibertec.vidasalud.auditoria.persistence.EventoAuditoria;
import pe.edu.cibertec.vidasalud.commons.reloj.RelojAutoConfiguration;

public record EventoAuditoriaResponse(UUID id, UUID eventId, OffsetDateTime fechaEvento, String tipoEvento,
                                      String tipoRecurso, UUID recursoId, String estadoAnterior,
                                      String estadoNuevo, String origen, OffsetDateTime fechaRegistro) {

    public static EventoAuditoriaResponse desde(EventoAuditoria e) {
        return new EventoAuditoriaResponse(e.getId(), e.getEventId(), enZonaHospital(e.getFechaEvento()),
                e.getTipoEvento(), e.getTipoRecurso(), e.getRecursoId(), e.getEstadoAnterior(),
                e.getEstadoNuevo(), e.getOrigen(), enZonaHospital(e.getFechaRegistro()));
    }

    private static OffsetDateTime enZonaHospital(OffsetDateTime fecha) {
        return fecha == null ? null
                : fecha.atZoneSameInstant(RelojAutoConfiguration.ZONA_HOSPITAL).toOffsetDateTime();
    }
}
