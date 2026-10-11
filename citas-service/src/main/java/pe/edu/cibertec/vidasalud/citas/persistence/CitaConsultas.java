package pe.edu.cibertec.vidasalud.citas.persistence;

import java.time.OffsetDateTime;
import org.springframework.data.domain.Sort;
import org.springframework.data.r2dbc.core.R2dbcEntityTemplate;
import org.springframework.data.relational.core.query.Criteria;
import org.springframework.data.relational.core.query.Query;
import org.springframework.stereotype.Component;
import pe.edu.cibertec.vidasalud.citas.domain.Cita;
import pe.edu.cibertec.vidasalud.citas.domain.FiltroCitas;
import pe.edu.cibertec.vidasalud.citas.domain.ZonaHospital;
import pe.edu.cibertec.vidasalud.commons.paginacion.ParametrosPagina;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

@Component
public class CitaConsultas {

    public static final Sort ORDEN = Sort.by("fechaHora", "id");

    private final R2dbcEntityTemplate template;

    public CitaConsultas(R2dbcEntityTemplate template) {
        this.template = template;
    }

    public Flux<Cita> listar(FiltroCitas filtro, ParametrosPagina pagina) {
        Query consulta = Query.query(criterio(filtro)).with(pagina.aPageable(ORDEN));
        return template.select(Cita.class).matching(consulta).all();
    }

    public Mono<Long> contar(FiltroCitas filtro) {
        return template.count(Query.query(criterio(filtro)), Cita.class);
    }

    static Criteria criterio(FiltroCitas f) {
        Criteria criterio = Criteria.empty();
        if (f.pacienteId() != null) {
            criterio = criterio.and("pacienteId").is(f.pacienteId());
        }
        if (f.estado() != null) {
            criterio = criterio.and("estado").is(f.estado().name());
        }
        if (f.especialidad() != null) {
            criterio = criterio.and("especialidad").is(f.especialidad().name());
        }
        if (f.profesionalCodigo() != null) {
            criterio = criterio.and("profesionalCodigo").is(f.profesionalCodigo());
        }
        if (f.fecha() != null) {
            OffsetDateTime desde = f.fecha().atStartOfDay(ZonaHospital.LIMA).toOffsetDateTime();
            OffsetDateTime hasta = f.fecha().plusDays(1).atStartOfDay(ZonaHospital.LIMA).toOffsetDateTime();
            criterio = criterio.and("fechaHora").greaterThanOrEquals(desde)
                    .and("fechaHora").lessThan(hasta);
        }
        return criterio;
    }
}
