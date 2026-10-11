package pe.edu.cibertec.vidasalud.atenciones.domain;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;

/** RN-16: la atención se registra desde N minutos antes de la cita, sin límite posterior. */
public final class VentanaAtencion {

    private final int minutosAntes;

    public VentanaAtencion(int minutosAntes) {
        if (minutosAntes < 0) {
            throw new IllegalArgumentException("Los minutos de anticipación no pueden ser negativos.");
        }
        this.minutosAntes = minutosAntes;
    }

    public int minutosAntes() {
        return minutosAntes;
    }

    public void validar(OffsetDateTime fechaHoraCita, Clock reloj) {
        Instant desde = fechaHoraCita.toInstant().minus(Duration.ofMinutes(minutosAntes));
        if (reloj.instant().isBefore(desde)) {
            throw ErroresAtencion.fueraDeVentana(minutosAntes, fechaHoraCita);
        }
    }
}
