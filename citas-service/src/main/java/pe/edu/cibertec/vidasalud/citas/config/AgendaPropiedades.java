package pe.edu.cibertec.vidasalud.citas.config;

import java.time.DayOfWeek;
import java.time.LocalTime;
import java.util.List;
import java.util.Set;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import pe.edu.cibertec.vidasalud.citas.domain.ParametrosAgenda;

@ConfigurationProperties("hospital.agenda")
public record AgendaPropiedades(
        @DefaultValue({"MONDAY", "TUESDAY", "WEDNESDAY", "THURSDAY", "FRIDAY", "SATURDAY"}) List<DayOfWeek> diasAtencion,
        @DefaultValue("08:00") String horaInicio,
        @DefaultValue("17:30") String horaUltimoInicio,
        @DefaultValue("30") int bloqueMinutos,
        @DefaultValue("90") int maxDiasAnticipacion,
        @DefaultValue("24") int plazoCambioHoras) {

    public ParametrosAgenda aParametros() {
        return new ParametrosAgenda(Set.copyOf(diasAtencion), LocalTime.parse(horaInicio.trim()),
                LocalTime.parse(horaUltimoInicio.trim()), bloqueMinutos, maxDiasAnticipacion, plazoCambioHoras);
    }
}
