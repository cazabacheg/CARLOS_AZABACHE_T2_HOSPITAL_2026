package pe.edu.cibertec.vidasalud.pagos.api;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.reactive.WebFluxTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.reactive.server.WebTestClient;
import pe.edu.cibertec.vidasalud.commons.error.ErrorNegocio;
import org.springframework.http.HttpStatus;
import pe.edu.cibertec.vidasalud.commons.error.ManejadorErrores;
import pe.edu.cibertec.vidasalud.pagos.api.dto.PagoResponse;
import pe.edu.cibertec.vidasalud.pagos.domain.EstadoPago;
import pe.edu.cibertec.vidasalud.pagos.domain.MedioPago;
import pe.edu.cibertec.vidasalud.pagos.service.PagoService;
import pe.edu.cibertec.vidasalud.pagos.service.ResultadoRegistro;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

@WebFluxTest(controllers = PagoController.class,
        properties = {"hospital.rabbit.enabled=false", "eureka.client.enabled=false"})
@Import(ManejadorErrores.class)
@ActiveProfiles("test")
class PagoControllerTest {

    private static final String CLAVE = "3f6c2a1e-7b1d-4a8e-9c55-0d2f1b7a9e10";

    @Autowired WebTestClient cliente;
    @MockitoBean PagoService servicio;

    private static String cuerpo(String monto) {
        return "{\"pacienteId\":\"" + UUID.randomUUID() + "\",\"citaId\":\"" + UUID.randomUUID()
                + "\",\"concepto\":\"Reserva de cita - Medicina General\",\"monto\":" + monto
                + ",\"medioPago\":\"TARJETA\"}";
    }

    private static PagoResponse respuesta() {
        OffsetDateTime ahora = OffsetDateTime.parse("2026-10-12T09:00:00-05:00");
        return new PagoResponse(UUID.randomUUID(), "OP-20261012-000001", UUID.randomUUID(), null, "Reserva",
                new BigDecimal("80.00"), "PEN", ahora, MedioPago.TARJETA, EstadoPago.REGISTRADO, null, ahora);
    }

    private WebTestClient.RequestHeadersSpec<?> post(String clave, String json) {
        WebTestClient.RequestBodySpec spec = cliente.post().uri("/api/pagos").contentType(MediaType.APPLICATION_JSON);
        if (clave != null) {
            spec.header("Idempotency-Key", clave);
        }
        return spec.bodyValue(json);
    }

    private void esProblema(WebTestClient.ResponseSpec r, int status, String codigo) {
        r.expectStatus().isEqualTo(status)
                .expectHeader().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON)
                .expectBody().jsonPath("$.codigo").isEqualTo(codigo);
    }

    @Test
    void postOkEs201SinCabeceraDeRepeticion() {
        PagoResponse resp = respuesta();
        when(servicio.registrar(any(), any())).thenReturn(Mono.just(new ResultadoRegistro(resp, false)));
        post(CLAVE, cuerpo("80.00")).exchange()
                .expectStatus().isCreated()
                .expectHeader().valueEquals("Location", "/api/pagos/" + resp.id())
                .expectHeader().doesNotExist("Idempotent-Replayed")
                .expectBody()
                .jsonPath("$.numeroOperacion").isEqualTo("OP-20261012-000001")
                .consumeWith(r -> org.assertj.core.api.Assertions.assertThat(new String(r.getResponseBody()))
                        .contains("\"monto\":80.00"));
    }

    @Test
    void montoNegativoEsMontoInvalido() {
        esProblema(post(CLAVE, cuerpo("-10")).exchange(), 400, "MONTO_INVALIDO");
        verify(servicio, never()).registrar(any(), any());
    }

    @Test
    void montoCeroEsMontoInvalido() {
        esProblema(post(CLAVE, cuerpo("0")).exchange(), 400, "MONTO_INVALIDO");
    }

    @Test
    void montoConTresDecimalesEsMontoInvalido() {
        esProblema(post(CLAVE, cuerpo("0.001")).exchange(), 400, "MONTO_INVALIDO");
    }

    @Test
    void medioPagoDesconocidoEsValidacionFallida() {
        String json = cuerpo("80.00").replace("TARJETA", "CHEQUE");
        esProblema(post(CLAVE, json).exchange(), 400, "VALIDACION_FALLIDA");
    }

    @Test
    void sinConceptoEsValidacionFallida() {
        String json = "{\"pacienteId\":\"" + UUID.randomUUID() + "\",\"monto\":80.00,\"medioPago\":\"TARJETA\"}";
        post(CLAVE, json).exchange().expectStatus().isBadRequest()
                .expectBody().jsonPath("$.codigo").isEqualTo("VALIDACION_FALLIDA")
                .jsonPath("$.errores[0].campo").isEqualTo("concepto");
    }

    @Test
    void sinCabeceraOClaveCortaEsClaveRequerida() {
        esProblema(post(null, cuerpo("80.00")).exchange(), 400, "CLAVE_IDEMPOTENCIA_REQUERIDA");
        esProblema(post("abc", cuerpo("80.00")).exchange(), 400, "CLAVE_IDEMPOTENCIA_REQUERIDA");
        verify(servicio, never()).registrar(any(), any());
    }

    @Test
    void repeticionEs201ConCabecera() {
        when(servicio.registrar(any(), any())).thenReturn(Mono.just(new ResultadoRegistro(respuesta(), true)));
        post(CLAVE, cuerpo("80.00")).exchange()
                .expectStatus().isCreated()
                .expectHeader().valueEquals("Idempotent-Replayed", "true");
    }

    @Test
    void claveReutilizadaEs422() {
        when(servicio.registrar(any(), any())).thenReturn(Mono.error(new ErrorNegocio(
                HttpStatus.UNPROCESSABLE_ENTITY, "CLAVE_IDEMPOTENCIA_REUTILIZADA", "La clave ya se usó")));
        esProblema(post(CLAVE, cuerpo("80.00")).exchange(), 422, "CLAVE_IDEMPOTENCIA_REUTILIZADA");
    }

    @Test
    void getInexistenteEs404YIdInvalidoEs400() {
        UUID id = UUID.randomUUID();
        when(servicio.obtener(id)).thenReturn(Mono.error(new ErrorNegocio(
                HttpStatus.NOT_FOUND, "PAGO_NO_ENCONTRADO", "No existe")));
        esProblema(cliente.get().uri("/api/pagos/" + id).exchange(), 404, "PAGO_NO_ENCONTRADO");
        cliente.get().uri("/api/pagos/no-es-uuid").exchange().expectStatus().isBadRequest();
    }

    @Test
    void anularValidaMotivoYTraduceConflicto() {
        UUID id = UUID.randomUUID();
        esProblema(cliente.patch().uri("/api/pagos/" + id + "/anular")
                .contentType(MediaType.APPLICATION_JSON).bodyValue("{}").exchange(), 400, "VALIDACION_FALLIDA");

        when(servicio.anular(any(), any())).thenReturn(Mono.just(respuesta()));
        cliente.patch().uri("/api/pagos/" + id + "/anular").contentType(MediaType.APPLICATION_JSON)
                .bodyValue("{\"motivo\":\"Pago registrado por error\"}").exchange().expectStatus().isOk();

        when(servicio.anular(any(), any())).thenReturn(Mono.error(new ErrorNegocio(
                HttpStatus.CONFLICT, "PAGO_YA_ANULADO", "El pago ya fue anulado.")));
        esProblema(cliente.patch().uri("/api/pagos/" + id + "/anular").contentType(MediaType.APPLICATION_JSON)
                .bodyValue("{\"motivo\":\"otra vez\"}").exchange(), 409, "PAGO_YA_ANULADO");
    }

    @Test
    void listarDevuelveTotalYValidaTamano() {
        when(servicio.listar(any(), any())).thenReturn(Flux.just(respuesta()));
        when(servicio.contar(any())).thenReturn(Mono.just(7L));
        cliente.get().uri("/api/pagos?estado=REGISTRADO").exchange()
                .expectStatus().isOk()
                .expectHeader().valueEquals("X-Total-Count", "7");
        esProblema(cliente.get().uri("/api/pagos?tamano=500").exchange(), 400, "VALIDACION_FALLIDA");
    }
}
