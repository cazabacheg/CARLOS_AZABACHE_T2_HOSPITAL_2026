package pe.edu.cibertec.vidasalud.auditoria.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import io.r2dbc.spi.ConnectionFactory;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.data.r2dbc.DataR2dbcTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Import;
import org.springframework.core.io.ClassPathResource;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.domain.PageRequest;
import org.springframework.r2dbc.connection.init.ResourceDatabasePopulator;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.test.context.ActiveProfiles;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import pe.edu.cibertec.vidasalud.auditoria.domain.FiltroAuditoria;
import reactor.test.StepVerifier;

@DataR2dbcTest
@Import(EventoAuditoriaRepositoryR2dbc.class)
@Testcontainers
@ActiveProfiles("test")
class AuditoriaRepositoryIT {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16");

    private static final OffsetDateTime T1 = OffsetDateTime.parse("2026-10-12T09:00:00-05:00");
    private static final OffsetDateTime T2 = OffsetDateTime.parse("2026-10-12T10:00:00-05:00");
    private static final OffsetDateTime T3 = OffsetDateTime.parse("2026-10-12T11:00:00-05:00");

    @Autowired
    EventoAuditoriaRepository repositorio;
    @Autowired
    DatabaseClient db;
    @Autowired
    ConnectionFactory connectionFactory;

    private static EventoAuditoria evento(UUID recursoId, UUID eventId, OffsetDateTime fecha, String tipo,
                                         String anterior, String nuevo) {
        return EventoAuditoria.nueva(eventId, fecha, tipo, "CITA", recursoId, anterior, nuevo,
                "citas-service", OffsetDateTime.parse("2026-10-12T12:00:00-05:00"));
    }

    private static EventoAuditoria evento(UUID recursoId, OffsetDateTime fecha, String tipo) {
        return evento(recursoId, UUID.randomUUID(), fecha, tipo, null, "PENDIENTE");
    }

    private List<String> columna(String sql) {
        return db.sql(sql).map(row -> row.get(0, String.class)).all().collectList().block();
    }

    @Test
    void esquemaCreadoYRepetible() {
        String triggers = "SELECT tgname FROM pg_trigger WHERE tgrelid = 'evento_auditoria'::regclass "
                + "AND NOT tgisinternal ORDER BY tgname";
        assertThat(columna(triggers)).containsExactly("tg_auditoria_sin_truncate", "tg_auditoria_sin_update_delete");
        assertThat(columna("SELECT indexname FROM pg_indexes WHERE tablename = 'evento_auditoria'"))
                .contains("ix_auditoria_recurso", "ix_auditoria_fecha");
        assertThat(columna("SELECT proname FROM pg_proc WHERE proname = 'fn_auditoria_inmutable'"))
                .containsExactly("fn_auditoria_inmutable");

        ResourceDatabasePopulator p = new ResourceDatabasePopulator(new ClassPathResource("schema.sql"));
        p.setSeparator("@@");
        StepVerifier.create(p.populate(connectionFactory)).verifyComplete();

        assertThat(columna(triggers)).hasSize(2);
    }

    @Test
    void insertaYLee() {
        EventoAuditoria e = evento(UUID.randomUUID(), UUID.randomUUID(), T1, "cita.confirmada",
                "PENDIENTE", "CONFIRMADA");
        StepVerifier.create(repositorio.insertar(e).then(repositorio.buscarPorId(e.getId())))
                .assertNext(l -> {
                    assertThat(l.getEventId()).isEqualTo(e.getEventId());
                    assertThat(l.getRecursoId()).isEqualTo(e.getRecursoId());
                    assertThat(l.getTipoEvento()).isEqualTo("cita.confirmada");
                    assertThat(l.getTipoRecurso()).isEqualTo("CITA");
                    assertThat(l.getEstadoAnterior()).isEqualTo("PENDIENTE");
                    assertThat(l.getEstadoNuevo()).isEqualTo("CONFIRMADA");
                    assertThat(l.getOrigen()).isEqualTo("citas-service");
                    assertThat(l.getFechaEvento().isEqual(T1)).isTrue();
                    assertThat(l.getFechaRegistro().isEqual(e.getFechaRegistro())).isTrue();
                }).verifyComplete();
    }

    @Test
    void eventIdEsUnico() {
        UUID eventId = UUID.randomUUID();
        UUID recurso = UUID.randomUUID();
        StepVerifier.create(repositorio.insertar(evento(recurso, eventId, T1, "cita.creada", null, "PENDIENTE"))
                        .then(repositorio.insertar(evento(recurso, eventId, T1, "cita.creada", null, "PENDIENTE"))))
                .expectError(DuplicateKeyException.class).verify();
    }

    @Test
    void filtrosYPaginacion() {
        UUID recurso = UUID.randomUUID();
        repositorio.insertar(evento(recurso, T1, "cita.creada")).block();
        repositorio.insertar(evento(recurso, T2, "cita.confirmada")).block();
        repositorio.insertar(evento(recurso, T3, "cita.atendida")).block();
        FiltroAuditoria base = FiltroAuditoria.de(null, recurso, null, null, null, null);
        var orden = EventoAuditoriaRepositoryR2dbc.ORDEN_LISTADO;

        StepVerifier.create(repositorio.buscar(base, PageRequest.of(0, 2, orden)))
                .assertNext(e -> assertThat(e.getFechaEvento().isEqual(T3)).isTrue())
                .assertNext(e -> assertThat(e.getFechaEvento().isEqual(T2)).isTrue())
                .verifyComplete();
        StepVerifier.create(repositorio.buscar(base, PageRequest.of(1, 2, orden)))
                .assertNext(e -> assertThat(e.getFechaEvento().isEqual(T1)).isTrue())
                .verifyComplete();
        StepVerifier.create(repositorio.contar(base)).expectNext(3L).verifyComplete();
        StepVerifier.create(repositorio.contar(FiltroAuditoria.de(null, recurso, "cita.creada", null, null, null)))
                .expectNext(1L).verifyComplete();
        StepVerifier.create(repositorio.contar(FiltroAuditoria.de("CITA", recurso, null, "citas-service", T2, null)))
                .expectNext(2L).verifyComplete();
        StepVerifier.create(repositorio.contar(FiltroAuditoria.de(null, recurso, null, null, null, T1)))
                .expectNext(1L).verifyComplete();
        StepVerifier.create(repositorio.contar(FiltroAuditoria.de("PAGO", recurso, null, null, null, null)))
                .expectNext(0L).verifyComplete();
    }

    @Test
    void historialAscendente() {
        UUID recurso = UUID.randomUUID();
        repositorio.insertar(evento(recurso, T3, "cita.atendida")).block();
        repositorio.insertar(evento(recurso, T1, "cita.creada")).block();
        repositorio.insertar(evento(recurso, T2, "cita.confirmada")).block();

        StepVerifier.create(repositorio.historial("CITA", recurso))
                .assertNext(e -> assertThat(e.getTipoEvento()).isEqualTo("cita.creada"))
                .assertNext(e -> assertThat(e.getTipoEvento()).isEqualTo("cita.confirmada"))
                .assertNext(e -> assertThat(e.getTipoEvento()).isEqualTo("cita.atendida"))
                .verifyComplete();
    }

    @Test
    void updateDirectoFalla() {
        EventoAuditoria e = evento(UUID.randomUUID(), T1, "cita.creada");
        repositorio.insertar(e).block();

        StepVerifier.create(db.sql("UPDATE evento_auditoria SET estado_nuevo = 'X' WHERE id = :id")
                        .bind("id", e.getId()).fetch().rowsUpdated())
                .expectErrorSatisfies(t -> assertThat(t)
                        .hasStackTraceContaining("evento_auditoria es inmutable: UPDATE no permitido"))
                .verify();
        StepVerifier.create(repositorio.buscarPorId(e.getId()))
                .assertNext(l -> assertThat(l.getEstadoNuevo()).isEqualTo("PENDIENTE")).verifyComplete();
    }

    @Test
    void deleteDirectoFalla() {
        EventoAuditoria e = evento(UUID.randomUUID(), T1, "cita.creada");
        repositorio.insertar(e).block();

        StepVerifier.create(db.sql("DELETE FROM evento_auditoria WHERE id = :id")
                        .bind("id", e.getId()).fetch().rowsUpdated())
                .expectErrorSatisfies(t -> assertThat(t)
                        .hasStackTraceContaining("evento_auditoria es inmutable: DELETE no permitido"))
                .verify();
        StepVerifier.create(repositorio.buscarPorId(e.getId())).expectNextCount(1).verifyComplete();
    }

    @Test
    void truncateFalla() {
        EventoAuditoria e = evento(UUID.randomUUID(), T1, "cita.creada");
        repositorio.insertar(e).block();

        StepVerifier.create(db.sql("TRUNCATE evento_auditoria").fetch().rowsUpdated())
                .expectErrorSatisfies(t -> assertThat(t)
                        .hasStackTraceContaining("evento_auditoria es inmutable: TRUNCATE no permitido"))
                .verify();
        StepVerifier.create(repositorio.buscarPorId(e.getId())).expectNextCount(1).verifyComplete();
    }
}
