package pe.edu.cibertec.vidasalud.notificaciones.messaging;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.rabbitmq.client.Channel;
import com.rabbitmq.client.Connection;
import com.rabbitmq.client.ConnectionFactory;
import com.rabbitmq.client.GetResponse;
import java.math.BigDecimal;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.util.Base64;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.reactive.AutoConfigureWebTestClient;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.data.r2dbc.core.R2dbcEntityTemplate;
import org.springframework.data.relational.core.query.Criteria;
import org.springframework.data.relational.core.query.Query;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.containers.RabbitMQContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import pe.edu.cibertec.vidasalud.commons.messaging.EventoEnvelope;
import pe.edu.cibertec.vidasalud.commons.messaging.PublicadorEventos;
import pe.edu.cibertec.vidasalud.commons.messaging.TopologiaHospital;
import pe.edu.cibertec.vidasalud.notificaciones.domain.Notificacion;
import pe.edu.cibertec.vidasalud.notificaciones.service.NotificacionService;
import reactor.core.publisher.Mono;
import reactor.rabbitmq.OutboundMessage;
import reactor.rabbitmq.Sender;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureWebTestClient
@Testcontainers
@ActiveProfiles("test")
class NotificacionesConsumerIT {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16");

    @Container
    static RabbitMQContainer RABBIT = new RabbitMQContainer("rabbitmq:3.13-management");

    @DynamicPropertySource
    static void rabbit(DynamicPropertyRegistry r) {
        r.add("hospital.rabbit.enabled", () -> "true");
        r.add("hospital.rabbit.host", RABBIT::getHost);
        r.add("hospital.rabbit.port", RABBIT::getAmqpPort);
        r.add("hospital.rabbit.username", RABBIT::getAdminUsername);
        r.add("hospital.rabbit.password", RABBIT::getAdminPassword);
    }

    private static final String COLA = "notificaciones.cola";
    private static final String DLQ = "notificaciones.dlq";

    @Autowired
    private PublicadorEventos publicador;
    @Autowired
    private Sender sender;
    @Autowired
    private R2dbcEntityTemplate template;
    @Autowired
    private ObjectMapper mapper;
    @Autowired
    private WebTestClient web;
    @MockitoSpyBean
    private NotificacionService servicio;

    private static Connection conexion;
    private static Channel canal;

    @BeforeAll
    static void conectar() throws Exception {
        ConnectionFactory f = new ConnectionFactory();
        f.setHost(RABBIT.getHost());
        f.setPort(RABBIT.getAmqpPort());
        f.setUsername(RABBIT.getAdminUsername());
        f.setPassword(RABBIT.getAdminPassword());
        conexion = f.newConnection();
        canal = conexion.createChannel();
    }

    @AfterAll
    static void cerrar() throws Exception {
        conexion.close();
    }

    @BeforeEach
    void esperarConsumidor() {
        await().atMost(Duration.ofSeconds(30)).ignoreExceptions()
                .until(() -> canal.queueDeclarePassive(COLA).getConsumerCount() == 1);
    }

    private long filasDe(UUID eventId) {
        Long n = template.select(Notificacion.class).matching(Query.query(Criteria.where("eventId").is(eventId)))
                .count().block();
        return n == null ? 0 : n;
    }

    private Notificacion filaDe(UUID eventId) {
        return template.select(Notificacion.class).matching(Query.query(Criteria.where("eventId").is(eventId)))
                .one().block();
    }

    private int mensajesEn(String cola) throws Exception {
        return canal.queueDeclarePassive(cola).getMessageCount();
    }

    private ObjectNode datosCita(UUID pacienteId) {
        ObjectNode d = mapper.createObjectNode();
        d.put("codigo", "CIT-20261012-000001");
        d.put("pacienteId", pacienteId.toString());
        d.put("especialidad", "MEDICINA_GENERAL");
        d.put("especialidadNombre", "Medicina General");
        d.put("fechaHora", "2026-10-15T15:00:00Z");
        d.put("profesionalCodigo", "MED-001");
        d.put("motivo", "Control");
        return d;
    }

    private EventoEnvelope evento(String tipo, String tipoRecurso, UUID recursoId, JsonNode datos) {
        return EventoEnvelope.nuevo(Clock.systemUTC(), tipo, "citas-service", tipoRecurso, recursoId,
                "PENDIENTE", "CONFIRMADA", datos);
    }

    private void publicar(EventoEnvelope e) {
        publicador.publicar(e.tipo(), e).block(Duration.ofSeconds(10));
    }

    @Test
    void citaConfirmadaGeneraFilaYSeConsultaPorApi() {
        UUID paciente = UUID.randomUUID();
        UUID cita = UUID.randomUUID();
        EventoEnvelope e = evento("cita.confirmada", "CITA", cita, datosCita(paciente));

        publicar(e);

        await().atMost(Duration.ofSeconds(15)).until(() -> filasDe(e.eventId()) == 1);
        Notificacion n = filaDe(e.eventId());
        assertThat(n.getMensaje())
                .isEqualTo("Su cita CIT-20261012-000001 de Medicina General del 15/10/2026 10:00 fue confirmada.");
        assertThat(n.getPacienteId()).isEqualTo(paciente);
        assertThat(n.getRecursoId()).isEqualTo(cita);
        assertThat(n.getTipoEvento()).isEqualTo("cita.confirmada");
        assertThat(n.getCanal()).isEqualTo("SIMULADO");

        web.get().uri("/api/notificaciones?pacienteId={p}", paciente).exchange()
                .expectStatus().isOk()
                .expectHeader().valueEquals("X-Total-Count", "1")
                .expectBody()
                .jsonPath("$.length()").isEqualTo(1)
                .jsonPath("$[0].mensaje").isEqualTo(n.getMensaje());
    }

    @Test
    void eventoDuplicadoNoCreaOtraFilaNiVaALaDlq() throws Exception {
        int dlqPrevia = mensajesEn(DLQ);
        EventoEnvelope e = evento("cita.confirmada", "CITA", UUID.randomUUID(), datosCita(UUID.randomUUID()));

        publicar(e);
        publicar(e);

        await().atMost(Duration.ofSeconds(8)).during(Duration.ofSeconds(2)).until(() -> filasDe(e.eventId()) == 1);
        assertThat(mensajesEn(DLQ)).isEqualTo(dlqPrevia);
        await().atMost(Duration.ofSeconds(5)).until(() -> mensajesEn(COLA) == 0);
    }

    @Test
    void jsonInvalidoTerminaEnLaDlq() throws Exception {
        int dlqPrevia = mensajesEn(DLQ);
        String cuerpo = "{esto no es json";

        sender.send(Mono.just(new OutboundMessage(TopologiaHospital.EXCHANGE, "cita.confirmada",
                cuerpo.getBytes(StandardCharsets.UTF_8)))).block(Duration.ofSeconds(10));

        await().atMost(Duration.ofSeconds(15)).until(() -> mensajesEn(DLQ) == dlqPrevia + 1);
        // La DLQ se comparte entre casos: se vacia buscando el mensaje propio.
        boolean encontrado = false;
        GetResponse r;
        while ((r = canal.basicGet(DLQ, true)) != null) {
            encontrado |= cuerpo.equals(new String(r.getBody(), StandardCharsets.UTF_8));
        }
        assertThat(encontrado).isTrue();
    }

    @Test
    void soloEnlazaLosOchoEventosYNoProcesaCitaAtendida() throws Exception {
        String auth = Base64.getEncoder().encodeToString(
                (RABBIT.getAdminUsername() + ":" + RABBIT.getAdminPassword()).getBytes(StandardCharsets.UTF_8));
        HttpResponse<String> resp = HttpClient.newHttpClient().send(
                HttpRequest.newBuilder(URI.create(RABBIT.getHttpUrl() + "/api/queues/%2F/" + COLA + "/bindings"))
                        .header("Authorization", "Basic " + auth).build(),
                HttpResponse.BodyHandlers.ofString());
        Set<String> claves = new HashSet<>();
        for (JsonNode b : mapper.readTree(resp.body())) {
            if ("hospital.eventos".equals(b.path("source").asText())) {
                claves.add(b.path("routing_key").asText());
            }
        }
        assertThat(claves).containsExactlyInAnyOrderElementsOf(BindingsNotificaciones.TODOS)
                .doesNotContain("cita.atendida");

        EventoEnvelope ignorado = evento("cita.atendida", "CITA", UUID.randomUUID(), datosCita(UUID.randomUUID()));
        EventoEnvelope centinela = evento("cita.confirmada", "CITA", UUID.randomUUID(), datosCita(UUID.randomUUID()));
        publicar(ignorado);
        publicar(centinela);

        await().atMost(Duration.ofSeconds(15)).until(() -> filasDe(centinela.eventId()) == 1);
        verify(servicio, never()).procesar(argThat(e -> e.eventId().equals(ignorado.eventId())));
        assertThat(filasDe(ignorado.eventId())).isZero();
    }

    @Test
    void pagoRegistradoUsaLaPlantillaDeLaFaseB() {
        ObjectNode datos = mapper.createObjectNode();
        datos.put("pacienteId", UUID.randomUUID().toString());
        datos.put("monto", new BigDecimal("80.00"));
        datos.put("numeroOperacion", "OP-20261012-000001");
        EventoEnvelope e = evento("pago.registrado", "PAGO", UUID.randomUUID(), datos);

        publicar(e);

        await().atMost(Duration.ofSeconds(15)).until(() -> filasDe(e.eventId()) == 1);
        assertThat(filaDe(e.eventId()).getMensaje())
                .isEqualTo("Se registró su pago de S/ 80.00 (operación OP-20261012-000001).");
    }
}
