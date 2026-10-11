package pe.edu.cibertec.vidasalud.commons.messaging;

import com.rabbitmq.client.Connection;
import reactor.core.publisher.Mono;

/** Conexion compartida a RabbitMQ (se abre perezosamente y se reabre si se cierra). */
public record ConexionRabbit(Mono<Connection> conexion) {
}
