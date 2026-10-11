package pe.edu.cibertec.vidasalud.notificaciones.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.data.r2dbc.DataR2dbcTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.r2dbc.core.R2dbcEntityTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import pe.edu.cibertec.vidasalud.commons.paginacion.ParametrosPagina;
import pe.edu.cibertec.vidasalud.notificaciones.domain.FiltroNotificaciones;
import pe.edu.cibertec.vidasalud.notificaciones.domain.Notificacion;
import reactor.test.StepVerifier;

@DataR2dbcTest
@Import(ConsultaNotificaciones.class)
@Testcontainers
@ActiveProfiles("test")
class NotificacionRepositoryIT {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16");

    private static final UUID P1 = UUID.randomUUID();
    private static final UUID P2 = UUID.randomUUID();
    private static final FiltroNotificaciones SIN_FILTRO = new FiltroNotificaciones(null, null, null);

    @Autowired
    private NotificacionRepository repositorio;
    @Autowired
    private ConsultaNotificaciones consulta;
    @Autowired
    private R2dbcEntityTemplate template;

    @BeforeEach
    void limpiar() {
        template.delete(Notificacion.class).all().block();
    }

    private Notificacion guardar(UUID paciente, String tipo, String fechaIso) {
        Notificacion n = Notificacion.nueva(UUID.randomUUID(), paciente, tipo, UUID.randomUUID(), "mensaje de prueba",
                OffsetDateTime.parse(fechaIso));
        return repositorio.save(n).block();
    }

    @Test
    void guardaYLeeLosMismosDatos() {
        Notificacion n = guardar(P1, "cita.confirmada", "2026-10-12T14:30:00.123456Z");

        StepVerifier.create(repositorio.findById(n.getId()))
                .assertNext(leida -> {
                    assertThat(leida.getEventId()).isEqualTo(n.getEventId());
                    assertThat(leida.getPacienteId()).isEqualTo(P1);
                    assertThat(leida.getTipoEvento()).isEqualTo("cita.confirmada");
                    assertThat(leida.getRecursoId()).isEqualTo(n.getRecursoId());
                    assertThat(leida.getCanal()).isEqualTo("SIMULADO");
                    assertThat(leida.getMensaje()).isEqualTo("mensaje de prueba");
                    assertThat(leida.getFechaEnvio()).isEqualTo(n.getFechaEnvio());
                    assertThat(leida.isNew()).isFalse();
                })
                .verifyComplete();
    }

    @Test
    void eventIdRepetidoLanzaDuplicateKey() {
        Notificacion a = guardar(P1, "cita.confirmada", "2026-10-12T14:30:00Z");
        Notificacion b = Notificacion.nueva(a.getEventId(), P1, "cita.confirmada", a.getRecursoId(), "otra",
                OffsetDateTime.parse("2026-10-12T14:31:00Z"));

        StepVerifier.create(repositorio.save(b)).expectError(DuplicateKeyException.class).verify();
    }

    @Test
    void filtraPorPacienteTipoYFecha() {
        guardar(P1, "cita.confirmada", "2026-10-13T04:30:00Z");   // 12/10 23:30 Lima
        guardar(P1, "cita.creada", "2026-10-13T05:30:00Z");       // 13/10 00:30 Lima
        guardar(P2, "cita.confirmada", "2026-10-13T04:45:00Z");   // 12/10 23:45 Lima

        assertThat(consulta.contar(new FiltroNotificaciones(P1, null, null)).block()).isEqualTo(2);
        assertThat(consulta.contar(new FiltroNotificaciones(null, "cita.confirmada", null)).block()).isEqualTo(2);
        assertThat(consulta.contar(new FiltroNotificaciones(null, null, LocalDate.of(2026, 10, 12))).block())
                .isEqualTo(2);
        assertThat(consulta.contar(new FiltroNotificaciones(null, null, LocalDate.of(2026, 10, 13))).block())
                .isEqualTo(1);

        List<Notificacion> combinado = consulta.buscar(
                new FiltroNotificaciones(P1, "cita.confirmada", LocalDate.of(2026, 10, 12)),
                new ParametrosPagina(0, 20)).collectList().block();
        assertThat(combinado).hasSize(1);
        assertThat(combinado.get(0).getPacienteId()).isEqualTo(P1);
    }

    @Test
    void ordenaPorFechaDescYPagina() {
        for (int i = 1; i <= 5; i++) {
            guardar(P1, "cita.creada", "2026-10-12T10:0" + i + ":00Z");
        }

        List<Notificacion> pagina = consulta.buscar(SIN_FILTRO, new ParametrosPagina(1, 2)).collectList().block();

        assertThat(pagina).hasSize(2);
        assertThat(pagina.get(0).getFechaEnvio().toInstant())
                .isEqualTo(OffsetDateTime.parse("2026-10-12T10:03:00Z").toInstant());
        assertThat(pagina.get(1).getFechaEnvio().toInstant())
                .isEqualTo(OffsetDateTime.parse("2026-10-12T10:02:00Z").toInstant());
        assertThat(consulta.contar(SIN_FILTRO).block()).isEqualTo(5);
    }
}
