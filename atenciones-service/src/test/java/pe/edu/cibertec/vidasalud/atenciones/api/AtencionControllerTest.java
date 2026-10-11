package pe.edu.cibertec.vidasalud.atenciones.api;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.time.OffsetDateTime;
import java.util.UUID;
import pe.edu.cibertec.vidasalud.atenciones.api.dto.AtencionResponse;
import pe.edu.cibertec.vidasalud.atenciones.domain.AreaAtencion;
import pe.edu.cibertec.vidasalud.atenciones.domain.ErroresAtencion;
import pe.edu.cibertec.vidasalud.atenciones.domain.EstadoAtencion;
import pe.edu.cibertec.vidasalud.atenciones.service.AtencionService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.reactive.WebFluxTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.reactive.server.WebTestClient;
import pe.edu.cibertec.vidasalud.commons.error.ManejadorErrores;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

@WebFluxTest(controllers = AtencionController.class,
        properties = {"hospital.rabbit.enabled=false", "eureka.client.enabled=false"})
@Import(ManejadorErrores.class)
@ActiveProfiles("test")
class AtencionControllerTest {

    @Autowired WebTestClient cliente;
    @MockitoBean AtencionService servicio;

    private static AtencionResponse respuesta(UUID id) {
        OffsetDateTime ahora = OffsetDateTime.parse("2026-10-14T09:00:00-05:00");
        return new AtencionResponse(id, "ATE-20261014-000001", UUID.randomUUID(), UUID.randomUUID(), ahora,
                AreaAtencion.CONSULTA_EXTERNA, "Consulta Externa", EstadoAtencion.REGISTRADA, ahora, ahora);
    }

    @Test
    void postValidoDevuelve201() {
        UUID id = UUID.randomUUID();
        when(servicio.registrar(any())).thenReturn(Mono.just(respuesta(id)));
        cliente.post().uri("/api/atenciones").contentType(MediaType.APPLICATION_JSON)
                .bodyValue("{\"citaId\":\"" + UUID.randomUUID() + "\",\"area\":\"CONSULTA_EXTERNA\"}")
                .exchange()
                .expectStatus().isCreated()
                .expectHeader().valueEquals("Location", "/api/atenciones/" + id)
                .expectBody()
                .jsonPath("$.codigo").isEqualTo("ATE-20261014-000001")
                .jsonPath("$.estado").isEqualTo("REGISTRADA")
                .jsonPath("$.areaNombre").isEqualTo("Consulta Externa");
    }

    @Test
    void postConAreaDesconocida() {
        cliente.post().uri("/api/atenciones").contentType(MediaType.APPLICATION_JSON)
                .bodyValue("{\"citaId\":\"" + UUID.randomUUID() + "\",\"area\":\"QUIROFANO\"}")
                .exchange()
                .expectStatus().isBadRequest()
                .expectHeader().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON)
                .expectBody().jsonPath("$.codigo").isEqualTo("VALIDACION_FALLIDA");
        verifyNoInteractions(servicio);
    }

    @Test
    void postSinCitaId() {
        cliente.post().uri("/api/atenciones").contentType(MediaType.APPLICATION_JSON)
                .bodyValue("{\"area\":\"TRIAJE\"}")
                .exchange()
                .expectStatus().isBadRequest()
                .expectBody()
                .jsonPath("$.codigo").isEqualTo("VALIDACION_FALLIDA")
                .jsonPath("$.errores[0].campo").isEqualTo("citaId");
    }

    @Test
    void getInexistente() {
        UUID id = UUID.randomUUID();
        when(servicio.obtener(id)).thenReturn(Mono.error(ErroresAtencion.atencionNoEncontrada(id)));
        cliente.get().uri("/api/atenciones/{id}", id).exchange()
                .expectStatus().isNotFound()
                .expectBody().jsonPath("$.codigo").isEqualTo("ATENCION_NO_ENCONTRADA");
    }

    @Test
    void patchProhibido() {
        UUID id = UUID.randomUUID();
        when(servicio.cambiarEstado(any(), any())).thenReturn(Mono.error(
                ErroresAtencion.transicionNoPermitida(EstadoAtencion.FINALIZADA, EstadoAtencion.ANULADA)));
        cliente.patch().uri("/api/atenciones/{id}/estado", id).contentType(MediaType.APPLICATION_JSON)
                .bodyValue("{\"estado\":\"ANULADA\"}")
                .exchange()
                .expectStatus().isEqualTo(HttpStatus.CONFLICT)
                .expectBody().jsonPath("$.codigo").isEqualTo("TRANSICION_NO_PERMITIDA");
    }

    @Test
    void listadoIncluyeTotal() {
        when(servicio.listar(any(), any())).thenReturn(Flux.just(respuesta(UUID.randomUUID())));
        when(servicio.contar(any())).thenReturn(Mono.just(7L));
        cliente.get().uri("/api/atenciones?estado=REGISTRADA").exchange()
                .expectStatus().isOk()
                .expectHeader().valueEquals("X-Total-Count", "7")
                .expectBody().jsonPath("$[0].codigo").isEqualTo("ATE-20261014-000001");
    }

    @Test
    void tamanoCeroEsInvalido() {
        cliente.get().uri("/api/atenciones?tamano=0").exchange()
                .expectStatus().isBadRequest()
                .expectBody().jsonPath("$.codigo").isEqualTo("VALIDACION_FALLIDA");
    }

    @Test
    void idMalFormado() {
        cliente.get().uri("/api/atenciones/no-es-uuid").exchange()
                .expectStatus().isBadRequest()
                .expectBody().jsonPath("$.codigo").isEqualTo("VALIDACION_FALLIDA");
    }
}
