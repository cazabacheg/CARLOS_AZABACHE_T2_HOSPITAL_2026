package pe.edu.cibertec.vidasalud.commons.error;

import java.util.List;
import org.springframework.http.HttpStatus;

/**
 * Unica excepcion que usan los servicios para errores esperados de negocio.
 * Se traduce a una respuesta {@code application/problem+json}.
 */
public class ErrorNegocio extends RuntimeException {

    private final HttpStatus status;
    private final String codigo;
    private final List<ErrorCampo> errores;

    public ErrorNegocio(HttpStatus status, String codigo, String detalle) {
        this(status, codigo, detalle, List.of());
    }

    public ErrorNegocio(HttpStatus status, String codigo, String detalle, List<ErrorCampo> errores) {
        // Sin stack trace: es un error esperado, no una falla del programa.
        super(detalle, null, false, false);
        this.status = status;
        this.codigo = codigo;
        this.errores = List.copyOf(errores);
    }

    /** Atajo para errores {@code 400 VALIDACION_FALLIDA} de un solo campo. */
    public static ErrorNegocio validacion(String campo, String mensaje) {
        return new ErrorNegocio(HttpStatus.BAD_REQUEST, "VALIDACION_FALLIDA",
                "Los datos enviados no son validos.", List.of(new ErrorCampo(campo, mensaje)));
    }

    public HttpStatus getStatus() {
        return status;
    }

    public String getCodigo() {
        return codigo;
    }

    public String getDetalle() {
        return getMessage();
    }

    public List<ErrorCampo> getErrores() {
        return errores;
    }
}
