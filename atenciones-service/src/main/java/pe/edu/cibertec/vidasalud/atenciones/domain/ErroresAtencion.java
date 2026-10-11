package pe.edu.cibertec.vidasalud.atenciones.domain;

import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import pe.edu.cibertec.vidasalud.commons.error.ErrorNegocio;
import pe.edu.cibertec.vidasalud.commons.reloj.RelojAutoConfiguration;

public final class ErroresAtencion {

    public static final String SERVICIO_CITAS_NO_DISPONIBLE = "SERVICIO_CITAS_NO_DISPONIBLE";

    private static final DateTimeFormatter FORMATO = DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm");

    private ErroresAtencion() {
    }

    public static ErrorNegocio citaNoEncontrada(UUID citaId) {
        return new ErrorNegocio(HttpStatus.NOT_FOUND, "CITA_NO_ENCONTRADA", "No existe la cita " + citaId + ".");
    }

    public static ErrorNegocio citaNoConfirmada(String estadoActual) {
        return new ErrorNegocio(HttpStatus.CONFLICT, "CITA_NO_CONFIRMADA",
                "Solo se registra atención para citas confirmadas (estado actual: " + estadoActual + ").");
    }

    public static ErrorNegocio fueraDeVentana(int minutosAntes, OffsetDateTime fechaHoraCita) {
        String hora = FORMATO.format(fechaHoraCita.atZoneSameInstant(RelojAutoConfiguration.ZONA_HOSPITAL));
        return new ErrorNegocio(HttpStatus.CONFLICT, "FUERA_DE_VENTANA_ATENCION",
                "La atención se puede registrar desde " + minutosAntes + " minutos antes de la cita (" + hora + ").");
    }

    public static ErrorNegocio atencionDuplicada() {
        return new ErrorNegocio(HttpStatus.CONFLICT, "ATENCION_DUPLICADA", "La cita ya tiene una atención registrada.");
    }

    public static ErrorNegocio atencionNoEncontrada(UUID id) {
        return new ErrorNegocio(HttpStatus.NOT_FOUND, "ATENCION_NO_ENCONTRADA", "No existe la atención " + id + ".");
    }

    public static ErrorNegocio transicionNoPermitida(EstadoAtencion actual, EstadoAtencion destino) {
        return new ErrorNegocio(HttpStatus.CONFLICT, "TRANSICION_NO_PERMITIDA",
                "No se puede pasar una atención de " + actual + " a " + destino + ".");
    }

    public static ErrorNegocio conflictoConcurrente() {
        return new ErrorNegocio(HttpStatus.CONFLICT, "CONFLICTO_CONCURRENTE",
                "La atención fue modificada por otra operación; vuelva a consultarla.");
    }
}
