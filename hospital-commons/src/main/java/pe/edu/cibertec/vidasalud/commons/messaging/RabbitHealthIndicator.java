package pe.edu.cibertec.vidasalud.commons.messaging;

import java.time.Duration;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.ReactiveHealthIndicator;
import reactor.core.publisher.Mono;

/** Indicador de salud "rabbit": UP si la conexion a RabbitMQ esta abierta. */
public class RabbitHealthIndicator implements ReactiveHealthIndicator {

    private final ConexionRabbit conexion;

    public RabbitHealthIndicator(ConexionRabbit conexion) {
        this.conexion = conexion;
    }

    @Override
    public Mono<Health> health() {
        return conexion.conexion()
                .timeout(Duration.ofSeconds(2))
                .map(c -> c.isOpen() ? Health.up().build() : Health.down().withDetail("motivo", "conexion cerrada").build())
                .onErrorResume(e -> Mono.just(Health.down().withDetail("motivo", e.getClass().getSimpleName()).build()));
    }
}
