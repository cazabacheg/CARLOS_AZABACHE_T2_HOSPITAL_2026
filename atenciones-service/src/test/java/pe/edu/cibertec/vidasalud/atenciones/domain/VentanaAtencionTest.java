package pe.edu.cibertec.vidasalud.atenciones.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import pe.edu.cibertec.vidasalud.commons.error.ErrorNegocio;

class VentanaAtencionTest {

    private static final Clock RELOJ = Clock.fixed(Instant.parse("2026-10-14T14:00:00Z"), ZoneId.of("America/Lima"));

    private static OffsetDateTime lima(String hora) {
        return OffsetDateTime.parse("2026-10-14T" + hora + ":00-05:00");
    }

    @Test
    void treintaMinutosExactosEsPermitido() {
        assertThatCode(() -> new VentanaAtencion(30).validar(lima("09:30"), RELOJ)).doesNotThrowAnyException();
    }

    @Test
    void citaPasadaEsPermitida() {
        assertThatCode(() -> new VentanaAtencion(30).validar(lima("07:00"), RELOJ)).doesNotThrowAnyException();
    }

    @Test
    void citaDemasiadoLejanaFalla() {
        assertThatThrownBy(() -> new VentanaAtencion(30).validar(lima("09:31"), RELOJ))
                .isInstanceOfSatisfying(ErrorNegocio.class, e -> {
                    assertThat(e.getCodigo()).isEqualTo("FUERA_DE_VENTANA_ATENCION");
                    assertThat(e.getStatus()).isEqualTo(HttpStatus.CONFLICT);
                    assertThat(e.getDetalle()).contains("30 minutos").contains("14/10/2026 09:31");
                });
    }

    @Test
    void comparaInstantesSinImportarElOffset() {
        OffsetDateTime enUtc = OffsetDateTime.parse("2026-10-14T14:30:00Z");
        assertThatCode(() -> new VentanaAtencion(30).validar(enUtc, RELOJ)).doesNotThrowAnyException();
    }

    @Test
    void usaLosMinutosConfigurados() {
        assertThatCode(() -> new VentanaAtencion(60).validar(lima("09:45"), RELOJ)).doesNotThrowAnyException();
    }

    @Test
    void rechazaMinutosNegativos() {
        assertThatThrownBy(() -> new VentanaAtencion(-1)).isInstanceOf(IllegalArgumentException.class);
    }
}
