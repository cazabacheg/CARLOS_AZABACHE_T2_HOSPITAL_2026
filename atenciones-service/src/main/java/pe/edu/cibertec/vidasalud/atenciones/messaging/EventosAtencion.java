package pe.edu.cibertec.vidasalud.atenciones.messaging;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import pe.edu.cibertec.vidasalud.atenciones.domain.Atencion;
import pe.edu.cibertec.vidasalud.atenciones.domain.EstadoAtencion;
import pe.edu.cibertec.vidasalud.commons.messaging.EventoEnvelope;
import pe.edu.cibertec.vidasalud.commons.messaging.TopologiaHospital;
import pe.edu.cibertec.vidasalud.commons.reloj.RelojAutoConfiguration;

public final class EventosAtencion {

    public static final String ORIGEN = "atenciones-service";
    public static final String TIPO_RECURSO = "ATENCION";

    private EventosAtencion() {
    }

    public static EventoEnvelope registrada(Atencion a, Clock reloj) {
        return EventoEnvelope.nuevo(reloj, TopologiaHospital.ATENCION_REGISTRADA, ORIGEN, TIPO_RECURSO,
                a.getId(), null, EstadoAtencion.REGISTRADA.name(), datosComunes(a));
    }

    public static EventoEnvelope cambioEstado(Atencion a, EstadoAtencion anterior, String motivo, Clock reloj) {
        ObjectNode datos = datosComunes(a);
        datos.put("motivo", motivo);
        return EventoEnvelope.nuevo(reloj, routingKeyDe(a.getEstado()), ORIGEN, TIPO_RECURSO,
                a.getId(), anterior.name(), a.getEstado().name(), datos);
    }

    public static String routingKeyDe(EstadoAtencion destino) {
        return switch (destino) {
            case FINALIZADA -> TopologiaHospital.ATENCION_FINALIZADA;
            case ANULADA -> TopologiaHospital.ATENCION_ANULADA;
            case REGISTRADA -> TopologiaHospital.ATENCION_REGISTRADA;
        };
    }

    static ObjectNode datosComunes(Atencion a) {
        ObjectNode datos = JsonNodeFactory.instance.objectNode();
        datos.put("atencionId", a.getId().toString());
        datos.put("citaId", a.getCitaId().toString());
        datos.put("pacienteId", a.getPacienteId().toString());
        datos.put("codigo", a.getCodigo());
        datos.put("area", a.getArea().name());
        datos.put("areaNombre", a.getArea().nombre());
        datos.put("fechaAtencion", DateTimeFormatter.ISO_OFFSET_DATE_TIME.format(enLima(a.getFechaAtencion())));
        return datos;
    }

    public static OffsetDateTime enLima(OffsetDateTime t) {
        return t.atZoneSameInstant(RelojAutoConfiguration.ZONA_HOSPITAL).toOffsetDateTime();
    }
}
