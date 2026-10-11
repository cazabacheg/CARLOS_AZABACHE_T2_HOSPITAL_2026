package pe.edu.cibertec.vidasalud.notificaciones.api;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.reactive.WebFluxTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.reactive.server.WebTestClient;
import pe.edu.cibertec.vidasalud.commons.error.ErrorNegocio;
import pe.edu.cibertec.vidasalud.commons.error.ManejadorErrores;
import pe.edu.cibertec.vidasalud.commons.paginacion.ParametrosPagina;
import pe.edu.cibertec.vidasalud.notificaciones.domain.FiltroNotificaciones;
import pe.edu.cibertec.vidasalud.notificaciones.service.NotificacionService;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

@WebFluxTest(controllers = NotificacionController.class,
        properties = {"hospital.rabbit.enabled=false", "eureka.client.enabled=false"})
@Import(ManejadorErrores.class)
class NotificacionControllerTest {

    private static final UUID ID = UUID.fromString("33333333-3333-4333-8333-333333333333");
    private static final UUID EVENTO = UUID.fromString("44444444-4444-4444-8444-444444444444");
    private static final UUID PACIENTE = UUID.fromString("11111111-1111-4111-8111-111111111111");

    @Autowired
    private WebTestClient cliente;

    @MockitoBean
    private NotificacionService servicio;

    private static NotificacionResponse respuesta() {
        return new NotificacionResponse(ID, EVENTO, PACIENTE, "cita.confirmada", UUID.randomUUID(), "SIMULADO",
                "Su cita fue confirmada.", OffsetDateTime.parse("2026-10-12T09:30:00-05:00"));
    }

    @Test
    void listaConFiltrosYPaginacion() {
        when(servicio.listar(any(), any())).thenReturn(Flux.just(respuesta()));
        when(servicio.contar(any())).thenReturn(Mono.just(7L));

        cliente.get().uri("/api/notificaciones?pacienteId={p}&tipoEvento=cita.confirmada&fecha=2026-10-12"
                        + "&pagina=1&tamano=5", PACIENTE)
                .exchange()
                .expectStatus().isOk()
                .expectHeader().valueEquals("X-Total-Count", "7")
                .expectBody().jsonPath("$.length()").isEqualTo(1);

        verify(servicio).listar(new FiltroNotificaciones(PACIENTE, "cita.confirmada", LocalDate.of(2026, 10, 12)),
                new ParametrosPagina(1, 5));
    }

    @Test
    void listaSinParametrosUsaValoresPorDefecto() {
        when(servicio.listar(any(), any())).thenReturn(Flux.empty());
        when(servicio.contar(any())).thenReturn(Mono.just(0L));

        cliente.get().uri("/api/notificaciones").exchange().expectStatus().isOk();

        verify(servicio).listar(new FiltroNotificaciones(null, null, null), new ParametrosPagina(0, 20));
    }

    @Test
    void obtienePorId() {
        when(servicio.obtener(ID)).thenReturn(Mono.just(respuesta()));

        cliente.get().uri("/api/notificaciones/{id}", ID).exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.mensaje").isEqualTo("Su cita fue confirmada.")
                .jsonPath("$.eventId").isEqualTo(EVENTO.toString());
    }

    @Test
    void inexistenteDevuelve404() {
        when(servicio.obtener(ID)).thenReturn(Mono.error(
                new ErrorNegocio(HttpStatus.NOT_FOUND, "NOTIFICACION_NO_ENCONTRADA", "No existe.")));

        cliente.get().uri("/api/notificaciones/{id}", ID).exchange()
                .expectStatus().isNotFound()
                .expectHeader().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON)
                .expectBody().jsonPath("$.codigo").isEqualTo("NOTIFICACION_NO_ENCONTRADA");
    }

    @Test
    void idNoUuidDevuelve400() {
        cliente.get().uri("/api/notificaciones/no-es-uuid").exchange()
                .expectStatus().isBadRequest()
                .expectBody().jsonPath("$.codigo").isEqualTo("VALIDACION_FALLIDA");
    }

    @Test
    void parametrosInvalidosDevuelven400() {
        when(servicio.listar(any(), any())).thenReturn(Flux.empty());
        when(servicio.contar(any())).thenReturn(Mono.just(0L));

        for (String uri : new String[] {"?tamano=0", "?tamano=500", "?pacienteId=abc", "?fecha=12/10/2026"}) {
            cliente.get().uri("/api/notificaciones" + uri).exchange()
                    .expectStatus().isBadRequest()
                    .expectBody().jsonPath("$.codigo").isEqualTo("VALIDACION_FALLIDA");
        }
    }

    @Test
    void noHayEndpointsDeEscritura() {
        cliente.post().uri("/api/notificaciones").exchange().expectStatus().is4xxClientError();
    }
}
