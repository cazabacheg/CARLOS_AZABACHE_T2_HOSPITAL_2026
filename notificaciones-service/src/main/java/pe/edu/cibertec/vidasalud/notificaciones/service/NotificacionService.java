package pe.edu.cibertec.vidasalud.notificaciones.service;

import java.time.Clock;
import java.time.OffsetDateTime;
import java.time.temporal.ChronoUnit;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import pe.edu.cibertec.vidasalud.commons.error.ErrorNegocio;
import pe.edu.cibertec.vidasalud.commons.messaging.EventoEnvelope;
import pe.edu.cibertec.vidasalud.commons.paginacion.ParametrosPagina;
import pe.edu.cibertec.vidasalud.notificaciones.api.NotificacionResponse;
import pe.edu.cibertec.vidasalud.notificaciones.domain.DatosEvento;
import pe.edu.cibertec.vidasalud.notificaciones.domain.FiltroNotificaciones;
import pe.edu.cibertec.vidasalud.notificaciones.domain.Notificacion;
import pe.edu.cibertec.vidasalud.notificaciones.domain.PlantillasNotificacion;
import pe.edu.cibertec.vidasalud.notificaciones.persistence.ConsultaNotificaciones;
import pe.edu.cibertec.vidasalud.notificaciones.persistence.NotificacionRepository;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

@Service
public class NotificacionService {

    private static final Logger log = LoggerFactory.getLogger(NotificacionService.class);

    private final NotificacionRepository repositorio;
    private final ConsultaNotificaciones consulta;
    private final Clock reloj;

    public NotificacionService(NotificacionRepository repositorio, ConsultaNotificaciones consulta, Clock reloj) {
        this.repositorio = repositorio;
        this.consulta = consulta;
        this.reloj = reloj;
    }

    /** Manejador de cada evento consumido. */
    public Mono<Void> procesar(EventoEnvelope evento) {
        return Mono.defer(() -> {
            DatosEvento datos = DatosEvento.desde(evento.tipo(), evento.recursoId(), evento.datos());
            Optional<String> texto = PlantillasNotificacion.generar(evento.tipo(), datos);
            if (texto.isEmpty()) {
                log.warn("evento sin plantilla ignorado eventId={} tipo={}", evento.eventId(), evento.tipo());
                return Mono.<Void>empty();
            }
            if (evento.recursoId() == null) {
                return Mono.<Void>error(new IllegalArgumentException("evento sin recursoId"));
            }
            Notificacion nueva = Notificacion.nueva(evento.eventId(), datos.pacienteId(), evento.tipo(),
                    evento.recursoId(), texto.get(), OffsetDateTime.now(reloj).truncatedTo(ChronoUnit.MICROS));
            return repositorio.save(nueva)
                    .doOnSuccess(n -> log.info(
                            "notificacion simulada enviada eventId={} tipo={} recursoId={} pacienteId={}",
                            evento.eventId(), evento.tipo(), evento.recursoId(), datos.pacienteId()))
                    .then();
        }).onErrorResume(DuplicateKeyException.class, e -> {
            log.info("notificacion repetida ignorada eventId={} tipo={}", evento.eventId(), evento.tipo());
            return Mono.empty();
        });
    }

    public Flux<NotificacionResponse> listar(FiltroNotificaciones filtro, ParametrosPagina pagina) {
        return consulta.buscar(filtro, pagina).map(NotificacionResponse::desde);
    }

    public Mono<Long> contar(FiltroNotificaciones filtro) {
        return consulta.contar(filtro);
    }

    public Mono<NotificacionResponse> obtener(UUID id) {
        return repositorio.findById(id).map(NotificacionResponse::desde)
                .switchIfEmpty(Mono.error(() -> new ErrorNegocio(HttpStatus.NOT_FOUND,
                        "NOTIFICACION_NO_ENCONTRADA", "No existe la notificación " + id + ".")));
    }
}
