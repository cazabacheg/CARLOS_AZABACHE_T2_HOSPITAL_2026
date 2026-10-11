package pe.edu.cibertec.vidasalud.notificaciones.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.dao.TransientDataAccessResourceException;
import org.springframework.http.HttpStatus;
import pe.edu.cibertec.vidasalud.commons.error.ErrorNegocio;
import pe.edu.cibertec.vidasalud.commons.messaging.EventoEnvelope;
import pe.edu.cibertec.vidasalud.commons.paginacion.ParametrosPagina;
import pe.edu.cibertec.vidasalud.notificaciones.domain.FiltroNotificaciones;
import pe.edu.cibertec.vidasalud.notificaciones.domain.Notificacion;
import pe.edu.cibertec.vidasalud.notificaciones.persistence.ConsultaNotificaciones;
import pe.edu.cibertec.vidasalud.notificaciones.persistence.NotificacionRepository;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

class NotificacionServiceTest {

    private static final Instant AHORA = Instant.parse("2026-10-12T14:30:00Z");
    private static final Clock RELOJ = Clock.fixed(AHORA, ZoneId.of("America/Lima"));
    private static final UUID PACIENTE = UUID.fromString("11111111-1111-4111-8111-111111111111");
    private static final UUID CITA = UUID.fromString("22222222-2222-4222-8222-222222222222");
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private NotificacionRepository repositorio;
    private ConsultaNotificaciones consulta;
    private NotificacionService servicio;

    @BeforeEach
    void preparar() {
        repositorio = mock(NotificacionRepository.class);
        consulta = mock(ConsultaNotificaciones.class);
        servicio = new NotificacionService(repositorio, consulta, RELOJ);
    }

    private static EventoEnvelope evento(String tipo, UUID recursoId, boolean conPaciente) {
        ObjectNode datos = MAPPER.createObjectNode();
        datos.put("codigo", "CIT-20261012-000001");
        if (conPaciente) {
            datos.put("pacienteId", PACIENTE.toString());
        }
        datos.put("especialidadNombre", "Medicina General");
        datos.put("fechaHora", "2026-10-15T15:00:00Z");
        return EventoEnvelope.nuevo(RELOJ, tipo, "citas-service", "CITA", recursoId, "PENDIENTE", "CONFIRMADA", datos);
    }

    private void guardarDevuelveLoRecibido() {
        when(repositorio.save(any())).thenAnswer(inv -> Mono.just(inv.getArgument(0)));
    }

    @Test
    void guardaLaNotificacionDeUnaCitaConfirmada() {
        guardarDevuelveLoRecibido();
        EventoEnvelope e = evento("cita.confirmada", CITA, true);

        StepVerifier.create(servicio.procesar(e)).verifyComplete();

        ArgumentCaptor<Notificacion> captor = ArgumentCaptor.forClass(Notificacion.class);
        verify(repositorio).save(captor.capture());
        Notificacion n = captor.getValue();
        assertThat(n.getEventId()).isEqualTo(e.eventId());
        assertThat(n.getPacienteId()).isEqualTo(PACIENTE);
        assertThat(n.getRecursoId()).isEqualTo(CITA);
        assertThat(n.getTipoEvento()).isEqualTo("cita.confirmada");
        assertThat(n.getCanal()).isEqualTo("SIMULADO");
        assertThat(n.getFechaEnvio().toInstant()).isEqualTo(AHORA);
        assertThat(n.isNew()).isTrue();
        assertThat(n.getMensaje()).isEqualTo(
                "Su cita CIT-20261012-000001 de Medicina General del 15/10/2026 10:00 fue confirmada.");
    }

    @Test
    void pacienteRegistradoSinPacienteIdUsaElRecurso() {
        guardarDevuelveLoRecibido();

        StepVerifier.create(servicio.procesar(evento("paciente.registrado", PACIENTE, false))).verifyComplete();

        ArgumentCaptor<Notificacion> captor = ArgumentCaptor.forClass(Notificacion.class);
        verify(repositorio).save(captor.capture());
        assertThat(captor.getValue().getPacienteId()).isEqualTo(PACIENTE);
    }

    @Test
    void duplicadoCompletaSinError() {
        when(repositorio.save(any())).thenReturn(Mono.error(new DuplicateKeyException("dup")));

        StepVerifier.create(servicio.procesar(evento("cita.confirmada", CITA, true))).verifyComplete();
    }

    @Test
    void tipoSinPlantillaSeIgnora() {
        StepVerifier.create(servicio.procesar(evento("cita.atendida", CITA, true))).verifyComplete();

        verify(repositorio, never()).save(any());
    }

    @Test
    void sinRecursoIdFalla() {
        StepVerifier.create(servicio.procesar(evento("cita.confirmada", null, true)))
                .verifyError(IllegalArgumentException.class);
    }

    @Test
    void otrosErroresDeBaseDeDatosSePropagan() {
        when(repositorio.save(any())).thenReturn(Mono.error(new TransientDataAccessResourceException("x")));

        StepVerifier.create(servicio.procesar(evento("cita.confirmada", CITA, true)))
                .verifyError(TransientDataAccessResourceException.class);
    }

    @Test
    void obtenerInexistenteLanza404() {
        when(repositorio.findById(any(UUID.class))).thenReturn(Mono.empty());

        StepVerifier.create(servicio.obtener(UUID.randomUUID()))
                .expectErrorSatisfies(t -> {
                    ErrorNegocio e = (ErrorNegocio) t;
                    assertThat(e.getStatus()).isEqualTo(HttpStatus.NOT_FOUND);
                    assertThat(e.getCodigo()).isEqualTo("NOTIFICACION_NO_ENCONTRADA");
                })
                .verify();
    }

    @Test
    void listarMapeaConOffsetDeLima() {
        Notificacion n = Notificacion.nueva(UUID.randomUUID(), PACIENTE, "cita.confirmada", CITA, "m",
                OffsetDateTime.parse("2026-10-12T14:30:00Z"));
        FiltroNotificaciones filtro = new FiltroNotificaciones(null, null, null);
        ParametrosPagina pagina = new ParametrosPagina(0, 20);
        when(consulta.buscar(filtro, pagina)).thenReturn(Flux.just(n));

        StepVerifier.create(servicio.listar(filtro, pagina))
                .assertNext(r -> {
                    assertThat(r.fechaEnvio().getOffset().getTotalSeconds()).isEqualTo(-5 * 3600);
                    assertThat(r.fechaEnvio().toInstant()).isEqualTo(AHORA);
                    assertThat(r.mensaje()).isEqualTo("m");
                })
                .verifyComplete();
    }
}
