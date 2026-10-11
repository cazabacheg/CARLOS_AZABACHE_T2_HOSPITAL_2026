package pe.edu.cibertec.vidasalud.pagos.persistence;

import static pe.edu.cibertec.vidasalud.commons.reloj.RelojAutoConfiguration.ZONA_HOSPITAL;

import java.time.OffsetDateTime;
import org.springframework.data.domain.Sort;
import org.springframework.data.r2dbc.core.R2dbcEntityTemplate;
import org.springframework.data.relational.core.query.Criteria;
import org.springframework.data.relational.core.query.Query;
import org.springframework.stereotype.Component;
import pe.edu.cibertec.vidasalud.commons.paginacion.ParametrosPagina;
import pe.edu.cibertec.vidasalud.pagos.domain.FiltroPagos;
import pe.edu.cibertec.vidasalud.pagos.domain.Pago;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

@Component
public class PagoConsultas {

    static final Sort ORDEN = Sort.by(Sort.Order.desc("fechaPago"), Sort.Order.asc("id"));

    private final R2dbcEntityTemplate template;

    public PagoConsultas(R2dbcEntityTemplate template) {
        this.template = template;
    }

    public Flux<Pago> buscar(FiltroPagos filtro, ParametrosPagina pagina) {
        return template.select(Pago.class)
                .matching(Query.query(criterio(filtro)).with(pagina.aPageable(ORDEN)))
                .all();
    }

    public Mono<Long> contar(FiltroPagos filtro) {
        return template.count(Query.query(criterio(filtro)), Pago.class);
    }

    static Criteria criterio(FiltroPagos f) {
        Criteria c = Criteria.empty();
        if (f.pacienteId() != null) {
            c = c.and("pacienteId").is(f.pacienteId());
        }
        if (f.citaId() != null) {
            c = c.and("citaId").is(f.citaId());
        }
        if (f.numeroOperacion() != null) {
            c = c.and("numeroOperacion").is(f.numeroOperacion().trim());
        }
        if (f.estado() != null) {
            c = c.and("estado").is(f.estado().name());
        }
        if (f.medioPago() != null) {
            c = c.and("medioPago").is(f.medioPago().name());
        }
        if (f.fecha() != null) {
            OffsetDateTime inicio = f.fecha().atStartOfDay(ZONA_HOSPITAL).toOffsetDateTime();
            OffsetDateTime fin = f.fecha().plusDays(1).atStartOfDay(ZONA_HOSPITAL).toOffsetDateTime();
            c = c.and("fechaPago").greaterThanOrEquals(inicio).and("fechaPago").lessThan(fin);
        }
        return c;
    }
}
