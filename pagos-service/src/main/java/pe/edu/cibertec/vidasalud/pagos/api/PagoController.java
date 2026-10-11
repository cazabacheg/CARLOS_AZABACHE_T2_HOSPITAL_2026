package pe.edu.cibertec.vidasalud.pagos.api;

import io.swagger.v3.oas.annotations.Operation;
import jakarta.validation.Valid;
import java.net.URI;
import java.time.LocalDate;
import java.util.UUID;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import pe.edu.cibertec.vidasalud.commons.paginacion.ParametrosPagina;
import pe.edu.cibertec.vidasalud.commons.paginacion.RespuestaPaginada;
import pe.edu.cibertec.vidasalud.pagos.api.dto.AnularPagoRequest;
import pe.edu.cibertec.vidasalud.pagos.api.dto.PagoCrearRequest;
import pe.edu.cibertec.vidasalud.pagos.api.dto.PagoResponse;
import pe.edu.cibertec.vidasalud.pagos.domain.ClaveIdempotencia;
import pe.edu.cibertec.vidasalud.pagos.domain.EstadoPago;
import pe.edu.cibertec.vidasalud.pagos.domain.FiltroPagos;
import pe.edu.cibertec.vidasalud.pagos.domain.MedioPago;
import pe.edu.cibertec.vidasalud.pagos.domain.ReglasPago;
import pe.edu.cibertec.vidasalud.pagos.service.PagoService;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

@RestController
@RequestMapping("/api/pagos")
public class PagoController {

    private final PagoService servicio;

    public PagoController(PagoService servicio) {
        this.servicio = servicio;
    }

    // required = false: si falta la cabecera debe salir CLAVE_IDEMPOTENCIA_REQUERIDA, no un 400 genérico.
    @PostMapping
    @Operation(summary = "Registra un pago simulado (requiere cabecera Idempotency-Key)")
    public Mono<ResponseEntity<PagoResponse>> registrar(
            @RequestHeader(name = ClaveIdempotencia.CABECERA, required = false) String clave,
            @Valid @RequestBody PagoCrearRequest req) {
        return Mono.defer(() -> {
                    ClaveIdempotencia.validar(clave);
                    ReglasPago.validarMonto(req.monto());
                    return servicio.registrar(clave, req);
                })
                .map(r -> {
                    ResponseEntity.BodyBuilder b = ResponseEntity.created(URI.create("/api/pagos/" + r.pago().id()));
                    if (r.repetido()) {
                        b.header(ClaveIdempotencia.CABECERA_REPETIDA, "true");
                    }
                    return b.body(r.pago());
                });
    }

    @GetMapping("/{id}")
    public Mono<PagoResponse> obtener(@PathVariable UUID id) {
        return servicio.obtener(id);
    }

    @GetMapping
    public Mono<ResponseEntity<Flux<PagoResponse>>> listar(
            @RequestParam(required = false) UUID pacienteId,
            @RequestParam(required = false) UUID citaId,
            @RequestParam(required = false) String numeroOperacion,
            @RequestParam(required = false) EstadoPago estado,
            @RequestParam(required = false) MedioPago medioPago,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate fecha,
            @RequestParam(required = false) Integer pagina,
            @RequestParam(required = false) Integer tamano) {
        return Mono.defer(() -> {
            ParametrosPagina p = ParametrosPagina.de(pagina, tamano);
            FiltroPagos f = new FiltroPagos(pacienteId, citaId, numeroOperacion, estado, medioPago, fecha);
            return RespuestaPaginada.de(servicio.listar(f, p), servicio.contar(f));
        });
    }

    @PatchMapping("/{id}/anular")
    public Mono<PagoResponse> anular(@PathVariable UUID id, @Valid @RequestBody AnularPagoRequest req) {
        return servicio.anular(id, req);
    }
}
