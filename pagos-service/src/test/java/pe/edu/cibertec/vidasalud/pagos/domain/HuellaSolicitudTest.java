package pe.edu.cibertec.vidasalud.pagos.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import java.math.BigDecimal;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class HuellaSolicitudTest {

    private static final UUID PACIENTE = UUID.randomUUID();
    private static final UUID CITA = UUID.randomUUID();

    private static String huella(UUID paciente, UUID cita, String concepto, String monto, MedioPago medio) {
        return HuellaSolicitud.calcular(paciente, cita, concepto, new BigDecimal(monto), medio);
    }

    @Test
    void mismoCuerpoMismaHuella() {
        assertThat(huella(PACIENTE, CITA, "Reserva", "80.00", MedioPago.TARJETA))
                .isEqualTo(huella(PACIENTE, CITA, "Reserva", "80.00", MedioPago.TARJETA));
    }

    @Test
    void escalaDelMontoNoCambiaLaHuella() {
        assertThat(huella(PACIENTE, CITA, "Reserva", "80", MedioPago.TARJETA))
                .isEqualTo(huella(PACIENTE, CITA, "Reserva", "80.00", MedioPago.TARJETA));
    }

    @Test
    void espaciosEnElConceptoNoCambianLaHuella() {
        assertThat(huella(PACIENTE, CITA, "  Reserva ", "80.00", MedioPago.TARJETA))
                .isEqualTo(huella(PACIENTE, CITA, "Reserva", "80.00", MedioPago.TARJETA));
    }

    @Test
    void cualquierCambioDeDatosCambiaLaHuella() {
        String base = huella(PACIENTE, CITA, "Reserva", "80.00", MedioPago.TARJETA);
        assertThat(huella(PACIENTE, CITA, "Reserva", "80.01", MedioPago.TARJETA)).isNotEqualTo(base);
        assertThat(huella(UUID.randomUUID(), CITA, "Reserva", "80.00", MedioPago.TARJETA)).isNotEqualTo(base);
        assertThat(huella(PACIENTE, CITA, "Reserva", "80.00", MedioPago.EFECTIVO)).isNotEqualTo(base);
        assertThat(huella(PACIENTE, null, "Reserva", "80.00", MedioPago.TARJETA)).isNotEqualTo(base);
    }

    @Test
    void tiene64CaracteresHexadecimales() {
        assertThat(huella(PACIENTE, CITA, "Reserva", "80.00", MedioPago.TARJETA)).matches("[0-9a-f]{64}");
    }

    @Test
    void montoInvalidoNoLanzaExcepcion() {
        assertThatCode(() -> huella(PACIENTE, CITA, "Reserva", "0.001", MedioPago.TARJETA)).doesNotThrowAnyException();
    }
}
