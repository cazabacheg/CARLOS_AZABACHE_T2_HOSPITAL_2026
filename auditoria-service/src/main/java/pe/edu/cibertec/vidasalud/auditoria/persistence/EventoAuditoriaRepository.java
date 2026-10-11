package pe.edu.cibertec.vidasalud.auditoria.persistence;

import java.util.UUID;
import org.springframework.data.domain.Pageable;
import pe.edu.cibertec.vidasalud.auditoria.domain.FiltroAuditoria;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/** Solo inserción y consultas; deliberadamente no extiende interfaces de Spring Data (D-40). */
public interface EventoAuditoriaRepository {

    Mono<EventoAuditoria> insertar(EventoAuditoria evento);

    Mono<EventoAuditoria> buscarPorId(UUID id);

    Flux<EventoAuditoria> buscar(FiltroAuditoria filtro, Pageable pagina);

    Mono<Long> contar(FiltroAuditoria filtro);

    Flux<EventoAuditoria> historial(String tipoRecurso, UUID recursoId);
}
