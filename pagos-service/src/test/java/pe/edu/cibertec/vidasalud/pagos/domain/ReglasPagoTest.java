package pe.edu.cibertec.vidasalud.pagos.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.HttpStatus;
import pe.edu.cibertec.vidasalud.commons.error.ErrorNegocio;

class ReglasPagoTest {

    @ParameterizedTest
    @CsvSource(delimiter = ';', value = {
            "0;El monto debe ser mayor que cero.",
            "-10;El monto debe ser mayor que cero.",
            "-50.00;El monto debe ser mayor que cero.",
            "0.001;El monto admite como máximo 2 decimales.",
            "100000.01;El monto excede el máximo permitido (100000.00)."})
    void rechazaMontosInvalidos(String monto, String mensaje) {
        assertThatThrownBy(() -> ReglasPago.validarMonto(new BigDecimal(monto)))
                .isInstanceOfSatisfying(ErrorNegocio.class, e -> {
                    assertThat(e.getCodigo()).isEqualTo("MONTO_INVALIDO");
                    assertThat(e.getStatus()).isEqualTo(HttpStatus.BAD_REQUEST);
                    assertThat(e.getDetalle()).isEqualTo(mensaje);
                });
    }

    @ParameterizedTest
    @ValueSource(strings = {"0.01", "80", "80.00", "80.000", "100000.00"})
    void aceptaMontosValidos(String monto) {
        assertThatCode(() -> ReglasPago.validarMonto(new BigDecimal(monto))).doesNotThrowAnyException();
    }

    @Test
    void montoNuloEsValidacionFallida() {
        assertThatThrownBy(() -> ReglasPago.validarMonto(null))
                .isInstanceOfSatisfying(ErrorNegocio.class, e -> {
                    assertThat(e.getCodigo()).isEqualTo("VALIDACION_FALLIDA");
                    assertThat(e.getStatus()).isEqualTo(HttpStatus.BAD_REQUEST);
                });
    }

    @Test
    void normalizaALaEscalaDeDos() {
        BigDecimal n = ReglasPago.normalizar(new BigDecimal("80"));
        assertThat(n.scale()).isEqualTo(2);
        assertThat(n.toPlainString()).isEqualTo("80.00");
        assertThat(ReglasPago.normalizar(new BigDecimal("80.000")).toPlainString()).isEqualTo("80.00");
    }
}
