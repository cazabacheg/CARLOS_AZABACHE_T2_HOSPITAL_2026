package pe.edu.cibertec.vidasalud.commons.http;

import java.net.ConnectException;
import java.time.Duration;
import java.util.concurrent.TimeoutException;
import org.springframework.http.HttpStatus;
import org.springframework.web.reactive.function.client.WebClientRequestException;
import org.springframework.web.reactive.function.client.WebClientResponseException;
import reactor.core.publisher.Mono;
import reactor.util.retry.Retry;
import pe.edu.cibertec.vidasalud.commons.error.ErrorNegocio;

/**
 * Politica para llamadas sincronas entre servicios: timeout de 2 s y un reintento
 * solo ante errores de red (nunca ante respuestas 4xx/5xx ni timeouts).
 */
public final class PoliticaResiliencia {

    public static final Duration TIMEOUT = Duration.ofSeconds(2);
    public static final Duration ESPERA_REINTENTO = Duration.ofMillis(200);

    private PoliticaResiliencia() {
    }

    /** Aplica timeout y reintento. Los errores originales se conservan. */
    public static <T> Mono<T> aplicar(Mono<T> llamada) {
        return aplicar(llamada, TIMEOUT);
    }

    static <T> Mono<T> aplicar(Mono<T> llamada, Duration timeout) {
        return llamada
                .retryWhen(Retry.fixedDelay(1, ESPERA_REINTENTO)
                        .filter(PoliticaResiliencia::esErrorDeRed)
                        .onRetryExhaustedThrow((spec, senal) -> senal.failure()))
                .timeout(timeout);
    }

    /**
     * {@link #aplicar} y traduccion de fallos de disponibilidad a {@link ErrorNegocio}:
     * timeout -> 504, 5xx / error de conexion -> 503, ambos con {@code codigoNoDisponible}.
     * Los errores 4xx y los {@link ErrorNegocio} ya traducidos pasan sin cambios.
     */
    public static <T> Mono<T> proteger(Mono<T> llamada, String codigoNoDisponible, String servicio) {
        return aplicar(llamada).onErrorMap(
                e -> !(e instanceof ErrorNegocio),
                e -> traducir(e, codigoNoDisponible, servicio));
    }

    static Throwable traducir(Throwable e, String codigo, String servicio) {
        if (e instanceof TimeoutException) {
            return new ErrorNegocio(HttpStatus.GATEWAY_TIMEOUT, codigo,
                    "El servicio de " + servicio + " no respondio a tiempo.");
        }
        if (e instanceof WebClientResponseException respuesta && respuesta.getStatusCode().is5xxServerError()) {
            return noDisponible(codigo, servicio);
        }
        if (esErrorDeRed(e)) {
            return noDisponible(codigo, servicio);
        }
        return e;
    }

    private static ErrorNegocio noDisponible(String codigo, String servicio) {
        return new ErrorNegocio(HttpStatus.SERVICE_UNAVAILABLE, codigo,
                "El servicio de " + servicio + " no esta disponible. Intente mas tarde.");
    }

    static boolean esErrorDeRed(Throwable e) {
        if (e instanceof WebClientRequestException) {
            return true;
        }
        Throwable causa = e;
        while (causa != null) {
            if (causa instanceof ConnectException) {
                return true;
            }
            causa = causa.getCause();
        }
        return false;
    }
}
