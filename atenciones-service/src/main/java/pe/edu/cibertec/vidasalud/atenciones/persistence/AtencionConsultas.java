package pe.edu.cibertec.vidasalud.atenciones.persistence;

import pe.edu.cibertec.vidasalud.atenciones.domain.Atencion;
import pe.edu.cibertec.vidasalud.atenciones.domain.FiltroAtenciones;
import org.springframework.data.domain.Sort;
import org.springframework.data.r2dbc.core.R2dbcEntityTemplate;
import org.springframework.data.relational.core.query.Criteria;
import org.springframework.data.relational.core.query.Query;
import org.springframework.stereotype.Component;
import pe.edu.cibertec.vidasalud.commons.paginacion.ParametrosPagina;
import pe.edu.cibertec.vidasalud.commons.reloj.RelojAutoConfiguration;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

@Component
public class AtencionConsultas {

    static final Sort ORDEN = Sort.by(Sort.Order.desc("fechaAtencion"), Sort.Order.asc("id"));

    private final R2dbcEntityTemplate template;

    public AtencionConsultas(R2dbcEntityTemplate template) {
        this.template = template;
    }

    public Flux<Atencion> buscar(FiltroAtenciones filtro, ParametrosPagina pagina) {
        return template.select(Atencion.class)
                .matching(Query.query(criterio(filtro)).with(pagina.aPageable(ORDEN)))
                .all();
    }

    public Mono<Long> contar(FiltroAtenciones filtro) {
        return template.count(Query.query(criterio(filtro)), Atencion.class);
    }

    static Criteria criterio(FiltroAtenciones f) {
        Criteria c = Criteria.empty();
        if (f.pacienteId() != null) {
            c = c.and("pacienteId").is(f.pacienteId());
        }
        if (f.citaId() != null) {
            c = c.and("citaId").is(f.citaId());
        }
        if (f.estado() != null) {
            c = c.and("estado").is(f.estado().name());
        }
        if (f.area() != null) {
            c = c.and("area").is(f.area().name());
        }
        if (f.fecha() != null) {
            var inicio = f.fecha().atStartOfDay(RelojAutoConfiguration.ZONA_HOSPITAL).toOffsetDateTime();
            var fin = f.fecha().plusDays(1).atStartOfDay(RelojAutoConfiguration.ZONA_HOSPITAL).toOffsetDateTime();
            c = c.and("fechaAtencion").greaterThanOrEquals(inicio).and("fechaAtencion").lessThan(fin);
        }
        return c;
    }
}
