package pe.edu.cibertec.vidasalud.notificaciones.domain;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.NullNode;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class DatosEventoTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final UUID PACIENTE = UUID.fromString("11111111-1111-4111-8111-111111111111");
    private static final UUID CITA = UUID.fromString("22222222-2222-4222-8222-222222222222");

    private static JsonNode json(String texto) throws Exception {
        return MAPPER.readTree(texto);
    }

    @Test
    void leeTodosLosCamposDeUnaCita() throws Exception {
        JsonNode datos = json("""
                {"pacienteId":"%s","codigo":"CIT-1","especialidadNombre":"Medicina General",
                 "fechaHora":"2026-10-15T10:00:00-05:00","fechaHoraAnterior":"2026-10-14T14:30:00Z",
                 "areaNombre":"Consulta Externa","fechaAtencion":"2026-10-15T15:05:00Z","citaId":"%s",
                 "monto":80.00,"numeroOperacion":"OP-1"}""".formatted(PACIENTE, CITA));

        DatosEvento d = DatosEvento.desde("cita.creada", CITA, datos);

        assertThat(d.pacienteId()).isEqualTo(PACIENTE);
        assertThat(d.codigo()).isEqualTo("CIT-1");
        assertThat(d.especialidadNombre()).isEqualTo("Medicina General");
        assertThat(d.fechaHora().toInstant()).isEqualTo(OffsetDateTime.parse("2026-10-15T15:00:00Z").toInstant());
        assertThat(d.fechaHoraAnterior().toInstant())
                .isEqualTo(OffsetDateTime.parse("2026-10-14T14:30:00Z").toInstant());
        assertThat(d.areaNombre()).isEqualTo("Consulta Externa");
        assertThat(d.fechaAtencion()).isNotNull();
        assertThat(d.citaId()).isEqualTo(CITA);
        assertThat(d.monto()).isEqualByComparingTo("80.00");
        assertThat(d.numeroOperacion()).isEqualTo("OP-1");
    }

    @Test
    void usaEspecialidadYAreaCuandoFaltanLosNombres() throws Exception {
        DatosEvento d = DatosEvento.desde("cita.creada", CITA,
                json("{\"especialidad\":\"PEDIATRIA\",\"area\":\"EMERGENCIA\"}"));

        assertThat(d.especialidadNombre()).isEqualTo("PEDIATRIA");
        assertThat(d.areaNombre()).isEqualTo("EMERGENCIA");
    }

    @Test
    void pacienteIdFaltanteUsaRecursoSoloEnPacienteRegistrado() throws Exception {
        assertThat(DatosEvento.desde("paciente.registrado", PACIENTE, json("{}")).pacienteId()).isEqualTo(PACIENTE);
        assertThat(DatosEvento.desde("cita.creada", CITA, json("{}")).pacienteId()).isNull();
    }

    @Test
    void pacienteIdInvalidoQuedaNulo() throws Exception {
        assertThat(DatosEvento.desde("cita.creada", CITA, json("{\"pacienteId\":\"abc\"}")).pacienteId()).isNull();
    }

    @Test
    void fechaInvalidaQuedaNulaYSinOffsetSeInterpretaEnLima() throws Exception {
        assertThat(DatosEvento.desde("cita.creada", CITA, json("{\"fechaHora\":\"mañana\"}")).fechaHora()).isNull();

        OffsetDateTime f = DatosEvento.desde("cita.creada", CITA,
                json("{\"fechaHora\":\"2026-10-15T10:00:00\"}")).fechaHora();

        assertThat(f.toInstant()).isEqualTo(OffsetDateTime.parse("2026-10-15T10:00:00-05:00").toInstant());
    }

    @Test
    void montoAceptaNumerosYTexto() throws Exception {
        for (String m : new String[] {"80", "80.0", "80.00", "\"80.00\""}) {
            BigDecimal monto = DatosEvento.desde("pago.registrado", CITA, json("{\"monto\":" + m + "}")).monto();
            assertThat(monto).isEqualByComparingTo("80.00");
        }
        assertThat(DatosEvento.desde("pago.registrado", CITA, json("{\"monto\":\"mucho\"}")).monto()).isNull();
    }

    @Test
    void datosNulosDejanTodoNulo() {
        for (JsonNode datos : new JsonNode[] {null, NullNode.getInstance()}) {
            DatosEvento d = DatosEvento.desde("cita.creada", CITA, datos);
            assertThat(d).isEqualTo(new DatosEvento(null, null, null, null, null, null, null, null, null, null));
        }
    }
}
