package pe.edu.cibertec.vidasalud.pacientes.persistence;

import java.util.UUID;
import org.springframework.data.repository.reactive.ReactiveCrudRepository;
import pe.edu.cibertec.vidasalud.pacientes.domain.Paciente;
import pe.edu.cibertec.vidasalud.pacientes.domain.TipoDocumento;
import reactor.core.publisher.Mono;

public interface PacienteRepository extends ReactiveCrudRepository<Paciente, UUID> {

    Mono<Boolean> existsByTipoDocumentoAndNumeroDocumento(TipoDocumento tipoDocumento, String numeroDocumento);
}
