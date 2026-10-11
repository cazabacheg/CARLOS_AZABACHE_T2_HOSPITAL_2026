package pe.edu.cibertec.vidasalud.pacientes;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.rabbitmq.client.Channel;
import com.rabbitmq.client.Connection;
import com.rabbitmq.client.ConnectionFactory;
import com.rabbitmq.client.GetResponse;
import java.time.Duration;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.reactive.server.EntityExchangeResult;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.containers.RabbitMQContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import pe.edu.cibertec.vidasalud.commons.messaging.TopologiaHospital;
import pe.edu.cibertec.vidasalud.pacientes.persistence.PacienteRepository;
import reactor.test.StepVerifier;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "eureka.client.enabled=false")
@ActiveProfiles("test")
@Testcontainers
class PacienteFlujoIT {

    static final String COLA_PRUEBA = "prueba.pacientes.cola";

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16");

    @Container
    static final RabbitMQContainer RABBIT = new RabbitMQContainer("rabbitmq:3.13-management");

    @DynamicPropertySource
    static void rabbit(DynamicPropertyRegistry r) {
        r.add("hospital.rabbit.enabled", () -> "true");
        r.add("hospital.rabbit.host", RABBIT::getHost);
        r.add("hospital.rabbit.port", RABBIT::getAmqpPort);
        r.add("hospital.rabbit.username", RABBIT::getAdminUsername);
        r.add("hospital.rabbit.password", RABBIT::getAdminPassword);
    }

    static Connection conexion;
    static Channel canal;

    @BeforeAll
    static void declararColaDePrueba() throws Exception {
        ConnectionFactory fabrica = new ConnectionFactory();
        fabrica.setHost(RABBIT.getHost());
        fabrica.setPort(RABBIT.getAmqpPort());
        fabrica.setUsername(RABBIT.getAdminUsername());
        fabrica.setPassword(RABBIT.getAdminPassword());
        conexion = fabrica.newConnection();
        canal = conexion.createChannel();
        canal.exchangeDeclare(TopologiaHospital.EXCHANGE, "topic", true);
        canal.queueDeclare(COLA_PRUEBA, false, false, false, null);
        canal.queueBind(COLA_PRUEBA, TopologiaHospital.EXCHANGE, TopologiaHospital.PACIENTE_REGISTRADO);
        canal.queueBind(COLA_PRUEBA, TopologiaHospital.EXCHANGE, TopologiaHospital.PACIENTE_ESTADO_CAMBIADO);
    }

    @AfterAll
    static void cerrar() throws Exception {
        conexion.close();
    }

    @BeforeEach
    void vaciarCola() throws Exception {
        canal.queuePurge(COLA_PRUEBA);
    }

    @Autowired
    WebTestClient cliente;
    @Autowired
    ObjectMapper mapper;
    @Autowired
    PacienteRepository repositorio;

    private static Map<String, Object> cuerpo(String numero) {
        return Map.of("tipoDocumento", "DNI", "numeroDocumento", numero, "nombres", "Ana María",
                "apellidos", "Prueba Ficticia", "fechaNacimiento", "1990-05-20",
                "telefono", "999000111", "correo", "ana.prueba@example.com");
    }

    private UUID registrar(String numero) {
        EntityExchangeResult<byte[]> resultado = cliente.post().uri("/api/pacientes")
                .contentType(MediaType.APPLICATION_JSON).bodyValue(cuerpo(numero))
                .exchange()
                .expectStatus().isCreated()
                .expectBody().returnResult();
        return UUID.fromString(leer(resultado.getResponseBody()).get("id").asText());
    }

    private JsonNode leer(byte[] json) {
        try {
            return mapper.readTree(json);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private String siguienteMensaje() {
        AtomicReference<String> cuerpo = new AtomicReference<>();
        await().atMost(Duration.ofSeconds(10)).until(() -> {
            GetResponse respuesta = canal.basicGet(COLA_PRUEBA, true);
            if (respuesta != null) {
                cuerpo.set(new String(respuesta.getBody(), java.nio.charset.StandardCharsets.UTF_8));
            }
            return respuesta != null;
        });
        return cuerpo.get();
    }

    @Test
    void registroRealGuardaFilaYPublicaEventoSinDatosPersonales() throws Exception {
        UUID id = registrar("00000101");

        StepVerifier.create(repositorio.findById(id)).expectNextCount(1).verifyComplete();
        String mensaje = siguienteMensaje();
        JsonNode evento = mapper.readTree(mensaje);
        assertThat(evento.get("tipo").asText()).isEqualTo("paciente.registrado");
        assertThat(evento.get("origen").asText()).isEqualTo("pacientes-service");
        assertThat(evento.get("tipoRecurso").asText()).isEqualTo("PACIENTE");
        assertThat(evento.get("recursoId").asText()).isEqualTo(id.toString());
        assertThat(evento.get("estadoAnterior").isNull()).isTrue();
        assertThat(evento.get("estadoNuevo").asText()).isEqualTo("ACTIVO");
        assertThat(evento.get("datos").get("pacienteId").asText()).isEqualTo(id.toString());
        assertThat(mensaje).doesNotContain("00000101", "Ana", "Prueba Ficticia", "999000111", "example.com");
    }

    @Test
    void registroDuplicadoDevuelve409() {
        registrar("00000102");

        cliente.post().uri("/api/pacientes").contentType(MediaType.APPLICATION_JSON).bodyValue(cuerpo("00000102"))
                .exchange()
                .expectStatus().isEqualTo(409)
                .expectBody().jsonPath("$.codigo").isEqualTo("PACIENTE_DUPLICADO");
    }

    @Test
    void consultaInexistenteDevuelve404() {
        cliente.get().uri("/api/pacientes/{id}", UUID.randomUUID())
                .exchange()
                .expectStatus().isNotFound()
                .expectBody().jsonPath("$.codigo").isEqualTo("PACIENTE_NO_ENCONTRADO");
    }

    @Test
    void listadoPorDocumentoDevuelveUnoConTotal() {
        registrar("00000103");

        cliente.get().uri("/api/pacientes?tipoDocumento=DNI&numeroDocumento=00000103")
                .exchange()
                .expectStatus().isOk()
                .expectHeader().valueEquals("X-Total-Count", "1")
                .expectBody().jsonPath("$.length()").isEqualTo(1);
    }

    @Test
    void cambioDeEstadoPublicaEventoYRepetirDevuelve409() throws Exception {
        UUID id = registrar("00000104");
        siguienteMensaje();

        cliente.patch().uri("/api/pacientes/{id}/estado", id).contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("estado", "INACTIVO", "motivo", "Prueba"))
                .exchange()
                .expectStatus().isOk()
                .expectBody().jsonPath("$.estado").isEqualTo("INACTIVO");

        JsonNode evento = mapper.readTree(siguienteMensaje());
        assertThat(evento.get("tipo").asText()).isEqualTo("paciente.estado-cambiado");
        assertThat(evento.get("estadoAnterior").asText()).isEqualTo("ACTIVO");
        assertThat(evento.get("estadoNuevo").asText()).isEqualTo("INACTIVO");

        cliente.patch().uri("/api/pacientes/{id}/estado", id).contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("estado", "INACTIVO"))
                .exchange()
                .expectStatus().isEqualTo(409)
                .expectBody().jsonPath("$.codigo").isEqualTo("TRANSICION_NO_PERMITIDA");
    }

    @Test
    void swaggerExponeLaApi() {
        cliente.get().uri("/v3/api-docs")
                .exchange()
                .expectStatus().isOk()
                .expectBody(String.class).value(c -> assertThat(c).contains("/api/pacientes"));
    }
}
