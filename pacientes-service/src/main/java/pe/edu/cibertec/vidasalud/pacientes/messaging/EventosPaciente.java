package pe.edu.cibertec.vidasalud.pacientes.messaging;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.time.Clock;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import pe.edu.cibertec.vidasalud.commons.messaging.EventoEnvelope;
import pe.edu.cibertec.vidasalud.commons.messaging.PublicadorEventos;
import pe.edu.cibertec.vidasalud.commons.messaging.TopologiaHospital;
import pe.edu.cibertec.vidasalud.pacientes.domain.EstadoPaciente;
import pe.edu.cibertec.vidasalud.pacientes.domain.Paciente;
import reactor.core.publisher.Mono;

/** Los eventos llevan solo ids y estados: nunca datos personales. */
@Component
public class EventosPaciente {

    public static final String ORIGEN = "pacientes-service";
    public static final String TIPO_RECURSO = "PACIENTE";

    private static final Logger log = LoggerFactory.getLogger(EventosPaciente.class);

    private final Optional<PublicadorEventos> publicador;
    private final Clock reloj;

    public EventosPaciente(Optional<PublicadorEventos> publicador, Clock reloj) {
        this.publicador = publicador;
        this.reloj = reloj;
    }

    public Mono<Void> publicarRegistrado(Paciente paciente) {
        ObjectNode datos = JsonNodeFactory.instance.objectNode()
                .put("pacienteId", paciente.getId().toString())
                .put("fechaRegistro", paciente.getFechaRegistro().toString());
        return publicar(EventoEnvelope.nuevo(reloj, TopologiaHospital.PACIENTE_REGISTRADO, ORIGEN, TIPO_RECURSO,
                paciente.getId(), null, paciente.getEstado().name(), datos));
    }

    public Mono<Void> publicarEstadoCambiado(Paciente paciente, EstadoPaciente anterior, String motivo) {
        ObjectNode datos = JsonNodeFactory.instance.objectNode()
                .put("pacienteId", paciente.getId().toString())
                .put("motivo", motivo);
        return publicar(EventoEnvelope.nuevo(reloj, TopologiaHospital.PACIENTE_ESTADO_CAMBIADO, ORIGEN,
                TIPO_RECURSO, paciente.getId(), anterior.name(), paciente.getEstado().name(), datos));
    }

    private Mono<Void> publicar(EventoEnvelope evento) {
        if (publicador.isEmpty()) {
            return Mono.empty();
        }
        return publicador.get().publicar(evento.tipo(), evento)
                .onErrorResume(e -> {
                    log.warn("no se publicó {} eventId={}: {}", evento.tipo(), evento.eventId(), e.toString());
                    return Mono.empty();
                });
    }
}
