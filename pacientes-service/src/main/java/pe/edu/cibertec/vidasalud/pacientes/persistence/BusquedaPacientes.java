package pe.edu.cibertec.vidasalud.pacientes.persistence;

import org.springframework.data.domain.Sort;
import org.springframework.data.r2dbc.core.R2dbcEntityTemplate;
import org.springframework.data.relational.core.query.Criteria;
import org.springframework.data.relational.core.query.Query;
import org.springframework.stereotype.Component;
import pe.edu.cibertec.vidasalud.commons.paginacion.ParametrosPagina;
import pe.edu.cibertec.vidasalud.pacientes.domain.FiltroPacientes;
import pe.edu.cibertec.vidasalud.pacientes.domain.Paciente;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

@Component
public class BusquedaPacientes {

    static final Sort ORDEN = Sort.by("apellidos", "nombres", "id");

    private final R2dbcEntityTemplate template;

    public BusquedaPacientes(R2dbcEntityTemplate template) {
        this.template = template;
    }

    public Flux<Paciente> buscar(FiltroPacientes filtro, ParametrosPagina pagina) {
        Query consulta = Query.query(criterio(filtro)).with(pagina.aPageable(ORDEN));
        return template.select(Paciente.class).matching(consulta).all();
    }

    public Mono<Long> contar(FiltroPacientes filtro) {
        return template.count(Query.query(criterio(filtro)), Paciente.class);
    }

    static Criteria criterio(FiltroPacientes filtro) {
        Criteria criterio = Criteria.empty();
        if (filtro.estado() != null) {
            criterio = criterio.and("estado").is(filtro.estado().name());
        }
        if (filtro.tipoDocumento() != null) {
            criterio = criterio.and("tipoDocumento").is(filtro.tipoDocumento().name())
                    .and("numeroDocumento").is(filtro.numeroDocumento());
        }
        if (filtro.apellidos() != null) {
            criterio = criterio.and("apellidos").like("%" + escaparLike(filtro.apellidos()) + "%").ignoreCase(true);
        }
        return criterio;
    }

    static String escaparLike(String valor) {
        return valor.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
    }
}
