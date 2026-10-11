package pe.edu.cibertec.vidasalud.citas;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.rabbitmq.client.Channel;
import com.rabbitmq.client.Connection;
import com.rabbitmq.client.ConnectionFactory;
import com.rabbitmq.client.GetResponse;
import java.io.IOException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.UUID;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.springframework.http.MediaType;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.reactive.server.EntityExchangeResult;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.containers.RabbitMQContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
@ActiveProfiles("test")
@DirtiesContext
class CitaFlujoIT {

    static final Clock RELOJ = Clock.fixed(Instant.parse("2026-10-14T14:00:00Z"), ZoneId.of("America/Lima"));
    static final String COLA_PRUEBA = "prueba.citas.flujo";

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16");
    @Container
    static final RabbitMQContainer RABBIT = new RabbitMQContainer("rabbitmq:3.13-management");
    static final MockWebServer PACIENTES = iniciar();

    private static MockWebServer iniciar() {
        try {
            MockWebServer servidor = new MockWebServer();
            servidor.start();
            return servidor;
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    @DynamicPropertySource
    static void propiedades(DynamicPropertyRegistry r) {
        r.add("hospital.rabbit.enabled", () -> "true");
        r.add("hospital.rabbit.host", RABBIT::getHost);
        r.add("hospital.rabbit.port", RABBIT::getAmqpPort);
        r.add("hospital.rabbit.username", RABBIT::getAdminUsername);
        r.add("hospital.rabbit.password", RABBIT::getAdminPassword);
        r.add("spring.cloud.discovery.client.simple.instances.pacientes-service[0].uri",
                () -> "http://localhost:" + PACIENTES.getPort());
    }

    @TestConfiguration
    static class RelojFijo {
        @Bean
        Clock reloj() {
            return RELOJ;
        }
    }

    static Connection conexion;
    static Channel canal;

    @Autowired
    WebTestClient cliente;
    @Autowired
    ObjectMapper mapper;

    @BeforeAll
    static void conectar() throws Exception {
        ConnectionFactory f = new ConnectionFactory();
        f.setHost(RABBIT.getHost());
        f.setPort(RABBIT.getAmqpPort());
        f.setUsername(RABBIT.getAdminUsername());
        f.setPassword(RABBIT.getAdminPassword());
        conexion = f.newConnection();
        canal = conexion.createChannel();
        canal.exchangeDeclare("hospital.eventos", "topic", true);
        canal.queueDeclare(COLA_PRUEBA, true, false, false, null);
        canal.queueBind(COLA_PRUEBA, "hospital.eventos", "cita.confirmada");
    }

    @AfterAll
    static void cerrar() throws Exception {
        conexion.close();
        PACIENTES.shutdown();
    }

    @Test
    void programarConsultarYConfirmarPublicaElEvento() throws Exception {
        UUID pacienteId = UUID.randomUUID();
        PACIENTES.enqueue(new MockResponse().setHeader("Content-Type", "application/json")
                .setBody("{\"id\":\"" + pacienteId + "\",\"estado\":\"ACTIVO\"}"));

        EntityExchangeResult<byte[]> creada = cliente.post().uri("/api/citas").contentType(MediaType.APPLICATION_JSON)
                .bodyValue("{\"pacienteId\":\"" + pacienteId + "\",\"especialidad\":\"MEDICINA_GENERAL\","
                        + "\"fechaHora\":\"2026-10-15T10:00:00-05:00\",\"profesionalCodigo\":\"MED-001\","
                        + "\"motivo\":\"Control\"}")
                .exchange()
                .expectStatus().isCreated()
                .expectHeader().exists("Location")
                .expectBody().jsonPath("$.codigo").value(c -> assertThat((String) c).startsWith("CIT-20261014-"))
                .returnResult();
        JsonNode cita = mapper.readTree(creada.getResponseBody());
        String id = cita.get("id").asText();

        RecordedRequest peticion = PACIENTES.takeRequest(5, java.util.concurrent.TimeUnit.SECONDS);
        assertThat(peticion).isNotNull();
        assertThat(peticion.getPath()).isEqualTo("/api/pacientes/" + pacienteId);

        cliente.get().uri("/api/citas/" + id).exchange().expectStatus().isOk()
                .expectBody().jsonPath("$.fechaHora").isEqualTo("2026-10-15T10:00:00-05:00");

        cliente.patch().uri("/api/citas/" + id + "/estado").contentType(MediaType.APPLICATION_JSON)
                .bodyValue("{\"estado\":\"CONFIRMADA\",\"motivo\":\"Confirmada por teléfono\"}")
                .exchange().expectStatus().isOk()
                .expectBody().jsonPath("$.estado").isEqualTo("CONFIRMADA");

        GetResponse[] mensaje = new GetResponse[1];
        await().atMost(Duration.ofSeconds(10)).until(() -> {
            mensaje[0] = canal.basicGet(COLA_PRUEBA, true);
            return mensaje[0] != null;
        });
        JsonNode sobre = mapper.readTree(mensaje[0].getBody());
        assertThat(sobre.get("tipo").asText()).isEqualTo("cita.confirmada");
        assertThat(sobre.get("origen").asText()).isEqualTo("citas-service");
        assertThat(sobre.get("tipoRecurso").asText()).isEqualTo("CITA");
        assertThat(sobre.get("recursoId").asText()).isEqualTo(id);
        assertThat(sobre.get("estadoAnterior").asText()).isEqualTo("PENDIENTE");
        assertThat(sobre.get("estadoNuevo").asText()).isEqualTo("CONFIRMADA");
        JsonNode datos = sobre.get("datos");
        assertThat(datos.get("codigo").asText()).isEqualTo(cita.get("codigo").asText());
        assertThat(datos.get("pacienteId").asText()).isEqualTo(pacienteId.toString());
        assertThat(datos.get("especialidad").asText()).isEqualTo("MEDICINA_GENERAL");
        assertThat(datos.get("especialidadNombre").asText()).isEqualTo("Medicina General");
        assertThat(datos.get("fechaHora").asText()).isEqualTo("2026-10-15T10:00:00-05:00");
        assertThat(datos.get("profesionalCodigo").asText()).isEqualTo("MED-001");
        assertThat(datos.get("motivo").asText()).isEqualTo("Confirmada por teléfono");
    }
}
