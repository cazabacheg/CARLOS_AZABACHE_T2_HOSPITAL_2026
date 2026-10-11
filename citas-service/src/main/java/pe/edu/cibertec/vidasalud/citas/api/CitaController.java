package pe.edu.cibertec.vidasalud.citas.api;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
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
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import pe.edu.cibertec.vidasalud.citas.api.dto.CambioEstadoCitaRequest;
import pe.edu.cibertec.vidasalud.citas.api.dto.CitaCrearRequest;
import pe.edu.cibertec.vidasalud.citas.api.dto.CitaResponse;
import pe.edu.cibertec.vidasalud.citas.api.dto.ReprogramarCitaRequest;
import pe.edu.cibertec.vidasalud.citas.domain.Especialidad;
import pe.edu.cibertec.vidasalud.citas.domain.EstadoCita;
import pe.edu.cibertec.vidasalud.citas.domain.FiltroCitas;
import pe.edu.cibertec.vidasalud.citas.service.CitaService;
import pe.edu.cibertec.vidasalud.commons.paginacion.ParametrosPagina;
import pe.edu.cibertec.vidasalud.commons.paginacion.RespuestaPaginada;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

@RestController
@RequestMapping("/api/citas")
@Tag(name = "Citas")
public class CitaController {

    private final CitaService servicio;

    public CitaController(CitaService servicio) {
        this.servicio = servicio;
    }

    @PostMapping
    @Operation(summary = "Programar una cita")
    public Mono<ResponseEntity<CitaResponse>> programar(@Valid @RequestBody CitaCrearRequest solicitud) {
        return servicio.programar(solicitud)
                .map(r -> ResponseEntity.created(URI.create("/api/citas/" + r.id())).body(r));
    }

    @GetMapping("/{id}")
    @Operation(summary = "Consultar cita por id")
    public Mono<CitaResponse> obtener(@PathVariable UUID id) {
        return servicio.obtener(id);
    }

    @GetMapping
    @Operation(summary = "Listar citas con filtros y paginación")
    public Mono<ResponseEntity<Flux<CitaResponse>>> listar(
            @RequestParam(required = false) UUID pacienteId,
            @RequestParam(required = false) EstadoCita estado,
            @RequestParam(required = false) Especialidad especialidad,
            @RequestParam(required = false) String profesionalCodigo,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate fecha,
            @RequestParam(required = false) Integer pagina,
            @RequestParam(required = false) Integer tamano) {
        return Mono.defer(() -> {
            ParametrosPagina parametros = ParametrosPagina.de(pagina, tamano);
            FiltroCitas filtro = new FiltroCitas(pacienteId, estado, especialidad, profesionalCodigo, fecha);
            return RespuestaPaginada.de(servicio.listar(filtro, parametros), servicio.contar(filtro));
        });
    }

    @PutMapping("/{id}/reprogramar")
    @Operation(summary = "Reprogramar una cita")
    public Mono<CitaResponse> reprogramar(@PathVariable UUID id, @Valid @RequestBody ReprogramarCitaRequest solicitud) {
        return servicio.reprogramar(id, solicitud);
    }

    @PatchMapping("/{id}/estado")
    @Operation(summary = "Cambiar el estado de una cita (CONFIRMADA, CANCELADA, AUSENTE)")
    public Mono<CitaResponse> cambiarEstado(@PathVariable UUID id,
                                            @Valid @RequestBody CambioEstadoCitaRequest solicitud) {
        return servicio.cambiarEstado(id, solicitud);
    }
}
