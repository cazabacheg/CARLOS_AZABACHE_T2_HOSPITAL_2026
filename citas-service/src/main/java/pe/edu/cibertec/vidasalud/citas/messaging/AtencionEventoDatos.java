package pe.edu.cibertec.vidasalud.citas.messaging;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.util.UUID;

@JsonIgnoreProperties(ignoreUnknown = true)
public record AtencionEventoDatos(UUID atencionId, UUID citaId) {
}
