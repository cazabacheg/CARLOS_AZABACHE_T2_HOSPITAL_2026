package pe.edu.cibertec.vidasalud.pagos.domain;

import java.util.regex.Pattern;
import org.springframework.http.HttpStatus;
import pe.edu.cibertec.vidasalud.commons.error.ErrorNegocio;

public final class ClaveIdempotencia {

    public static final String CABECERA = "Idempotency-Key";
    public static final String CABECERA_REPETIDA = "Idempotent-Replayed";
    private static final Pattern PATRON = Pattern.compile("^[A-Za-z0-9-]{8,64}$");

    private ClaveIdempotencia() {
    }

    public static String validar(String clave) {
        if (clave == null || !PATRON.matcher(clave).matches()) {
            throw new ErrorNegocio(HttpStatus.BAD_REQUEST, "CLAVE_IDEMPOTENCIA_REQUERIDA",
                    "La cabecera Idempotency-Key es obligatoria (8–64 caracteres alfanuméricos o guiones).");
        }
        return clave;
    }
}
