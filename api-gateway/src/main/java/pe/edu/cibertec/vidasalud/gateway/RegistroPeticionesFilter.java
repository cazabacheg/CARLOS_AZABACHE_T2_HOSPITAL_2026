package pe.edu.cibertec.vidasalud.gateway;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

/** Registra metodo, ruta (sin parametros de consulta), estado y duracion. Nunca cabeceras ni cuerpos. */
@Component
public class RegistroPeticionesFilter implements GlobalFilter, Ordered {

    private static final Logger log = LoggerFactory.getLogger(RegistroPeticionesFilter.class);

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        long inicio = System.nanoTime();
        return chain.filter(exchange).doFinally(senal -> {
            long ms = (System.nanoTime() - inicio) / 1_000_000;
            var estado = exchange.getResponse().getStatusCode();
            log.info("{} {} -> {} en {} ms", exchange.getRequest().getMethod(),
                    exchange.getRequest().getPath().value(), estado == null ? "?" : estado.value(), ms);
        });
    }

    @Override
    public int getOrder() {
        return Ordered.HIGHEST_PRECEDENCE + 1;
    }
}
