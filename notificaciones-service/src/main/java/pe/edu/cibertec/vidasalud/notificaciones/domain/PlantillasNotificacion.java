package pe.edu.cibertec.vidasalud.notificaciones.domain;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import pe.edu.cibertec.vidasalud.commons.messaging.TopologiaHospital;

public final class PlantillasNotificacion {

    public static final int LARGO_MAXIMO = 300;
    public static final String SIN_DATO = "N/D";
    private static final ZoneId ZONA_LIMA = ZoneId.of("America/Lima");
    private static final DateTimeFormatter FORMATO_FECHA =
            DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm", Locale.ROOT);
    private static final Set<String> TIPOS = Set.of(
            TopologiaHospital.PACIENTE_REGISTRADO, TopologiaHospital.CITA_CREADA, TopologiaHospital.CITA_CONFIRMADA,
            TopologiaHospital.CITA_REPROGRAMADA, TopologiaHospital.CITA_CANCELADA,
            TopologiaHospital.ATENCION_REGISTRADA, TopologiaHospital.PAGO_REGISTRADO, TopologiaHospital.PAGO_ANULADO);

    private PlantillasNotificacion() {
    }

    /** Texto del aviso; vacío si el tipo no tiene plantilla (o es null). */
    public static Optional<String> generar(String tipo, DatosEvento d) {
        if (tipo == null) {
            return Optional.empty();
        }
        String texto = switch (tipo) {
            case TopologiaHospital.PACIENTE_REGISTRADO ->
                    "Bienvenido/a al Hospital Vida Salud. Su registro fue exitoso (paciente "
                            + valor(d.pacienteId()) + ").";
            case TopologiaHospital.CITA_CREADA ->
                    "Su cita " + valor(d.codigo()) + " de " + valor(d.especialidadNombre())
                            + " fue registrada para el " + fecha(d.fechaHora()) + ". Está pendiente de confirmación.";
            case TopologiaHospital.CITA_CONFIRMADA ->
                    "Su cita " + valor(d.codigo()) + " de " + valor(d.especialidadNombre()) + " del "
                            + fecha(d.fechaHora()) + " fue confirmada.";
            case TopologiaHospital.CITA_REPROGRAMADA ->
                    "Su cita " + valor(d.codigo()) + " de " + valor(d.especialidadNombre())
                            + " fue reprogramada del " + fecha(d.fechaHoraAnterior()) + " al "
                            + fecha(d.fechaHora()) + ".";
            case TopologiaHospital.CITA_CANCELADA ->
                    "Su cita " + valor(d.codigo()) + " de " + valor(d.especialidadNombre()) + " del "
                            + fecha(d.fechaHora()) + " fue cancelada.";
            case TopologiaHospital.ATENCION_REGISTRADA ->
                    "Su atención " + valor(d.codigo()) + " en " + valor(d.areaNombre()) + " fue registrada el "
                            + fecha(d.fechaAtencion()) + " (cita " + valor(d.citaId()) + ").";
            case TopologiaHospital.PAGO_REGISTRADO ->
                    "Se registró su pago de S/ " + monto(d.monto()) + " (operación "
                            + valor(d.numeroOperacion()) + ").";
            case TopologiaHospital.PAGO_ANULADO ->
                    "Su pago con operación " + valor(d.numeroOperacion()) + " fue anulado.";
            default -> null;
        };
        return Optional.ofNullable(texto).map(PlantillasNotificacion::recortar);
    }

    public static Set<String> tiposSoportados() {
        return TIPOS;
    }

    static String fecha(OffsetDateTime f) {
        return f == null ? SIN_DATO : f.atZoneSameInstant(ZONA_LIMA).format(FORMATO_FECHA);
    }

    static String monto(BigDecimal m) {
        return m == null ? SIN_DATO : m.setScale(2, RoundingMode.HALF_UP).toPlainString();
    }

    static String valor(Object o) {
        return o == null ? SIN_DATO : String.valueOf(o);
    }

    private static String recortar(String t) {
        return t.length() <= LARGO_MAXIMO ? t : t.substring(0, LARGO_MAXIMO);
    }
}
