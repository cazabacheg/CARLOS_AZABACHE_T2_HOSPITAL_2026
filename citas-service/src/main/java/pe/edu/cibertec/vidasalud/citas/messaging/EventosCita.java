package pe.edu.cibertec.vidasalud.citas.messaging;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.util.UUID;
import org.springframework.stereotype.Component;
import pe.edu.cibertec.vidasalud.citas.domain.Cita;
import pe.edu.cibertec.vidasalud.citas.domain.EstadoCita;
import pe.edu.cibertec.vidasalud.citas.domain.ZonaHospital;
import pe.edu.cibertec.vidasalud.commons.messaging.EventoEnvelope;
import pe.edu.cibertec.vidasalud.commons.messaging.TopologiaHospital;

@Component
public class EventosCita {

    public static final String ORIGEN = "citas-service";
    public static final String TIPO_RECURSO = "CITA";

    private final ObjectMapper mapper;
    private final Clock reloj;

    public EventosCita(ObjectMapper mapper, Clock reloj) {
        this.mapper = mapper;
        this.reloj = reloj;
    }

    public EventoEnvelope creada(Cita c) {
        return EventoEnvelope.nuevo(reloj, TopologiaHospital.CITA_CREADA, ORIGEN, TIPO_RECURSO, c.getId(),
                null, c.getEstado().name(), datosComunes(c));
    }

    public EventoEnvelope cambioEstado(Cita c, EstadoCita anterior, String motivo, UUID atencionId) {
        ObjectNode datos = datosComunes(c);
        if (motivo != null) {
            datos.put("motivo", motivo);
        }
        if (atencionId != null) {
            datos.put("atencionId", atencionId.toString());
        }
        return EventoEnvelope.nuevo(reloj, routingKeyPara(c.getEstado()), ORIGEN, TIPO_RECURSO, c.getId(),
                anterior.name(), c.getEstado().name(), datos);
    }

    public EventoEnvelope reprogramada(Cita c, EstadoCita anterior, OffsetDateTime fechaHoraAnterior,
                                       String profesionalAnterior) {
        ObjectNode datos = datosComunes(c);
        datos.put("fechaHoraAnterior", formato(fechaHoraAnterior));
        datos.put("profesionalAnterior", profesionalAnterior);
        return EventoEnvelope.nuevo(reloj, TopologiaHospital.CITA_REPROGRAMADA, ORIGEN, TIPO_RECURSO, c.getId(),
                anterior.name(), c.getEstado().name(), datos);
    }

    public static String routingKeyPara(EstadoCita destino) {
        return switch (destino) {
            case CONFIRMADA -> TopologiaHospital.CITA_CONFIRMADA;
            case CANCELADA -> TopologiaHospital.CITA_CANCELADA;
            case AUSENTE -> TopologiaHospital.CITA_AUSENTE;
            case ATENDIDA -> TopologiaHospital.CITA_ATENDIDA;
            case PENDIENTE -> throw new IllegalArgumentException("PENDIENTE no tiene evento de cambio de estado");
        };
    }

    private ObjectNode datosComunes(Cita c) {
        return mapper.createObjectNode()
                .put("codigo", c.getCodigo())
                .put("pacienteId", c.getPacienteId().toString())
                .put("especialidad", c.getEspecialidad().name())
                .put("especialidadNombre", c.getEspecialidad().getNombre())
                .put("fechaHora", formato(c.getFechaHora()))
                .put("profesionalCodigo", c.getProfesionalCodigo());
    }

    private static String formato(OffsetDateTime fecha) {
        return DateTimeFormatter.ISO_OFFSET_DATE_TIME.format(ZonaHospital.aLima(fecha));
    }
}
