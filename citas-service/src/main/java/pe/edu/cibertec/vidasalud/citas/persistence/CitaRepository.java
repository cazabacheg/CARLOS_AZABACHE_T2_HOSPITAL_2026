package pe.edu.cibertec.vidasalud.citas.persistence;

import java.time.OffsetDateTime;
import java.util.UUID;
import org.springframework.data.r2dbc.repository.Query;
import org.springframework.data.repository.reactive.ReactiveCrudRepository;
import pe.edu.cibertec.vidasalud.citas.domain.Cita;
import reactor.core.publisher.Mono;

public interface CitaRepository extends ReactiveCrudRepository<Cita, UUID> {

    @Query("SELECT EXISTS (SELECT 1 FROM cita WHERE profesional_codigo = :profesionalCodigo "
            + "AND fecha_hora = :fechaHora AND estado IN ('PENDIENTE','CONFIRMADA'))")
    Mono<Boolean> existeActivaDeProfesional(String profesionalCodigo, OffsetDateTime fechaHora);

    @Query("SELECT EXISTS (SELECT 1 FROM cita WHERE paciente_id = :pacienteId "
            + "AND fecha_hora = :fechaHora AND estado IN ('PENDIENTE','CONFIRMADA'))")
    Mono<Boolean> existeActivaDePaciente(UUID pacienteId, OffsetDateTime fechaHora);
}
