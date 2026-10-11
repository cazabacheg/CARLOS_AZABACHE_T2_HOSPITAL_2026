package pe.edu.cibertec.vidasalud.pagos.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import pe.edu.cibertec.vidasalud.commons.error.ErrorNegocio;

class PagoTest {

    private static final Clock RELOJ = Clock.fixed(Instant.parse("2026-10-12T14:00:00Z"), ZoneId.of("America/Lima"));

    private static DatosPago datos(String monto) {
        return new DatosPago(UUID.randomUUID(), null, "  Reserva de cita  ", new BigDecimal(monto), MedioPago.TARJETA);
    }

    private static Pago pago() {
        return Pago.registrar(datos("80"), "OP-20261012-000001", "clave-12345678", "h".repeat(64), RELOJ);
    }

    @Test
    void registrarDejaElPagoRegistrado() {
        Pago p = pago();
        assertThat(p.getEstado()).isEqualTo(EstadoPago.REGISTRADO);
        assertThat(p.getMoneda()).isEqualTo("PEN");
        assertThat(p.getNumeroOperacion()).isEqualTo("OP-20261012-000001");
        assertThat(p.getFechaPago().toInstant()).isEqualTo(RELOJ.instant());
        assertThat(p.getFechaActualizacion()).isEqualTo(p.getFechaPago());
        assertThat(p.getMonto().scale()).isEqualTo(2);
        assertThat(p.getConcepto()).isEqualTo("Reserva de cita");
        assertThat(p.isNew()).isTrue();
        assertThat(p.getVersion()).isNull();
    }

    @Test
    void marcarComoExistenteQuitaLaMarcaDeNuevo() {
        assertThat(pago().marcarComoExistente().isNew()).isFalse();
    }

    @Test
    void registrarConMontoCeroFalla() {
        assertThatThrownBy(() -> Pago.registrar(datos("0"), "OP-1", "clave-12345678", "h", RELOJ))
                .isInstanceOfSatisfying(ErrorNegocio.class,
                        e -> assertThat(e.getCodigo()).isEqualTo("MONTO_INVALIDO"));
    }

    @Test
    void anularConservaElNumeroYRecortaElMotivo() {
        Pago p = pago();
        p.anular("  Error de registro ", RELOJ);
        assertThat(p.getEstado()).isEqualTo(EstadoPago.ANULADO);
        assertThat(p.getMotivoAnulacion()).isEqualTo("Error de registro");
        assertThat(p.getNumeroOperacion()).isEqualTo("OP-20261012-000001");
    }

    @Test
    void anularDosVecesEsConflicto() {
        Pago p = pago();
        p.anular("motivo", RELOJ);
        assertThatThrownBy(() -> p.anular("otro", RELOJ)).isInstanceOfSatisfying(ErrorNegocio.class, e -> {
            assertThat(e.getCodigo()).isEqualTo("PAGO_YA_ANULADO");
            assertThat(e.getStatus()).isEqualTo(HttpStatus.CONFLICT);
        });
    }
}
