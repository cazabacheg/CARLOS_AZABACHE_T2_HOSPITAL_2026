package pe.edu.cibertec.vidasalud.commons.messaging;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Function;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.dao.DuplicateKeyException;
import reactor.core.Disposable;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.rabbitmq.AcknowledgableDelivery;
import reactor.rabbitmq.BindingSpecification;
import reactor.rabbitmq.ConsumeOptions;
import reactor.rabbitmq.ExchangeSpecification;
import reactor.rabbitmq.QueueSpecification;
import reactor.rabbitmq.Receiver;
import reactor.rabbitmq.Sender;
import reactor.util.retry.Retry;

/**
 * Consumo reactivo de eventos con ack manual:
 * exito -> ack; {@link DuplicateKeyException} -> ack (idempotencia);
 * mensaje no deserializable -> DLQ inmediata; otro error -> 3 intentos con espera
 * exponencial desde 500 ms y luego DLQ.
 */
public class ConsumidorEventos implements DisposableBean {

    private static final Logger log = LoggerFactory.getLogger(ConsumidorEventos.class);
    private static final int REINTENTOS = 2; // + intento inicial = 3 intentos
    private static final Duration ESPERA_INICIAL = Duration.ofMillis(500);

    private final Sender sender;
    private final Receiver receiver;
    private final ObjectMapper mapper;
    private final List<Disposable> suscripciones = new CopyOnWriteArrayList<>();

    public ConsumidorEventos(Sender sender, Receiver receiver, ObjectMapper mapper) {
        this.sender = sender;
        this.receiver = receiver;
        this.mapper = mapper;
    }

    /**
     * Suscribe el consumo al arrancar la aplicacion (unica suscripcion manual permitida:
     * es de ciclo de vida, no del pipeline de una peticion).
     */
    public Disposable iniciar(String cola, List<String> bindings, Function<EventoEnvelope, Mono<Void>> manejador) {
        Disposable suscripcion = consumir(cola, bindings, manejador)
                .subscribe(v -> { }, e -> log.error("el consumo de {} termino con error", cola, e));
        suscripciones.add(suscripcion);
        return suscripcion;
    }

    public Flux<Void> consumir(String cola, List<String> bindings, Function<EventoEnvelope, Mono<Void>> manejador) {
        return declarar(cola, bindings)
                .thenMany(Flux.defer(() -> receiver.consumeManualAck(cola, new ConsumeOptions().qos(10))
                        .flatMap(entrega -> procesar(cola, entrega, manejador), 4)))
                .retryWhen(Retry.backoff(Long.MAX_VALUE, Duration.ofSeconds(1))
                        .maxBackoff(Duration.ofSeconds(30))
                        .doBeforeRetry(s -> log.warn("reintentando conexion del consumidor de {}: {}",
                                cola, s.failure().toString())));
    }

    private Mono<Void> declarar(String cola, List<String> bindings) {
        String colaMuertos = TopologiaHospital.colaMuertos(cola);
        Map<String, Object> argumentos = Map.of(
                "x-dead-letter-exchange", TopologiaHospital.EXCHANGE_DLX,
                "x-dead-letter-routing-key", colaMuertos);
        Mono<Void> base = sender.declareExchange(ExchangeSpecification.exchange(TopologiaHospital.EXCHANGE)
                        .type("topic").durable(true))
                .then(sender.declareExchange(ExchangeSpecification.exchange(TopologiaHospital.EXCHANGE_DLX)
                        .type("direct").durable(true)))
                .then(sender.declareQueue(QueueSpecification.queue(colaMuertos).durable(true)))
                .then(sender.bind(BindingSpecification.binding(TopologiaHospital.EXCHANGE_DLX, colaMuertos, colaMuertos)))
                .then(sender.declareQueue(QueueSpecification.queue(cola).durable(true).arguments(argumentos)))
                .then();
        return Flux.fromIterable(bindings)
                .concatMap(clave -> sender.bind(BindingSpecification.binding(TopologiaHospital.EXCHANGE, clave, cola)))
                .then()
                .as(enlaces -> base.then(enlaces));
    }

    private Mono<Void> procesar(String cola, AcknowledgableDelivery entrega,
                                Function<EventoEnvelope, Mono<Void>> manejador) {
        EventoEnvelope evento;
        try {
            evento = mapper.readValue(entrega.getBody(), EventoEnvelope.class);
            if (evento.eventId() == null || evento.tipo() == null) {
                throw new IllegalArgumentException("el sobre no tiene eventId o tipo");
            }
        } catch (Exception e) {
            log.error("mensaje no deserializable en {}; se envia a la DLQ: {}", cola, e.toString());
            entrega.nack(false);
            return Mono.empty();
        }
        EventoEnvelope recibido = evento;
        return Mono.defer(() -> manejador.apply(recibido))
                .onErrorResume(DuplicateKeyException.class, e -> {
                    log.info("evento repetido ignorado eventId={} tipo={}", recibido.eventId(), recibido.tipo());
                    return Mono.empty();
                })
                .retryWhen(Retry.backoff(REINTENTOS, ESPERA_INICIAL).onRetryExhaustedThrow((s, r) -> r.failure()))
                .doOnSuccess(v -> {
                    log.info("evento procesado eventId={} tipo={}", recibido.eventId(), recibido.tipo());
                    entrega.ack();
                })
                .onErrorResume(e -> {
                    log.error("evento a la DLQ eventId={} tipo={} tras reintentos: {}",
                            recibido.eventId(), recibido.tipo(), e.toString());
                    entrega.nack(false);
                    return Mono.empty();
                });
    }

    @Override
    public void destroy() {
        suscripciones.forEach(Disposable::dispose);
    }
}
