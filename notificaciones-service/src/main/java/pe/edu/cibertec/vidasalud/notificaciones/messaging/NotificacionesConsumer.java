package pe.edu.cibertec.vidasalud.notificaciones.messaging;

import org.springframework.stereotype.Component;
import pe.edu.cibertec.vidasalud.commons.messaging.EventoEnvelope;
import pe.edu.cibertec.vidasalud.notificaciones.service.NotificacionService;
import reactor.core.publisher.Mono;

@Component
public class NotificacionesConsumer {

    private final NotificacionService servicio;

    public NotificacionesConsumer(NotificacionService servicio) {
        this.servicio = servicio;
    }

    /** Manejador que se registra en ConsumidorEventos. */
    public Mono<Void> manejar(EventoEnvelope evento) {
        return servicio.procesar(evento);
    }
}
