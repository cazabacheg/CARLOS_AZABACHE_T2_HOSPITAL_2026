package pe.edu.cibertec.vidasalud.atenciones.persistence;

import java.util.UUID;
import pe.edu.cibertec.vidasalud.atenciones.domain.Atencion;
import org.springframework.data.r2dbc.repository.Query;
import org.springframework.data.repository.reactive.ReactiveCrudRepository;
import reactor.core.publisher.Mono;

public interface AtencionRepository extends ReactiveCrudRepository<Atencion, UUID> {

    @Query("SELECT EXISTS (SELECT 1 FROM atencion WHERE cita_id = :citaId AND estado <> 'ANULADA')")
    Mono<Boolean> existsActivaByCitaId(UUID citaId);
}
