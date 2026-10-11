package pe.edu.cibertec.vidasalud.notificaciones.messaging;

import java.util.List;
import java.util.stream.Stream;
import pe.edu.cibertec.vidasalud.commons.messaging.TopologiaHospital;

public final class BindingsNotificaciones {

    /** Fase A: prioridad normal. */
    public static final List<String> FASE_A = List.of(
            TopologiaHospital.PACIENTE_REGISTRADO, TopologiaHospital.CITA_CREADA, TopologiaHospital.CITA_CONFIRMADA,
            TopologiaHospital.CITA_REPROGRAMADA, TopologiaHospital.CITA_CANCELADA);

    /** Fase B: prioridad baja (solo agrega bindings y plantillas). */
    public static final List<String> FASE_B = List.of(
            TopologiaHospital.ATENCION_REGISTRADA, TopologiaHospital.PAGO_REGISTRADO, TopologiaHospital.PAGO_ANULADO);

    /** Lo que se enlaza a notificaciones.cola. Bindings explícitos (D-34): nunca comodines. */
    public static final List<String> TODOS = Stream.concat(FASE_A.stream(), FASE_B.stream()).toList();

    private BindingsNotificaciones() {
    }
}
