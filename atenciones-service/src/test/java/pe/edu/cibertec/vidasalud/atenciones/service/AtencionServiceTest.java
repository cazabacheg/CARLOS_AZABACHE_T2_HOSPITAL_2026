package pe.edu.cibertec.vidasalud.atenciones.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.UUID;
import pe.edu.cibertec.vidasalud.atenciones.api.dto.AtencionCrearRequest;
import pe.edu.cibertec.vidasalud.atenciones.api.dto.CambioEstadoAtencionRequest;
import pe.edu.cibertec.vidasalud.atenciones.client.CitasClient;
import pe.edu.cibertec.vidasalud.atenciones.domain.AreaAtencion;
import pe.edu.cibertec.vidasalud.atenciones.domain.Atencion;
import pe.edu.cibertec.vidasalud.atenciones.domain.CitaResumen;
import pe.edu.cibertec.vidasalud.atenciones.domain.EstadoAtencion;
import pe.edu.cibertec.vidasalud.atenciones.domain.VentanaAtencion;
import pe.edu.cibertec.vidasalud.atenciones.persistence.AtencionConsultas;
import pe.edu.cibertec.vidasalud.atenciones.persistence.AtencionRepository;
import pe.edu.cibertec.vidasalud.atenciones.persistence.GeneradorCodigoAtencion;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.http.HttpStatus;
import pe.edu.cibertec.vidasalud.commons.error.ErrorNegocio;
import pe.edu.cibertec.vidasalud.commons.messaging.EventoEnvelope;
import pe.edu.cibertec.vidasalud.commons.messaging.PublicadorEventos;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

@ExtendWith(MockitoExtension.class)
class AtencionServiceTest {

    private static final Clock RELOJ = Clock.fixed(Instant.parse("2026-10-14T14:00:00Z"), ZoneId.of("America/Lima"));

    @Mock AtencionRepository repositorio;
    @Mock AtencionConsultas consultas;
    @Mock GeneradorCodigoAtencion generador;
    @Mock CitasClient citas;
    @Mock PublicadorEventos publicador;

    private AtencionService servicio;

    @BeforeEach
    void preparar() {
        servicio = new AtencionService(repositorio, consultas, generador, citas, publicador,
                new VentanaAtencion(30), RELOJ);
    }

    private static CitaResumen cita(String estado, String hora) {
        return new CitaResumen(UUID.randomUUID(), UUID.randomUUID(), estado,
                OffsetDateTime.parse("2026-10-14T" + hora + ":00-05:00"));
    }

    private static AtencionCrearRequest solicitud(CitaResumen c) {
        return new AtencionCrearRequest(c.id(), AreaAtencion.CONSULTA_EXTERNA);
    }

    private void stubsDeRegistroExitoso(CitaResumen c) {
        when(citas.obtener(c.id())).thenReturn(Mono.just(c));
        when(repositorio.existsActivaByCitaId(c.id())).thenReturn(Mono.just(false));
        when(generador.siguiente()).thenReturn(Mono.just("ATE-20261014-000001"));
        when(repositorio.save(any(Atencion.class))).thenAnswer(inv -> Mono.just(inv.getArgument(0)));
        when(publicador.publicar(anyString(), any())).thenReturn(Mono.empty());
    }

    private static void esError(Throwable e, HttpStatus status, String codigo) {
        assertThat(e).isInstanceOf(ErrorNegocio.class);
        assertThat(((ErrorNegocio) e).getStatus()).isEqualTo(status);
        assertThat(((ErrorNegocio) e).getCodigo()).isEqualTo(codigo);
    }

    private Atencion registrada() {
        CitaResumen c = cita("CONFIRMADA", "09:30");
        return Atencion.registrar(c, AreaAtencion.TRIAJE, "ATE-20261014-000002", RELOJ);
    }

    @Test
    void registraYPublicaElEvento() {
        CitaResumen c = cita("CONFIRMADA", "09:30");
        stubsDeRegistroExitoso(c);

        StepVerifier.create(servicio.registrar(solicitud(c)))
                .assertNext(r -> {
                    assertThat(r.estado()).isEqualTo(EstadoAtencion.REGISTRADA);
                    assertThat(r.pacienteId()).isEqualTo(c.pacienteId());
                    assertThat(r.codigo()).isEqualTo("ATE-20261014-000001");
                    ArgumentCaptor<EventoEnvelope> captor = ArgumentCaptor.forClass(EventoEnvelope.class);
                    verify(publicador).publicar(eq("atencion.registrada"), captor.capture());
                    EventoEnvelope e = captor.getValue();
                    assertThat(e.recursoId()).isEqualTo(r.id());
                    assertThat(e.datos().get("citaId").asText()).isEqualTo(c.id().toString());
                    assertThat(e.datos().get("pacienteId").asText()).isEqualTo(c.pacienteId().toString());
                })
                .verifyComplete();
    }

    @Test
    void registraCitaPasada() {
        CitaResumen c = cita("CONFIRMADA", "07:00");
        stubsDeRegistroExitoso(c);
        StepVerifier.create(servicio.registrar(solicitud(c))).expectNextCount(1).verifyComplete();
    }

    @Test
    void rechazaFueraDeVentana() {
        CitaResumen c = cita("CONFIRMADA", "09:31");
        when(citas.obtener(c.id())).thenReturn(Mono.just(c));
        StepVerifier.create(servicio.registrar(solicitud(c)))
                .expectErrorSatisfies(e -> esError(e, HttpStatus.CONFLICT, "FUERA_DE_VENTANA_ATENCION"))
                .verify();
        verify(repositorio, never()).save(any());
        verifyNoInteractions(publicador);
    }

    @Test
    void rechazaCitaPendiente() {
        CitaResumen c = cita("PENDIENTE", "09:30");
        when(citas.obtener(c.id())).thenReturn(Mono.just(c));
        StepVerifier.create(servicio.registrar(solicitud(c)))
                .expectErrorSatisfies(e -> esError(e, HttpStatus.CONFLICT, "CITA_NO_CONFIRMADA"))
                .verify();
        verify(repositorio, never()).existsActivaByCitaId(any());
        verify(repositorio, never()).save(any());
        verifyNoInteractions(publicador);
    }

    @Test
    void rechazaCitaCancelada() {
        CitaResumen c = cita("CANCELADA", "09:30");
        when(citas.obtener(c.id())).thenReturn(Mono.just(c));
        StepVerifier.create(servicio.registrar(solicitud(c)))
                .expectErrorSatisfies(e -> esError(e, HttpStatus.CONFLICT, "CITA_NO_CONFIRMADA"))
                .verify();
    }

    @Test
    void rechazaAtencionActivaExistente() {
        CitaResumen c = cita("CONFIRMADA", "09:30");
        when(citas.obtener(c.id())).thenReturn(Mono.just(c));
        when(repositorio.existsActivaByCitaId(c.id())).thenReturn(Mono.just(true));
        StepVerifier.create(servicio.registrar(solicitud(c)))
                .expectErrorSatisfies(e -> esError(e, HttpStatus.CONFLICT, "ATENCION_DUPLICADA"))
                .verify();
        verifyNoInteractions(generador);
        verify(repositorio, never()).save(any());
    }

    @Test
    void traduceViolacionDelIndiceUnico() {
        CitaResumen c = cita("CONFIRMADA", "09:30");
        when(citas.obtener(c.id())).thenReturn(Mono.just(c));
        when(repositorio.existsActivaByCitaId(c.id())).thenReturn(Mono.just(false));
        when(generador.siguiente()).thenReturn(Mono.just("ATE-20261014-000001"));
        when(repositorio.save(any(Atencion.class)))
                .thenReturn(Mono.error(new DuplicateKeyException("ux_atencion_cita_activa")));
        StepVerifier.create(servicio.registrar(solicitud(c)))
                .expectErrorSatisfies(e -> esError(e, HttpStatus.CONFLICT, "ATENCION_DUPLICADA"))
                .verify();
        verifyNoInteractions(publicador);
    }

    @Test
    void propagaCitasCaido() {
        CitaResumen c = cita("CONFIRMADA", "09:30");
        when(citas.obtener(c.id())).thenReturn(Mono.error(new ErrorNegocio(HttpStatus.SERVICE_UNAVAILABLE,
                "SERVICIO_CITAS_NO_DISPONIBLE", "El servicio de citas no esta disponible.")));
        StepVerifier.create(servicio.registrar(solicitud(c)))
                .expectErrorSatisfies(e -> esError(e, HttpStatus.SERVICE_UNAVAILABLE, "SERVICIO_CITAS_NO_DISPONIBLE"))
                .verify();
        verify(repositorio, never()).save(any());
    }

    @Test
    void propagaCitaInexistente() {
        UUID id = UUID.randomUUID();
        when(citas.obtener(id)).thenReturn(Mono.error(new ErrorNegocio(HttpStatus.NOT_FOUND,
                "CITA_NO_ENCONTRADA", "No existe la cita.")));
        StepVerifier.create(servicio.registrar(new AtencionCrearRequest(id, AreaAtencion.EMERGENCIA)))
                .expectErrorSatisfies(e -> esError(e, HttpStatus.NOT_FOUND, "CITA_NO_ENCONTRADA"))
                .verify();
    }

    @Test
    void anulaYPublica() {
        Atencion a = registrada();
        when(repositorio.findById(a.getId())).thenReturn(Mono.just(a));
        when(repositorio.save(any(Atencion.class))).thenAnswer(inv -> Mono.just(inv.getArgument(0)));
        when(publicador.publicar(anyString(), any())).thenReturn(Mono.empty());

        StepVerifier.create(servicio.cambiarEstado(a.getId(),
                        new CambioEstadoAtencionRequest(EstadoAtencion.ANULADA, "Error de registro")))
                .assertNext(r -> assertThat(r.estado()).isEqualTo(EstadoAtencion.ANULADA))
                .verifyComplete();
        ArgumentCaptor<EventoEnvelope> captor = ArgumentCaptor.forClass(EventoEnvelope.class);
        verify(publicador).publicar(eq("atencion.anulada"), captor.capture());
        assertThat(captor.getValue().estadoAnterior()).isEqualTo("REGISTRADA");
        assertThat(captor.getValue().datos().get("motivo").asText()).isEqualTo("Error de registro");
    }

    @Test
    void finalizaYPublica() {
        Atencion a = registrada();
        when(repositorio.findById(a.getId())).thenReturn(Mono.just(a));
        when(repositorio.save(any(Atencion.class))).thenAnswer(inv -> Mono.just(inv.getArgument(0)));
        when(publicador.publicar(anyString(), any())).thenReturn(Mono.empty());

        StepVerifier.create(servicio.cambiarEstado(a.getId(),
                        new CambioEstadoAtencionRequest(EstadoAtencion.FINALIZADA, null)))
                .expectNextCount(1)
                .verifyComplete();
        verify(publicador).publicar(eq("atencion.finalizada"), any());
    }

    @Test
    void rechazaTransicionNoPermitida() {
        Atencion a = registrada();
        a.cambiarEstado(EstadoAtencion.FINALIZADA, RELOJ);
        when(repositorio.findById(a.getId())).thenReturn(Mono.just(a));
        StepVerifier.create(servicio.cambiarEstado(a.getId(),
                        new CambioEstadoAtencionRequest(EstadoAtencion.ANULADA, null)))
                .expectErrorSatisfies(e -> esError(e, HttpStatus.CONFLICT, "TRANSICION_NO_PERMITIDA"))
                .verify();
        verify(repositorio, never()).save(any());
    }

    @Test
    void traduceConflictoOptimista() {
        Atencion a = registrada();
        when(repositorio.findById(a.getId())).thenReturn(Mono.just(a));
        when(repositorio.save(any(Atencion.class))).thenReturn(Mono.error(new OptimisticLockingFailureException("x")));
        StepVerifier.create(servicio.cambiarEstado(a.getId(),
                        new CambioEstadoAtencionRequest(EstadoAtencion.FINALIZADA, null)))
                .expectErrorSatisfies(e -> esError(e, HttpStatus.CONFLICT, "CONFLICTO_CONCURRENTE"))
                .verify();
        verifyNoInteractions(publicador);
    }

    @Test
    void atencionInexistente() {
        UUID id = UUID.randomUUID();
        when(repositorio.findById(id)).thenReturn(Mono.empty());
        StepVerifier.create(servicio.obtener(id))
                .expectErrorSatisfies(e -> esError(e, HttpStatus.NOT_FOUND, "ATENCION_NO_ENCONTRADA"))
                .verify();
        StepVerifier.create(servicio.cambiarEstado(id, new CambioEstadoAtencionRequest(EstadoAtencion.ANULADA, null)))
                .expectErrorSatisfies(e -> esError(e, HttpStatus.NOT_FOUND, "ATENCION_NO_ENCONTRADA"))
                .verify();
    }
}
