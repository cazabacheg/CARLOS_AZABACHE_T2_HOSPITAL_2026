package pe.edu.cibertec.vidasalud.atenciones.api;

import io.swagger.v3.oas.annotations.Operation;
import jakarta.validation.Valid;
import java.net.URI;
import java.time.LocalDate;
import java.util.UUID;
import pe.edu.cibertec.vidasalud.atenciones.api.dto.AtencionCrearRequest;
import pe.edu.cibertec.vidasalud.atenciones.api.dto.AtencionResponse;
import pe.edu.cibertec.vidasalud.atenciones.api.dto.CambioEstadoAtencionRequest;
import pe.edu.cibertec.vidasalud.atenciones.domain.AreaAtencion;
import pe.edu.cibertec.vidasalud.atenciones.domain.EstadoAtencion;
import pe.edu.cibertec.vidasalud.atenciones.domain.FiltroAtenciones;
import pe.edu.cibertec.vidasalud.atenciones.service.AtencionService;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import pe.edu.cibertec.vidasalud.commons.paginacion.ParametrosPagina;
import pe.edu.cibertec.vidasalud.commons.paginacion.RespuestaPaginada;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

@RestController
@RequestMapping("/api/atenciones")
public class AtencionController {

    private final AtencionService servicio;

    public AtencionController(AtencionService servicio) {
        this.servicio = servicio;
    }

    @Operation(summary = "Registrar la atención de una cita confirmada")
    @PostMapping
    public Mono<ResponseEntity<AtencionResponse>> registrar(@Valid @RequestBody AtencionCrearRequest solicitud) {
        return servicio.registrar(solicitud)
                .map(r -> ResponseEntity.created(URI.create("/api/atenciones/" + r.id())).body(r));
    }

    @Operation(summary = "Obtener una atención por id")
    @GetMapping("/{id}")
    public Mono<AtencionResponse> obtener(@PathVariable UUID id) {
        return servicio.obtener(id);
    }

    @Operation(summary = "Listar atenciones (paginado, filtros opcionales)")
    @GetMapping
    public Mono<ResponseEntity<Flux<AtencionResponse>>> listar(
            @RequestParam(required = false) UUID pacienteId,
            @RequestParam(required = false) UUID citaId,
            @RequestParam(required = false) EstadoAtencion estado,
            @RequestParam(required = false) AreaAtencion area,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate fecha,
            @RequestParam(required = false) Integer pagina,
            @RequestParam(required = false) Integer tamano) {
        return Mono.fromCallable(() -> ParametrosPagina.de(pagina, tamano))
                .flatMap(p -> {
                    var filtro = new FiltroAtenciones(pacienteId, citaId, estado, area, fecha);
                    return RespuestaPaginada.de(servicio.listar(filtro, p), servicio.contar(filtro));
                });
    }

    @Operation(summary = "Finalizar o anular una atención")
    @PatchMapping("/{id}/estado")
    public Mono<AtencionResponse> cambiarEstado(@PathVariable UUID id,
                                                @Valid @RequestBody CambioEstadoAtencionRequest solicitud) {
        return servicio.cambiarEstado(id, solicitud);
    }
}
