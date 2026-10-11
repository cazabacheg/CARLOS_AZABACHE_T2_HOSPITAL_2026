package pe.edu.cibertec.vidasalud.pacientes.api;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.reactive.WebFluxTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.reactive.server.WebTestClient;
import pe.edu.cibertec.vidasalud.commons.error.ErrorNegocio;
import pe.edu.cibertec.vidasalud.commons.error.ManejadorErrores;
import pe.edu.cibertec.vidasalud.commons.paginacion.ParametrosPagina;
import pe.edu.cibertec.vidasalud.pacientes.api.dto.PacienteResponse;
import pe.edu.cibertec.vidasalud.pacientes.domain.ErroresPaciente;
import pe.edu.cibertec.vidasalud.pacientes.domain.EstadoPaciente;
import pe.edu.cibertec.vidasalud.pacientes.domain.FiltroPacientes;
import pe.edu.cibertec.vidasalud.pacientes.domain.TipoDocumento;
import pe.edu.cibertec.vidasalud.pacientes.service.PacienteService;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

@WebFluxTest(controllers = PacienteController.class,
        properties = {"hospital.rabbit.enabled=false", "eureka.client.enabled=false"})
@Import(ManejadorErrores.class)
@ActiveProfiles("test")
class PacienteControllerTest {

    @Autowired
    WebTestClient cliente;
    @MockitoBean
    PacienteService servicio;

    private static Map<String, Object> cuerpoBase() {
        return Map.of("tipoDocumento", "DNI", "numeroDocumento", "00000001", "nombres", "Ana María",
                "apellidos", "Prueba Ficticia", "fechaNacimiento", "1990-05-20",
                "telefono", "999000111", "correo", "ana.prueba@example.com");
    }

    private static Map<String, Object> cuerpoCon(String clave, Object valor) {
        var mapa = new java.util.HashMap<>(cuerpoBase());
        mapa.put(clave, valor);
        return mapa;
    }

    private static PacienteResponse respuesta(UUID id, EstadoPaciente estado) {
        return new PacienteResponse(id, TipoDocumento.DNI, "00000001", "Ana María", "Prueba Ficticia",
                LocalDate.of(1990, 5, 20), "999000111", "ana.prueba@example.com", null,
                OffsetDateTime.parse("2026-10-12T09:30:00-05:00"), estado);
    }

    @Test
    void postValidoDevuelve201ConLocation() {
        UUID id = UUID.randomUUID();
        when(servicio.registrar(any())).thenReturn(Mono.just(respuesta(id, EstadoPaciente.ACTIVO)));

        cliente.post().uri("/api/pacientes").contentType(MediaType.APPLICATION_JSON).bodyValue(cuerpoBase())
                .exchange()
                .expectStatus().isCreated()
                .expectHeader().valueEquals("Location", "/api/pacientes/" + id)
                .expectBody()
                .jsonPath("$.id").isEqualTo(id.toString())
                .jsonPath("$.estado").isEqualTo("ACTIVO");
    }

    @Test
    void postSinNombresDevuelveValidacionFallida() {
        var cuerpo = new java.util.HashMap<>(cuerpoBase());
        cuerpo.remove("nombres");

        cliente.post().uri("/api/pacientes").contentType(MediaType.APPLICATION_JSON).bodyValue(cuerpo)
                .exchange()
                .expectStatus().isBadRequest()
                .expectBody()
                .jsonPath("$.codigo").isEqualTo("VALIDACION_FALLIDA")
                .jsonPath("$.errores[*].campo").value(c -> org.assertj.core.api.Assertions.assertThat(c.toString())
                        .contains("nombres"));

        verifyNoInteractions(servicio);
    }

    @Test
    void postConCorreoInvalidoDevuelveErrorEnCorreo() {
        cliente.post().uri("/api/pacientes").contentType(MediaType.APPLICATION_JSON)
                .bodyValue(cuerpoCon("correo", "correo-sin-arroba"))
                .exchange()
                .expectStatus().isBadRequest()
                .expectBody()
                .jsonPath("$.errores[*].campo").value(c -> org.assertj.core.api.Assertions.assertThat(c.toString())
                        .contains("correo"));

        verifyNoInteractions(servicio);
    }

    @Test
    void postConTipoDocumentoDesconocidoDevuelve400() {
        cliente.post().uri("/api/pacientes").contentType(MediaType.APPLICATION_JSON)
                .bodyValue(cuerpoCon("tipoDocumento", "XYZ"))
                .exchange()
                .expectStatus().isBadRequest()
                .expectBody().jsonPath("$.codigo").isEqualTo("VALIDACION_FALLIDA");
    }

    @Test
    void postDuplicadoDevuelve409ProblemJson() {
        when(servicio.registrar(any())).thenReturn(
                Mono.error(ErroresPaciente.duplicado(TipoDocumento.DNI, "00000001")));

        cliente.post().uri("/api/pacientes").contentType(MediaType.APPLICATION_JSON).bodyValue(cuerpoBase())
                .exchange()
                .expectStatus().isEqualTo(HttpStatus.CONFLICT)
                .expectHeader().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON)
                .expectBody()
                .jsonPath("$.codigo").isEqualTo("PACIENTE_DUPLICADO")
                .jsonPath("$.instance").isEqualTo("/api/pacientes");
    }

    @Test
    void getInexistenteDevuelve404() {
        UUID id = UUID.randomUUID();
        when(servicio.obtener(id)).thenReturn(Mono.error(ErroresPaciente.noEncontrado(id)));

        cliente.get().uri("/api/pacientes/{id}", id)
                .exchange()
                .expectStatus().isNotFound()
                .expectBody().jsonPath("$.codigo").isEqualTo("PACIENTE_NO_ENCONTRADO");
    }

    @Test
    void getConIdQueNoEsUuidDevuelve400() {
        cliente.get().uri("/api/pacientes/no-es-uuid")
                .exchange()
                .expectStatus().isBadRequest()
                .expectBody().jsonPath("$.codigo").isEqualTo("VALIDACION_FALLIDA");
    }

    @Test
    void listarDevuelveTotalEnCabeceraYUsaPaginaPorDefecto() {
        when(servicio.listar(any(), any())).thenReturn(Flux.just(
                respuesta(UUID.randomUUID(), EstadoPaciente.ACTIVO), respuesta(UUID.randomUUID(), EstadoPaciente.ACTIVO)));
        when(servicio.contar(any())).thenReturn(Mono.just(2L));

        cliente.get().uri("/api/pacientes")
                .exchange()
                .expectStatus().isOk()
                .expectHeader().valueEquals("X-Total-Count", "2")
                .expectBody().jsonPath("$.length()").isEqualTo(2);

        ArgumentCaptor<ParametrosPagina> captor = ArgumentCaptor.forClass(ParametrosPagina.class);
        verify(servicio).listar(any(FiltroPacientes.class), captor.capture());
        org.assertj.core.api.Assertions.assertThat(captor.getValue()).isEqualTo(new ParametrosPagina(0, 20));
    }

    @Test
    void listarConTamanoExcesivoDevuelve400() {
        cliente.get().uri("/api/pacientes?tamano=500")
                .exchange()
                .expectStatus().isBadRequest()
                .expectBody().jsonPath("$.codigo").isEqualTo("VALIDACION_FALLIDA");

        verifyNoInteractions(servicio);
    }

    @Test
    void listarConTipoDocumentoSinNumeroDevuelve400() {
        cliente.get().uri("/api/pacientes?tipoDocumento=DNI")
                .exchange()
                .expectStatus().isBadRequest();
    }

    @Test
    void listarConEstadoDesconocidoDevuelve400() {
        cliente.get().uri("/api/pacientes?estado=OTRO")
                .exchange()
                .expectStatus().isBadRequest();
    }

    @Test
    void putValidoDevuelve200() {
        UUID id = UUID.randomUUID();
        when(servicio.actualizar(any(), any())).thenReturn(Mono.just(respuesta(id, EstadoPaciente.ACTIVO)));

        cliente.put().uri("/api/pacientes/{id}", id).contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("nombres", "Ana María", "apellidos", "Prueba Ficticia",
                        "fechaNacimiento", "1990-05-20"))
                .exchange()
                .expectStatus().isOk()
                .expectBody().jsonPath("$.id").isEqualTo(id.toString());
    }

    @Test
    void patchSinEstadoDevuelve400() {
        cliente.patch().uri("/api/pacientes/{id}/estado", UUID.randomUUID()).contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("motivo", "x"))
                .exchange()
                .expectStatus().isBadRequest();

        verifyNoInteractions(servicio);
    }

    @Test
    void patchConTransicionNoPermitidaDevuelve409() {
        UUID id = UUID.randomUUID();
        ErrorNegocio error = ErroresPaciente.transicionNoPermitida(EstadoPaciente.ACTIVO);
        when(servicio.cambiarEstado(any(), any())).thenReturn(Mono.error(error));

        cliente.patch().uri("/api/pacientes/{id}/estado", id).contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("estado", "ACTIVO"))
                .exchange()
                .expectStatus().isEqualTo(HttpStatus.CONFLICT)
                .expectBody().jsonPath("$.codigo").isEqualTo("TRANSICION_NO_PERMITIDA");
    }
}
