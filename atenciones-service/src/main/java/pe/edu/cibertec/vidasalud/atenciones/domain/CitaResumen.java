package pe.edu.cibertec.vidasalud.atenciones.domain;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.time.OffsetDateTime;
import java.util.UUID;

@JsonIgnoreProperties(ignoreUnknown = true)
public record CitaResumen(UUID id, UUID pacienteId, String estado, OffsetDateTime fechaHora) {

    public static final String ESTADO_CONFIRMADA = "CONFIRMADA";

    public boolean estaConfirmada() {
        return ESTADO_CONFIRMADA.equals(estado);
    }
}
