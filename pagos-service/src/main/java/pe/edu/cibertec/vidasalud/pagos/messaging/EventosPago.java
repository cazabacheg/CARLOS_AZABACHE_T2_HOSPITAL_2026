package pe.edu.cibertec.vidasalud.pagos.messaging;

import static pe.edu.cibertec.vidasalud.commons.reloj.RelojAutoConfiguration.ZONA_HOSPITAL;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.time.Clock;
import java.time.format.DateTimeFormatter;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import pe.edu.cibertec.vidasalud.commons.messaging.EventoEnvelope;
import pe.edu.cibertec.vidasalud.commons.messaging.PublicadorEventos;
import pe.edu.cibertec.vidasalud.commons.messaging.TopologiaHospital;
import pe.edu.cibertec.vidasalud.pagos.domain.Pago;
import reactor.core.publisher.Mono;

/** Los eventos llevan solo ids y datos del pago: nunca datos personales del paciente. */
@Component
public class EventosPago {

    static final String ORIGEN = "pagos-service";
    static final String TIPO_RECURSO = "PAGO";

    private static final Logger log = LoggerFactory.getLogger(EventosPago.class);

    private final Optional<PublicadorEventos> publicador;
    private final Clock reloj;

    public EventosPago(Optional<PublicadorEventos> publicador, Clock reloj) {
        this.publicador = publicador;
        this.reloj = reloj;
    }

    public Mono<Void> registrado(Pago p) {
        return publicar(TopologiaHospital.PAGO_REGISTRADO, EventoEnvelope.nuevo(reloj,
                TopologiaHospital.PAGO_REGISTRADO, ORIGEN, TIPO_RECURSO, p.getId(), null, "REGISTRADO", datos(p)));
    }

    public Mono<Void> anulado(Pago p) {
        ObjectNode datos = datos(p).put("motivo", p.getMotivoAnulacion());
        return publicar(TopologiaHospital.PAGO_ANULADO, EventoEnvelope.nuevo(reloj,
                TopologiaHospital.PAGO_ANULADO, ORIGEN, TIPO_RECURSO, p.getId(), "REGISTRADO", "ANULADO", datos));
    }

    static ObjectNode datos(Pago p) {
        return JsonNodeFactory.instance.objectNode()
                .put("pagoId", p.getId().toString())
                .put("pacienteId", p.getPacienteId().toString())
                .put("citaId", p.getCitaId() == null ? null : p.getCitaId().toString())
                .put("numeroOperacion", p.getNumeroOperacion())
                .put("concepto", p.getConcepto())
                .put("monto", p.getMonto())
                .put("moneda", p.getMoneda().trim())
                .put("medioPago", p.getMedioPago().name())
                .put("fechaPago", DateTimeFormatter.ISO_OFFSET_DATE_TIME
                        .format(p.getFechaPago().atZoneSameInstant(ZONA_HOSPITAL).toOffsetDateTime()));
    }

    private Mono<Void> publicar(String routingKey, EventoEnvelope evento) {
        if (publicador.isEmpty()) {
            return Mono.empty();
        }
        return publicador.get().publicar(routingKey, evento)
                .onErrorResume(e -> {
                    log.warn("no se publicó {} eventId={}: {}", routingKey, evento.eventId(), e.toString());
                    return Mono.empty();
                });
    }
}
