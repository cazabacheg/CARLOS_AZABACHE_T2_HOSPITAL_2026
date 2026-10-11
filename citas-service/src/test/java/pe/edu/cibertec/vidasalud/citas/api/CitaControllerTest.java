package pe.edu.cibertec.vidasalud.citas.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.reactive.WebFluxTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.reactive.server.WebTestClient;
import pe.edu.cibertec.vidasalud.citas.api.dto.CitaResponse;
import pe.edu.cibertec.vidasalud.citas.domain.Especialidad;
import pe.edu.cibertec.vidasalud.citas.domain.EstadoCita;
import pe.edu.cibertec.vidasalud.citas.domain.FiltroCitas;
import pe.edu.cibertec.vidasalud.citas.service.CitaService;
import pe.edu.cibertec.vidasalud.commons.error.ErrorNegocio;
import pe.edu.cibertec.vidasalud.commons.error.ManejadorErrores;
import pe.edu.cibertec.vidasalud.commons.paginacion.ParametrosPagina;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

@WebFluxTest(controllers = CitaController.class,
        properties = {"hospital.rabbit.enabled=false", "eureka.client.enabled=false"})
@Import(ManejadorErrores.class)
class CitaControllerTest {

    @Autowired
    WebTestClient cliente;
    @MockitoBean
    CitaService servicio;

    private static CitaResponse respuesta(UUID id) {
        OffsetDateTime fecha = OffsetDateTime.parse("2026-10-15T10:00:00-05:00");
        return new CitaResponse(id, "CIT-20261014-000001", UUID.randomUUID(), Especialidad.MEDICINA_GENERAL,
                "Medicina General", fecha, "MED-001", "Control", EstadoCita.PENDIENTE, fecha, fecha);
    }

    private static String cuerpo(String especialidad, String profesional) {
        return "{\"pacienteId\":\"" + UUID.randomUUID() + "\",\"especialidad\":\"" + especialidad
                + "\",\"fechaHora\":\"2026-10-15T10:00:00-05:00\",\"profesionalCodigo\":\"" + profesional
                + "\",\"motivo\":\"Control\"}";
    }

    @Test
    void postOkDevuelve201ConLocation() {
        UUID id = UUID.randomUUID();
        when(servicio.programar(any())).thenReturn(Mono.just(respuesta(id)));
        cliente.post().uri("/api/citas").contentType(MediaType.APPLICATION_JSON)
                .bodyValue(cuerpo("MEDICINA_GENERAL", "MED-001")).exchange()
                .expectStatus().isCreated()
                .expectHeader().valueEquals("Location", "/api/citas/" + id)
                .expectBody().jsonPath("$.estado").isEqualTo("PENDIENTE")
                .jsonPath("$.especialidadNombre").isEqualTo("Medicina General");
    }

    @Test
    void profesionalInvalidoDevuelve400() {
        cliente.post().uri("/api/citas").contentType(MediaType.APPLICATION_JSON)
                .bodyValue(cuerpo("MEDICINA_GENERAL", "med-1")).exchange()
                .expectStatus().isBadRequest()
                .expectHeader().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON)
                .expectBody().jsonPath("$.codigo").isEqualTo("VALIDACION_FALLIDA")
                .jsonPath("$.errores[0].campo").isEqualTo("profesionalCodigo");
        verifyNoInteractions(servicio);
    }

    @Test
    void especialidadDesconocidaDevuelve400() {
        cliente.post().uri("/api/citas").contentType(MediaType.APPLICATION_JSON)
                .bodyValue(cuerpo("ASTROLOGIA", "MED-001")).exchange()
                .expectStatus().isBadRequest()
                .expectBody().jsonPath("$.codigo").isEqualTo("VALIDACION_FALLIDA");
    }

    @Test
    void getInexistenteDevuelve404() {
        when(servicio.obtener(any())).thenReturn(Mono.error(
                new ErrorNegocio(HttpStatus.NOT_FOUND, "CITA_NO_ENCONTRADA", "No existe la cita")));
        cliente.get().uri("/api/citas/" + UUID.randomUUID()).exchange()
                .expectStatus().isNotFound()
                .expectBody().jsonPath("$.codigo").isEqualTo("CITA_NO_ENCONTRADA");
    }

    @Test
    void getConIdNoUuidDevuelve400() {
        cliente.get().uri("/api/citas/abc").exchange()
                .expectStatus().isBadRequest()
                .expectBody().jsonPath("$.codigo").isEqualTo("VALIDACION_FALLIDA");
    }

    @Test
    void patchProhibidoDevuelve409() {
        when(servicio.cambiarEstado(any(), any())).thenReturn(Mono.error(
                new ErrorNegocio(HttpStatus.CONFLICT, "TRANSICION_NO_PERMITIDA", "No se puede")));
        cliente.patch().uri("/api/citas/" + UUID.randomUUID() + "/estado").contentType(MediaType.APPLICATION_JSON)
                .bodyValue("{\"estado\":\"CONFIRMADA\"}").exchange()
                .expectStatus().isEqualTo(HttpStatus.CONFLICT)
                .expectBody().jsonPath("$.codigo").isEqualTo("TRANSICION_NO_PERMITIDA");
    }

    @Test
    void patchSinEstadoDevuelve400() {
        cliente.patch().uri("/api/citas/" + UUID.randomUUID() + "/estado").contentType(MediaType.APPLICATION_JSON)
                .bodyValue("{}").exchange()
                .expectStatus().isBadRequest()
                .expectBody().jsonPath("$.errores[0].campo").isEqualTo("estado");
    }

    @Test
    void listadoConTotalYFiltro() {
        when(servicio.listar(any(), any())).thenReturn(Flux.just(respuesta(UUID.randomUUID())));
        when(servicio.contar(any())).thenReturn(Mono.just(1L));
        cliente.get().uri("/api/citas?fecha=2026-10-15&pagina=0&tamano=10").exchange()
                .expectStatus().isOk()
                .expectHeader().valueEquals("X-Total-Count", "1")
                .expectBody().jsonPath("$.length()").isEqualTo(1);
        ArgumentCaptor<FiltroCitas> filtro = ArgumentCaptor.forClass(FiltroCitas.class);
        verify(servicio).listar(filtro.capture(), any(ParametrosPagina.class));
        assertThat(filtro.getValue().fecha()).isEqualTo(LocalDate.of(2026, 10, 15));
    }

    @Test
    void tamanoExcesivoDevuelve400() {
        cliente.get().uri("/api/citas?tamano=500").exchange()
                .expectStatus().isBadRequest()
                .expectBody().jsonPath("$.codigo").isEqualTo("VALIDACION_FALLIDA");
    }
}
