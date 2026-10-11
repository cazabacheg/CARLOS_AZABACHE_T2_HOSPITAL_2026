package pe.edu.cibertec.vidasalud.atenciones;

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
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.UUID;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.reactive.server.EntityExchangeResult;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.springframework.web.reactive.function.client.WebClient;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.containers.RabbitMQContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import pe.edu.cibertec.vidasalud.atenciones.client.CitasClient;
import pe.edu.cibertec.vidasalud.commons.messaging.EventoEnvelope;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
@ActiveProfiles("test")
class AtencionFlujoIT {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16");

    @Container
    static RabbitMQContainer RABBIT = new RabbitMQContainer("rabbitmq:3.13-management");

    static final MockWebServer CITAS = new MockWebServer();

    static {
        try {
            CITAS.start();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @DynamicPropertySource
    static void propiedades(DynamicPropertyRegistry r) {
        r.add("hospital.rabbit.host", RABBIT::getHost);
        r.add("hospital.rabbit.port", RABBIT::getAmqpPort);
        r.add("hospital.rabbit.username", RABBIT::getAdminUsername);
        r.add("hospital.rabbit.password", RABBIT::getAdminPassword);
        r.add("eureka.client.enabled", () -> "false");
    }

    @TestConfiguration
    static class CitasDePrueba {
        @Bean
        @Primary
        CitasClient citasClientPrueba() {
            return new CitasClient(WebClient.builder(), CITAS.url("/").toString());
        }
    }

    @Autowired WebTestClient cliente;
    @Autowired ObjectMapper mapper;

    @AfterAll
    static void cerrar() throws IOException {
        CITAS.shutdown();
    }

    private EventoEnvelope siguienteEvento(Channel canal) {
        GetResponse[] respuesta = new GetResponse[1];
        await().atMost(Duration.ofSeconds(10)).until(() -> {
            respuesta[0] = canal.basicGet("prueba.atenciones", true);
            return respuesta[0] != null;
        });
        try {
            return mapper.readValue(respuesta[0].getBody(), EventoEnvelope.class);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @Test
    void registrarPublicaAtencionRegistradaYAnularPublicaAnulada() throws Exception {
        ConnectionFactory fabrica = new ConnectionFactory();
        fabrica.setHost(RABBIT.getHost());
        fabrica.setPort(RABBIT.getAmqpPort());
        fabrica.setUsername(RABBIT.getAdminUsername());
        fabrica.setPassword(RABBIT.getAdminPassword());

        try (Connection conexion = fabrica.newConnection(); Channel canal = conexion.createChannel()) {
            canal.exchangeDeclare("hospital.eventos", "topic", true);
            canal.queueDeclare("prueba.atenciones", false, false, false, null);
            canal.queueBind("prueba.atenciones", "hospital.eventos", "atencion.*");

            UUID citaId = UUID.randomUUID();
            UUID pacienteId = UUID.randomUUID();
            OffsetDateTime fecha = OffsetDateTime.now(ZoneId.of("America/Lima")).plusMinutes(10);
            CITAS.enqueue(new MockResponse().setHeader("Content-Type", "application/json").setBody(
                    "{\"id\":\"" + citaId + "\",\"pacienteId\":\"" + pacienteId + "\","
                            + "\"estado\":\"CONFIRMADA\",\"fechaHora\":\"" + fecha + "\"}"));

            EntityExchangeResult<byte[]> creada = cliente.post().uri("/api/atenciones")
                    .contentType(MediaType.APPLICATION_JSON)
                    .bodyValue("{\"citaId\":\"" + citaId + "\",\"area\":\"CONSULTA_EXTERNA\"}")
                    .exchange()
                    .expectStatus().isCreated()
                    .expectHeader().exists("Location")
                    .expectBody().jsonPath("$.codigo").value(c -> assertThat((String) c).matches("^ATE-\\d{8}-\\d{6}$"))
                    .returnResult();
            JsonNode cuerpo = mapper.readTree(creada.getResponseBody());
            String id = cuerpo.get("id").asText();

            EventoEnvelope registrada = siguienteEvento(canal);
            assertThat(registrada.tipo()).isEqualTo("atencion.registrada");
            assertThat(registrada.origen()).isEqualTo("atenciones-service");
            assertThat(registrada.tipoRecurso()).isEqualTo("ATENCION");
            assertThat(registrada.recursoId().toString()).isEqualTo(id);
            assertThat(registrada.estadoNuevo()).isEqualTo("REGISTRADA");
            assertThat(registrada.datos().get("citaId").asText()).isEqualTo(citaId.toString());
            assertThat(registrada.datos().get("pacienteId").asText()).isEqualTo(pacienteId.toString());
            assertThat(CITAS.takeRequest().getPath()).isEqualTo("/api/citas/" + citaId);

            cliente.patch().uri("/api/atenciones/{id}/estado", id)
                    .contentType(MediaType.APPLICATION_JSON)
                    .bodyValue("{\"estado\":\"ANULADA\",\"motivo\":\"Prueba\"}")
                    .exchange()
                    .expectStatus().isOk();

            EventoEnvelope anulada = siguienteEvento(canal);
            assertThat(anulada.tipo()).isEqualTo("atencion.anulada");
            assertThat(anulada.estadoAnterior()).isEqualTo("REGISTRADA");
        }
    }
}
