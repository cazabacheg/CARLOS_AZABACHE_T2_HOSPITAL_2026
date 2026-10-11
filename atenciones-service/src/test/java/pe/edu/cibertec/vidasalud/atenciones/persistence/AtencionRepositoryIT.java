package pe.edu.cibertec.vidasalud.atenciones.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.UUID;
import pe.edu.cibertec.vidasalud.atenciones.domain.AreaAtencion;
import pe.edu.cibertec.vidasalud.atenciones.domain.Atencion;
import pe.edu.cibertec.vidasalud.atenciones.domain.CitaResumen;
import pe.edu.cibertec.vidasalud.atenciones.domain.EstadoAtencion;
import pe.edu.cibertec.vidasalud.atenciones.domain.FiltroAtenciones;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.data.r2dbc.DataR2dbcTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.test.context.ActiveProfiles;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import pe.edu.cibertec.vidasalud.commons.paginacion.ParametrosPagina;
import reactor.test.StepVerifier;

@DataR2dbcTest
@Testcontainers
@ActiveProfiles("test")
@Import({GeneradorCodigoAtencion.class, AtencionConsultas.class, AtencionRepositoryIT.RelojFijo.class})
class AtencionRepositoryIT {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16");

    @TestConfiguration
    static class RelojFijo {
        @Bean
        Clock reloj() {
            return Clock.fixed(Instant.parse("2026-10-14T14:00:00Z"), ZoneId.of("America/Lima"));
        }
    }

    private static final ZoneId LIMA = ZoneId.of("America/Lima");

    @Autowired AtencionRepository repositorio;
    @Autowired AtencionConsultas consultas;
    @Autowired GeneradorCodigoAtencion generador;

    private static Clock reloj(int hora) {
        return Clock.fixed(Instant.parse("2026-10-14T00:00:00Z").plusSeconds((hora + 5) * 3600L), LIMA);
    }

    private static CitaResumen cita(UUID citaId, UUID pacienteId) {
        return new CitaResumen(citaId, pacienteId, "CONFIRMADA", OffsetDateTime.parse("2026-10-14T12:00:00-05:00"));
    }

    private static String codigo() {
        return "ATE-T-" + UUID.randomUUID().toString().substring(0, 8);
    }

    private static Atencion nueva(UUID citaId, UUID pacienteId, int hora) {
        return Atencion.registrar(cita(citaId, pacienteId), AreaAtencion.CONSULTA_EXTERNA, codigo(), reloj(hora));
    }

    @Test
    void mismaCitaActivaDosVecesViolaElIndice() {
        UUID citaId = UUID.randomUUID();
        StepVerifier.create(repositorio.save(nueva(citaId, UUID.randomUUID(), 9))).expectNextCount(1).verifyComplete();
        StepVerifier.create(repositorio.save(nueva(citaId, UUID.randomUUID(), 9)))
                .expectError(DuplicateKeyException.class)
                .verify();
    }

    @Test
    void anularLiberaLaCita() {
        UUID citaId = UUID.randomUUID();
        Atencion primera = repositorio.save(nueva(citaId, UUID.randomUUID(), 9)).block();
        Atencion leida = repositorio.findById(primera.getId()).block();
        leida.cambiarEstado(EstadoAtencion.ANULADA, reloj(10));
        StepVerifier.create(repositorio.save(leida)).expectNextCount(1).verifyComplete();

        StepVerifier.create(repositorio.save(nueva(citaId, UUID.randomUUID(), 10)))
                .expectNextCount(1)
                .verifyComplete();
    }

    @Test
    void existsActivaIgnoraLasAnuladas() {
        UUID citaId = UUID.randomUUID();
        Atencion guardada = repositorio.save(nueva(citaId, UUID.randomUUID(), 9)).block();
        StepVerifier.create(repositorio.existsActivaByCitaId(citaId)).expectNext(true).verifyComplete();

        Atencion leida = repositorio.findById(guardada.getId()).block();
        leida.cambiarEstado(EstadoAtencion.ANULADA, reloj(10));
        repositorio.save(leida).block();
        StepVerifier.create(repositorio.existsActivaByCitaId(citaId)).expectNext(false).verifyComplete();
    }

    @Test
    void secuenciaDeCodigos() {
        String primero = generador.siguiente().block();
        String segundo = generador.siguiente().block();
        assertThat(primero).matches("^ATE-20261014-\\d{6}$");
        assertThat(segundo).matches("^ATE-20261014-\\d{6}$");
        assertThat(Long.parseLong(segundo.substring(13))).isEqualTo(Long.parseLong(primero.substring(13)) + 1);
    }

    @Test
    void versionOptimista() {
        Atencion guardada = repositorio.save(nueva(UUID.randomUUID(), UUID.randomUUID(), 9)).block();
        Atencion a = repositorio.findById(guardada.getId()).block();
        Atencion b = repositorio.findById(guardada.getId()).block();
        assertThat(a.getVersion()).isZero();

        a.cambiarEstado(EstadoAtencion.FINALIZADA, reloj(10));
        StepVerifier.create(repositorio.save(a)).assertNext(g -> assertThat(g.getVersion()).isEqualTo(1L)).verifyComplete();

        b.cambiarEstado(EstadoAtencion.ANULADA, reloj(10));
        StepVerifier.create(repositorio.save(b)).expectError(OptimisticLockingFailureException.class).verify();
    }

    @Test
    void listadoConFiltros() {
        UUID paciente = UUID.randomUUID();
        Atencion de9 = repositorio.save(nueva(UUID.randomUUID(), paciente, 9)).block();
        repositorio.save(nueva(UUID.randomUUID(), paciente, 10)).block();
        Atencion de11 = repositorio.save(nueva(UUID.randomUUID(), paciente, 11)).block();
        Atencion leida = repositorio.findById(de9.getId()).block();
        leida.cambiarEstado(EstadoAtencion.ANULADA, reloj(12));
        repositorio.save(leida).block();

        var activas = new FiltroAtenciones(paciente, null, EstadoAtencion.REGISTRADA, null, null);
        StepVerifier.create(consultas.contar(activas)).expectNext(2L).verifyComplete();

        var porPaciente = new FiltroAtenciones(paciente, null, null, null, null);
        StepVerifier.create(consultas.buscar(porPaciente, ParametrosPagina.de(0, 1)))
                .assertNext(a -> assertThat(a.getId()).isEqualTo(de11.getId()))
                .verifyComplete();

        var hoy = new FiltroAtenciones(paciente, null, null, null, LocalDate.of(2026, 10, 14));
        var manana = new FiltroAtenciones(paciente, null, null, null, LocalDate.of(2026, 10, 15));
        StepVerifier.create(consultas.contar(hoy)).expectNext(3L).verifyComplete();
        StepVerifier.create(consultas.contar(manana)).expectNext(0L).verifyComplete();
    }
}
