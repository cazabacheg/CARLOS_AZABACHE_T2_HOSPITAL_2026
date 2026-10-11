package pe.edu.cibertec.vidasalud.citas.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.UUID;
import java.util.function.Consumer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.http.HttpStatus;
import pe.edu.cibertec.vidasalud.citas.api.dto.CambioEstadoCitaRequest;
import pe.edu.cibertec.vidasalud.citas.api.dto.CitaCrearRequest;
import pe.edu.cibertec.vidasalud.citas.api.dto.ReprogramarCitaRequest;
import pe.edu.cibertec.vidasalud.citas.client.PacienteResumen;
import pe.edu.cibertec.vidasalud.citas.client.PacientesClient;
import pe.edu.cibertec.vidasalud.citas.domain.Cita;
import pe.edu.cibertec.vidasalud.citas.domain.DatosNuevaCita;
import pe.edu.cibertec.vidasalud.citas.domain.Especialidad;
import pe.edu.cibertec.vidasalud.citas.domain.EstadoCita;
import pe.edu.cibertec.vidasalud.citas.domain.ParametrosAgenda;
import pe.edu.cibertec.vidasalud.citas.domain.ReglasAgenda;
import pe.edu.cibertec.vidasalud.citas.messaging.EventosCita;
import pe.edu.cibertec.vidasalud.citas.persistence.CitaConsultas;
import pe.edu.cibertec.vidasalud.citas.persistence.CitaRepository;
import pe.edu.cibertec.vidasalud.citas.persistence.SecuenciaCodigoCita;
import pe.edu.cibertec.vidasalud.commons.error.ErrorNegocio;
import pe.edu.cibertec.vidasalud.commons.messaging.EventoEnvelope;
import pe.edu.cibertec.vidasalud.commons.messaging.PublicadorEventos;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

@ExtendWith(MockitoExtension.class)
class CitaServiceTest {

    private static final Clock RELOJ = Clock.fixed(Instant.parse("2026-10-14T14:00:00Z"), ZoneId.of("America/Lima"));
    private static final OffsetDateTime FECHA = OffsetDateTime.parse("2026-10-15T10:00:00-05:00");

    @Mock
    CitaRepository repositorio;
    @Mock
    CitaConsultas consultas;
    @Mock
    SecuenciaCodigoCita secuencia;
    @Mock
    PacientesClient pacientes;
    @Mock
    PublicadorEventos publicador;

    CitaService servicio;
    final UUID pacienteId = UUID.randomUUID();

    @BeforeEach
    void montar() {
        servicio = new CitaService(repositorio, consultas, secuencia, pacientes, publicador,
                new EventosCita(new ObjectMapper(), RELOJ), new ReglasAgenda(RELOJ, ParametrosAgenda.porDefecto()),
                RELOJ);
        lenient().when(publicador.publicar(any(), any())).thenReturn(Mono.empty());
        lenient().when(repositorio.save(any(Cita.class)))
                .thenAnswer(inv -> Mono.just(inv.getArgument(0)));
    }

    private CitaCrearRequest solicitud(OffsetDateTime fecha) {
        return new CitaCrearRequest(pacienteId, Especialidad.MEDICINA_GENERAL, fecha, "MED-001", "Control");
    }

    private Cita citaEn(EstadoCita estado, OffsetDateTime fecha) {
        Cita cita = Cita.programar(new DatosNuevaCita(pacienteId, Especialidad.MEDICINA_GENERAL, fecha,
                "MED-001", "Control"), "CIT-20261014-000001", RELOJ);
        if (estado == EstadoCita.CONFIRMADA || estado == EstadoCita.ATENDIDA || estado == EstadoCita.AUSENTE) {
            cita.cambiarEstado(EstadoCita.CONFIRMADA, RELOJ);
        }
        if (estado == EstadoCita.ATENDIDA || estado == EstadoCita.AUSENTE || estado == EstadoCita.CANCELADA) {
            cita.cambiarEstado(estado, RELOJ);
        }
        return cita.marcarComoExistente();
    }

    private void paciente(String estado) {
        when(pacientes.obtener(pacienteId)).thenReturn(Mono.just(new PacienteResumen(pacienteId, estado)));
    }

    private void sinChoques() {
        when(repositorio.existeActivaDeProfesional(any(), any())).thenReturn(Mono.just(false));
        when(repositorio.existeActivaDePaciente(any(), any())).thenReturn(Mono.just(false));
    }

    private static Consumer<Throwable> errorNegocio(HttpStatus status, String codigo) {
        return e -> assertThat(e).isInstanceOfSatisfying(ErrorNegocio.class, n -> {
            assertThat(n.getStatus()).isEqualTo(status);
            assertThat(n.getCodigo()).isEqualTo(codigo);
        });
    }

    private EventoEnvelope eventoPublicado(String routingKey) {
        ArgumentCaptor<EventoEnvelope> evento = ArgumentCaptor.forClass(EventoEnvelope.class);
        verify(publicador).publicar(org.mockito.ArgumentMatchers.eq(routingKey), evento.capture());
        return evento.getValue();
    }

    @Test
    void programarOkPublicaCitaCreada() {
        paciente("ACTIVO");
        sinChoques();
        when(secuencia.siguiente()).thenReturn(Mono.just(7L));

        StepVerifier.create(servicio.programar(solicitud(FECHA)))
                .assertNext(r -> {
                    assertThat(r.estado()).isEqualTo(EstadoCita.PENDIENTE);
                    assertThat(r.codigo()).isEqualTo("CIT-20261014-000007");
                })
                .verifyComplete();

        EventoEnvelope e = eventoPublicado("cita.creada");
        assertThat(e.estadoAnterior()).isNull();
        assertThat(e.estadoNuevo()).isEqualTo("PENDIENTE");
        assertThat(e.origen()).isEqualTo("citas-service");
        assertThat(e.tipoRecurso()).isEqualTo("CITA");
        assertThat(e.datos().get("fechaHora").asText()).isEqualTo("2026-10-15T10:00:00-05:00");
        assertThat(e.datos().get("especialidadNombre").asText()).isEqualTo("Medicina General");
        assertThat(e.datos().has("nombres")).isFalse();
    }

    @Test
    void agendaInvalidaNoLlamaAPacientes() {
        StepVerifier.create(servicio.programar(solicitud(OffsetDateTime.parse("2026-10-18T10:00:00-05:00"))))
                .expectErrorSatisfies(errorNegocio(HttpStatus.BAD_REQUEST, "HORARIO_FUERA_DE_AGENDA"))
                .verify();
        verify(pacientes, never()).obtener(any());
        verifyNoInteractions(repositorio, secuencia, publicador);
    }

    @Test
    void pacienteInactivo() {
        paciente("INACTIVO");
        StepVerifier.create(servicio.programar(solicitud(FECHA)))
                .expectErrorSatisfies(errorNegocio(HttpStatus.UNPROCESSABLE_ENTITY, "PACIENTE_INACTIVO"))
                .verify();
        verify(repositorio, never()).save(any());
    }

    @Test
    void pacienteInexistente() {
        when(pacientes.obtener(pacienteId)).thenReturn(Mono.error(
                new ErrorNegocio(HttpStatus.NOT_FOUND, "PACIENTE_NO_ENCONTRADO", "No existe")));
        StepVerifier.create(servicio.programar(solicitud(FECHA)))
                .expectErrorSatisfies(errorNegocio(HttpStatus.NOT_FOUND, "PACIENTE_NO_ENCONTRADO"))
                .verify();
    }

    @Test
    void choqueDeProfesional() {
        paciente("ACTIVO");
        when(repositorio.existeActivaDeProfesional(any(), any())).thenReturn(Mono.just(true));
        StepVerifier.create(servicio.programar(solicitud(FECHA)))
                .expectErrorSatisfies(e -> {
                    errorNegocio(HttpStatus.CONFLICT, "CITA_CHOQUE_AGENDA").accept(e);
                    assertThat(((ErrorNegocio) e).getDetalle()).contains("MED-001");
                })
                .verify();
        verify(repositorio, never()).save(any());
        verifyNoInteractions(secuencia);
    }

    @Test
    void choqueDePaciente() {
        paciente("ACTIVO");
        when(repositorio.existeActivaDeProfesional(any(), any())).thenReturn(Mono.just(false));
        when(repositorio.existeActivaDePaciente(any(), any())).thenReturn(Mono.just(true));
        StepVerifier.create(servicio.programar(solicitud(FECHA)))
                .expectErrorSatisfies(e -> assertThat(((ErrorNegocio) e).getDetalle())
                        .isEqualTo("El paciente ya tiene una cita en ese horario."))
                .verify();
    }

    @Test
    void duplicateKeyPorIndiceEsChoque() {
        paciente("ACTIVO");
        sinChoques();
        when(secuencia.siguiente()).thenReturn(Mono.just(1L));
        when(repositorio.save(any(Cita.class))).thenReturn(Mono.error(new DuplicateKeyException(
                "duplicate key value violates unique constraint \"ux_cita_profesional_horario\"")));
        StepVerifier.create(servicio.programar(solicitud(FECHA)))
                .expectErrorSatisfies(e -> {
                    errorNegocio(HttpStatus.CONFLICT, "CITA_CHOQUE_AGENDA").accept(e);
                    assertThat(((ErrorNegocio) e).getDetalle()).isEqualTo("El horario ya fue ocupado.");
                })
                .verify();
        verifyNoInteractions(publicador);
    }

    @Test
    void duplicateKeyDeLaClavePrimariaSePropaga() {
        paciente("ACTIVO");
        sinChoques();
        when(secuencia.siguiente()).thenReturn(Mono.just(1L));
        when(repositorio.save(any(Cita.class))).thenReturn(Mono.error(
                new DuplicateKeyException("duplicate key value violates unique constraint \"cita_pkey\"")));
        StepVerifier.create(servicio.programar(solicitud(FECHA)))
                .expectError(DuplicateKeyException.class)
                .verify();
    }

    @Test
    void confirmarPublicaCitaConfirmada() {
        Cita cita = citaEn(EstadoCita.PENDIENTE, FECHA);
        when(repositorio.findById(cita.getId())).thenReturn(Mono.just(cita));
        StepVerifier.create(servicio.cambiarEstado(cita.getId(),
                        new CambioEstadoCitaRequest(EstadoCita.CONFIRMADA, "Por teléfono")))
                .assertNext(r -> assertThat(r.estado()).isEqualTo(EstadoCita.CONFIRMADA))
                .verifyComplete();
        EventoEnvelope e = eventoPublicado("cita.confirmada");
        assertThat(e.estadoAnterior()).isEqualTo("PENDIENTE");
        assertThat(e.estadoNuevo()).isEqualTo("CONFIRMADA");
        assertThat(e.datos().get("motivo").asText()).isEqualTo("Por teléfono");
    }

    @Test
    void pedirAtendidaPorApiEsConflicto() {
        Cita cita = citaEn(EstadoCita.CONFIRMADA, FECHA);
        when(repositorio.findById(cita.getId())).thenReturn(Mono.just(cita));
        StepVerifier.create(servicio.cambiarEstado(cita.getId(), new CambioEstadoCitaRequest(EstadoCita.ATENDIDA, null)))
                .expectErrorSatisfies(e -> {
                    errorNegocio(HttpStatus.CONFLICT, "TRANSICION_NO_PERMITIDA").accept(e);
                    assertThat(((ErrorNegocio) e).getDetalle())
                            .isEqualTo("Una cita pasa a ATENDIDA solo al registrar su atención.");
                })
                .verify();
        verify(repositorio, never()).save(any());
    }

    @Test
    void confirmarUnaCanceladaEsConflicto() {
        Cita cita = citaEn(EstadoCita.CANCELADA, FECHA);
        when(repositorio.findById(cita.getId())).thenReturn(Mono.just(cita));
        StepVerifier.create(servicio.cambiarEstado(cita.getId(), new CambioEstadoCitaRequest(EstadoCita.CONFIRMADA, null)))
                .expectErrorSatisfies(errorNegocio(HttpStatus.CONFLICT, "TRANSICION_NO_PERMITIDA"))
                .verify();
    }

    @Test
    void conflictoOptimistaEsConflictoConcurrente() {
        Cita cita = citaEn(EstadoCita.PENDIENTE, FECHA);
        when(repositorio.findById(cita.getId())).thenReturn(Mono.just(cita));
        when(repositorio.save(any(Cita.class))).thenReturn(Mono.error(new OptimisticLockingFailureException("x")));
        StepVerifier.create(servicio.cambiarEstado(cita.getId(), new CambioEstadoCitaRequest(EstadoCita.CONFIRMADA, null)))
                .expectErrorSatisfies(errorNegocio(HttpStatus.CONFLICT, "CONFLICTO_CONCURRENTE"))
                .verify();
        verifyNoInteractions(publicador);
    }

    @Test
    void cancelarAMenosDe24HorasEsFueraDePlazo() {
        Cita cita = citaEn(EstadoCita.PENDIENTE, OffsetDateTime.parse("2026-10-15T08:30:00-05:00"));
        when(repositorio.findById(cita.getId())).thenReturn(Mono.just(cita));
        StepVerifier.create(servicio.cambiarEstado(cita.getId(), new CambioEstadoCitaRequest(EstadoCita.CANCELADA, null)))
                .expectErrorSatisfies(errorNegocio(HttpStatus.CONFLICT, "FUERA_DE_PLAZO"))
                .verify();
        verify(repositorio, never()).save(any());
    }

    @Test
    void obtenerInexistente() {
        UUID id = UUID.randomUUID();
        when(repositorio.findById(id)).thenReturn(Mono.empty());
        StepVerifier.create(servicio.obtener(id))
                .expectErrorSatisfies(errorNegocio(HttpStatus.NOT_FOUND, "CITA_NO_ENCONTRADA"))
                .verify();
    }

    @Test
    void reprogramarConfirmadaPublicaCitaReprogramada() {
        Cita cita = citaEn(EstadoCita.CONFIRMADA, OffsetDateTime.parse("2026-10-16T10:00:00-05:00"));
        when(repositorio.findById(cita.getId())).thenReturn(Mono.just(cita));
        sinChoques();
        StepVerifier.create(servicio.reprogramar(cita.getId(),
                        new ReprogramarCitaRequest(OffsetDateTime.parse("2026-10-16T11:30:00-05:00"), null)))
                .assertNext(r -> assertThat(r.estado()).isEqualTo(EstadoCita.PENDIENTE))
                .verifyComplete();
        EventoEnvelope e = eventoPublicado("cita.reprogramada");
        assertThat(e.estadoAnterior()).isEqualTo("CONFIRMADA");
        assertThat(e.datos().get("fechaHoraAnterior").asText()).isEqualTo("2026-10-16T10:00:00-05:00");
        assertThat(e.datos().get("profesionalAnterior").asText()).isEqualTo("MED-001");
        verifyNoInteractions(pacientes);
    }

    @Test
    void marcarAtendidaSobreConfirmada() {
        Cita cita = citaEn(EstadoCita.CONFIRMADA, FECHA);
        UUID atencionId = UUID.randomUUID();
        when(repositorio.findById(cita.getId())).thenReturn(Mono.just(cita));
        StepVerifier.create(servicio.marcarAtendida(cita.getId(), atencionId)).verifyComplete();
        assertThat(cita.getEstado()).isEqualTo(EstadoCita.ATENDIDA);
        EventoEnvelope e = eventoPublicado("cita.atendida");
        assertThat(e.datos().get("atencionId").asText()).isEqualTo(atencionId.toString());
    }

    @Test
    void marcarAtendidaEsIdempotente() {
        Cita cita = citaEn(EstadoCita.ATENDIDA, FECHA);
        when(repositorio.findById(cita.getId())).thenReturn(Mono.just(cita));
        StepVerifier.create(servicio.marcarAtendida(cita.getId(), UUID.randomUUID())).verifyComplete();
        verify(repositorio, never()).save(any());
        verifyNoInteractions(publicador);
    }

    @Test
    void marcarAtendidaSobreCanceladaFalla() {
        Cita cita = citaEn(EstadoCita.CANCELADA, FECHA);
        when(repositorio.findById(cita.getId())).thenReturn(Mono.just(cita));
        StepVerifier.create(servicio.marcarAtendida(cita.getId(), UUID.randomUUID()))
                .expectErrorSatisfies(errorNegocio(HttpStatus.CONFLICT, "TRANSICION_NO_PERMITIDA"))
                .verify();
        verifyNoInteractions(publicador);
    }

    @Test
    void marcarAtendidaInexistenteFalla() {
        UUID id = UUID.randomUUID();
        when(repositorio.findById(id)).thenReturn(Mono.empty());
        StepVerifier.create(servicio.marcarAtendida(id, UUID.randomUUID()))
                .expectErrorSatisfies(errorNegocio(HttpStatus.NOT_FOUND, "CITA_NO_ENCONTRADA"))
                .verify();
    }

    @Test
    void anulacionAntesDeLaHoraCancela() {
        Cita cita = citaEn(EstadoCita.ATENDIDA, FECHA);
        UUID atencionId = UUID.randomUUID();
        when(repositorio.findById(cita.getId())).thenReturn(Mono.just(cita));
        StepVerifier.create(servicio.cancelarPorAtencionAnulada(cita.getId(), atencionId)).verifyComplete();
        assertThat(cita.getEstado()).isEqualTo(EstadoCita.CANCELADA);
        EventoEnvelope e = eventoPublicado("cita.cancelada");
        assertThat(e.estadoAnterior()).isEqualTo("ATENDIDA");
        assertThat(e.datos().get("motivo").asText()).isEqualTo("Atención anulada");
        assertThat(e.datos().get("atencionId").asText()).isEqualTo(atencionId.toString());
    }

    @Test
    void anulacionDespuesDeLaHoraNoCambiaNada() {
        Cita cita = citaEn(EstadoCita.ATENDIDA, OffsetDateTime.parse("2026-10-14T08:30:00-05:00"));
        when(repositorio.findById(cita.getId())).thenReturn(Mono.just(cita));
        StepVerifier.create(servicio.cancelarPorAtencionAnulada(cita.getId(), UUID.randomUUID())).verifyComplete();
        assertThat(cita.getEstado()).isEqualTo(EstadoCita.ATENDIDA);
        verify(repositorio, never()).save(any());
        verifyNoInteractions(publicador);
    }

    @Test
    void anulacionSobreCanceladaEsIdempotente() {
        Cita cita = citaEn(EstadoCita.CANCELADA, FECHA);
        when(repositorio.findById(cita.getId())).thenReturn(Mono.just(cita));
        StepVerifier.create(servicio.cancelarPorAtencionAnulada(cita.getId(), UUID.randomUUID())).verifyComplete();
        verify(repositorio, never()).save(any());
        verifyNoInteractions(publicador);
    }
}
