package pe.edu.cibertec.vidasalud.pacientes.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.HttpStatus;
import pe.edu.cibertec.vidasalud.commons.error.ErrorNegocio;

class ReglasDocumentoTest {

    @Test
    void dniDeOchoDigitosEsValido() {
        assertThat(ReglasDocumento.validar(TipoDocumento.DNI, "00000001")).isEqualTo("00000001");
    }

    @ParameterizedTest
    @ValueSource(strings = {"0000000A", "0000001", "000000012"})
    void dniConLetrasOLongitudIncorrectaSeRechaza(String numero) {
        assertThatThrownBy(() -> ReglasDocumento.validar(TipoDocumento.DNI, numero))
                .isInstanceOf(ErrorNegocio.class);
    }

    @ParameterizedTest
    @ValueSource(strings = {"ABC123456", "ABC123456789"})
    void ceEnLosLimitesEsValido(String numero) {
        assertThat(ReglasDocumento.validar(TipoDocumento.CE, numero)).isEqualTo(numero);
    }

    @ParameterizedTest
    @ValueSource(strings = {"ABC12345", "ABC1234567890"})
    void ceFueraDeLosLimitesSeRechaza(String numero) {
        assertThatThrownBy(() -> ReglasDocumento.validar(TipoDocumento.CE, numero))
                .isInstanceOf(ErrorNegocio.class);
    }

    @ParameterizedTest
    @ValueSource(strings = {"AB1234", "AB1234567890"})
    void pasaporteEnLosLimitesEsValido(String numero) {
        assertThat(ReglasDocumento.validar(TipoDocumento.PASAPORTE, numero)).isEqualTo(numero);
    }

    @ParameterizedTest
    @ValueSource(strings = {"AB123", "AB12345678901", "AB-12345"})
    void pasaporteFueraDeLosLimitesOConSimbolosSeRechaza(String numero) {
        assertThatThrownBy(() -> ReglasDocumento.validar(TipoDocumento.PASAPORTE, numero))
                .isInstanceOf(ErrorNegocio.class);
    }

    @Test
    void normalizaEspaciosYMayusculas() {
        assertThat(ReglasDocumento.validar(TipoDocumento.PASAPORTE, "  ab123456 ")).isEqualTo("AB123456");
        assertThat(ReglasDocumento.validar(TipoDocumento.DNI, " 00000001 ")).isEqualTo("00000001");
    }

    @Test
    void elErrorEsUnaValidacionDelCampoNumeroDocumento() {
        assertThatThrownBy(() -> ReglasDocumento.validar(TipoDocumento.DNI, "123"))
                .isInstanceOfSatisfying(ErrorNegocio.class, e -> {
                    assertThat(e.getStatus()).isEqualTo(HttpStatus.BAD_REQUEST);
                    assertThat(e.getCodigo()).isEqualTo("VALIDACION_FALLIDA");
                    assertThat(e.getErrores()).singleElement().satisfies(campo -> {
                        assertThat(campo.campo()).isEqualTo("numeroDocumento");
                        assertThat(campo.mensaje()).contains("DNI");
                    });
                });
    }

    @Test
    void numeroNuloSeRechaza() {
        assertThatThrownBy(() -> ReglasDocumento.validar(TipoDocumento.DNI, null))
                .isInstanceOf(ErrorNegocio.class);
    }
}
