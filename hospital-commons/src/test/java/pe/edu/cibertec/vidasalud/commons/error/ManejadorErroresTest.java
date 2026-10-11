package pe.edu.cibertec.vidasalud.commons.error;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.reactive.WebFluxTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

@WebFluxTest(ManejadorErroresTest.PruebaController.class)
@Import({ManejadorErrores.class, ManejadorErroresTest.PruebaController.class})
class ManejadorErroresTest {

    record Entrada(@NotBlank String nombres) {
    }

    @RestController
    static class PruebaController {
        @GetMapping("/negocio")
        Mono<String> negocio() {
            return Mono.error(new ErrorNegocio(HttpStatus.CONFLICT, "PACIENTE_DUPLICADO", "Ya existe."));
        }

        @GetMapping("/inesperado")
        Mono<String> inesperado() {
            return Mono.error(new IllegalStateException("secreto interno"));
        }

        @GetMapping("/uuid/{id}")
        Mono<String> uuid(@PathVariable UUID id) {
            return Mono.just(id.toString());
        }

        @PostMapping("/validar")
        Mono<String> validar(@Valid @RequestBody Entrada entrada) {
            return Mono.just(entrada.nombres());
        }
    }

    @Autowired
    WebTestClient cliente;

    @Test
    void errorDeNegocioSeTraduceAProblemJsonConCodigoYTimestamp() {
        cliente.get().uri("/negocio").exchange()
                .expectStatus().isEqualTo(HttpStatus.CONFLICT)
                .expectHeader().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON)
                .expectBody()
                .jsonPath("$.status").isEqualTo(409)
                .jsonPath("$.codigo").isEqualTo("PACIENTE_DUPLICADO")
                .jsonPath("$.detail").isEqualTo("Ya existe.")
                .jsonPath("$.instance").isEqualTo("/negocio")
                .jsonPath("$.timestamp").isNotEmpty();
    }

    @Test
    void validacionDelCuerpoDevuelve400ConListaDeErrores() {
        cliente.post().uri("/validar").contentType(MediaType.APPLICATION_JSON)
                .bodyValue("{\"nombres\":\"\"}").exchange()
                .expectStatus().isBadRequest()
                .expectBody()
                .jsonPath("$.codigo").isEqualTo("VALIDACION_FALLIDA")
                .jsonPath("$.errores[0].campo").isEqualTo("nombres")
                .jsonPath("$.errores[0].mensaje").isNotEmpty();
    }

    @Test
    void jsonIlegibleDevuelve400() {
        cliente.post().uri("/validar").contentType(MediaType.APPLICATION_JSON)
                .bodyValue("{no es json").exchange()
                .expectStatus().isBadRequest()
                .expectBody().jsonPath("$.codigo").isEqualTo("VALIDACION_FALLIDA");
    }

    @Test
    void idQueNoEsUuidDevuelve400() {
        cliente.get().uri("/uuid/no-es-uuid").exchange()
                .expectStatus().isBadRequest()
                .expectBody().jsonPath("$.codigo").isEqualTo("VALIDACION_FALLIDA");
    }

    @Test
    void rutaInexistenteDevuelve404ConFormatoUnico() {
        cliente.get().uri("/no-existe").exchange()
                .expectStatus().isNotFound()
                .expectBody().jsonPath("$.codigo").isEqualTo("RECURSO_NO_ENCONTRADO");
    }

    @Test
    void errorInesperadoDevuelve500SinFiltrarDetalleInterno() {
        cliente.get().uri("/inesperado").exchange()
                .expectStatus().is5xxServerError()
                .expectBody()
                .jsonPath("$.codigo").isEqualTo("ERROR_INTERNO")
                .jsonPath("$.detail").value(d -> org.assertj.core.api.Assertions.assertThat(d.toString())
                        .doesNotContain("secreto interno"));
    }
}
