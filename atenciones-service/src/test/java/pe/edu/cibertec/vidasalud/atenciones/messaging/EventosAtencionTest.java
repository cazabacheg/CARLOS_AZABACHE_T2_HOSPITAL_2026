package pe.edu.cibertec.vidasalud.atenciones.messaging;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.UUID;
import pe.edu.cibertec.vidasalud.atenciones.domain.AreaAtencion;
import pe.edu.cibertec.vidasalud.atenciones.domain.Atencion;
import pe.edu.cibertec.vidasalud.atenciones.domain.CitaResumen;
import pe.edu.cibertec.vidasalud.atenciones.domain.EstadoAtencion;
import org.junit.jupiter.api.Test;
import pe.edu.cibertec.vidasalud.commons.messaging.EventoEnvelope;

class EventosAtencionTest {

    private static final Clock RELOJ = Clock.fixed(Instant.parse("2026-10-14T14:00:00Z"), ZoneId.of("America/Lima"));

    private final CitaResumen cita = new CitaResumen(UUID.randomUUID(), UUID.randomUUID(), "CONFIRMADA",
            OffsetDateTime.parse("2026-10-14T09:30:00-05:00"));

    private Atencion nueva() {
        return Atencion.registrar(cita, AreaAtencion.CONSULTA_EXTERNA, "ATE-20261014-000001", RELOJ);
    }

    @Test
    void eventoRegistrada() {
        Atencion a = nueva();
        EventoEnvelope e = EventosAtencion.registrada(a, RELOJ);
        assertThat(e.tipo()).isEqualTo("atencion.registrada");
        assertThat(e.origen()).isEqualTo("atenciones-service");
        assertThat(e.tipoRecurso()).isEqualTo("ATENCION");
        assertThat(e.recursoId()).isEqualTo(a.getId());
        assertThat(e.estadoAnterior()).isNull();
        assertThat(e.estadoNuevo()).isEqualTo("REGISTRADA");
        assertThat(e.eventId()).isNotNull();
        assertThat(e.datos().get("atencionId").asText()).isEqualTo(a.getId().toString());
        assertThat(e.datos().get("citaId").asText()).isEqualTo(cita.id().toString());
        assertThat(e.datos().get("pacienteId").asText()).isEqualTo(cita.pacienteId().toString());
        assertThat(e.datos().get("codigo").asText()).isEqualTo("ATE-20261014-000001");
        assertThat(e.datos().get("area").asText()).isEqualTo("CONSULTA_EXTERNA");
        assertThat(e.datos().get("areaNombre").asText()).isEqualTo("Consulta Externa");
        assertThat(e.datos().get("fechaAtencion").asText()).isEqualTo("2026-10-14T09:00:00-05:00");
    }

    @Test
    void eventoAnuladaConMotivo() {
        Atencion a = nueva();
        EstadoAtencion anterior = a.cambiarEstado(EstadoAtencion.ANULADA, RELOJ);
        EventoEnvelope e = EventosAtencion.cambioEstado(a, anterior, "Error de registro", RELOJ);
        assertThat(e.tipo()).isEqualTo("atencion.anulada");
        assertThat(e.estadoAnterior()).isEqualTo("REGISTRADA");
        assertThat(e.estadoNuevo()).isEqualTo("ANULADA");
        assertThat(e.datos().get("motivo").asText()).isEqualTo("Error de registro");
    }

    @Test
    void motivoAusenteSeEmiteComoNull() {
        Atencion a = nueva();
        EstadoAtencion anterior = a.cambiarEstado(EstadoAtencion.FINALIZADA, RELOJ);
        EventoEnvelope e = EventosAtencion.cambioEstado(a, anterior, null, RELOJ);
        assertThat(e.datos().has("motivo")).isTrue();
        assertThat(e.datos().get("motivo").isNull()).isTrue();
    }

    @Test
    void routingKeyDeFinalizada() {
        assertThat(EventosAtencion.routingKeyDe(EstadoAtencion.FINALIZADA)).isEqualTo("atencion.finalizada");
    }
}
