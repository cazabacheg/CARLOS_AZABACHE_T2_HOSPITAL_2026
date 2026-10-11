package pe.edu.cibertec.vidasalud.notificaciones.persistence;

import java.util.UUID;
import org.springframework.data.repository.reactive.ReactiveCrudRepository;
import pe.edu.cibertec.vidasalud.notificaciones.domain.Notificacion;

public interface NotificacionRepository extends ReactiveCrudRepository<Notificacion, UUID> {
}
