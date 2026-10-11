package pe.edu.cibertec.vidasalud.commons.paginacion;

import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import pe.edu.cibertec.vidasalud.commons.error.ErrorNegocio;

/** Parametros {@code pagina} (desde 0) y {@code tamano} (1-100) de los listados. */
public record ParametrosPagina(int pagina, int tamano) {

    public static final int TAMANO_POR_DEFECTO = 20;
    public static final int TAMANO_MAXIMO = 100;

    public static ParametrosPagina de(Integer pagina, Integer tamano) {
        int p = pagina == null ? 0 : pagina;
        int t = tamano == null ? TAMANO_POR_DEFECTO : tamano;
        if (p < 0) {
            throw ErrorNegocio.validacion("pagina", "debe ser mayor o igual a 0");
        }
        if (t < 1 || t > TAMANO_MAXIMO) {
            throw ErrorNegocio.validacion("tamano", "debe estar entre 1 y " + TAMANO_MAXIMO);
        }
        return new ParametrosPagina(p, t);
    }

    public Pageable aPageable(Sort orden) {
        return PageRequest.of(pagina, tamano, orden);
    }
}
