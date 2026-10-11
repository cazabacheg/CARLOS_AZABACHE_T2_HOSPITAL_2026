package pe.edu.cibertec.vidasalud.citas.client;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.reactive.function.client.WebClient;
import pe.edu.cibertec.vidasalud.commons.error.ErrorNegocio;
import pe.edu.cibertec.vidasalud.commons.http.ClienteHttpFactory;
import reactor.test.StepVerifier;

class PacientesClientTest {

    private MockWebServer servidor;
    private PacientesClient cliente;
    private final UUID pacienteId = UUID.randomUUID();

    @BeforeEach
    void iniciar() throws IOException {
        servidor = new MockWebServer();
        servidor.start();
        cliente = new PacientesClient(ClienteHttpFactory.crear(WebClient.builder(), servidor.url("/").toString()));
    }

    @AfterEach
    void detener() throws IOException {
        servidor.shutdown();
    }

    private void esperaError(HttpStatus status, String codigo) {
        StepVerifier.create(cliente.obtener(pacienteId))
                .expectErrorSatisfies(e -> assertThat(e).isInstanceOfSatisfying(ErrorNegocio.class, n -> {
                    assertThat(n.getStatus()).isEqualTo(status);
                    assertThat(n.getCodigo()).isEqualTo(codigo);
                }))
                .verify(Duration.ofSeconds(10));
    }

    @Test
    void respondeActivo() throws InterruptedException {
        servidor.enqueue(new MockResponse().setHeader("Content-Type", "application/json")
                .setBody("{\"id\":\"" + pacienteId + "\",\"estado\":\"ACTIVO\",\"nombres\":\"Ana\",\"apellidos\":\"Prueba\"}"));
        StepVerifier.create(cliente.obtener(pacienteId))
                .assertNext(r -> {
                    assertThat(r.id()).isEqualTo(pacienteId);
                    assertThat(r.estado()).isEqualTo("ACTIVO");
                    assertThat(r.estaActivo()).isTrue();
                })
                .verifyComplete();
        assertThat(servidor.takeRequest().getPath()).isEqualTo("/api/pacientes/" + pacienteId);
    }

    @Test
    void noEncontradoNoReintenta() {
        servidor.enqueue(new MockResponse().setResponseCode(404));
        esperaError(HttpStatus.NOT_FOUND, "PACIENTE_NO_ENCONTRADO");
        assertThat(servidor.getRequestCount()).isEqualTo(1);
    }

    @Test
    void errorDelServidorEsNoDisponible() {
        servidor.enqueue(new MockResponse().setResponseCode(500));
        servidor.enqueue(new MockResponse().setHeader("Content-Type", "application/json")
                .setBody("{\"id\":\"" + pacienteId + "\",\"estado\":\"ACTIVO\"}"));
        esperaError(HttpStatus.SERVICE_UNAVAILABLE, PacientesClient.CODIGO_NO_DISPONIBLE);
        assertThat(servidor.getRequestCount()).isEqualTo(1);
    }

    @Test
    void respuestaLentaEsTimeout() {
        servidor.enqueue(new MockResponse().setHeader("Content-Type", "application/json")
                .setBody("{\"id\":\"" + pacienteId + "\",\"estado\":\"ACTIVO\"}")
                .setHeadersDelay(3, TimeUnit.SECONDS));
        esperaError(HttpStatus.GATEWAY_TIMEOUT, PacientesClient.CODIGO_NO_DISPONIBLE);
    }

    @Test
    void errorDeConexionReintentaUnaVez() throws IOException {
        String url = servidor.url("/").toString();
        servidor.shutdown();
        AtomicInteger intentos = new AtomicInteger();
        WebClient.Builder contador = WebClient.builder().filter((peticion, siguiente) -> {
            intentos.incrementAndGet();
            return siguiente.exchange(peticion);
        });
        cliente = new PacientesClient(ClienteHttpFactory.crear(contador, url));
        esperaError(HttpStatus.SERVICE_UNAVAILABLE, PacientesClient.CODIGO_NO_DISPONIBLE);
        assertThat(intentos.get()).isEqualTo(2);
        servidor = new MockWebServer();
    }
}
