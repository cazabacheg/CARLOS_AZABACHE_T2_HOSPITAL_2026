package pe.edu.cibertec.vidasalud.pagos.client;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.util.UUID;

@JsonIgnoreProperties(ignoreUnknown = true)
public record PacienteResumen(UUID id, String estado) {
}
