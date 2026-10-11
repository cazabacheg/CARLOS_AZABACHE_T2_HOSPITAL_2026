package pe.edu.cibertec.vidasalud.gateway;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.reactive.AutoConfigureWebTestClient;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.reactive.server.WebTestClient;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "eureka.client.enabled=false")
@AutoConfigureWebTestClient
class EnrutamientoTest {

    static MockWebServer destino;

    @BeforeAll
    static void iniciar() throws IOException {
        destino = new MockWebServer();
        destino.start();
    }

    @AfterAll
    static void detener() throws IOException {
        destino.shutdown();
    }

    @DynamicPropertySource
    static void rutas(DynamicPropertyRegistry registro) {
        String base = "spring.cloud.gateway.server.webflux.routes[0].";
        registro.add(base + "id", () -> "pacientes");
        registro.add(base + "uri", () -> "http://localhost:" + destino.getPort());
        registro.add(base + "predicates[0]", () -> "Path=/api/pacientes/**");
        String fantasma = "spring.cloud.gateway.server.webflux.routes[1].";
        registro.add(fantasma + "id", () -> "fantasma");
        registro.add(fantasma + "uri", () -> "lb://servicio-inexistente");
        registro.add(fantasma + "predicates[0]", () -> "Path=/api/fantasma/**");
    }

    @Autowired
    WebTestClient cliente;

    @Test
    void reenviaLaPeticionConsuRutaCuerpoYEstadoIntactos() throws InterruptedException {
        destino.enqueue(new MockResponse().setResponseCode(201)
                .setHeader("Content-Type", "application/json").setBody("{\"id\":\"123\"}"));

        cliente.post().uri("/api/pacientes/123?x=1").contentType(MediaType.APPLICATION_JSON)
                .bodyValue("{\"a\":1}").exchange()
                .expectStatus().isCreated()
                .expectBody().json("{\"id\":\"123\"}");

        RecordedRequest recibida = destino.takeRequest();
        assertThat(recibida.getPath()).isEqualTo("/api/pacientes/123?x=1");
        assertThat(recibida.getMethod()).isEqualTo("POST");
        assertThat(recibida.getBody().readUtf8()).isEqualTo("{\"a\":1}");
    }

    @Test
    void rutaSinInstanciaDisponibleDevuelve503ProblemJson() {
        cliente.get().uri("/api/fantasma/1").exchange()
                .expectStatus().isEqualTo(503)
                .expectHeader().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON)
                .expectBody()
                .jsonPath("$.codigo").isEqualTo("SERVICIO_NO_DISPONIBLE")
                .jsonPath("$.status").isEqualTo(503)
                .jsonPath("$.timestamp").isNotEmpty();
    }

    @Test
    void rutaInexistenteDevuelve404ProblemJson() {
        cliente.get().uri("/api/otra-cosa").exchange()
                .expectStatus().isNotFound()
                .expectHeader().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON)
                .expectBody().jsonPath("$.codigo").isEqualTo("RUTA_NO_ENCONTRADA");
    }

    @Test
    void sinAgenteNoAgregaXTraceId() {
        destino.enqueue(new MockResponse().setBody("ok"));
        cliente.get().uri("/api/pacientes/9").exchange()
                .expectStatus().isOk()
                .expectHeader().doesNotExist("X-Trace-Id");
    }
}
