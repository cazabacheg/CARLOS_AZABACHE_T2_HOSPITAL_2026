package pe.edu.cibertec.vidasalud.pacientes.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import pe.edu.cibertec.vidasalud.commons.error.ErrorNegocio;

class FiltroPacientesTest {

    @Test
    void sinParametrosDevuelveFiltroVacio() {
        assertThat(FiltroPacientes.de(null, null, null, null))
                .isEqualTo(new FiltroPacientes(null, null, null, null));
    }

    @Test
    void tipoSinNumeroONumeroSinTipoSeRechaza() {
        assertThatThrownBy(() -> FiltroPacientes.de(null, TipoDocumento.DNI, null, null))
                .isInstanceOfSatisfying(ErrorNegocio.class, e -> {
                    assertThat(e.getStatus()).isEqualTo(HttpStatus.BAD_REQUEST);
                    assertThat(e.getErrores()).singleElement()
                            .satisfies(campo -> assertThat(campo.campo()).isEqualTo("numeroDocumento"));
                });
        assertThatThrownBy(() -> FiltroPacientes.de(null, null, "00000001", null))
                .isInstanceOf(ErrorNegocio.class);
    }

    @Test
    void normalizaNumeroYApellidosEnBlancoPasanANulo() {
        FiltroPacientes filtro = FiltroPacientes.de(EstadoPaciente.ACTIVO, TipoDocumento.PASAPORTE, " ab123456 ", "  ");

        assertThat(filtro.numeroDocumento()).isEqualTo("AB123456");
        assertThat(filtro.apellidos()).isNull();
        assertThat(filtro.estado()).isEqualTo(EstadoPaciente.ACTIVO);
    }
}
