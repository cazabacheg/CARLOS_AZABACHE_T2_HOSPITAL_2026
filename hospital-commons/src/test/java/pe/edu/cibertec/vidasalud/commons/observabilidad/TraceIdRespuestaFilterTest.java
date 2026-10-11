package pe.edu.cibertec.vidasalud.commons.observabilidad;

import static org.assertj.core.api.Assertions.assertThat;

import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.context.Scope;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import org.junit.jupiter.api.Test;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;

class TraceIdRespuestaFilterTest {

    private final TraceIdRespuestaFilter filtro = new TraceIdRespuestaFilter();

    @Test
    void sinSpanValidoNoAgregaLaCabecera() {
        MockServerWebExchange exchange = MockServerWebExchange.from(MockServerHttpRequest.get("/x"));
        filtro.filter(exchange, e -> e.getResponse().setComplete()).block();
        assertThat(exchange.getResponse().getHeaders().containsKey("X-Trace-Id")).isFalse();
    }

    @Test
    void conSpanActivoAgregaElTraceId() {
        Tracer tracer = SdkTracerProvider.builder().build().get("prueba");
        Span span = tracer.spanBuilder("peticion").startSpan();
        MockServerWebExchange exchange = MockServerWebExchange.from(MockServerHttpRequest.get("/x"));
        try (Scope ignorado = span.makeCurrent()) {
            filtro.filter(exchange, e -> e.getResponse().setComplete()).block();
        } finally {
            span.end();
        }
        assertThat(exchange.getResponse().getHeaders().getFirst("X-Trace-Id"))
                .isEqualTo(span.getSpanContext().getTraceId())
                .hasSize(32);
    }
}
