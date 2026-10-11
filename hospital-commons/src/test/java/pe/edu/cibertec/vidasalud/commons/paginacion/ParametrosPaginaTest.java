package pe.edu.cibertec.vidasalud.commons.paginacion;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import org.springframework.data.domain.Sort;
import org.springframework.http.ResponseEntity;
import pe.edu.cibertec.vidasalud.commons.error.ErrorNegocio;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

class ParametrosPaginaTest {

    @Test
    void usaValoresPorDefecto() {
        ParametrosPagina p = ParametrosPagina.de(null, null);
        assertThat(p.pagina()).isZero();
        assertThat(p.tamano()).isEqualTo(20);
    }

    @Test
    void aceptaLimites() {
        assertThat(ParametrosPagina.de(3, 100).tamano()).isEqualTo(100);
        assertThat(ParametrosPagina.de(0, 1).tamano()).isEqualTo(1);
    }

    @Test
    void rechazaTamanoFueraDeRango() {
        assertThatThrownBy(() -> ParametrosPagina.de(0, 101)).isInstanceOf(ErrorNegocio.class);
        assertThatThrownBy(() -> ParametrosPagina.de(0, 0)).isInstanceOf(ErrorNegocio.class);
    }

    @Test
    void rechazaPaginaNegativa() {
        assertThatThrownBy(() -> ParametrosPagina.de(-1, 10))
                .isInstanceOf(ErrorNegocio.class)
                .hasMessageContaining("no son validos");
    }

    @Test
    void conviertePageableConOrden() {
        var pageable = ParametrosPagina.de(2, 10).aPageable(Sort.by("fechaHora", "id"));
        assertThat(pageable.getPageNumber()).isEqualTo(2);
        assertThat(pageable.getOffset()).isEqualTo(20);
        assertThat(pageable.getSort()).isEqualTo(Sort.by("fechaHora", "id"));
    }

    @Test
    void respuestaIncluyeCabeceraDeTotal() {
        Mono<ResponseEntity<Flux<String>>> respuesta = RespuestaPaginada.de(Flux.just("a", "b"), Mono.just(57L));
        StepVerifier.create(respuesta)
                .assertNext(r -> {
                    assertThat(r.getHeaders().getFirst("X-Total-Count")).isEqualTo("57");
                    StepVerifier.create(r.getBody()).expectNext("a", "b").verifyComplete();
                })
                .verifyComplete();
    }
}
