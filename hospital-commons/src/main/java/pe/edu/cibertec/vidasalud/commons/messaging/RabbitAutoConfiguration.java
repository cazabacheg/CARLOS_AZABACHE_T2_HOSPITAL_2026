package pe.edu.cibertec.vidasalud.commons.messaging;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.rabbitmq.client.Connection;
import com.rabbitmq.client.ConnectionFactory;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;
import reactor.rabbitmq.RabbitFlux;
import reactor.rabbitmq.Receiver;
import reactor.rabbitmq.ReceiverOptions;
import reactor.rabbitmq.Sender;
import reactor.rabbitmq.SenderOptions;

@AutoConfiguration(afterName = "org.springframework.boot.autoconfigure.jackson.JacksonAutoConfiguration")
@ConditionalOnProperty(prefix = "hospital.rabbit", name = "enabled", havingValue = "true", matchIfMissing = true)
@EnableConfigurationProperties(RabbitPropiedades.class)
public class RabbitAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    public ConexionRabbit conexionRabbit(RabbitPropiedades props) {
        ConnectionFactory fabrica = new ConnectionFactory();
        fabrica.setHost(props.host());
        fabrica.setPort(props.port());
        fabrica.setUsername(props.username());
        fabrica.setPassword(props.password());
        fabrica.setVirtualHost(props.virtualHost());
        fabrica.setAutomaticRecoveryEnabled(true);
        fabrica.setRequestedHeartbeat(30);
        Mono<Connection> conexion = Mono.fromCallable(() -> fabrica.newConnection("hospital-vida-salud"))
                .subscribeOn(Schedulers.boundedElastic())
                .cacheInvalidateIf(c -> !c.isOpen());
        return new ConexionRabbit(conexion);
    }

    @Bean(destroyMethod = "close")
    @ConditionalOnMissingBean
    public Sender senderRabbit(ConexionRabbit conexion) {
        return RabbitFlux.createSender(new SenderOptions()
                .connectionMono(conexion.conexion())
                .resourceManagementScheduler(Schedulers.boundedElastic()));
    }

    @Bean(destroyMethod = "close")
    @ConditionalOnMissingBean
    public Receiver receiverRabbit(ConexionRabbit conexion) {
        return RabbitFlux.createReceiver(new ReceiverOptions()
                .connectionMono(conexion.conexion())
                .connectionSubscriptionScheduler(Schedulers.boundedElastic()));
    }

    @Bean
    @ConditionalOnMissingBean
    public PublicadorEventos publicadorEventos(Sender sender, ObjectMapper mapper) {
        return new PublicadorRabbit(sender, mapper);
    }

    @Bean
    @ConditionalOnMissingBean
    public ConsumidorEventos consumidorEventos(Sender sender, Receiver receiver, ObjectMapper mapper) {
        return new ConsumidorEventos(sender, receiver, mapper);
    }

    @Bean
    public RabbitHealthIndicator rabbitHealthIndicator(ConexionRabbit conexion) {
        return new RabbitHealthIndicator(conexion);
    }
}
