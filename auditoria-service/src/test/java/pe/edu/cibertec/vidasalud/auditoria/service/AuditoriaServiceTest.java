package pe.edu.cibertec.vidasalud.auditoria.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import pe.edu.cibertec.vidasalud.auditoria.domain.FiltroAuditoria;
import pe.edu.cibertec.vidasalud.auditoria.domain.SobreInvalidoException;
import pe.edu.cibertec.vidasalud.auditoria.domain.TipoRecurso;
import pe.edu.cibertec.vidasalud.auditoria.persistence.EventoAuditoria;
import pe.edu.cibertec.vidasalud.auditoria.persistence.EventoAuditoriaRepository;
import pe.edu.cibertec.vidasalud.auditoria.persistence.EventoAuditoriaRepositoryR2dbc;
import pe.edu.cibertec.vidasalud.commons.error.ErrorNegocio;
import pe.edu.cibertec.vidasalud.commons.messaging.EventoEnvelope;
import pe.edu.cibertec.vidasalud.commons.paginacion.ParametrosPagina;
import pe.edu.cibertec.vidasalud.commons.reloj.RelojAutoConfiguration;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

class AuditoriaServiceTest {

    private static final OffsetDateTime OCURRIO = OffsetDateTime.parse("2026-10-12T09:30:00-05:00");
    private final Clock reloj = Clock.fixed(Instant.parse("2026-10-12T14:30:00Z"), RelojAutoConfiguration.ZONA_HOSPITAL);
    private final EventoAuditoriaRepository repositorio = mock(EventoAuditoriaRepository.class);
    private final AuditoriaService servicio = new AuditoriaService(repositorio, reloj);

    private EventoEnvelope sobre(UUID recursoId) {
        return new EventoEnvelope(UUID.randomUUID(), "cita.confirmada", OCURRIO, "citas-service", "CITA",
                recursoId, "PENDIENTE", "CONFIRMADA", null);
    }

    private EventoAuditoria evento(String nuevo, int hora) {
        return new EventoAuditoria(UUID.randomUUID(), UUID.randomUUID(), OCURRIO.withHour(hora), "cita.x",
                "CITA", UUID.randomUUID(), null, nuevo, "citas-service", OCURRIO);
    }

    @Test
    void registraElEvento() {
        when(repositorio.insertar(any())).thenAnswer(i -> Mono.just(i.getArgument(0)));
        EventoEnvelope s = sobre(UUID.randomUUID());

        StepVerifier.create(servicio.registrar(s)).verifyComplete();

        ArgumentCaptor<EventoAuditoria> captor = ArgumentCaptor.forClass(EventoAuditoria.class);
        verify(repositorio).insertar(captor.capture());
        EventoAuditoria e = captor.getValue();
        assertThat(e.getEventId()).isEqualTo(s.eventId());
        assertThat(e.getEstadoAnterior()).isEqualTo("PENDIENTE");
        assertThat(e.getEstadoNuevo()).isEqualTo("CONFIRMADA");
        assertThat(e.getOrigen()).isEqualTo("citas-service");
        assertThat(e.getFechaEvento()).isEqualTo(OCURRIO);
        assertThat(e.getFechaRegistro()).isEqualTo(OffsetDateTime.now(reloj));
    }

    @Test
    void duplicadoSeIgnora() {
        when(repositorio.insertar(any())).thenReturn(Mono.error(new DuplicateKeyException("dup")));
        StepVerifier.create(servicio.registrar(sobre(UUID.randomUUID()))).verifyComplete();
    }

    @Test
    void sobreIncompletoNoTocaLaBd() {
        StepVerifier.create(servicio.registrar(sobre(null))).expectError(SobreInvalidoException.class).verify();
        verify(repositorio, never()).insertar(any());
    }

    @Test
    void otroErrorDeBdSePropaga() {
        when(repositorio.insertar(any())).thenReturn(Mono.error(new DataAccessResourceFailureException("x")));
        StepVerifier.create(servicio.registrar(sobre(UUID.randomUUID())))
                .expectError(DataAccessResourceFailureException.class).verify();
    }

    @Test
    void historialConservaElOrden() {
        UUID id = UUID.randomUUID();
        when(repositorio.historial("CITA", id)).thenReturn(Flux.just(
                evento("PENDIENTE", 9), evento("CONFIRMADA", 10), evento("ATENDIDA", 11)));

        StepVerifier.create(servicio.historial(TipoRecurso.CITA, id))
                .expectNextMatches(r -> r.estadoNuevo().equals("PENDIENTE"))
                .expectNextMatches(r -> r.estadoNuevo().equals("CONFIRMADA"))
                .expectNextMatches(r -> r.estadoNuevo().equals("ATENDIDA"))
                .verifyComplete();
        verify(repositorio).historial("CITA", id);
    }

    @Test
    void obtenerInexistenteEs404() {
        when(repositorio.buscarPorId(any())).thenReturn(Mono.empty());
        StepVerifier.create(servicio.obtener(UUID.randomUUID()))
                .expectErrorSatisfies(e -> {
                    assertThat(e).isInstanceOf(ErrorNegocio.class);
                    ErrorNegocio en = (ErrorNegocio) e;
                    assertThat(en.getStatus()).isEqualTo(HttpStatus.NOT_FOUND);
                    assertThat(en.getCodigo()).isEqualTo("EVENTO_AUDITORIA_NO_ENCONTRADO");
                }).verify();
    }

    @Test
    void buscarUsaPaginaYOrden() {
        when(repositorio.buscar(any(), any())).thenReturn(Flux.empty());
        StepVerifier.create(servicio.buscar(FiltroAuditoria.vacio(), ParametrosPagina.de(2, 5))).verifyComplete();

        ArgumentCaptor<Pageable> captor = ArgumentCaptor.forClass(Pageable.class);
        verify(repositorio).buscar(any(), captor.capture());
        Pageable p = captor.getValue();
        assertThat(p.getPageNumber()).isEqualTo(2);
        assertThat(p.getPageSize()).isEqualTo(5);
        assertThat(p.getSort()).isEqualTo(EventoAuditoriaRepositoryR2dbc.ORDEN_LISTADO);
    }
}
