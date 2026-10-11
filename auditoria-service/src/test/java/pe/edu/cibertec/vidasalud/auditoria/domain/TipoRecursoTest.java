package pe.edu.cibertec.vidasalud.auditoria.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.HttpStatus;
import pe.edu.cibertec.vidasalud.commons.error.ErrorNegocio;

class TipoRecursoTest {

    @Test
    void desdeAceptaValorExacto() {
        assertThat(TipoRecurso.desde("CITA")).isEqualTo(TipoRecurso.CITA);
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(strings = {"cita", "REPORTE", " "})
    void desdeRechazaInvalidos(String valor) {
        assertThatThrownBy(() -> TipoRecurso.desde(valor)).isInstanceOfSatisfying(ErrorNegocio.class, e -> {
            assertThat(e.getStatus()).isEqualTo(HttpStatus.BAD_REQUEST);
            assertThat(e.getCodigo()).isEqualTo("VALIDACION_FALLIDA");
            assertThat(e.getErrores()).anyMatch(c -> c.campo().equals("tipoRecurso"));
        });
    }

    @Test
    void desdeOpcionalTrataVacioComoNulo() {
        assertThat(TipoRecurso.desdeOpcional(null)).isNull();
        assertThat(TipoRecurso.desdeOpcional("")).isNull();
        assertThat(TipoRecurso.desdeOpcional("PAGO")).isEqualTo(TipoRecurso.PAGO);
        assertThatThrownBy(() -> TipoRecurso.desdeOpcional("X")).isInstanceOf(ErrorNegocio.class);
    }
}
