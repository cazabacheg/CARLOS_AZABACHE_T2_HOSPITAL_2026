package pe.edu.cibertec.vidasalud.commons.paginacion;

import org.springframework.http.ResponseEntity;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/** Arma la respuesta de un listado: arreglo JSON + total en la cabecera {@code X-Total-Count}. */
public final class RespuestaPaginada {

    public static final String CABECERA_TOTAL = "X-Total-Count";

    private RespuestaPaginada() {
    }

    public static <T> Mono<ResponseEntity<Flux<T>>> de(Flux<T> pagina, Mono<Long> total) {
        return total.map(cantidad -> ResponseEntity.ok()
                .header(CABECERA_TOTAL, String.valueOf(cantidad))
                .body(pagina));
    }
}
