package pe.edu.cibertec.vidasalud.auditoria.persistence;

import java.util.UUID;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.r2dbc.core.R2dbcEntityTemplate;
import org.springframework.data.relational.core.query.Criteria;
import org.springframework.data.relational.core.query.Query;
import org.springframework.stereotype.Repository;
import pe.edu.cibertec.vidasalud.auditoria.domain.FiltroAuditoria;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

@Repository
public class EventoAuditoriaRepositoryR2dbc implements EventoAuditoriaRepository {

    public static final Sort ORDEN_LISTADO = Sort.by(Sort.Order.desc("fechaEvento"), Sort.Order.asc("id"));
    static final Sort ORDEN_HISTORIAL = Sort.by(Sort.Order.asc("fechaEvento"),
            Sort.Order.asc("fechaRegistro"), Sort.Order.asc("id"));

    private final R2dbcEntityTemplate template;

    public EventoAuditoriaRepositoryR2dbc(R2dbcEntityTemplate template) {
        this.template = template;
    }

    @Override
    public Mono<EventoAuditoria> insertar(EventoAuditoria evento) {
        return template.insert(evento);
    }

    @Override
    public Mono<EventoAuditoria> buscarPorId(UUID id) {
        return template.selectOne(Query.query(Criteria.where("id").is(id)), EventoAuditoria.class);
    }

    @Override
    public Flux<EventoAuditoria> buscar(FiltroAuditoria filtro, Pageable pagina) {
        return template.select(Query.query(criterio(filtro)).with(pagina), EventoAuditoria.class);
    }

    @Override
    public Mono<Long> contar(FiltroAuditoria filtro) {
        return template.count(Query.query(criterio(filtro)), EventoAuditoria.class);
    }

    @Override
    public Flux<EventoAuditoria> historial(String tipoRecurso, UUID recursoId) {
        Criteria criterio = Criteria.where("tipoRecurso").is(tipoRecurso).and("recursoId").is(recursoId);
        return template.select(Query.query(criterio).sort(ORDEN_HISTORIAL), EventoAuditoria.class);
    }

    static Criteria criterio(FiltroAuditoria f) {
        Criteria c = Criteria.empty();
        if (f.tipoRecurso() != null) {
            c = c.and("tipoRecurso").is(f.tipoRecurso().name());
        }
        if (f.recursoId() != null) {
            c = c.and("recursoId").is(f.recursoId());
        }
        if (f.tipoEvento() != null) {
            c = c.and("tipoEvento").is(f.tipoEvento());
        }
        if (f.origen() != null) {
            c = c.and("origen").is(f.origen());
        }
        if (f.desde() != null) {
            c = c.and("fechaEvento").greaterThanOrEquals(f.desde());
        }
        if (f.hasta() != null) {
            c = c.and("fechaEvento").lessThanOrEquals(f.hasta());
        }
        return c;
    }
}
