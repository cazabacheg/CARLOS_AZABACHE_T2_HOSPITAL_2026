package pe.edu.cibertec.vidasalud.pacientes.domain;

import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;
import pe.edu.cibertec.vidasalud.commons.error.ErrorNegocio;

public final class ReglasDocumento {

    private static final Map<TipoDocumento, Pattern> FORMATOS = Map.of(
            TipoDocumento.DNI, Pattern.compile("^\\d{8}$"),
            TipoDocumento.CE, Pattern.compile("^[A-Z0-9]{9,12}$"),
            TipoDocumento.PASAPORTE, Pattern.compile("^[A-Z0-9]{6,12}$"));

    private ReglasDocumento() {
    }

    public static String normalizar(String numero) {
        return numero == null ? null : numero.trim().toUpperCase(Locale.ROOT);
    }

    /** Devuelve el número normalizado o lanza 400 si no cumple el formato de su tipo. */
    public static String validar(TipoDocumento tipo, String numero) {
        String normalizado = normalizar(numero);
        if (normalizado == null || !FORMATOS.get(tipo).matcher(normalizado).matches()) {
            throw ErrorNegocio.validacion("numeroDocumento", "formato inválido para " + tipo);
        }
        return normalizado;
    }
}
