package pe.edu.cibertec.vidasalud.notificaciones.api;

import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.UUID;
import pe.edu.cibertec.vidasalud.notificaciones.domain.Notificacion;

public record NotificacionResponse(UUID id, UUID eventId, UUID pacienteId, String tipoEvento, UUID recursoId,
                                   String canal, String mensaje, OffsetDateTime fechaEnvio) {

    private static final ZoneId ZONA_LIMA = ZoneId.of("America/Lima");

    public static NotificacionResponse desde(Notificacion n) {
        OffsetDateTime fecha = n.getFechaEnvio() == null ? null
                : n.getFechaEnvio().atZoneSameInstant(ZONA_LIMA).toOffsetDateTime();
        return new NotificacionResponse(n.getId(), n.getEventId(), n.getPacienteId(), n.getTipoEvento(),
                n.getRecursoId(), n.getCanal(), n.getMensaje(), fecha);
    }
}
