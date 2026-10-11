package pe.edu.cibertec.vidasalud.atenciones.client;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.time.Duration;
import java.time.OffsetDateTime;
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
import reactor.test.StepVerifier;

class CitasClientTest {

    private MockWebServer servidor;
    private CitasClient cliente;

    @BeforeEach
    void iniciar() throws IOException {
        servidor = new MockWebServer();
        servidor.start();
        cliente = new CitasClient(WebClient.builder(), servidor.url("/").toString());
    }

    @AfterEach
    void detener() throws IOException {
        servidor.shutdown();
    }

    private static void esError(Throwable e, HttpStatus status, String codigo) {
        assertThat(e).isInstanceOf(ErrorNegocio.class);
        assertThat(((ErrorNegocio) e).getStatus()).isEqualTo(status);
        assertThat(((ErrorNegocio) e).getCodigo()).isEqualTo(codigo);
    }

    @Test
    void respuesta200() throws Exception {
        UUID c = UUID.randomUUID();
        UUID p = UUID.randomUUID();
        servidor.enqueue(new MockResponse().setHeader("Content-Type", "application/json").setBody(
                "{\"id\":\"" + c + "\",\"pacienteId\":\"" + p + "\",\"estado\":\"CONFIRMADA\","
                        + "\"fechaHora\":\"2026-10-14T09:30:00-05:00\",\"codigo\":\"CIT-20261014-000001\","
                        + "\"especialidad\":\"MEDICINA_GENERAL\"}"));

        StepVerifier.create(cliente.obtener(c))
                .assertNext(r -> {
                    assertThat(r.id()).isEqualTo(c);
                    assertThat(r.pacienteId()).isEqualTo(p);
                    assertThat(r.estado()).isEqualTo("CONFIRMADA");
                    assertThat(r.fechaHora().isEqual(OffsetDateTime.parse("2026-10-14T09:30:00-05:00"))).isTrue();
                })
                .verifyComplete();
        assertThat(servidor.takeRequest().getPath()).isEqualTo("/api/citas/" + c);
    }

    @Test
    void respuesta404NoReintenta() {
        servidor.enqueue(new MockResponse().setResponseCode(404));
        StepVerifier.create(cliente.obtener(UUID.randomUUID()))
                .expectErrorSatisfies(e -> esError(e, HttpStatus.NOT_FOUND, "CITA_NO_ENCONTRADA"))
                .verify();
        assertThat(servidor.getRequestCount()).isEqualTo(1);
    }

    @Test
    void respuesta500EsServicioNoDisponible() {
        servidor.enqueue(new MockResponse().setResponseCode(500));
        StepVerifier.create(cliente.obtener(UUID.randomUUID()))
                .expectErrorSatisfies(e -> esError(e, HttpStatus.SERVICE_UNAVAILABLE, "SERVICIO_CITAS_NO_DISPONIBLE"))
                .verify();
        assertThat(servidor.getRequestCount()).isEqualTo(1);
    }

    @Test
    void retrasoEsTimeout() {
        UUID c = UUID.randomUUID();
        servidor.enqueue(new MockResponse().setHeader("Content-Type", "application/json")
                .setHeadersDelay(3, TimeUnit.SECONDS)
                .setBody("{\"id\":\"" + c + "\",\"estado\":\"CONFIRMADA\"}"));
        StepVerifier.create(cliente.obtener(c))
                .expectErrorSatisfies(e -> esError(e, HttpStatus.GATEWAY_TIMEOUT, "SERVICIO_CITAS_NO_DISPONIBLE"))
                .verify(Duration.ofSeconds(6));
    }

    @Test
    void errorDeRedReintentaUnaVez() throws IOException {
        String url = servidor.url("/").toString();
        servidor.shutdown();
        AtomicInteger intentos = new AtomicInteger();
        CitasClient sinServidor = new CitasClient(WebClient.builder().filter((req, next) -> {
            intentos.incrementAndGet();
            return next.exchange(req);
        }), url);

        StepVerifier.create(sinServidor.obtener(UUID.randomUUID()))
                .expectErrorSatisfies(e -> esError(e, HttpStatus.SERVICE_UNAVAILABLE, "SERVICIO_CITAS_NO_DISPONIBLE"))
                .verify(Duration.ofSeconds(6));
        assertThat(intentos.get()).isEqualTo(2);
        servidor = new MockWebServer();
    }
}
