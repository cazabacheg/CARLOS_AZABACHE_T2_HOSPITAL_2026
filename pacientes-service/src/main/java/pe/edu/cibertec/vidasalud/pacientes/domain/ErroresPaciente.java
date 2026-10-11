package pe.edu.cibertec.vidasalud.pacientes.domain;

import java.util.UUID;
import org.springframework.http.HttpStatus;
import pe.edu.cibertec.vidasalud.commons.error.ErrorNegocio;

public final class ErroresPaciente {

    public static final String PACIENTE_DUPLICADO = "PACIENTE_DUPLICADO";
    public static final String PACIENTE_NO_ENCONTRADO = "PACIENTE_NO_ENCONTRADO";
    public static final String TRANSICION_NO_PERMITIDA = "TRANSICION_NO_PERMITIDA";

    private ErroresPaciente() {
    }

    public static ErrorNegocio duplicado(TipoDocumento tipo, String numero) {
        return new ErrorNegocio(HttpStatus.CONFLICT, PACIENTE_DUPLICADO,
                "Ya existe un paciente con el documento " + tipo + " " + numero + ".");
    }

    public static ErrorNegocio noEncontrado(UUID id) {
        return new ErrorNegocio(HttpStatus.NOT_FOUND, PACIENTE_NO_ENCONTRADO, "No existe el paciente " + id + ".");
    }

    public static ErrorNegocio transicionNoPermitida(EstadoPaciente estado) {
        return new ErrorNegocio(HttpStatus.CONFLICT, TRANSICION_NO_PERMITIDA, "El paciente ya está " + estado + ".");
    }
}
