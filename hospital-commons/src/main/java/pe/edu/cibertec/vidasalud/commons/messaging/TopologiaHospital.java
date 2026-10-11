package pe.edu.cibertec.vidasalud.commons.messaging;

/** Nombres de exchange, colas y routing keys de la plataforma (documento tecnico, 6.4). */
public final class TopologiaHospital {

    public static final String EXCHANGE = "hospital.eventos";
    public static final String EXCHANGE_DLX = "hospital.eventos.dlx";

    public static final String PACIENTE_REGISTRADO = "paciente.registrado";
    public static final String PACIENTE_ESTADO_CAMBIADO = "paciente.estado-cambiado";
    public static final String CITA_CREADA = "cita.creada";
    public static final String CITA_CONFIRMADA = "cita.confirmada";
    public static final String CITA_REPROGRAMADA = "cita.reprogramada";
    public static final String CITA_CANCELADA = "cita.cancelada";
    public static final String CITA_ATENDIDA = "cita.atendida";
    public static final String CITA_AUSENTE = "cita.ausente";
    public static final String ATENCION_REGISTRADA = "atencion.registrada";
    public static final String ATENCION_FINALIZADA = "atencion.finalizada";
    public static final String ATENCION_ANULADA = "atencion.anulada";
    public static final String PAGO_REGISTRADO = "pago.registrado";
    public static final String PAGO_ANULADO = "pago.anulado";

    public static final String COLA_NOTIFICACIONES = "notificaciones.cola";
    public static final String COLA_AUDITORIA = "auditoria.cola";
    public static final String COLA_CITAS_ATENCIONES = "citas.atenciones.cola";

    private TopologiaHospital() {
    }

    /** {@code notificaciones.cola} -> {@code notificaciones.dlq}. */
    public static String colaMuertos(String cola) {
        return cola.endsWith(".cola") ? cola.substring(0, cola.length() - ".cola".length()) + ".dlq" : cola + ".dlq";
    }
}
