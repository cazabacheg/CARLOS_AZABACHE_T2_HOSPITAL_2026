package pe.edu.cibertec.vidasalud.auditoria.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.time.OffsetDateTime;
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
import pe.edu.cibertec.vidasalud.auditoria.domain.FiltroAuditoria;
import pe.edu.cibertec.vidasalud.auditoria.domain.TipoRecurso;
import pe.edu.cibertec.vidasalud.auditoria.service.AuditoriaService;
import pe.edu.cibertec.vidasalud.commons.error.ErrorNegocio;
import pe.edu.cibertec.vidasalud.commons.error.ManejadorErrores;
import pe.edu.cibertec.vidasalud.commons.paginacion.ParametrosPagina;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

@WebFluxTest(controllers = AuditoriaController.class,
        properties = {"hospital.rabbit.enabled=false", "eureka.client.enabled=false"})
@Import(ManejadorErrores.class)
@ActiveProfiles("test")
class AuditoriaControllerTest {

    private static final OffsetDateTime T = OffsetDateTime.parse("2026-10-12T09:30:00-05:00");

    @Autowired
    WebTestClient cliente;
    @MockitoBean
    AuditoriaService servicio;

    private static EventoAuditoriaResponse dto(String anterior, String nuevo) {
        return new EventoAuditoriaResponse(UUID.randomUUID(), UUID.randomUUID(), T, "cita.x", "CITA",
                UUID.randomUUID(), anterior, nuevo, "citas-service", T);
    }

    @Test
    void listaConTodosLosFiltros() {
        UUID recurso = UUID.randomUUID();
        when(servicio.buscar(any(), any())).thenReturn(Flux.just(dto("PENDIENTE", "CONFIRMADA")));
        when(servicio.contar(any())).thenReturn(Mono.just(7L));

        cliente.get().uri("/api/auditoria?tipoRecurso=CITA&recursoId=" + recurso
                        + "&tipoEvento=cita.confirmada&origen=citas-service"
                        + "&desde=2026-10-01T00:00:00-05:00&hasta=2026-10-31T23:59:59-05:00&pagina=0&tamano=5")
                .exchange()
                .expectStatus().isOk()
                .expectHeader().valueEquals("X-Total-Count", "7")
                .expectBody().jsonPath("$[0].estadoNuevo").isEqualTo("CONFIRMADA");

        ArgumentCaptor<FiltroAuditoria> filtro = ArgumentCaptor.forClass(FiltroAuditoria.class);
        ArgumentCaptor<ParametrosPagina> pagina = ArgumentCaptor.forClass(ParametrosPagina.class);
        verify(servicio).buscar(filtro.capture(), pagina.capture());
        FiltroAuditoria f = filtro.getValue();
        assertThat(f.tipoRecurso()).isEqualTo(TipoRecurso.CITA);
        assertThat(f.recursoId()).isEqualTo(recurso);
        assertThat(f.tipoEvento()).isEqualTo("cita.confirmada");
        assertThat(f.origen()).isEqualTo("citas-service");
        assertThat(f.desde().isEqual(OffsetDateTime.parse("2026-10-01T00:00:00-05:00"))).isTrue();
        assertThat(f.hasta().isEqual(OffsetDateTime.parse("2026-10-31T23:59:59-05:00"))).isTrue();
        assertThat(pagina.getValue()).isEqualTo(new ParametrosPagina(0, 5));
    }

    @Test
    void listaSinParametros() {
        when(servicio.buscar(any(), any())).thenReturn(Flux.empty());
        when(servicio.contar(any())).thenReturn(Mono.just(0L));

        cliente.get().uri("/api/auditoria").exchange().expectStatus().isOk();

        ArgumentCaptor<FiltroAuditoria> filtro = ArgumentCaptor.forClass(FiltroAuditoria.class);
        ArgumentCaptor<ParametrosPagina> pagina = ArgumentCaptor.forClass(ParametrosPagina.class);
        verify(servicio).buscar(filtro.capture(), pagina.capture());
        assertThat(filtro.getValue()).isEqualTo(FiltroAuditoria.vacio());
        assertThat(pagina.getValue()).isEqualTo(new ParametrosPagina(0, 20));
    }

    @Test
    void desdePosteriorAHastaEs400() {
        cliente.get().uri("/api/auditoria?desde=2026-10-31T00:00:00-05:00&hasta=2026-10-01T00:00:00-05:00")
                .exchange()
                .expectStatus().isBadRequest()
                .expectHeader().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON)
                .expectBody().jsonPath("$.codigo").isEqualTo("VALIDACION_FALLIDA");
        verifyNoInteractions(servicio);
    }

    @Test
    void parametrosInvalidosSon400() {
        for (String uri : new String[] {"/api/auditoria?tamano=500", "/api/auditoria?desde=no-es-fecha",
                "/api/auditoria?tipoRecurso=REPORTE", "/api/auditoria/no-es-uuid"}) {
            cliente.get().uri(uri).exchange().expectStatus().isBadRequest()
                    .expectBody().jsonPath("$.codigo").isEqualTo("VALIDACION_FALLIDA");
        }
        verifyNoInteractions(servicio);
    }

    @Test
    void detalleInexistenteEs404() {
        when(servicio.obtener(any())).thenReturn(Mono.error(new ErrorNegocio(HttpStatus.NOT_FOUND,
                "EVENTO_AUDITORIA_NO_ENCONTRADO", "No existe el evento de auditoria.")));
        cliente.get().uri("/api/auditoria/" + UUID.randomUUID()).exchange()
                .expectStatus().isNotFound()
                .expectBody().jsonPath("$.codigo").isEqualTo("EVENTO_AUDITORIA_NO_ENCONTRADO");
    }

    @Test
    void detalleExistente() {
        when(servicio.obtener(any())).thenReturn(Mono.just(dto("PENDIENTE", "CONFIRMADA")));
        cliente.get().uri("/api/auditoria/" + UUID.randomUUID()).exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.estadoNuevo").isEqualTo("CONFIRMADA")
                .jsonPath("$.origen").isEqualTo("citas-service");
    }

    @Test
    void historialConTipoInvalidoEs400() {
        cliente.get().uri("/api/auditoria/recursos/FACTURA/" + UUID.randomUUID() + "/historial").exchange()
                .expectStatus().isBadRequest()
                .expectBody().jsonPath("$.codigo").isEqualTo("VALIDACION_FALLIDA");
        verify(servicio, never()).historial(any(), any());
    }

    @Test
    void historialEnOrdenDelServicio() {
        UUID id = UUID.randomUUID();
        when(servicio.historial(TipoRecurso.CITA, id)).thenReturn(Flux.just(
                dto(null, "PENDIENTE"), dto("PENDIENTE", "CONFIRMADA"), dto("CONFIRMADA", "ATENDIDA")));
        cliente.get().uri("/api/auditoria/recursos/CITA/" + id + "/historial").exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$[0].estadoNuevo").isEqualTo("PENDIENTE")
                .jsonPath("$[1].estadoNuevo").isEqualTo("CONFIRMADA")
                .jsonPath("$[2].estadoNuevo").isEqualTo("ATENDIDA");
    }

    @Test
    void noHayEscrituras() {
        cliente.delete().uri("/api/auditoria/" + UUID.randomUUID()).exchange().expectStatus().is4xxClientError();
        cliente.put().uri("/api/auditoria/" + UUID.randomUUID()).exchange().expectStatus().is4xxClientError();
    }
}
