package pe.edu.cibertec.vidasalud.commons.messaging;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.JsonNode;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * Sobre comun de los eventos de negocio (documento tecnico, seccion 6.4).
 * {@code datos} es generico: lo construye el productor y cada consumidor lo lee
 * con su propio DTO tolerante a campos desconocidos.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record EventoEnvelope(
        UUID eventId,
        String tipo,
        OffsetDateTime ocurrioEn,
        String origen,
        String tipoRecurso,
        UUID recursoId,
        String estadoAnterior,
        String estadoNuevo,
        JsonNode datos) {

    public static EventoEnvelope nuevo(Clock reloj, String tipo, String origen, String tipoRecurso,
                                       UUID recursoId, String estadoAnterior, String estadoNuevo,
                                       JsonNode datos) {
        return new EventoEnvelope(UUID.randomUUID(), tipo, OffsetDateTime.now(reloj), origen,
                tipoRecurso, recursoId, estadoAnterior, estadoNuevo, datos);
    }
}
