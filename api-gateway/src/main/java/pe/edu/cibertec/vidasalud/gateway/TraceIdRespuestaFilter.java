package pe.edu.cibertec.vidasalud.gateway;

import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanContext;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;

/**
 * Copia de la clase homonima de hospital-commons (el Gateway no depende de esa libreria).
 * Agrega {@code X-Trace-Id} a la respuesta con el traceId actual de OpenTelemetry.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class TraceIdRespuestaFilter implements WebFilter {

    public static final String CABECERA = "X-Trace-Id";

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
        return Mono.defer(() -> {
            SpanContext contexto = Span.current().getSpanContext();
            if (contexto.isValid()) {
                String traceId = contexto.getTraceId();
                exchange.getResponse().beforeCommit(() -> {
                    exchange.getResponse().getHeaders().set(CABECERA, traceId);
                    return Mono.empty();
                });
            }
            return chain.filter(exchange);
        });
    }
}
