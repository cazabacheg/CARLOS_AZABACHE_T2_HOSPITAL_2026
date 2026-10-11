package pe.edu.cibertec.vidasalud.notificaciones.persistence;

import java.time.OffsetDateTime;
import java.time.ZoneId;
import org.springframework.data.domain.Sort;
import org.springframework.data.r2dbc.core.R2dbcEntityTemplate;
import org.springframework.data.relational.core.query.Criteria;
import org.springframework.data.relational.core.query.Query;
import org.springframework.stereotype.Component;
import pe.edu.cibertec.vidasalud.commons.paginacion.ParametrosPagina;
import pe.edu.cibertec.vidasalud.notificaciones.domain.FiltroNotificaciones;
import pe.edu.cibertec.vidasalud.notificaciones.domain.Notificacion;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

@Component
public class ConsultaNotificaciones {

    public static final Sort ORDEN = Sort.by(Sort.Order.desc("fechaEnvio"), Sort.Order.asc("id"));
    private static final ZoneId ZONA_LIMA = ZoneId.of("America/Lima");

    private final R2dbcEntityTemplate template;

    public ConsultaNotificaciones(R2dbcEntityTemplate template) {
        this.template = template;
    }

    public Flux<Notificacion> buscar(FiltroNotificaciones filtro, ParametrosPagina pagina) {
        return template.select(Query.query(criterio(filtro)).with(pagina.aPageable(ORDEN)), Notificacion.class);
    }

    public Mono<Long> contar(FiltroNotificaciones filtro) {
        return template.count(Query.query(criterio(filtro)), Notificacion.class);
    }

    static Criteria criterio(FiltroNotificaciones f) {
        Criteria c = Criteria.empty();
        if (f.pacienteId() != null) {
            c = c.and("pacienteId").is(f.pacienteId());
        }
        if (f.tipoEvento() != null) {
            c = c.and("tipoEvento").is(f.tipoEvento());
        }
        if (f.fecha() != null) {
            OffsetDateTime inicio = f.fecha().atStartOfDay(ZONA_LIMA).toOffsetDateTime();
            OffsetDateTime fin = f.fecha().plusDays(1).atStartOfDay(ZONA_LIMA).toOffsetDateTime();
            c = c.and("fechaEnvio").greaterThanOrEquals(inicio).and("fechaEnvio").lessThan(fin);
        }
        return c;
    }
}
