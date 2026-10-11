package pe.edu.cibertec.vidasalud.commons.error;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

class ErrorNegocioTest {

    @Test
    void validacionCreaErrorConUnCampo() {
        ErrorNegocio e = ErrorNegocio.validacion("pagina", "debe ser mayor o igual a 0");
        assertThat(e.getStatus()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(e.getCodigo()).isEqualTo("VALIDACION_FALLIDA");
        assertThat(e.getErrores()).containsExactly(new ErrorCampo("pagina", "debe ser mayor o igual a 0"));
    }

    @Test
    void noCapturaStackTrace() {
        ErrorNegocio e = new ErrorNegocio(HttpStatus.NOT_FOUND, "X", "detalle");
        assertThat(e.getStackTrace()).isEmpty();
        assertThat(e.getDetalle()).isEqualTo("detalle");
    }
}
