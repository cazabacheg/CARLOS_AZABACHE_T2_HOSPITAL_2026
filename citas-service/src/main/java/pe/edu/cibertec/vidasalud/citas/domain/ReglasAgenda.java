package pe.edu.cibertec.vidasalud.citas.domain;

import java.time.Clock;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import org.springframework.http.HttpStatus;
import pe.edu.cibertec.vidasalud.commons.error.ErrorNegocio;

public final class ReglasAgenda {

    private static final String FUERA_DE_AGENDA = "HORARIO_FUERA_DE_AGENDA";

    private final Clock reloj;
    private final ParametrosAgenda parametros;

    public ReglasAgenda(Clock reloj, ParametrosAgenda parametros) {
        this.reloj = reloj;
        this.parametros = parametros;
    }

    public void validar(OffsetDateTime fechaHora) {
        OffsetDateTime lima = ZonaHospital.aLima(fechaHora);
        OffsetDateTime ahora = ZonaHospital.aLima(OffsetDateTime.now(reloj));
        if (!lima.isAfter(ahora)) {
            throw fuera("La cita debe programarse en una fecha futura.");
        }
        if (!parametros.diasAtencion().contains(lima.getDayOfWeek())) {
            throw fuera("No se atiende ese día.");
        }
        LocalTime hora = lima.toLocalTime();
        if (hora.isBefore(parametros.horaInicio()) || hora.isAfter(parametros.horaUltimoInicio())) {
            throw fuera("Horario de atención: " + parametros.horaInicio() + " a "
                    + parametros.horaUltimoInicio() + ".");
        }
        if (hora.getSecond() != 0 || hora.getNano() != 0
                || (hora.getHour() * 60 + hora.getMinute()) % parametros.bloqueMinutos() != 0) {
            throw fuera("Las citas se programan en bloques de " + parametros.bloqueMinutos() + " minutos.");
        }
        if (lima.toLocalDate().isAfter(ahora.toLocalDate().plusDays(parametros.maxDiasAnticipacion()))) {
            throw fuera("Solo se programan citas con hasta " + parametros.maxDiasAnticipacion()
                    + " días de anticipación.");
        }
    }

    public void validarPlazoCambio(OffsetDateTime fechaHoraCita) {
        OffsetDateTime ahora = OffsetDateTime.now(reloj);
        if (!ahora.isBefore(fechaHoraCita.minusHours(parametros.plazoCambioHoras()))) {
            throw new ErrorNegocio(HttpStatus.CONFLICT, "FUERA_DE_PLAZO",
                    "Solo se puede cancelar o reprogramar hasta " + parametros.plazoCambioHoras()
                            + " horas antes de la cita.");
        }
    }

    public ParametrosAgenda parametros() {
        return parametros;
    }

    private static ErrorNegocio fuera(String detalle) {
        return new ErrorNegocio(HttpStatus.BAD_REQUEST, FUERA_DE_AGENDA, detalle);
    }
}
