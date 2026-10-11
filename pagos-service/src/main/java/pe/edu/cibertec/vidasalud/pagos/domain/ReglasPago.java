package pe.edu.cibertec.vidasalud.pagos.domain;

import java.math.BigDecimal;
import java.math.RoundingMode;
import org.springframework.http.HttpStatus;
import pe.edu.cibertec.vidasalud.commons.error.ErrorNegocio;

public final class ReglasPago {

    public static final BigDecimal MAXIMO = new BigDecimal("100000.00");
    private static final String CODIGO = "MONTO_INVALIDO";

    private ReglasPago() {
    }

    public static void validarMonto(BigDecimal monto) {
        if (monto == null) {
            throw ErrorNegocio.validacion("monto", "no debe ser nulo");
        }
        if (monto.signum() <= 0) {
            throw new ErrorNegocio(HttpStatus.BAD_REQUEST, CODIGO, "El monto debe ser mayor que cero.");
        }
        if (monto.stripTrailingZeros().scale() > 2) {
            throw new ErrorNegocio(HttpStatus.BAD_REQUEST, CODIGO, "El monto admite como máximo 2 decimales.");
        }
        if (monto.compareTo(MAXIMO) > 0) {
            throw new ErrorNegocio(HttpStatus.BAD_REQUEST, CODIGO, "El monto excede el máximo permitido (100000.00).");
        }
    }

    /** Solo se llama tras {@link #validarMonto}, por eso nunca redondea. */
    public static BigDecimal normalizar(BigDecimal monto) {
        return monto.setScale(2, RoundingMode.UNNECESSARY);
    }
}
