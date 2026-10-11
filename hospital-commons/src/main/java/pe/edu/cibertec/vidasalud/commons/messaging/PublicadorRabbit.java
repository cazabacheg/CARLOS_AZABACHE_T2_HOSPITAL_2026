package pe.edu.cibertec.vidasalud.commons.messaging;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.rabbitmq.client.AMQP;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.rabbitmq.ExchangeSpecification;
import reactor.rabbitmq.OutboundMessage;
import reactor.rabbitmq.Sender;

public class PublicadorRabbit implements PublicadorEventos {

    private static final Logger log = LoggerFactory.getLogger(PublicadorRabbit.class);
    private static final Duration TIMEOUT_CONFIRMACION = Duration.ofSeconds(3);

    private final Sender sender;
    private final ObjectMapper mapper;
    private final AtomicBoolean exchangeDeclarado = new AtomicBoolean(false);

    public PublicadorRabbit(Sender sender, ObjectMapper mapper) {
        this.sender = sender;
        this.mapper = mapper;
    }

    @Override
    public Mono<Void> publicar(String routingKey, EventoEnvelope evento) {
        return declararExchange()
                .then(Mono.fromCallable(() -> mapper.writeValueAsBytes(evento)))
                .flatMap(cuerpo -> enviar(routingKey, evento, cuerpo))
                .timeout(TIMEOUT_CONFIRMACION)
                .doOnSuccess(v -> log.info("evento publicado routingKey={} eventId={}", routingKey, evento.eventId()))
                .onErrorResume(e -> {
                    log.error("no se pudo publicar el evento routingKey={} eventId={}: {}",
                            routingKey, evento.eventId(), e.toString());
                    return Mono.empty();
                });
    }

    private Mono<Void> declararExchange() {
        return Mono.defer(() -> exchangeDeclarado.get()
                ? Mono.empty()
                : sender.declareExchange(ExchangeSpecification.exchange(TopologiaHospital.EXCHANGE)
                                .type("topic").durable(true))
                        .doOnSuccess(ok -> exchangeDeclarado.set(true))
                        .then());
    }

    private Mono<Void> enviar(String routingKey, EventoEnvelope evento, byte[] cuerpo) {
        AMQP.BasicProperties propiedades = new AMQP.BasicProperties.Builder()
                .contentType("application/json")
                .contentEncoding("UTF-8")
                .deliveryMode(2)
                .messageId(evento.eventId().toString())
                .type(evento.tipo())
                .build();
        OutboundMessage mensaje = new OutboundMessage(TopologiaHospital.EXCHANGE, routingKey, propiedades, cuerpo);
        return sender.sendWithPublishConfirms(Flux.just(mensaje))
                .single()
                .flatMap(r -> r.isAck()
                        ? Mono.<Void>empty()
                        : Mono.error(new IllegalStateException("el broker no confirmo (nack) el mensaje")));
    }
}
