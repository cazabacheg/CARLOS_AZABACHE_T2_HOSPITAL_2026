package pe.edu.cibertec.vidasalud.auditoria.messaging;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.rabbitmq.client.Channel;
import com.rabbitmq.client.Connection;
import com.rabbitmq.client.ConnectionFactory;
import com.rabbitmq.client.GetResponse;
import com.rabbitmq.client.MessageProperties;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.reactive.AutoConfigureWebTestClient;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.containers.RabbitMQContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import pe.edu.cibertec.vidasalud.auditoria.persistence.EventoAuditoria;
import pe.edu.cibertec.vidasalud.auditoria.persistence.EventoAuditoriaRepository;
import pe.edu.cibertec.vidasalud.commons.messaging.EventoEnvelope;
import pe.edu.cibertec.vidasalud.commons.messaging.PublicadorEventos;
import pe.edu.cibertec.vidasalud.commons.messaging.TopologiaHospital;

@SpringBootTest(properties = "eureka.client.enabled=false")
@AutoConfigureWebTestClient
@Testcontainers
@ActiveProfiles("test")
class AuditoriaConsumerIT {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16");
    @Container
    static RabbitMQContainer RABBIT = new RabbitMQContainer("rabbitmq:3.13-management");

    @DynamicPropertySource
    static void rabbit(DynamicPropertyRegistry r) {
        r.add("hospital.rabbit.host", RABBIT::getHost);
        r.add("hospital.rabbit.port", RABBIT::getAmqpPort);
        r.add("hospital.rabbit.username", RABBIT::getAdminUsername);
        r.add("hospital.rabbit.password", RABBIT::getAdminPassword);
    }

    private static final String COLA = TopologiaHospital.COLA_AUDITORIA;
    private static final String DLQ = "auditoria.dlq";
    private static final OffsetDateTime T1 = OffsetDateTime.parse("2026-10-12T09:00:00-05:00");
    private static final OffsetDateTime T2 = OffsetDateTime.parse("2026-10-12T10:00:00-05:00");
    private static final OffsetDateTime T3 = OffsetDateTime.parse("2026-10-12T11:00:00-05:00");

    static Connection conexionDirecta;

    @Autowired
    PublicadorEventos publicador;
    @Autowired
    EventoAuditoriaRepository repositorio;
    @Autowired
    WebTestClient cliente;
    @Autowired
    ObjectMapper mapper;
    @Autowired
    DatabaseClient db;

    @BeforeAll
    static void conectar() throws Exception {
        ConnectionFactory f = new ConnectionFactory();
        f.setHost(RABBIT.getHost());
        f.setPort(RABBIT.getAmqpPort());
        f.setUsername(RABBIT.getAdminUsername());
        f.setPassword(RABBIT.getAdminPassword());
        conexionDirecta = f.newConnection();
    }

    @AfterAll
    static void cerrar() throws Exception {
        if (conexionDirecta != null) {
            conexionDirecta.close();
        }
    }

    @BeforeEach
    void esperarConsumidor() throws Exception {
        try (Channel canal = conexionDirecta.createChannel()) {
            await().atMost(Duration.ofSeconds(30)).ignoreExceptions().until(() -> {
                try (Channel c = conexionDirecta.createChannel()) {
                    return c.queueDeclarePassive(COLA).getConsumerCount() >= 1;
                }
            });
            canal.queuePurge(DLQ);
        }
    }

    private long mensajesEn(String cola) throws Exception {
        try (Channel c = conexionDirecta.createChannel()) {
            return c.queueDeclarePassive(cola).getMessageCount();
        }
    }

    private EventoEnvelope sobre(String tipo, OffsetDateTime cuando, String origen, String tipoRecurso,
                                 UUID recursoId, String anterior, String nuevo) {
        JsonNode datos = mapper.valueToTree(Map.of("codigo", "CIT-20261012-000001"));
        return new EventoEnvelope(UUID.randomUUID(), tipo, cuando, origen, tipoRecurso, recursoId,
                anterior, nuevo, datos);
    }

    private void publicar(String routingKey, EventoEnvelope sobre) {
        publicador.publicar(routingKey, sobre).block(Duration.ofSeconds(10));
    }

    private List<EventoAuditoria> historial(String tipoRecurso, UUID recursoId) {
        return repositorio.historial(tipoRecurso, recursoId).collectList().block();
    }

    @Test
    void citaConfirmadaQuedaRegistrada() throws Exception {
        UUID cita = UUID.randomUUID();
        EventoEnvelope s = sobre("cita.confirmada", T1, "citas-service", "CITA", cita, "PENDIENTE", "CONFIRMADA");
        publicar(TopologiaHospital.CITA_CONFIRMADA, s);

        await().atMost(Duration.ofSeconds(15)).untilAsserted(() -> {
            List<EventoAuditoria> filas = historial("CITA", cita);
            assertThat(filas).hasSize(1);
            EventoAuditoria e = filas.get(0);
            assertThat(e.getTipoEvento()).isEqualTo("cita.confirmada");
            assertThat(e.getTipoRecurso()).isEqualTo("CITA");
            assertThat(e.getRecursoId()).isEqualTo(cita);
            assertThat(e.getEstadoAnterior()).isEqualTo("PENDIENTE");
            assertThat(e.getEstadoNuevo()).isEqualTo("CONFIRMADA");
            assertThat(e.getOrigen()).isEqualTo("citas-service");
            assertThat(e.getEventId()).isEqualTo(s.eventId());
            assertThat(e.getFechaEvento().isEqual(T1)).isTrue();
            assertThat(e.getFechaRegistro()).isNotNull();
        });
        assertThat(mensajesEn(DLQ)).isZero();
    }

    @Test
    void eventoRepetidoGeneraUnaSolaFila() throws Exception {
        UUID cita = UUID.randomUUID();
        EventoEnvelope s = sobre("cita.confirmada", T1, "citas-service", "CITA", cita, "PENDIENTE", "CONFIRMADA");
        publicar(TopologiaHospital.CITA_CONFIRMADA, s);
        publicar(TopologiaHospital.CITA_CONFIRMADA, s);

        await().during(Duration.ofSeconds(2)).atMost(Duration.ofSeconds(15))
                .until(() -> historial("CITA", cita).size() == 1);
        await().atMost(Duration.ofSeconds(10)).until(() -> mensajesEn(COLA) == 0);
        assertThat(mensajesEn(DLQ)).isZero();
    }

    @Test
    void cualquierRoutingKeyLlegaPorAlmohadilla() {
        UUID pago = UUID.randomUUID();
        UUID reporte = UUID.randomUUID();
        publicar(TopologiaHospital.PAGO_ANULADO,
                sobre("pago.anulado", T1, "pagos-service", "PAGO", pago, "REGISTRADO", "ANULADO"));
        publicar("reporte.generado",
                sobre("reporte.generado", T1, "reportes-service", "REPORTE", reporte, null, "GENERADO"));

        await().atMost(Duration.ofSeconds(15)).untilAsserted(() -> {
            assertThat(historial("PAGO", pago)).hasSize(1);
            assertThat(historial("REPORTE", reporte)).hasSize(1);
        });
    }

    @Test
    void historialCronologicoAunqueLleguenEnDesorden() {
        UUID cita = UUID.randomUUID();
        publicar(TopologiaHospital.CITA_ATENDIDA,
                sobre("cita.atendida", T3, "citas-service", "CITA", cita, "CONFIRMADA", "ATENDIDA"));
        publicar(TopologiaHospital.CITA_CREADA,
                sobre("cita.creada", T1, "citas-service", "CITA", cita, null, "PENDIENTE"));
        publicar(TopologiaHospital.CITA_CONFIRMADA,
                sobre("cita.confirmada", T2, "citas-service", "CITA", cita, "PENDIENTE", "CONFIRMADA"));

        await().atMost(Duration.ofSeconds(15)).until(() -> historial("CITA", cita).size() == 3);

        cliente.get().uri("/api/auditoria/recursos/CITA/{id}/historial", cita).exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$[0].tipoEvento").isEqualTo("cita.creada")
                .jsonPath("$[0].estadoAnterior").isEmpty()
                .jsonPath("$[1].estadoNuevo").isEqualTo("CONFIRMADA")
                .jsonPath("$[2].estadoNuevo").isEqualTo("ATENDIDA");
    }

    @Test
    void jsonInvalidoVaALaDlq() throws Exception {
        byte[] cuerpo = "{esto no es json".getBytes(StandardCharsets.UTF_8);
        try (Channel canal = conexionDirecta.createChannel()) {
            canal.basicPublish(TopologiaHospital.EXCHANGE, "cita.cancelada",
                    MessageProperties.PERSISTENT_TEXT_PLAIN, cuerpo);
        }
        await().atMost(Duration.ofSeconds(15)).until(() -> mensajesEn(DLQ) == 1);
        try (Channel canal = conexionDirecta.createChannel()) {
            GetResponse r = canal.basicGet(DLQ, true);
            assertThat(r.getBody()).isEqualTo(cuerpo);
        }
    }

    @Test
    void sobreSinRecursoIdVaALaDlqTrasReintentos() throws Exception {
        EventoEnvelope s = sobre("cita.creada", T1, "citas-service", "CITA", null, null, "PENDIENTE");
        publicar(TopologiaHospital.CITA_CREADA, s);

        await().atMost(Duration.ofSeconds(20)).until(() -> mensajesEn(DLQ) == 1);
        Long filas = db.sql("SELECT count(*) FROM evento_auditoria WHERE event_id = :e")
                .bind("e", s.eventId()).map(row -> row.get(0, Long.class)).one().block();
        assertThat(filas).isZero();
    }
}
