package pe.edu.cibertec.vidasalud.commons.http;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.time.Duration;
import java.util.concurrent.TimeoutException;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.SocketPolicy;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.reactive.function.client.WebClient;
import pe.edu.cibertec.vidasalud.commons.error.ErrorNegocio;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

class PoliticaResilienciaTest {

    private MockWebServer servidor;
    private WebClient cliente;

    @BeforeEach
    void iniciar() throws IOException {
        servidor = new MockWebServer();
        servidor.start();
        cliente = ClienteHttpFactory.crear(WebClient.builder(), servidor.url("/").toString());
    }

    @AfterEach
    void detener() throws IOException {
        servidor.shutdown();
    }

    private Mono<String> obtener() {
        return cliente.get().uri("/api/x").retrieve().bodyToMono(String.class);
    }

    @Test
    void exitoDevuelveElCuerpo() {
        servidor.enqueue(new MockResponse().setBody("ok"));
        StepVerifier.create(PoliticaResiliencia.proteger(obtener(), "SERVICIO_X_NO_DISPONIBLE", "x"))
                .expectNext("ok").verifyComplete();
    }

    @Test
    void respuestaLentaSeTraduceA504() {
        servidor.enqueue(new MockResponse().setBody("tarde").setBodyDelay(1500, java.util.concurrent.TimeUnit.MILLISECONDS));
        StepVerifier.create(PoliticaResiliencia.aplicar(obtener(), Duration.ofMillis(300))
                        .onErrorMap(e -> PoliticaResiliencia.traducir(e, "SERVICIO_X_NO_DISPONIBLE", "x")))
                .expectErrorSatisfies(e -> {
                    assertThat(e).isInstanceOf(ErrorNegocio.class);
                    assertThat(((ErrorNegocio) e).getStatus()).isEqualTo(HttpStatus.GATEWAY_TIMEOUT);
                    assertThat(((ErrorNegocio) e).getCodigo()).isEqualTo("SERVICIO_X_NO_DISPONIBLE");
                }).verify();
        assertThat(servidor.getRequestCount()).isEqualTo(1); // el timeout NO se reintenta
    }

    @Test
    void error500SeTraduceA503SinReintentar() {
        servidor.enqueue(new MockResponse().setResponseCode(500));
        servidor.enqueue(new MockResponse().setBody("no deberia llegar"));
        StepVerifier.create(PoliticaResiliencia.proteger(obtener(), "SERVICIO_X_NO_DISPONIBLE", "x"))
                .expectErrorSatisfies(e -> assertThat(((ErrorNegocio) e).getStatus())
                        .isEqualTo(HttpStatus.SERVICE_UNAVAILABLE))
                .verify();
        assertThat(servidor.getRequestCount()).isEqualTo(1);
    }

    @Test
    void error404NoSeReintentaNiSeTraduce() {
        servidor.enqueue(new MockResponse().setResponseCode(404));
        StepVerifier.create(PoliticaResiliencia.proteger(obtener(), "SERVICIO_X_NO_DISPONIBLE", "x"))
                .expectErrorSatisfies(e -> assertThat(e).isNotInstanceOf(ErrorNegocio.class))
                .verify();
        assertThat(servidor.getRequestCount()).isEqualTo(1);
    }

    @Test
    void errorDeConexionReintentaUnaVezYLuegoDa503() throws IOException {
        String url = servidor.url("/").toString();
        servidor.shutdown(); // nadie escucha: conexion rechazada
        WebClient sinServidor = ClienteHttpFactory.crear(WebClient.builder(), url);
        int[] intentos = {0};
        Mono<String> llamada = Mono.defer(() -> {
            intentos[0]++;
            return sinServidor.get().uri("/api/x").retrieve().bodyToMono(String.class);
        });
        StepVerifier.create(PoliticaResiliencia.proteger(llamada, "SERVICIO_X_NO_DISPONIBLE", "x"))
                .expectErrorSatisfies(e -> {
                    assertThat(e).isInstanceOf(ErrorNegocio.class);
                    assertThat(((ErrorNegocio) e).getStatus()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
                }).verify();
        assertThat(intentos[0]).isEqualTo(2); // intento inicial + 1 reintento
        servidor = new MockWebServer(); // para que @AfterEach pueda cerrar
    }

    @Test
    void timeoutExceptionSeReconoce() {
        assertThat(PoliticaResiliencia.traducir(new TimeoutException(), "C", "x"))
                .isInstanceOf(ErrorNegocio.class);
    }
}
