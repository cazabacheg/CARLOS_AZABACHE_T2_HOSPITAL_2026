package pe.edu.cibertec.vidasalud.pagos.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.data.r2dbc.DataR2dbcTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.test.context.ActiveProfiles;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import pe.edu.cibertec.vidasalud.commons.paginacion.ParametrosPagina;
import pe.edu.cibertec.vidasalud.pagos.domain.DatosPago;
import pe.edu.cibertec.vidasalud.pagos.domain.EstadoPago;
import pe.edu.cibertec.vidasalud.pagos.domain.FiltroPagos;
import pe.edu.cibertec.vidasalud.pagos.domain.MedioPago;
import pe.edu.cibertec.vidasalud.pagos.domain.Pago;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

@DataR2dbcTest(properties = {"hospital.rabbit.enabled=false", "eureka.client.enabled=false"})
@Import({PagoConsultas.class, SecuenciaOperacion.class})
@Testcontainers
@ActiveProfiles("test")
class PagoRepositoryIT {

    private static final ZoneId LIMA = ZoneId.of("America/Lima");

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16");

    @Autowired PagoRepository repositorio;
    @Autowired PagoConsultas consultas;
    @Autowired SecuenciaOperacion secuencia;
    @Autowired DatabaseClient db;

    @BeforeEach
    void limpiar() {
        db.sql("DELETE FROM pago").then().block();
    }

    private static Clock reloj(String instante) {
        return Clock.fixed(Instant.parse(instante), LIMA);
    }

    private Mono<Void> insertar(String numero, String clave, String monto) {
        return db.sql("INSERT INTO pago (id, numero_operacion, paciente_id, concepto, monto, medio_pago, "
                        + "clave_idempotencia, huella_solicitud) VALUES (:id, :num, :pac, 'Prueba', :monto, "
                        + "'EFECTIVO', :clave, :huella)")
                .bind("id", UUID.randomUUID()).bind("num", numero).bind("pac", UUID.randomUUID())
                .bind("monto", new BigDecimal(monto)).bind("clave", clave).bind("huella", "h".repeat(64))
                .then();
    }

    private Pago pago(UUID paciente, MedioPago medio, String instante, int n) {
        return Pago.registrar(new DatosPago(paciente, null, "Concepto " + n, new BigDecimal("50"), medio),
                "OP-20261012-00000" + n, "clave-prueba-" + n, "h".repeat(64), reloj(instante));
    }

    @Test
    void checkRechazaMontoCeroYNegativo() {
        StepVerifier.create(insertar("OP-1", "clave-0000001", "0"))
                .expectError(DataIntegrityViolationException.class).verify();
        StepVerifier.create(insertar("OP-2", "clave-0000002", "-10"))
                .expectErrorSatisfies(e -> {
                    assertThat(e).isInstanceOf(DataIntegrityViolationException.class);
                    assertThat(e).isNotInstanceOf(DuplicateKeyException.class);
                }).verify();
    }

    @Test
    void numeroOperacionDuplicadoEsDuplicateKey() {
        StepVerifier.create(insertar("OP-DUP", "clave-0000001", "10")
                        .then(insertar("OP-DUP", "clave-0000002", "10")))
                .expectError(DuplicateKeyException.class).verify();
    }

    @Test
    void claveIdempotenciaDuplicadaEsDuplicateKey() {
        StepVerifier.create(insertar("OP-A", "clave-repetida", "10")
                        .then(insertar("OP-B", "clave-repetida", "10")))
                .expectError(DuplicateKeyException.class).verify();
    }

    @Test
    void secuenciaGeneraNumerosCrecientes() {
        StepVerifier.create(secuencia.siguiente().zipWith(secuencia.siguiente()))
                .assertNext(par -> assertThat(par.getT2()).isGreaterThan(par.getT1())).verifyComplete();
    }

    @Test
    void idaYVuelta() {
        Pago nuevo = pago(UUID.randomUUID(), MedioPago.TARJETA, "2026-10-12T14:00:00Z", 1);
        StepVerifier.create(repositorio.save(nuevo).map(Pago::getId).flatMap(repositorio::findById))
                .assertNext(p -> {
                    assertThat(p.getMonto().scale()).isEqualTo(2);
                    assertThat(p.getMonto().compareTo(new BigDecimal("50.00"))).isZero();
                    assertThat(p.getMedioPago()).isEqualTo(MedioPago.TARJETA);
                    assertThat(p.getEstado()).isEqualTo(EstadoPago.REGISTRADO);
                    assertThat(p.getVersion()).isZero();
                    assertThat(p.getMoneda().trim()).isEqualTo("PEN");
                }).verifyComplete();
        StepVerifier.create(repositorio.findByClaveIdempotencia("clave-prueba-1"))
                .assertNext(p -> assertThat(p.getId()).isEqualTo(nuevo.getId())).verifyComplete();
    }

    @Test
    void versionDetectaModificacionConcurrente() {
        Pago nuevo = pago(UUID.randomUUID(), MedioPago.EFECTIVO, "2026-10-12T14:00:00Z", 1);
        repositorio.save(nuevo).block();
        Pago p1 = repositorio.findById(nuevo.getId()).block();
        Pago p2 = repositorio.findById(nuevo.getId()).block();
        p1.anular("primero", reloj("2026-10-12T15:00:00Z"));
        StepVerifier.create(repositorio.save(p1)).expectNextCount(1).verifyComplete();
        p2.anular("segundo", reloj("2026-10-12T15:00:00Z"));
        StepVerifier.create(repositorio.save(p2)).expectError(OptimisticLockingFailureException.class).verify();
    }

    @Test
    void filtrosYPaginacion() {
        UUID a = UUID.randomUUID();
        UUID b = UUID.randomUUID();
        // 12-10 en Lima: 14:00Z = 09:00; 04:30Z del 13-10 = 23:30 del 12-10 en Lima; 13-10 en Lima: 15:00Z, 16:00Z.
        Pago p1 = pago(a, MedioPago.TARJETA, "2026-10-12T14:00:00Z", 1);
        Pago p2 = pago(a, MedioPago.EFECTIVO, "2026-10-13T04:30:00Z", 2);
        Pago p3 = pago(b, MedioPago.TARJETA, "2026-10-13T15:00:00Z", 3);
        Pago p4 = pago(b, MedioPago.BILLETERA, "2026-10-13T16:00:00Z", 4);
        Pago p5 = pago(a, MedioPago.TARJETA, "2026-10-13T17:00:00Z", 5);
        p4.anular("error", reloj("2026-10-13T18:00:00Z"));
        for (Pago p : List.of(p1, p2, p3, p4, p5)) {
            repositorio.save(p).block();
        }
        ParametrosPagina todas = ParametrosPagina.de(0, 20);
        FiltroPagos sin = new FiltroPagos(null, null, null, null, null, null);

        StepVerifier.create(consultas.contar(sin)).expectNext(5L).verifyComplete();
        StepVerifier.create(consultas.buscar(sin, todas).map(Pago::getId).collectList())
                .assertNext(ids -> assertThat(ids).containsExactly(
                        p5.getId(), p4.getId(), p3.getId(), p2.getId(), p1.getId())).verifyComplete();
        StepVerifier.create(consultas.contar(new FiltroPagos(a, null, null, null, null, null)))
                .expectNext(3L).verifyComplete();
        StepVerifier.create(consultas.contar(new FiltroPagos(null, null, null, EstadoPago.ANULADO, null, null)))
                .expectNext(1L).verifyComplete();
        StepVerifier.create(consultas.contar(new FiltroPagos(null, null, null, null, MedioPago.TARJETA, null)))
                .expectNext(3L).verifyComplete();
        StepVerifier.create(consultas.buscar(
                        new FiltroPagos(null, null, p3.getNumeroOperacion(), null, null, null), todas).collectList())
                .assertNext(l -> assertThat(l).extracting(Pago::getId).containsExactly(p3.getId())).verifyComplete();
        StepVerifier.create(consultas.buscar(
                        new FiltroPagos(null, null, null, null, null, LocalDate.of(2026, 10, 12)), todas)
                        .map(Pago::getId).collectList())
                .assertNext(ids -> assertThat(ids).containsExactly(p2.getId(), p1.getId())).verifyComplete();
        StepVerifier.create(consultas.contar(new FiltroPagos(null, null, null, null, null, LocalDate.of(2026, 10, 13))))
                .expectNext(3L).verifyComplete();
        StepVerifier.create(consultas.buscar(sin, ParametrosPagina.de(1, 2)).map(Pago::getId).collectList())
                .assertNext(ids -> assertThat(ids).containsExactly(p3.getId(), p2.getId())).verifyComplete();
    }
}
