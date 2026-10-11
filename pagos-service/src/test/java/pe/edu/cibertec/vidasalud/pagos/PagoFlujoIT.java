package pe.edu.cibertec.vidasalud.pagos;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.rabbitmq.client.Channel;
import com.rabbitmq.client.Connection;
import com.rabbitmq.client.ConnectionFactory;
import com.rabbitmq.client.GetResponse;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import okhttp3.mockwebserver.Dispatcher;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.springframework.web.reactive.function.client.WebClient;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.containers.RabbitMQContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import pe.edu.cibertec.vidasalud.pagos.client.PacientesClient;
import reactor.core.publisher.Flux;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
@ActiveProfiles("test")
class PagoFlujoIT {

    private static final String COLA = "pagos.prueba.cola";
    private static final ObjectMapper JSON = new ObjectMapper();

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16");
    @Container
    static final RabbitMQContainer RABBIT = new RabbitMQContainer("rabbitmq:3.13-management");
    static final MockWebServer PACIENTES = new MockWebServer();

    static {
        try {
            PACIENTES.start();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @DynamicPropertySource
    static void propiedades(DynamicPropertyRegistry r) {
        r.add("hospital.rabbit.enabled", () -> "true");
        r.add("hospital.rabbit.host", RABBIT::getHost);
        r.add("hospital.rabbit.port", RABBIT::getAmqpPort);
        r.add("hospital.rabbit.username", RABBIT::getAdminUsername);
        r.add("hospital.rabbit.password", RABBIT::getAdminPassword);
        r.add("eureka.client.enabled", () -> "false");
    }

    @TestConfiguration
    static class PacientesFalso {
        @Bean
        @Primary
        PacientesClient pacientesClientPrueba() {
            return new PacientesClient(WebClient.builder().baseUrl(PACIENTES.url("/").toString()).build());
        }
    }

    @Autowired WebTestClient cliente;
    @Autowired DatabaseClient db;
    @LocalServerPort int puerto;

    private Connection conexion;
    private Channel canal;

    @BeforeAll
    static void simularPacientes() {
        PACIENTES.setDispatcher(new Dispatcher() {
            @Override
            public MockResponse dispatch(RecordedRequest peticion) {
                String id = peticion.getPath().substring(peticion.getPath().lastIndexOf('/') + 1);
                return new MockResponse().setResponseCode(200).setHeader("Content-Type", "application/json")
                        .setBody("{\"id\":\"" + id + "\",\"estado\":\"ACTIVO\"}")
                        .setHeadersDelay(500, TimeUnit.MILLISECONDS);
            }
        });
    }

    @AfterAll
    static void cerrar() throws IOException {
        PACIENTES.shutdown();
    }

    @BeforeEach
    void prepararCola() throws Exception {
        ConnectionFactory f = new ConnectionFactory();
        f.setHost(RABBIT.getHost());
        f.setPort(RABBIT.getAmqpPort());
        f.setUsername(RABBIT.getAdminUsername());
        f.setPassword(RABBIT.getAdminPassword());
        conexion = f.newConnection();
        canal = conexion.createChannel();
        canal.exchangeDeclare("hospital.eventos", "topic", true);
        canal.queueDeclare(COLA, false, false, false, null);
        canal.queueBind(COLA, "hospital.eventos", "pago.registrado");
        canal.queuePurge(COLA);
    }

    @AfterEach
    void cerrarConexion() throws Exception {
        conexion.close();
    }

    private static String cuerpo(String monto) {
        return "{\"pacienteId\":\"" + UUID.randomUUID() + "\",\"concepto\":\"Reserva de cita\",\"monto\":" + monto
                + ",\"medioPago\":\"TARJETA\"}";
    }

    private long filas(String clave) {
        return db.sql("SELECT count(*) FROM pago WHERE clave_idempotencia = :c").bind("c", clave)
                .map(f -> f.get(0, Long.class)).one().block();
    }

    private int mensajes() throws IOException {
        return canal.queueDeclarePassive(COLA).getMessageCount();
    }

    private WebTestClient.ResponseSpec post(String clave, String json) {
        return cliente.post().uri("/api/pagos").contentType(MediaType.APPLICATION_JSON)
                .header("Idempotency-Key", clave).bodyValue(json).exchange();
    }

    @Test
    void postGuardaFilaYPublicaMensaje() throws Exception {
        String clave = UUID.randomUUID().toString();
        byte[] cuerpoRespuesta = post(clave, cuerpo("80.00")).expectStatus().isCreated()
                .expectBody().jsonPath("$.numeroOperacion").value(n -> assertThat((String) n).startsWith("OP-"))
                .returnResult().getResponseBody();
        JsonNode pago = JSON.readTree(cuerpoRespuesta);

        assertThat(filas(clave)).isEqualTo(1);
        await().atMost(Duration.ofSeconds(10)).until(() -> mensajes() == 1);
        GetResponse m = canal.basicGet(COLA, true);
        JsonNode evento = JSON.readTree(m.getBody());
        assertThat(evento.get("tipo").asText()).isEqualTo("pago.registrado");
        assertThat(evento.get("recursoId").asText()).isEqualTo(pago.get("id").asText());
        assertThat(evento.get("datos").get("numeroOperacion").asText()).isEqualTo(pago.get("numeroOperacion").asText());
    }

    @Test
    void mismoPostDosVecesGeneraUnaFilaYUnMensaje() throws Exception {
        String clave = UUID.randomUUID().toString();
        String json = cuerpo("80.00");
        JsonNode primero = JSON.readTree(post(clave, json).expectStatus().isCreated()
                .expectHeader().doesNotExist("Idempotent-Replayed").expectBody().returnResult().getResponseBody());
        JsonNode segundo = JSON.readTree(post(clave, json).expectStatus().isCreated()
                .expectHeader().valueEquals("Idempotent-Replayed", "true").expectBody().returnResult()
                .getResponseBody());

        assertThat(segundo.get("id")).isEqualTo(primero.get("id"));
        assertThat(segundo.get("numeroOperacion")).isEqualTo(primero.get("numeroOperacion"));
        assertThat(filas(clave)).isEqualTo(1);
        await().atMost(Duration.ofSeconds(10)).until(() -> mensajes() == 1);
        Thread.sleep(1000);
        assertThat(mensajes()).isEqualTo(1);
    }

    @Test
    void dosPostSimultaneosGeneranUnaFila() throws Exception {
        String clave = UUID.randomUUID().toString();
        String json = cuerpo("80.00");
        WebClient web = WebClient.create("http://localhost:" + puerto);
        List<ResponseEntity<String>> respuestas = Flux.merge(llamar(web, clave, json), llamar(web, clave, json))
                .collectList().block(Duration.ofSeconds(15));

        assertThat(respuestas).hasSize(2).allMatch(r -> r.getStatusCode().value() == 201);
        assertThat(JSON.readTree(respuestas.get(0).getBody()).get("id"))
                .isEqualTo(JSON.readTree(respuestas.get(1).getBody()).get("id"));
        assertThat(respuestas).anyMatch(r -> "true".equals(r.getHeaders().getFirst("Idempotent-Replayed")));
        assertThat(filas(clave)).isEqualTo(1);
        await().atMost(Duration.ofSeconds(10)).until(() -> mensajes() == 1);
        Thread.sleep(1000);
        assertThat(mensajes()).isEqualTo(1);
    }

    private static reactor.core.publisher.Mono<ResponseEntity<String>> llamar(WebClient web, String clave, String json) {
        return web.post().uri("/api/pagos").contentType(MediaType.APPLICATION_JSON)
                .header("Idempotency-Key", clave).bodyValue(json).retrieve().toEntity(String.class);
    }

    @Test
    void mismaClaveConOtroMontoEs422() {
        String clave = UUID.randomUUID().toString();
        String json = cuerpo("80.00");
        post(clave, json).expectStatus().isCreated();
        post(clave, json.replace("80.00", "90.00")).expectStatus().isEqualTo(422)
                .expectBody().jsonPath("$.codigo").isEqualTo("CLAVE_IDEMPOTENCIA_REUTILIZADA");
        assertThat(filas(clave)).isEqualTo(1);
    }

    @Test
    void montoCeroEs400SinFilas() {
        String clave = UUID.randomUUID().toString();
        post(clave, cuerpo("0")).expectStatus().isBadRequest()
                .expectBody().jsonPath("$.codigo").isEqualTo("MONTO_INVALIDO");
        assertThat(filas(clave)).isZero();
    }
}
