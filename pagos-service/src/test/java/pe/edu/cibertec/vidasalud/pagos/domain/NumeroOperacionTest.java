package pe.edu.cibertec.vidasalud.pagos.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;

class NumeroOperacionTest {

    @Test
    void formateaConRelleno() {
        assertThat(NumeroOperacion.formatear(LocalDate.of(2026, 10, 12), 1)).isEqualTo("OP-20261012-000001");
    }

    @Test
    void permiteCrecerMasAllaDeSeisDigitos() {
        assertThat(NumeroOperacion.formatear(LocalDate.of(2026, 10, 12), 1234567)).isEqualTo("OP-20261012-1234567");
    }

    @Test
    void usaLaFechaDeLimaAunqueElRelojEsteEnUtc() {
        Clock utc = Clock.fixed(Instant.parse("2026-10-13T03:00:00Z"), ZoneOffset.UTC);
        assertThat(NumeroOperacion.fechaEnLima(utc)).isEqualTo(LocalDate.of(2026, 10, 12));
    }
}
