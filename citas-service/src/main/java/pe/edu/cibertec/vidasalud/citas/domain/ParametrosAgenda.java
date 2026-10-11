package pe.edu.cibertec.vidasalud.citas.domain;

import java.time.DayOfWeek;
import java.time.LocalTime;
import java.util.Objects;
import java.util.Set;

public record ParametrosAgenda(Set<DayOfWeek> diasAtencion, LocalTime horaInicio, LocalTime horaUltimoInicio,
                               int bloqueMinutos, int maxDiasAnticipacion, int plazoCambioHoras) {

    public ParametrosAgenda {
        Objects.requireNonNull(diasAtencion, "diasAtencion");
        Objects.requireNonNull(horaInicio, "horaInicio");
        Objects.requireNonNull(horaUltimoInicio, "horaUltimoInicio");
        if (diasAtencion.isEmpty()) {
            throw new IllegalArgumentException("Debe haber al menos un día de atención.");
        }
        if (horaInicio.isAfter(horaUltimoInicio)) {
            throw new IllegalArgumentException("La hora de inicio no puede ser posterior a la del último inicio.");
        }
        if (bloqueMinutos <= 0 || maxDiasAnticipacion <= 0 || plazoCambioHoras < 0) {
            throw new IllegalArgumentException("Los parámetros numéricos de la agenda no son válidos.");
        }
        diasAtencion = Set.copyOf(diasAtencion);
    }

    public static ParametrosAgenda porDefecto() {
        return new ParametrosAgenda(
                Set.of(DayOfWeek.MONDAY, DayOfWeek.TUESDAY, DayOfWeek.WEDNESDAY, DayOfWeek.THURSDAY,
                        DayOfWeek.FRIDAY, DayOfWeek.SATURDAY),
                LocalTime.of(8, 0), LocalTime.of(17, 30), 30, 90, 24);
    }
}
