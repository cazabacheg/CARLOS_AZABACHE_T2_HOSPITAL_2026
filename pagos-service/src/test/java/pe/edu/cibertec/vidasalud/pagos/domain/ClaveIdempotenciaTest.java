package pe.edu.cibertec.vidasalud.pagos.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.HttpStatus;
import pe.edu.cibertec.vidasalud.commons.error.ErrorNegocio;

class ClaveIdempotenciaTest {

    @Test
    void aceptaClavesValidas() {
        String uuid = UUID.randomUUID().toString();
        assertThat(ClaveIdempotencia.validar(uuid)).isEqualTo(uuid);
        assertThat(ClaveIdempotencia.validar("abcd1234")).isEqualTo("abcd1234");
        String larga = "a".repeat(64);
        assertThat(ClaveIdempotencia.validar(larga)).isEqualTo(larga);
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"abcd123", "con espacio1", "guion_bajo1", " abcd1234"})
    void rechazaClavesInvalidas(String clave) {
        assertThatThrownBy(() -> ClaveIdempotencia.validar(clave)).isInstanceOfSatisfying(ErrorNegocio.class, e -> {
            assertThat(e.getCodigo()).isEqualTo("CLAVE_IDEMPOTENCIA_REQUERIDA");
            assertThat(e.getStatus()).isEqualTo(HttpStatus.BAD_REQUEST);
        });
    }

    @Test
    void rechazaClaveDe65Caracteres() {
        assertThatThrownBy(() -> ClaveIdempotencia.validar("a".repeat(65)))
                .isInstanceOfSatisfying(ErrorNegocio.class,
                        e -> assertThat(e.getCodigo()).isEqualTo("CLAVE_IDEMPOTENCIA_REQUERIDA"));
    }
}
