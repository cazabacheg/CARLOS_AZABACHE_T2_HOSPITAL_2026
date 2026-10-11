package pe.edu.cibertec.vidasalud.commons.messaging;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.rabbitmq.client.Channel;
import com.rabbitmq.client.Connection;
import com.rabbitmq.client.ConnectionFactory;
import com.rabbitmq.client.GetResponse;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DuplicateKeyException;
import org.testcontainers.containers.RabbitMQContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import reactor.core.publisher.Mono;
import reactor.rabbitmq.OutboundMessage;
import reactor.rabbitmq.Sender;

@Testcontainers
class MensajeriaRabbitIT {

    @Container
    static final RabbitMQContainer RABBIT = new RabbitMQContainer("rabbitmq:3.13-management");

    static RabbitAutoConfiguration config = new RabbitAutoConfiguration();
    static ObjectMapper mapper = new ObjectMapper()
            .registerModule(new JavaTimeModule())
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
    static Sender sender;
    static ConsumidorEventos consumidor;
    static PublicadorEventos publicador;
    static Connection conexionDirecta;

    @BeforeAll
    static void preparar() throws Exception {
        RabbitPropiedades props = new RabbitPropiedades(true, RABBIT.getHost(), RABBIT.getAmqpPort(),
                "guest", "guest", "/");
        ConexionRabbit conexion = config.conexionRabbit(props);
        sender = config.senderRabbit(conexion);
        consumidor = config.consumidorEventos(sender, config.receiverRabbit(conexion), mapper);
        publicador = config.publicadorEventos(sender, mapper);
        ConnectionFactory f = new ConnectionFactory();
        f.setHost(RABBIT.getHost());
        f.setPort(RABBIT.getAmqpPort());
        conexionDirecta = f.newConnection();
    }

    @AfterAll
    static void cerrar() throws Exception {
        consumidor.destroy();
        sender.close();
        conexionDirecta.close();
    }

    private static EventoEnvelope evento(String tipo) {
        return EventoEnvelope.nuevo(Clock.systemUTC(), tipo, "prueba", "CITA", UUID.randomUUID(),
                "PENDIENTE", "CONFIRMADA", mapper.valueToTree(Map.of("codigo", "CIT-1")));
    }

    private static void esperarCola(String cola) {
        await().atMost(Duration.ofSeconds(15)).ignoreExceptions().until(() -> {
            try (Channel canal = conexionDirecta.createChannel()) {
                canal.queueDeclarePassive(cola);
                return true;
            }
        });
    }

    private static int mensajesEn(String cola) throws Exception {
        try (Channel canal = conexionDirecta.createChannel()) {
            return canal.queueDeclarePassive(cola).getMessageCount();
        }
    }

    @Test
    void publicarYConsumirConfirmaElMensaje() throws Exception {
        List<EventoEnvelope> recibidos = new CopyOnWriteArrayList<>();
        consumidor.iniciar("prueba1.cola", List.of("cita.confirmada"), e -> {
            recibidos.add(e);
            return Mono.empty();
        });
        esperarCola("prueba1.cola");

        EventoEnvelope enviado = evento("cita.confirmada");
        publicador.publicar("cita.confirmada", enviado).block(Duration.ofSeconds(10));

        await().atMost(Duration.ofSeconds(10)).until(() -> !recibidos.isEmpty());
        assertThat(recibidos.get(0).eventId()).isEqualTo(enviado.eventId());
        assertThat(recibidos.get(0).datos().get("codigo").asText()).isEqualTo("CIT-1");
        await().atMost(Duration.ofSeconds(5)).until(() -> mensajesEn("prueba1.cola") == 0);
        assertThat(mensajesEn("prueba1.dlq")).isZero();
    }

    @Test
    void soloLlegaLoQueCoincideConElBinding() throws Exception {
        List<EventoEnvelope> recibidos = new CopyOnWriteArrayList<>();
        consumidor.iniciar("prueba2.cola", List.of("cita.confirmada"), e -> {
            recibidos.add(e);
            return Mono.empty();
        });
        esperarCola("prueba2.cola");

        publicador.publicar("cita.atendida", evento("cita.atendida")).block(Duration.ofSeconds(10));
        publicador.publicar("cita.confirmada", evento("cita.confirmada")).block(Duration.ofSeconds(10));

        await().atMost(Duration.ofSeconds(10)).until(() -> !recibidos.isEmpty());
        Thread.sleep(500);
        assertThat(recibidos).hasSize(1);
        assertThat(recibidos.get(0).tipo()).isEqualTo("cita.confirmada");
    }

    @Test
    void errorPersistenteHace3IntentosYVaALaDlq() throws Exception {
        AtomicInteger intentos = new AtomicInteger();
        consumidor.iniciar("prueba3.cola", List.of("pago.registrado"), e -> {
            intentos.incrementAndGet();
            return Mono.error(new IllegalStateException("falla de base de datos"));
        });
        esperarCola("prueba3.cola");

        publicador.publicar("pago.registrado", evento("pago.registrado")).block(Duration.ofSeconds(10));

        await().atMost(Duration.ofSeconds(20)).until(() -> mensajesEn("prueba3.dlq") == 1);
        assertThat(intentos.get()).isEqualTo(3);
        assertThat(mensajesEn("prueba3.cola")).isZero();
    }

    @Test
    void fallaTransitoriaSeRecuperaSinIrALaDlq() throws Exception {
        AtomicInteger intentos = new AtomicInteger();
        consumidor.iniciar("prueba4.cola", List.of("pago.anulado"), e ->
                intentos.incrementAndGet() < 2 ? Mono.error(new IllegalStateException("transitoria")) : Mono.empty());
        esperarCola("prueba4.cola");

        publicador.publicar("pago.anulado", evento("pago.anulado")).block(Duration.ofSeconds(10));

        await().atMost(Duration.ofSeconds(15)).until(() -> intentos.get() == 2 && mensajesEn("prueba4.cola") == 0);
        assertThat(mensajesEn("prueba4.dlq")).isZero();
    }

    @Test
    void eventoDuplicadoSeConfirmaSinReintentos() throws Exception {
        AtomicInteger intentos = new AtomicInteger();
        consumidor.iniciar("prueba5.cola", List.of("atencion.registrada"), e -> {
            intentos.incrementAndGet();
            return Mono.error(new DuplicateKeyException("event_id repetido"));
        });
        esperarCola("prueba5.cola");

        publicador.publicar("atencion.registrada", evento("atencion.registrada")).block(Duration.ofSeconds(10));

        await().atMost(Duration.ofSeconds(10)).until(() -> intentos.get() == 1 && mensajesEn("prueba5.cola") == 0);
        Thread.sleep(1500);
        assertThat(intentos.get()).isEqualTo(1);
        assertThat(mensajesEn("prueba5.dlq")).isZero();
    }

    @Test
    void mensajeNoDeserializableVaDirectoALaDlqSinInvocarAlManejador() throws Exception {
        AtomicInteger intentos = new AtomicInteger();
        consumidor.iniciar("prueba6.cola", List.of("cita.cancelada"), e -> {
            intentos.incrementAndGet();
            return Mono.empty();
        });
        esperarCola("prueba6.cola");

        sender.send(Mono.just(new OutboundMessage(TopologiaHospital.EXCHANGE, "cita.cancelada",
                "{esto no es json".getBytes()))).block(Duration.ofSeconds(10));

        await().atMost(Duration.ofSeconds(10)).until(() -> mensajesEn("prueba6.dlq") == 1);
        assertThat(intentos.get()).isZero();
        try (Channel canal = conexionDirecta.createChannel()) {
            GetResponse r = canal.basicGet("prueba6.dlq", true);
            assertThat(new String(r.getBody())).isEqualTo("{esto no es json");
        }
    }

    @Test
    void publicadorNoFallaSiElBrokerNoResponde() {
        RabbitPropiedades malas = new RabbitPropiedades(true, "localhost", 1, "x", "x", "/");
        ConexionRabbit conexion = config.conexionRabbit(malas);
        try (Sender senderMalo = config.senderRabbit(conexion)) {
            PublicadorEventos pub = new PublicadorRabbit(senderMalo, mapper);
            // No debe lanzar: el error se registra y la operacion de negocio sigue (D-3).
            pub.publicar("cita.creada", evento("cita.creada")).block(Duration.ofSeconds(10));
        }
    }
}
