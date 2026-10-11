package pe.edu.cibertec.vidasalud.citas.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.data.r2dbc.DataR2dbcTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.test.context.ActiveProfiles;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import pe.edu.cibertec.vidasalud.citas.domain.Cita;
import pe.edu.cibertec.vidasalud.citas.domain.DatosNuevaCita;
import pe.edu.cibertec.vidasalud.citas.domain.Especialidad;
import pe.edu.cibertec.vidasalud.citas.domain.EstadoCita;
import pe.edu.cibertec.vidasalud.citas.domain.FiltroCitas;
import pe.edu.cibertec.vidasalud.commons.paginacion.ParametrosPagina;
import reactor.core.publisher.Flux;
import reactor.test.StepVerifier;

@DataR2dbcTest(properties = {"hospital.rabbit.enabled=false", "eureka.client.enabled=false"})
@Import({SecuenciaCodigoCita.class, CitaConsultas.class})
@ActiveProfiles("test")
@Testcontainers
class CitaRepositoryIT {

    private static final Clock RELOJ = Clock.fixed(Instant.parse("2026-10-14T14:00:00Z"), ZoneId.of("America/Lima"));
    private static final OffsetDateTime FECHA = OffsetDateTime.parse("2026-10-15T10:00:00-05:00");

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16");

    @Autowired
    CitaRepository repositorio;
    @Autowired
    SecuenciaCodigoCita secuencia;
    @Autowired
    CitaConsultas consultas;
    @Autowired
    DatabaseClient db;

    @BeforeEach
    void limpiar() {
        db.sql("DELETE FROM cita").then().block();
    }

    private static Cita cita(UUID paciente, String profesional, OffsetDateTime fecha) {
        return Cita.programar(new DatosNuevaCita(paciente, Especialidad.MEDICINA_GENERAL, fecha, profesional,
                "Control"), "CIT-" + UUID.randomUUID().toString().substring(0, 20), RELOJ);
    }

    private static void esDuplicadoDe(Throwable e, String indice) {
        assertThat(e).isInstanceOf(DuplicateKeyException.class);
        assertThat(e.getMessage() + " " + ((DuplicateKeyException) e).getMostSpecificCause().getMessage())
                .contains(indice);
    }

    @Test
    void guardaYLeeLaCita() {
        Cita original = cita(UUID.randomUUID(), "MED-001", FECHA);
        StepVerifier.create(repositorio.save(original).then(repositorio.findById(original.getId())))
                .assertNext(leida -> {
                    assertThat(leida.getVersion()).isEqualTo(0L);
                    assertThat(leida.getFechaHora().isEqual(FECHA)).isTrue();
                    assertThat(leida.getFechaHora().getOffset().getTotalSeconds()).isEqualTo(-5 * 3600);
                    assertThat(leida.getEstado()).isEqualTo(EstadoCita.PENDIENTE);
                    assertThat(leida.getEspecialidad()).isEqualTo(Especialidad.MEDICINA_GENERAL);
                    assertThat(leida.isNew()).isFalse();
                })
                .verifyComplete();
    }

    @Test
    void profesionalOcupadoEnElMismoHorarioChoca() {
        StepVerifier.create(repositorio.save(cita(UUID.randomUUID(), "MED-001", FECHA))
                        .then(repositorio.save(cita(UUID.randomUUID(), "MED-001", FECHA))))
                .expectErrorSatisfies(e -> esDuplicadoDe(e, "ux_cita_profesional_horario"))
                .verify();
    }

    @Test
    void canceladaLiberaElHorario() {
        Cita primera = cita(UUID.randomUUID(), "MED-001", FECHA);
        StepVerifier.create(repositorio.save(primera)
                        .then(repositorio.findById(primera.getId()))
                        .doOnNext(c -> c.cambiarEstado(EstadoCita.CANCELADA, RELOJ))
                        .flatMap(repositorio::save)
                        .then(repositorio.save(cita(UUID.randomUUID(), "MED-001", FECHA)))
                        .then(repositorio.count()))
                .expectNext(2L)
                .verifyComplete();
    }

    @Test
    void pacienteOcupadoEnElMismoHorarioChoca() {
        UUID paciente = UUID.randomUUID();
        StepVerifier.create(repositorio.save(cita(paciente, "MED-001", FECHA))
                        .then(repositorio.save(cita(paciente, "MED-002", FECHA))))
                .expectErrorSatisfies(e -> esDuplicadoDe(e, "ux_cita_paciente_horario"))
                .verify();
    }

    @Test
    void mismoInstanteConOtroOffsetTambienChoca() {
        StepVerifier.create(repositorio.save(cita(UUID.randomUUID(), "MED-001", FECHA))
                        .then(repositorio.save(cita(UUID.randomUUID(), "MED-001",
                                OffsetDateTime.parse("2026-10-15T15:00:00Z")))))
                .expectErrorSatisfies(e -> esDuplicadoDe(e, "ux_cita_profesional_horario"))
                .verify();
    }

    @Test
    void secuenciaAvanzaDeAUno() {
        StepVerifier.create(secuencia.siguiente().zipWith(secuencia.siguiente()))
                .assertNext(par -> assertThat(par.getT2()).isEqualTo(par.getT1() + 1))
                .verifyComplete();
    }

    @Test
    void versionDetectaActualizacionConcurrente() {
        Cita original = cita(UUID.randomUUID(), "MED-001", FECHA);
        repositorio.save(original).block();
        Cita a = repositorio.findById(original.getId()).block();
        Cita b = repositorio.findById(original.getId()).block();
        a.cambiarEstado(EstadoCita.CONFIRMADA, RELOJ);
        b.cambiarEstado(EstadoCita.CONFIRMADA, RELOJ);

        StepVerifier.create(repositorio.save(a))
                .assertNext(guardada -> assertThat(guardada.getVersion()).isEqualTo(1L))
                .verifyComplete();
        StepVerifier.create(repositorio.save(b))
                .expectError(OptimisticLockingFailureException.class)
                .verify();
    }

    @Test
    void existenciaDeCitasActivas() {
        UUID paciente = UUID.randomUUID();
        Cita original = cita(paciente, "MED-001", FECHA);
        repositorio.save(original).block();

        StepVerifier.create(repositorio.existeActivaDeProfesional("MED-001", FECHA)).expectNext(true).verifyComplete();
        StepVerifier.create(repositorio.existeActivaDePaciente(paciente, FECHA)).expectNext(true).verifyComplete();
        StepVerifier.create(repositorio.existeActivaDeProfesional("MED-002", FECHA)).expectNext(false).verifyComplete();

        Cita leida = repositorio.findById(original.getId()).block();
        leida.cambiarEstado(EstadoCita.CANCELADA, RELOJ);
        repositorio.save(leida).block();
        StepVerifier.create(repositorio.existeActivaDeProfesional("MED-001", FECHA)).expectNext(false).verifyComplete();
        StepVerifier.create(repositorio.existeActivaDePaciente(paciente, FECHA)).expectNext(false).verifyComplete();
    }

    @Test
    void consultasFiltranPorDiaDeLimaOrdenanYPaginan() {
        UUID paciente = UUID.randomUUID();
        Cita manana = cita(paciente, "MED-001", OffsetDateTime.parse("2026-10-15T08:00:00-05:00"));
        Cita noche = cita(UUID.randomUUID(), "MED-002", OffsetDateTime.parse("2026-10-15T23:30:00-05:00"));
        Cita otroDia = cita(UUID.randomUUID(), "MED-001", OffsetDateTime.parse("2026-10-16T08:00:00-05:00"));
        Flux.just(noche, otroDia, manana).concatMap(repositorio::save).blockLast();

        FiltroCitas dia = new FiltroCitas(null, null, null, null, LocalDate.of(2026, 10, 15));
        StepVerifier.create(consultas.listar(dia, new ParametrosPagina(0, 10)).map(Cita::getId))
                .expectNext(manana.getId(), noche.getId())
                .verifyComplete();
        StepVerifier.create(consultas.contar(dia)).expectNext(2L).verifyComplete();

        FiltroCitas porProfesional = new FiltroCitas(null, EstadoCita.PENDIENTE, null, "MED-001", null);
        StepVerifier.create(consultas.contar(porProfesional)).expectNext(2L).verifyComplete();
        FiltroCitas porPaciente = new FiltroCitas(paciente, EstadoCita.CONFIRMADA, null, null, null);
        StepVerifier.create(consultas.contar(porPaciente)).expectNext(0L).verifyComplete();

        StepVerifier.create(consultas.listar(dia, new ParametrosPagina(1, 1)).map(Cita::getId))
                .expectNext(noche.getId())
                .verifyComplete();
    }
}
