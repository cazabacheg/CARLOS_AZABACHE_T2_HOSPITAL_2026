package pe.edu.cibertec.vidasalud.citas.messaging;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.rabbitmq.client.Channel;
import com.rabbitmq.client.Connection;
import com.rabbitmq.client.ConnectionFactory;
import com.rabbitmq.client.GetResponse;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.containers.RabbitMQContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import pe.edu.cibertec.vidasalud.citas.domain.Cita;
import pe.edu.cibertec.vidasalud.citas.domain.DatosNuevaCita;
import pe.edu.cibertec.vidasalud.citas.domain.Especialidad;
import pe.edu.cibertec.vidasalud.citas.domain.EstadoCita;
import pe.edu.cibertec.vidasalud.citas.persistence.CitaRepository;
import pe.edu.cibertec.vidasalud.commons.messaging.EventoEnvelope;
import pe.edu.cibertec.vidasalud.commons.messaging.PublicadorEventos;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@Testcontainers
@ActiveProfiles("test")
@DirtiesContext
class AtencionRegistradaConsumerIT {

    static final Clock RELOJ = Clock.fixed(Instant.parse("2026-10-14T14:00:00Z"), ZoneId.of("America/Lima"));
    static final String COLA_PRUEBA = "prueba.citas.eventos";
    static final String DLQ = "citas.atenciones.dlq";

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16");
    @Container
    static final RabbitMQContainer RABBIT = new RabbitMQContainer("rabbitmq:3.13-management");

    @DynamicPropertySource
    static void rabbit(DynamicPropertyRegistry r) {
        r.add("hospital.rabbit.enabled", () -> "true");
        r.add("hospital.rabbit.host", RABBIT::getHost);
        r.add("hospital.rabbit.port", RABBIT::getAmqpPort);
        r.add("hospital.rabbit.username", RABBIT::getAdminUsername);
        r.add("hospital.rabbit.password", RABBIT::getAdminPassword);
    }

    @TestConfiguration
    static class RelojFijo {
        @Bean
        Clock reloj() {
            return RELOJ;
        }
    }

    static Connection conexion;
    static Channel canal;

    @Autowired
    CitaRepository repositorio;
    @Autowired
    PublicadorEventos publicador;
    @Autowired
    ObjectMapper mapper;

    @BeforeAll
    static void conectar() throws Exception {
        ConnectionFactory f = new ConnectionFactory();
        f.setHost(RABBIT.getHost());
        f.setPort(RABBIT.getAmqpPort());
        f.setUsername(RABBIT.getAdminUsername());
        f.setPassword(RABBIT.getAdminPassword());
        conexion = f.newConnection();
        canal = conexion.createChannel();
        canal.exchangeDeclare("hospital.eventos", "topic", true);
        canal.queueDeclare(COLA_PRUEBA, true, false, false, null);
        canal.queueBind(COLA_PRUEBA, "hospital.eventos", "cita.atendida");
        canal.queueBind(COLA_PRUEBA, "hospital.eventos", "cita.cancelada");
    }

    @AfterAll
    static void cerrar() throws Exception {
        conexion.close();
    }

    @BeforeEach
    void preparar() throws Exception {
        await().atMost(Duration.ofSeconds(20)).until(() -> {
            try {
                return canal.queueDeclarePassive("citas.atenciones.cola").getConsumerCount() >= 1;
            } catch (Exception e) {
                return false;
            }
        });
        canal.queuePurge(COLA_PRUEBA);
        canal.queuePurge(DLQ);
    }

    private Cita insertar(EstadoCita estado) {
        Cita cita = Cita.programar(new DatosNuevaCita(UUID.randomUUID(), Especialidad.MEDICINA_GENERAL,
                OffsetDateTime.parse("2026-10-15T10:00:00-05:00"), "MED-001", "Control"), "CIT-" + UUID.randomUUID()
                .toString().substring(0, 20), RELOJ);
        if (estado != EstadoCita.PENDIENTE) {
            cita.cambiarEstado(EstadoCita.CONFIRMADA, RELOJ);
        }
        if (estado == EstadoCita.CANCELADA) {
            cita.cambiarEstado(EstadoCita.CANCELADA, RELOJ);
        }
        return repositorio.save(cita).block();
    }

    private EventoEnvelope evento(String tipo, UUID atencionId, UUID citaId) {
        ObjectNode datos = mapper.createObjectNode().put("atencionId", atencionId.toString())
                .put("citaId", citaId.toString()).put("pacienteId", UUID.randomUUID().toString())
                .put("area", "CONSULTA_EXTERNA");
        return EventoEnvelope.nuevo(RELOJ, tipo, "atenciones-service", "ATENCION", atencionId, null, "REGISTRADA",
                datos);
    }

    private EstadoCita estadoDe(UUID id) {
        return repositorio.findById(id).block().getEstado();
    }

    private GetResponse esperarMensaje(String cola) {
        GetResponse[] respuesta = new GetResponse[1];
        await().atMost(Duration.ofSeconds(20)).until(() -> {
            respuesta[0] = canal.basicGet(cola, true);
            return respuesta[0] != null;
        });
        return respuesta[0];
    }

    @Test
    void atencionRegistradaDejaLaCitaAtendidaYPublica() throws Exception {
        Cita cita = insertar(EstadoCita.CONFIRMADA);
        UUID atencionId = UUID.randomUUID();
        publicador.publicar("atencion.registrada", evento("atencion.registrada", atencionId, cita.getId())).block();

        EventoEnvelope publicado = mapper.readValue(esperarMensaje(COLA_PRUEBA).getBody(), EventoEnvelope.class);
        assertThat(publicado.tipo()).isEqualTo("cita.atendida");
        assertThat(publicado.recursoId()).isEqualTo(cita.getId());
        assertThat(publicado.estadoAnterior()).isEqualTo("CONFIRMADA");
        assertThat(publicado.datos().get("atencionId").asText()).isEqualTo(atencionId.toString());
        assertThat(estadoDe(cita.getId())).isEqualTo(EstadoCita.ATENDIDA);
    }

    @Test
    void elMismoEventoDosVecesPublicaUnaSolaVez() throws Exception {
        Cita cita = insertar(EstadoCita.CONFIRMADA);
        EventoEnvelope recibido = evento("atencion.registrada", UUID.randomUUID(), cita.getId());
        publicador.publicar("atencion.registrada", recibido).block();
        publicador.publicar("atencion.registrada", recibido).block();

        esperarMensaje(COLA_PRUEBA);
        Thread.sleep(2000);
        assertThat(canal.basicGet(COLA_PRUEBA, true)).isNull();
        assertThat(estadoDe(cita.getId())).isEqualTo(EstadoCita.ATENDIDA);
    }

    @Test
    void atencionAnuladaCancelaLaCitaFutura() throws Exception {
        Cita cita = insertar(EstadoCita.CONFIRMADA);
        UUID atencionId = UUID.randomUUID();
        publicador.publicar("atencion.registrada", evento("atencion.registrada", atencionId, cita.getId())).block();
        esperarMensaje(COLA_PRUEBA);

        publicador.publicar("atencion.anulada", evento("atencion.anulada", atencionId, cita.getId())).block();
        EventoEnvelope publicado = mapper.readValue(esperarMensaje(COLA_PRUEBA).getBody(), EventoEnvelope.class);
        assertThat(publicado.tipo()).isEqualTo("cita.cancelada");
        assertThat(publicado.datos().get("atencionId").asText()).isEqualTo(atencionId.toString());
        assertThat(publicado.datos().get("motivo").asText()).isEqualTo("Atención anulada");
        assertThat(estadoDe(cita.getId())).isEqualTo(EstadoCita.CANCELADA);
    }

    @Test
    void atencionRegistradaSobreCanceladaTerminaEnLaDlq() throws Exception {
        Cita cita = insertar(EstadoCita.CANCELADA);
        publicador.publicar("atencion.registrada",
                evento("atencion.registrada", UUID.randomUUID(), cita.getId())).block();

        esperarMensaje(DLQ);
        assertThat(estadoDe(cita.getId())).isEqualTo(EstadoCita.CANCELADA);
        assertThat(canal.basicGet(COLA_PRUEBA, true)).isNull();
    }
}
