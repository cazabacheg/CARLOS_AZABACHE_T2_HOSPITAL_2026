package pe.edu.cibertec.vidasalud.auditoria.service;

import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import pe.edu.cibertec.vidasalud.auditoria.api.EventoAuditoriaResponse;
import pe.edu.cibertec.vidasalud.auditoria.domain.FiltroAuditoria;
import pe.edu.cibertec.vidasalud.auditoria.domain.TipoRecurso;
import pe.edu.cibertec.vidasalud.auditoria.messaging.EventoAuditoriaMapper;
import pe.edu.cibertec.vidasalud.auditoria.persistence.EventoAuditoriaRepository;
import pe.edu.cibertec.vidasalud.auditoria.persistence.EventoAuditoriaRepositoryR2dbc;
import pe.edu.cibertec.vidasalud.commons.error.ErrorNegocio;
import pe.edu.cibertec.vidasalud.commons.messaging.EventoEnvelope;
import pe.edu.cibertec.vidasalud.commons.paginacion.ParametrosPagina;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

@Service
public class AuditoriaService {

    private static final Logger log = LoggerFactory.getLogger(AuditoriaService.class);

    private final EventoAuditoriaRepository repositorio;
    private final Clock reloj;

    public AuditoriaService(EventoAuditoriaRepository repositorio, Clock reloj) {
        this.repositorio = repositorio;
        this.reloj = reloj;
    }

    /** Manejador del consumidor: un sobre inválido o un fallo de BD se propaga para que se reintente y vaya a la DLQ. */
    public Mono<Void> registrar(EventoEnvelope sobre) {
        return Mono.fromCallable(() -> EventoAuditoriaMapper.aEntidad(sobre, OffsetDateTime.now(reloj)))
                .flatMap(repositorio::insertar)
                .doOnNext(e -> log.info("auditoria registrada eventId={} tipo={} recurso={}/{} {}->{}",
                        e.getEventId(), e.getTipoEvento(), e.getTipoRecurso(), e.getRecursoId(),
                        e.getEstadoAnterior(), e.getEstadoNuevo()))
                .onErrorResume(DuplicateKeyException.class, ex -> {
                    log.info("evento de auditoria repetido ignorado eventId={} tipo={}",
                            sobre.eventId(), sobre.tipo());
                    return Mono.empty();
                })
                .then();
    }

    public Flux<EventoAuditoriaResponse> buscar(FiltroAuditoria filtro, ParametrosPagina pagina) {
        return repositorio.buscar(filtro, pagina.aPageable(EventoAuditoriaRepositoryR2dbc.ORDEN_LISTADO))
                .map(EventoAuditoriaResponse::desde);
    }

    public Mono<Long> contar(FiltroAuditoria filtro) {
        return repositorio.contar(filtro);
    }

    public Mono<EventoAuditoriaResponse> obtener(UUID id) {
        return repositorio.buscarPorId(id)
                .map(EventoAuditoriaResponse::desde)
                .switchIfEmpty(Mono.error(() -> new ErrorNegocio(HttpStatus.NOT_FOUND,
                        "EVENTO_AUDITORIA_NO_ENCONTRADO", "No existe el evento de auditoria " + id + ".")));
    }

    public Flux<EventoAuditoriaResponse> historial(TipoRecurso tipoRecurso, UUID recursoId) {
        return repositorio.historial(tipoRecurso.name(), recursoId).map(EventoAuditoriaResponse::desde);
    }
}
