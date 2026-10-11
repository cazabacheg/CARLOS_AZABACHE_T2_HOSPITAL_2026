package pe.edu.cibertec.vidasalud.citas.messaging;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import pe.edu.cibertec.vidasalud.citas.service.CitaService;
import pe.edu.cibertec.vidasalud.commons.messaging.EventoEnvelope;
import pe.edu.cibertec.vidasalud.commons.messaging.TopologiaHospital;
import reactor.core.publisher.Mono;

@Component
public class AtencionEventosConsumer {

    private static final Logger log = LoggerFactory.getLogger(AtencionEventosConsumer.class);

    private final CitaService citaService;
    private final ObjectMapper mapper;

    public AtencionEventosConsumer(CitaService citaService, ObjectMapper mapper) {
        this.citaService = citaService;
        this.mapper = mapper;
    }

    public Mono<Void> manejar(EventoEnvelope evento) {
        return Mono.defer(() -> {
            if (evento.datos() == null) {
                return Mono.error(new IllegalArgumentException("evento sin citaId"));
            }
            return Mono.fromCallable(() -> mapper.treeToValue(evento.datos(), AtencionEventoDatos.class));
        }).flatMap(datos -> {
            if (datos.citaId() == null) {
                return Mono.error(new IllegalArgumentException("evento sin citaId"));
            }
            var atencionId = datos.atencionId() != null ? datos.atencionId() : evento.recursoId();
            return switch (evento.tipo()) {
                case TopologiaHospital.ATENCION_REGISTRADA -> citaService.marcarAtendida(datos.citaId(), atencionId);
                case TopologiaHospital.ATENCION_ANULADA ->
                        citaService.cancelarPorAtencionAnulada(datos.citaId(), atencionId);
                default -> {
                    log.warn("tipo de evento no esperado eventId={} tipo={}", evento.eventId(), evento.tipo());
                    yield Mono.<Void>empty();
                }
            };
        });
    }
}
