package pe.edu.cibertec.vidasalud.commons.messaging;

import com.rabbitmq.client.Connection;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.boot.actuate.health.Status;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

class RabbitHealthIndicatorTest {

    @Test
    void conexionAbiertaEsUp() {
        Connection c = Mockito.mock(Connection.class);
        Mockito.when(c.isOpen()).thenReturn(true);
        StepVerifier.create(new RabbitHealthIndicator(new ConexionRabbit(Mono.just(c))).health())
                .expectNextMatches(h -> h.getStatus().equals(Status.UP)).verifyComplete();
    }

    @Test
    void conexionCerradaEsDown() {
        Connection c = Mockito.mock(Connection.class);
        Mockito.when(c.isOpen()).thenReturn(false);
        StepVerifier.create(new RabbitHealthIndicator(new ConexionRabbit(Mono.just(c))).health())
                .expectNextMatches(h -> h.getStatus().equals(Status.DOWN)).verifyComplete();
    }

    @Test
    void errorAlConectarEsDown() {
        StepVerifier.create(new RabbitHealthIndicator(new ConexionRabbit(Mono.error(new RuntimeException("x")))).health())
                .expectNextMatches(h -> h.getStatus().equals(Status.DOWN)).verifyComplete();
    }
}
