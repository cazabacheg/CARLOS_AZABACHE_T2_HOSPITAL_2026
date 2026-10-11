package pe.edu.cibertec.vidasalud.commons.error;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.bind.support.WebExchangeBindException;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.ServerWebInputException;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import pe.edu.cibertec.vidasalud.commons.reloj.RelojAutoConfiguration;

/** Traduce excepciones a {@code application/problem+json} con {@code codigo} y {@code timestamp}. */
@RestControllerAdvice
public class ManejadorErrores {

    private static final Logger log = LoggerFactory.getLogger(ManejadorErrores.class);

    @ExceptionHandler(ErrorNegocio.class)
    public ResponseEntity<ProblemDetail> negocio(ErrorNegocio e, ServerWebExchange exchange) {
        return respuesta(e.getStatus(), e.getCodigo(), e.getDetalle(), e.getErrores(), exchange);
    }

    @ExceptionHandler(WebExchangeBindException.class)
    public ResponseEntity<ProblemDetail> validacionCuerpo(WebExchangeBindException e, ServerWebExchange exchange) {
        List<ErrorCampo> errores = e.getFieldErrors().stream()
                .map(f -> new ErrorCampo(f.getField(), f.getDefaultMessage()))
                .toList();
        return respuesta(HttpStatus.BAD_REQUEST, "VALIDACION_FALLIDA",
                "Los datos enviados no son validos.", errores, exchange);
    }

    @ExceptionHandler(HandlerMethodValidationException.class)
    public ResponseEntity<ProblemDetail> validacionParametros(HandlerMethodValidationException e,
                                                              ServerWebExchange exchange) {
        List<ErrorCampo> errores = e.getAllErrors().stream()
                .map(r -> new ErrorCampo("parametro", r.getDefaultMessage()))
                .toList();
        return respuesta(HttpStatus.BAD_REQUEST, "VALIDACION_FALLIDA",
                "Los parametros enviados no son validos.", errores, exchange);
    }

    @ExceptionHandler(ServerWebInputException.class)
    public ResponseEntity<ProblemDetail> entradaInvalida(ServerWebInputException e, ServerWebExchange exchange) {
        return respuesta(HttpStatus.BAD_REQUEST, "VALIDACION_FALLIDA",
                "La peticion tiene un formato invalido (JSON ilegible o parametro con tipo incorrecto).",
                List.of(), exchange);
    }

    @ExceptionHandler(ResponseStatusException.class)
    public ResponseEntity<ProblemDetail> estadoHttp(ResponseStatusException e, ServerWebExchange exchange) {
        HttpStatusCode status = e.getStatusCode();
        String codigo = status.value() == 404 ? "RECURSO_NO_ENCONTRADO" : "ERROR_HTTP_" + status.value();
        String detalle = status.value() == 404 ? "El recurso solicitado no existe." : "La peticion no pudo procesarse.";
        return respuesta(status, codigo, detalle, List.of(), exchange);
    }

    @ExceptionHandler(Throwable.class)
    public ResponseEntity<ProblemDetail> inesperado(Throwable e, ServerWebExchange exchange) {
        log.error("Error interno no controlado en {}", exchange.getRequest().getPath(), e);
        return respuesta(HttpStatus.INTERNAL_SERVER_ERROR, "ERROR_INTERNO",
                "Ocurrio un error interno. Intente mas tarde.", List.of(), exchange);
    }

    private ResponseEntity<ProblemDetail> respuesta(HttpStatusCode status, String codigo, String detalle,
                                                    List<ErrorCampo> errores, ServerWebExchange exchange) {
        ProblemDetail problema = ProblemDetail.forStatusAndDetail(status, detalle);
        problema.setTitle(titulo(status));
        problema.setInstance(java.net.URI.create(exchange.getRequest().getPath().value()));
        problema.setProperty("codigo", codigo);
        problema.setProperty("timestamp", OffsetDateTime.now(RelojAutoConfiguration.ZONA_HOSPITAL).toString());
        if (!errores.isEmpty()) {
            problema.setProperty("errores", errores.stream()
                    .map(c -> Map.of("campo", c.campo(), "mensaje", String.valueOf(c.mensaje())))
                    .toList());
        }
        return ResponseEntity.status(status)
                .contentType(MediaType.APPLICATION_PROBLEM_JSON)
                .body(problema);
    }

    private static String titulo(HttpStatusCode status) {
        HttpStatus conocido = HttpStatus.resolve(status.value());
        if (conocido == null) {
            return "Error";
        }
        return switch (conocido) {
            case BAD_REQUEST -> "Solicitud invalida";
            case NOT_FOUND -> "No encontrado";
            case CONFLICT -> "Conflicto";
            case UNPROCESSABLE_ENTITY -> "Regla de negocio no cumplida";
            case SERVICE_UNAVAILABLE -> "Servicio no disponible";
            case GATEWAY_TIMEOUT -> "Tiempo de espera agotado";
            case INTERNAL_SERVER_ERROR -> "Error interno";
            default -> conocido.getReasonPhrase();
        };
    }
}
