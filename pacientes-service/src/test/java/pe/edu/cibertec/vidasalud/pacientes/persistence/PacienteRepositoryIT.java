package pe.edu.cibertec.vidasalud.pacientes.persistence;

import static org.assertj.core.api.Assertions.assertThat;

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
import org.springframework.dao.DuplicateKeyException;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.test.context.ActiveProfiles;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import pe.edu.cibertec.vidasalud.commons.paginacion.ParametrosPagina;
import pe.edu.cibertec.vidasalud.pacientes.domain.DatosPersonales;
import pe.edu.cibertec.vidasalud.pacientes.domain.EstadoPaciente;
import pe.edu.cibertec.vidasalud.pacientes.domain.FiltroPacientes;
import pe.edu.cibertec.vidasalud.pacientes.domain.Paciente;
import pe.edu.cibertec.vidasalud.pacientes.domain.TipoDocumento;
import reactor.core.publisher.Flux;
import reactor.test.StepVerifier;

@DataR2dbcTest(properties = {"hospital.rabbit.enabled=false", "eureka.client.enabled=false"})
@Import(BusquedaPacientes.class)
@ActiveProfiles("test")
@Testcontainers
class PacienteRepositoryIT {

    private static final Clock RELOJ = Clock.fixed(Instant.parse("2026-10-12T14:30:00Z"), ZoneId.of("America/Lima"));

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16");

    @Autowired
    PacienteRepository repositorio;
    @Autowired
    BusquedaPacientes busqueda;
    @Autowired
    DatabaseClient db;

    @BeforeEach
    void limpiar() {
        db.sql("DELETE FROM paciente").then().block();
    }

    private static Paciente paciente(TipoDocumento tipo, String numero, String apellidos) {
        return Paciente.registrar(tipo, numero, new DatosPersonales("Nombre", apellidos,
                LocalDate.of(1990, 5, 20), "999000111", "prueba@example.com", null), RELOJ);
    }

    private Long contarFilas(String sql) {
        return db.sql(sql).map(fila -> fila.get(0, Long.class)).one().block();
    }

    @Test
    void schemaCreaTablaEIndice() {
        assertThat(contarFilas("SELECT count(*) FROM information_schema.tables WHERE table_name = 'paciente'"))
                .isEqualTo(1L);
        assertThat(contarFilas("SELECT count(*) FROM pg_indexes WHERE indexname = 'ix_paciente_apellidos'"))
                .isEqualTo(1L);
    }

    @Test
    void guardaYLeeElPaciente() {
        Paciente original = paciente(TipoDocumento.DNI, "00000001", "Alva");

        StepVerifier.create(repositorio.save(original).then(repositorio.findById(original.getId())))
                .assertNext(leido -> {
                    assertThat(leido.getTipoDocumento()).isEqualTo(TipoDocumento.DNI);
                    assertThat(leido.getFechaNacimiento()).isEqualTo(LocalDate.of(1990, 5, 20));
                    assertThat(leido.getEstado()).isEqualTo(EstadoPaciente.ACTIVO);
                    assertThat(leido.getFechaRegistro().isEqual(original.getFechaRegistro())).isTrue();
                    assertThat(leido.isNew()).isFalse();
                })
                .verifyComplete();
    }

    @Test
    void mismoTipoYNumeroLanzaDuplicateKey() {
        StepVerifier.create(repositorio.save(paciente(TipoDocumento.DNI, "00000001", "Alva"))
                        .then(repositorio.save(paciente(TipoDocumento.DNI, "00000001", "Benites"))))
                .expectError(DuplicateKeyException.class)
                .verify();
    }

    @Test
    void mismoNumeroConDistintoTipoSePermite() {
        StepVerifier.create(repositorio.save(paciente(TipoDocumento.CE, "000000001", "Alva"))
                        .then(repositorio.save(paciente(TipoDocumento.PASAPORTE, "000000001", "Benites")))
                        .then(repositorio.count()))
                .expectNext(2L)
                .verifyComplete();
    }

    @Test
    void existsDistingueElTipoDeDocumento() {
        StepVerifier.create(repositorio.save(paciente(TipoDocumento.DNI, "00000001", "Alva"))
                        .then(repositorio.existsByTipoDocumentoAndNumeroDocumento(TipoDocumento.DNI, "00000001")))
                .expectNext(true)
                .verifyComplete();
        StepVerifier.create(repositorio.existsByTipoDocumentoAndNumeroDocumento(TipoDocumento.CE, "00000001"))
                .expectNext(false)
                .verifyComplete();
    }

    @Test
    void guardarUnPacienteExistenteHaceUpdate() {
        Paciente original = paciente(TipoDocumento.DNI, "00000001", "Alva");

        StepVerifier.create(repositorio.save(original)
                        .then(repositorio.findById(original.getId()))
                        .doOnNext(p -> p.cambiarEstado(EstadoPaciente.INACTIVO))
                        .flatMap(repositorio::save)
                        .then(repositorio.findById(original.getId())))
                .assertNext(p -> assertThat(p.getEstado()).isEqualTo(EstadoPaciente.INACTIVO))
                .verifyComplete();
        StepVerifier.create(repositorio.count()).expectNext(1L).verifyComplete();
    }

    private void sembrarCinco() {
        Paciente inactivo = paciente(TipoDocumento.DNI, "00000005", "Diaz");
        inactivo.cambiarEstado(EstadoPaciente.INACTIVO);
        StepVerifier.create(repositorio.saveAll(List.of(
                        paciente(TipoDocumento.DNI, "00000001", "Alva"),
                        paciente(TipoDocumento.DNI, "00000002", "Benites"),
                        paciente(TipoDocumento.DNI, "00000003", "Benavides"),
                        paciente(TipoDocumento.DNI, "00000004", "Castro"),
                        inactivo)).then())
                .verifyComplete();
    }

    private static FiltroPacientes sinFiltro() {
        return FiltroPacientes.de(null, null, null, null);
    }

    private Flux<String> apellidosDePagina(int pagina, int tamano) {
        return busqueda.buscar(sinFiltro(), new ParametrosPagina(pagina, tamano)).map(Paciente::getApellidos);
    }

    @Test
    void filtraPorEstadoApellidosYDocumento() {
        sembrarCinco();

        StepVerifier.create(busqueda.contar(FiltroPacientes.de(EstadoPaciente.ACTIVO, null, null, null)))
                .expectNext(4L).verifyComplete();
        StepVerifier.create(busqueda.contar(FiltroPacientes.de(null, null, null, "ben")))
                .expectNext(2L).verifyComplete();
        StepVerifier.create(busqueda.contar(FiltroPacientes.de(null, TipoDocumento.DNI, "00000003", null)))
                .expectNext(1L).verifyComplete();
    }

    @Test
    void paginaConOrdenEstableYTotalIndependienteDeLaPagina() {
        sembrarCinco();

        StepVerifier.create(apellidosDePagina(0, 2)).expectNext("Alva", "Benavides").verifyComplete();
        StepVerifier.create(apellidosDePagina(1, 2)).expectNext("Benites", "Castro").verifyComplete();
        StepVerifier.create(apellidosDePagina(2, 2)).expectNext("Diaz").verifyComplete();
        StepVerifier.create(busqueda.contar(sinFiltro())).expectNext(5L).verifyComplete();
    }

    @Test
    void losComodinesDelUsuarioSeEscapan() {
        sembrarCinco();

        StepVerifier.create(busqueda.contar(FiltroPacientes.de(null, null, null, "%")))
                .expectNext(0L).verifyComplete();
    }

    @Test
    void idInexistenteNoDevuelveNada() {
        StepVerifier.create(repositorio.findById(UUID.randomUUID())).verifyComplete();
    }
}
