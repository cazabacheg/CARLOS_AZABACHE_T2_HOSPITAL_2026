package pe.edu.cibertec.vidasalud.pagos.client;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
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

class PacientesClientTest {

    private MockWebServer servidor;
    private PacientesClient cliente;

    @BeforeEach
    void iniciar() throws IOException {
        servidor = new MockWebServer();
        servidor.start();
        cliente = new PacientesClient(WebClient.builder().baseUrl(servidor.url("/").toString()).build());
    }

    @AfterEach
    void cerrar() throws IOException {
        servidor.shutdown();
    }

    private static MockResponse json(UUID id, String estado) {
        return new MockResponse().setResponseCode(200).setHeader("Content-Type", "application/json")
                .setBody("{\"id\":\"" + id + "\",\"estado\":\"" + estado + "\",\"nombres\":\"Ana\",\"tipoDocumento\":\"DNI\"}");
    }

    private static void esperaError(reactor.core.publisher.Mono<?> mono, HttpStatus status, String codigo) {
        StepVerifier.create(mono).expectErrorSatisfies(e -> {
            assertThat(e).isInstanceOf(ErrorNegocio.class);
            assertThat(((ErrorNegocio) e).getStatus()).isEqualTo(status);
            assertThat(((ErrorNegocio) e).getCodigo()).isEqualTo(codigo);
        }).verify();
    }

    @Test
    void pacienteActivo() throws InterruptedException {
        UUID id = UUID.randomUUID();
        servidor.enqueue(json(id, "ACTIVO"));
        StepVerifier.create(cliente.obtener(id))
                .assertNext(p -> {
                    assertThat(p.id()).isEqualTo(id);
                    assertThat(p.estado()).isEqualTo("ACTIVO");
                }).verifyComplete();
        assertThat(servidor.takeRequest().getPath()).isEqualTo("/api/pacientes/" + id);
    }

    @Test
    void pacienteInactivoSeDevuelveSinDecidir() {
        UUID id = UUID.randomUUID();
        servidor.enqueue(json(id, "INACTIVO"));
        StepVerifier.create(cliente.obtener(id)).assertNext(p -> assertThat(p.estado()).isEqualTo("INACTIVO"))
                .verifyComplete();
    }

    @Test
    void noEncontradoNoReintenta() {
        servidor.enqueue(new MockResponse().setResponseCode(404));
        esperaError(cliente.obtener(UUID.randomUUID()), HttpStatus.NOT_FOUND, "PACIENTE_NO_ENCONTRADO");
        assertThat(servidor.getRequestCount()).isEqualTo(1);
    }

    @Test
    void errorDelServidorEs503() {
        servidor.enqueue(new MockResponse().setResponseCode(500));
        esperaError(cliente.obtener(UUID.randomUUID()), HttpStatus.SERVICE_UNAVAILABLE,
                "SERVICIO_PACIENTES_NO_DISPONIBLE");
        assertThat(servidor.getRequestCount()).isEqualTo(1);
    }

    @Test
    void respuestaLentaEs504() {
        UUID id = UUID.randomUUID();
        servidor.enqueue(json(id, "ACTIVO").setHeadersDelay(3, TimeUnit.SECONDS));
        esperaError(cliente.obtener(id), HttpStatus.GATEWAY_TIMEOUT, "SERVICIO_PACIENTES_NO_DISPONIBLE");
        assertThat(servidor.getRequestCount()).isEqualTo(1);
    }

    @Test
    void conexionRechazadaReintentaUnaVez() throws IOException {
        String url = servidor.url("/").toString();
        servidor.shutdown();
        AtomicInteger intentos = new AtomicInteger();
        PacientesClient sinServidor = new PacientesClient(WebClient.builder().baseUrl(url)
                .filter((req, next) -> {
                    intentos.incrementAndGet();
                    return next.exchange(req);
                }).build());
        esperaError(sinServidor.obtener(UUID.randomUUID()), HttpStatus.SERVICE_UNAVAILABLE,
                "SERVICIO_PACIENTES_NO_DISPONIBLE");
        assertThat(intentos.get()).isEqualTo(2);
        servidor = new MockWebServer();
    }
}
