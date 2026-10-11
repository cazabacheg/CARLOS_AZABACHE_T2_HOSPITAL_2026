package pe.edu.cibertec.vidasalud.pagos.persistence;

import java.util.UUID;
import org.springframework.data.repository.reactive.ReactiveCrudRepository;
import pe.edu.cibertec.vidasalud.pagos.domain.Pago;
import reactor.core.publisher.Mono;

public interface PagoRepository extends ReactiveCrudRepository<Pago, UUID> {

    Mono<Pago> findByClaveIdempotencia(String claveIdempotencia);
}
