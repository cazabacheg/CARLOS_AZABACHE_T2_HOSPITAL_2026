package pe.edu.cibertec.vidasalud.pacientes.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import pe.edu.cibertec.vidasalud.commons.error.ErrorNegocio;

class PacienteTest {

    private static final Clock RELOJ = Clock.fixed(Instant.parse("2026-10-12T14:30:00Z"), ZoneId.of("America/Lima"));

    private static DatosPersonales datos() {
        return new DatosPersonales("Ana María", "Prueba Ficticia", LocalDate.of(1990, 5, 20),
                "999000111", "ana.prueba@example.com", null);
    }

    private static Paciente nuevo() {
        return Paciente.registrar(TipoDocumento.DNI, "00000001", datos(), RELOJ);
    }

    @Test
    void registrarDejaElPacienteActivoConLaFechaDelReloj() {
        Paciente paciente = Paciente.registrar(TipoDocumento.PASAPORTE, " ab123456 ", datos(), RELOJ);

        assertThat(paciente.getId()).isNotNull();
        assertThat(paciente.getEstado()).isEqualTo(EstadoPaciente.ACTIVO);
        assertThat(paciente.isNew()).isTrue();
        assertThat(paciente.getFechaRegistro()).isEqualTo(OffsetDateTime.now(RELOJ));
        assertThat(paciente.getNumeroDocumento()).isEqualTo("AB123456");
    }

    @Test
    void registrarConDniInvalidoLanzaValidacion() {
        assertThatThrownBy(() -> Paciente.registrar(TipoDocumento.DNI, "123", datos(), RELOJ))
                .isInstanceOfSatisfying(ErrorNegocio.class,
                        e -> assertThat(e.getStatus()).isEqualTo(HttpStatus.BAD_REQUEST));
    }

    @Test
    void fechaDeNacimientoDeHaceMasDe120AniosSeRechaza() {
        DatosPersonales antigua = new DatosPersonales("Ana", "Prueba", LocalDate.of(1905, 10, 11), null, null, null);

        assertThatThrownBy(() -> Paciente.registrar(TipoDocumento.DNI, "00000001", antigua, RELOJ))
                .isInstanceOfSatisfying(ErrorNegocio.class, e -> {
                    assertThat(e.getStatus()).isEqualTo(HttpStatus.BAD_REQUEST);
                    assertThat(e.getErrores()).singleElement()
                            .satisfies(campo -> assertThat(campo.campo()).isEqualTo("fechaNacimiento"));
                });
    }

    @Test
    void fechaDeNacimientoDeExactamente120AniosEsValida() {
        DatosPersonales limite = new DatosPersonales("Ana", "Prueba", LocalDate.of(1906, 10, 12), null, null, null);

        assertThatCode(() -> Paciente.registrar(TipoDocumento.DNI, "00000001", limite, RELOJ))
                .doesNotThrowAnyException();
    }

    @Test
    void cambiarEstadoDevuelveElAnterior() {
        Paciente paciente = nuevo();

        EstadoPaciente anterior = paciente.cambiarEstado(EstadoPaciente.INACTIVO);

        assertThat(anterior).isEqualTo(EstadoPaciente.ACTIVO);
        assertThat(paciente.getEstado()).isEqualTo(EstadoPaciente.INACTIVO);
    }

    @Test
    void cambiarAlMismoEstadoLanzaConflicto() {
        Paciente paciente = nuevo();

        assertThatThrownBy(() -> paciente.cambiarEstado(EstadoPaciente.ACTIVO))
                .isInstanceOfSatisfying(ErrorNegocio.class, e -> {
                    assertThat(e.getStatus()).isEqualTo(HttpStatus.CONFLICT);
                    assertThat(e.getCodigo()).isEqualTo(ErroresPaciente.TRANSICION_NO_PERMITIDA);
                });
    }

    @Test
    void actualizarDatosNoTocaDocumentoEstadoNiFechaDeRegistro() {
        Paciente paciente = nuevo();
        var id = paciente.getId();
        var registro = paciente.getFechaRegistro();

        paciente.actualizarDatos(new DatosPersonales("Beatriz", "Otra Ficticia", LocalDate.of(1985, 1, 1),
                "988000222", "beatriz@example.com", "Calle Falsa 123"), RELOJ);

        assertThat(paciente.getNombres()).isEqualTo("Beatriz");
        assertThat(paciente.getCorreo()).isEqualTo("beatriz@example.com");
        assertThat(paciente.getId()).isEqualTo(id);
        assertThat(paciente.getTipoDocumento()).isEqualTo(TipoDocumento.DNI);
        assertThat(paciente.getNumeroDocumento()).isEqualTo("00000001");
        assertThat(paciente.getEstado()).isEqualTo(EstadoPaciente.ACTIVO);
        assertThat(paciente.getFechaRegistro()).isEqualTo(registro);
    }

    @Test
    void actualizarDatosValidaLaFechaDeNacimiento() {
        Paciente paciente = nuevo();
        DatosPersonales antigua = new DatosPersonales("Ana", "Prueba", LocalDate.of(1800, 1, 1), null, null, null);

        assertThatThrownBy(() -> paciente.actualizarDatos(antigua, RELOJ)).isInstanceOf(ErrorNegocio.class);
    }

    @Test
    void recortaTextosYConvierteOpcionalesEnBlancoEnNulo() {
        DatosPersonales conEspacios = new DatosPersonales("  Ana  ", " Prueba ", LocalDate.of(1990, 5, 20),
                "  ", " ", "");

        Paciente paciente = Paciente.registrar(TipoDocumento.DNI, "00000001", conEspacios, RELOJ);

        assertThat(paciente.getNombres()).isEqualTo("Ana");
        assertThat(paciente.getApellidos()).isEqualTo("Prueba");
        assertThat(paciente.getTelefono()).isNull();
        assertThat(paciente.getCorreo()).isNull();
        assertThat(paciente.getDireccion()).isNull();
    }

    @Test
    void marcarComoExistenteQuitaLaMarcaDeNuevo() {
        assertThat(nuevo().marcarComoExistente().isNew()).isFalse();
    }
}
