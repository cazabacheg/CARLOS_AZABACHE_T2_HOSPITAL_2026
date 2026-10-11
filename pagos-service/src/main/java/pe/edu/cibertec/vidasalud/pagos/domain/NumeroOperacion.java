package pe.edu.cibertec.vidasalud.pagos.domain;

import java.time.Clock;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import pe.edu.cibertec.vidasalud.commons.reloj.RelojAutoConfiguration;

public final class NumeroOperacion {

    public static final String PREFIJO = "OP-";

    private NumeroOperacion() {
    }

    public static String formatear(LocalDate fechaLima, long secuencia) {
        return PREFIJO + fechaLima.format(DateTimeFormatter.BASIC_ISO_DATE) + "-" + String.format("%06d", secuencia);
    }

    public static LocalDate fechaEnLima(Clock reloj) {
        return LocalDate.ofInstant(reloj.instant(), RelojAutoConfiguration.ZONA_HOSPITAL);
    }
}
