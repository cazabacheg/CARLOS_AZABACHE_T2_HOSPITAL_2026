package pe.edu.cibertec.vidasalud.pacientes.api;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.net.URI;
import java.util.UUID;
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
import pe.edu.cibertec.vidasalud.commons.paginacion.ParametrosPagina;
import pe.edu.cibertec.vidasalud.commons.paginacion.RespuestaPaginada;
import pe.edu.cibertec.vidasalud.pacientes.api.dto.CambioEstadoRequest;
import pe.edu.cibertec.vidasalud.pacientes.api.dto.PacienteActualizarRequest;
import pe.edu.cibertec.vidasalud.pacientes.api.dto.PacienteCrearRequest;
import pe.edu.cibertec.vidasalud.pacientes.api.dto.PacienteResponse;
import pe.edu.cibertec.vidasalud.pacientes.domain.EstadoPaciente;
import pe.edu.cibertec.vidasalud.pacientes.domain.FiltroPacientes;
import pe.edu.cibertec.vidasalud.pacientes.domain.TipoDocumento;
import pe.edu.cibertec.vidasalud.pacientes.service.PacienteService;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

@RestController
@RequestMapping("/api/pacientes")
@Tag(name = "Pacientes")
public class PacienteController {

    private final PacienteService servicio;

    public PacienteController(PacienteService servicio) {
        this.servicio = servicio;
    }

    @PostMapping
    @Operation(summary = "Registrar paciente")
    public Mono<ResponseEntity<PacienteResponse>> registrar(@Valid @RequestBody PacienteCrearRequest req) {
        return servicio.registrar(req)
                .map(r -> ResponseEntity.created(URI.create("/api/pacientes/" + r.id())).body(r));
    }

    @GetMapping("/{id}")
    @Operation(summary = "Consultar paciente por id")
    public Mono<PacienteResponse> obtener(@PathVariable UUID id) {
        return servicio.obtener(id);
    }

    @GetMapping
    @Operation(summary = "Listar pacientes con filtros y paginación")
    public Mono<ResponseEntity<Flux<PacienteResponse>>> listar(
            @RequestParam(required = false) EstadoPaciente estado,
            @RequestParam(required = false) TipoDocumento tipoDocumento,
            @RequestParam(required = false) String numeroDocumento,
            @RequestParam(required = false) String apellidos,
            @RequestParam(required = false) Integer pagina,
            @RequestParam(required = false) Integer tamano) {
        return Mono.defer(() -> {
            FiltroPacientes filtro = FiltroPacientes.de(estado, tipoDocumento, numeroDocumento, apellidos);
            ParametrosPagina parametros = ParametrosPagina.de(pagina, tamano);
            return RespuestaPaginada.de(servicio.listar(filtro, parametros), servicio.contar(filtro));
        });
    }

    @PutMapping("/{id}")
    @Operation(summary = "Actualizar datos de contacto")
    public Mono<PacienteResponse> actualizar(@PathVariable UUID id,
                                             @Valid @RequestBody PacienteActualizarRequest req) {
        return servicio.actualizar(id, req);
    }

    @PatchMapping("/{id}/estado")
    @Operation(summary = "Cambiar estado (ACTIVO/INACTIVO)")
    public Mono<PacienteResponse> cambiarEstado(@PathVariable UUID id,
                                                @Valid @RequestBody CambioEstadoRequest req) {
        return servicio.cambiarEstado(id, req);
    }
}
