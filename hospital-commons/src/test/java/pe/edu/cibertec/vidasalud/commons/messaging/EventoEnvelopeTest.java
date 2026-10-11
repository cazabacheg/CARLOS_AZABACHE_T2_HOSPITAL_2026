package pe.edu.cibertec.vidasalud.commons.messaging;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class EventoEnvelopeTest {

    private final ObjectMapper mapper = new ObjectMapper()
            .registerModule(new JavaTimeModule())
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

    @Test
    void serializaYDeserializaConDatosGenericos() throws Exception {
        Clock reloj = Clock.fixed(Instant.parse("2026-10-12T14:30:00Z"), ZoneId.of("America/Lima"));
        JsonNode datos = mapper.valueToTree(Map.of("codigo", "CIT-20261012-000001"));
        UUID recurso = UUID.randomUUID();
        EventoEnvelope e = EventoEnvelope.nuevo(reloj, "cita.confirmada", "citas-service", "CITA",
                recurso, "PENDIENTE", "CONFIRMADA", datos);

        String json = mapper.writeValueAsString(e);
        assertThat(json).contains("\"tipo\":\"cita.confirmada\"").contains("-05:00");

        EventoEnvelope leido = mapper.readValue(json, EventoEnvelope.class);
        assertThat(leido.eventId()).isEqualTo(e.eventId());
        assertThat(leido.recursoId()).isEqualTo(recurso);
        assertThat(leido.estadoNuevo()).isEqualTo("CONFIRMADA");
        assertThat(leido.datos().get("codigo").asText()).isEqualTo("CIT-20261012-000001");
    }

    @Test
    void ignoraCamposDesconocidos() throws Exception {
        String json = "{\"eventId\":\"" + UUID.randomUUID() + "\",\"tipo\":\"x\",\"campoNuevo\":1}";
        assertThat(mapper.readValue(json, EventoEnvelope.class).tipo()).isEqualTo("x");
    }

    @Test
    void colaMuertosSeDerivaDelNombreDeLaCola() {
        assertThat(TopologiaHospital.colaMuertos("notificaciones.cola")).isEqualTo("notificaciones.dlq");
        assertThat(TopologiaHospital.colaMuertos("citas.atenciones.cola")).isEqualTo("citas.atenciones.dlq");
    }
}
