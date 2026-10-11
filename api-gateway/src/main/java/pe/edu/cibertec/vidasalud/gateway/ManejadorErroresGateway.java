package pe.edu.cibertec.vidasalud.gateway;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.ConnectException;
import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.TimeoutException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.web.reactive.error.ErrorWebExceptionHandler;
import org.springframework.core.annotation.Order;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

/** Convierte los errores del Gateway al formato application/problem+json comun a toda la plataforma. */
@Component
@Order(-2)
public class ManejadorErroresGateway implements ErrorWebExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(ManejadorErroresGateway.class);
    private static final ZoneId LIMA = ZoneId.of("America/Lima");

    private final ObjectMapper mapper;

    public ManejadorErroresGateway(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    @Override
    public Mono<Void> handle(ServerWebExchange exchange, Throwable error) {
        if (exchange.getResponse().isCommitted()) {
            return Mono.error(error);
        }
        HttpStatusCode status = estadoDe(error);
        String codigo;
        String detalle;
        switch (status.value()) {
            case 503 -> {
                codigo = "SERVICIO_NO_DISPONIBLE";
                detalle = "El servicio solicitado no esta disponible. Intente mas tarde.";
            }
            case 504 -> {
                codigo = "SERVICIO_TIEMPO_AGOTADO";
                detalle = "El servicio solicitado no respondio a tiempo.";
            }
            case 404 -> {
                codigo = "RUTA_NO_ENCONTRADA";
                detalle = "La ruta solicitada no existe.";
            }
            default -> {
                codigo = status.is5xxServerError() ? "ERROR_INTERNO" : "ERROR_HTTP_" + status.value();
                detalle = "La peticion no pudo procesarse.";
            }
        }
        if (status.value() == 500) {
            log.error("error interno del gateway en {}", exchange.getRequest().getPath(), error);
        }
        Map<String, Object> cuerpo = new LinkedHashMap<>();
        cuerpo.put("type", "about:blank");
        cuerpo.put("title", HttpStatus.resolve(status.value()) != null
                ? HttpStatus.resolve(status.value()).getReasonPhrase() : "Error");
        cuerpo.put("status", status.value());
        cuerpo.put("detail", detalle);
        cuerpo.put("instance", exchange.getRequest().getPath().value());
        cuerpo.put("codigo", codigo);
        cuerpo.put("timestamp", OffsetDateTime.now(LIMA).toString());

        exchange.getResponse().setStatusCode(status);
        exchange.getResponse().getHeaders().setContentType(MediaType.APPLICATION_PROBLEM_JSON);
        try {
            DataBuffer buffer = exchange.getResponse().bufferFactory()
                    .wrap(mapper.writeValueAsString(cuerpo).getBytes(StandardCharsets.UTF_8));
            return exchange.getResponse().writeWith(Mono.just(buffer));
        } catch (Exception e) {
            return Mono.error(e);
        }
    }

    private static HttpStatusCode estadoDe(Throwable error) {
        for (Throwable t = error; t != null; t = t.getCause()) {
            if (t instanceof ResponseStatusException rse) {
                return rse.getStatusCode();
            }
            if (t instanceof ConnectException) {
                return HttpStatus.SERVICE_UNAVAILABLE;
            }
            if (t instanceof TimeoutException) {
                return HttpStatus.GATEWAY_TIMEOUT;
            }
        }
        return HttpStatus.INTERNAL_SERVER_ERROR;
    }
}
