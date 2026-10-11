package pe.edu.cibertec.vidasalud.auditoria.messaging;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.lang.reflect.Field;
import java.time.OffsetDateTime;
import java.util.Arrays;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import pe.edu.cibertec.vidasalud.auditoria.domain.SobreInvalidoException;
import pe.edu.cibertec.vidasalud.auditoria.persistence.EventoAuditoria;
import pe.edu.cibertec.vidasalud.commons.messaging.EventoEnvelope;

class EventoAuditoriaMapperTest {

    private static final OffsetDateTime OCURRIO = OffsetDateTime.parse("2026-10-12T09:30:00-05:00");
    private static final OffsetDateTime REGISTRO = OffsetDateTime.parse("2026-10-12T09:30:05-05:00");
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static EventoEnvelope sobre(String tipo, String anterior, String nuevo, JsonNode datos) {
        return new EventoEnvelope(UUID.randomUUID(), tipo, OCURRIO, "citas-service", "CITA",
                UUID.randomUUID(), anterior, nuevo, datos);
    }

    @Test
    void copiaTodosLosCampos() {
        EventoEnvelope s = sobre("cita.confirmada", "PENDIENTE", "CONFIRMADA", null);
        EventoAuditoria e = EventoAuditoriaMapper.aEntidad(s, REGISTRO);

        assertThat(e.getId()).isNotNull();
        assertThat(e.isNew()).isTrue();
        assertThat(e.getEventId()).isEqualTo(s.eventId());
        assertThat(e.getFechaEvento()).isEqualTo(OCURRIO);
        assertThat(e.getFechaRegistro()).isEqualTo(REGISTRO);
        assertThat(e.getTipoEvento()).isEqualTo("cita.confirmada");
        assertThat(e.getTipoRecurso()).isEqualTo("CITA");
        assertThat(e.getRecursoId()).isEqualTo(s.recursoId());
        assertThat(e.getEstadoAnterior()).isEqualTo("PENDIENTE");
        assertThat(e.getEstadoNuevo()).isEqualTo("CONFIRMADA");
        assertThat(e.getOrigen()).isEqualTo("citas-service");
    }

    @Test
    void creacionSinEstadoAnterior() {
        EventoAuditoria e = EventoAuditoriaMapper.aEntidad(sobre("cita.creada", null, "PENDIENTE", null), REGISTRO);
        assertThat(e.getEstadoAnterior()).isNull();
        assertThat(e.getEstadoNuevo()).isEqualTo("PENDIENTE");
    }

    @Test
    void datosNoSeGuardan() {
        assertThat(Arrays.stream(EventoAuditoria.class.getDeclaredFields()).map(Field::getName))
                .doesNotContain("datos");
        EventoEnvelope base = sobre("cita.creada", null, "PENDIENTE", null);
        EventoEnvelope conDatos = new EventoEnvelope(base.eventId(), base.tipo(), base.ocurrioEn(), base.origen(),
                base.tipoRecurso(), base.recursoId(), null, "PENDIENTE", MAPPER.valueToTree(
                        java.util.Map.of("codigo", "CIT-1")));
        EventoAuditoria a = EventoAuditoriaMapper.aEntidad(base, REGISTRO);
        EventoAuditoria b = EventoAuditoriaMapper.aEntidad(conDatos, REGISTRO);
        assertThat(b).usingRecursiveComparison().ignoringFields("id").isEqualTo(a);
    }

    @Test
    void sinRecursoIdEsInvalido() {
        EventoEnvelope b = sobre("cita.creada", null, "PENDIENTE", null);
        EventoEnvelope s = new EventoEnvelope(b.eventId(), b.tipo(), b.ocurrioEn(), b.origen(), b.tipoRecurso(),
                null, null, "PENDIENTE", null);
        assertThatThrownBy(() -> EventoAuditoriaMapper.aEntidad(s, REGISTRO))
                .isInstanceOf(SobreInvalidoException.class).hasMessageContaining("recursoId");
    }

    @ParameterizedTest
    @ValueSource(strings = {"tipoRecurso", "estadoNuevo", "origen", "ocurrioEn"})
    void camposObligatorios(String campo) {
        EventoEnvelope b = sobre("cita.creada", null, "PENDIENTE", null);
        EventoEnvelope s = new EventoEnvelope(b.eventId(), b.tipo(),
                campo.equals("ocurrioEn") ? null : b.ocurrioEn(),
                campo.equals("origen") ? null : b.origen(),
                campo.equals("tipoRecurso") ? " " : b.tipoRecurso(), b.recursoId(), null,
                campo.equals("estadoNuevo") ? null : "PENDIENTE", null);
        assertThatThrownBy(() -> EventoAuditoriaMapper.aEntidad(s, REGISTRO))
                .isInstanceOf(SobreInvalidoException.class).hasMessageContaining(campo);
    }

    @Test
    void origenDemasiadoLargo() {
        EventoEnvelope b = sobre("cita.creada", null, "PENDIENTE", null);
        EventoEnvelope s = new EventoEnvelope(b.eventId(), b.tipo(), b.ocurrioEn(), "x".repeat(41),
                b.tipoRecurso(), b.recursoId(), null, "PENDIENTE", null);
        assertThatThrownBy(() -> EventoAuditoriaMapper.aEntidad(s, REGISTRO))
                .isInstanceOf(SobreInvalidoException.class).hasMessageContaining("origen");
    }
}
