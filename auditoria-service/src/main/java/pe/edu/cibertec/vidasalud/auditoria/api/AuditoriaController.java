package pe.edu.cibertec.vidasalud.auditoria.api;

import io.swagger.v3.oas.annotations.Operation;
import java.time.OffsetDateTime;
import java.util.UUID;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import pe.edu.cibertec.vidasalud.auditoria.domain.FiltroAuditoria;
import pe.edu.cibertec.vidasalud.auditoria.domain.TipoRecurso;
import pe.edu.cibertec.vidasalud.auditoria.service.AuditoriaService;
import pe.edu.cibertec.vidasalud.commons.paginacion.ParametrosPagina;
import pe.edu.cibertec.vidasalud.commons.paginacion.RespuestaPaginada;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

@RestController
@RequestMapping("/api/auditoria")
public class AuditoriaController {

    private final AuditoriaService servicio;

    public AuditoriaController(AuditoriaService servicio) {
        this.servicio = servicio;
    }

    @Operation(summary = "Lista eventos de auditoria con filtros y paginacion")
    @GetMapping
    public Mono<ResponseEntity<Flux<EventoAuditoriaResponse>>> listar(
            @RequestParam(required = false) String tipoRecurso,
            @RequestParam(required = false) UUID recursoId,
            @RequestParam(required = false) String tipoEvento,
            @RequestParam(required = false) String origen,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) OffsetDateTime desde,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) OffsetDateTime hasta,
            @RequestParam(required = false) Integer pagina,
            @RequestParam(required = false) Integer tamano) {
        ParametrosPagina parametros = ParametrosPagina.de(pagina, tamano);
        FiltroAuditoria filtro = FiltroAuditoria.de(tipoRecurso, recursoId, tipoEvento, origen, desde, hasta);
        return RespuestaPaginada.de(servicio.buscar(filtro, parametros), servicio.contar(filtro));
    }

    @Operation(summary = "Detalle de un evento de auditoria")
    @GetMapping("/{id}")
    public Mono<EventoAuditoriaResponse> obtener(@PathVariable UUID id) {
        return servicio.obtener(id);
    }

    @Operation(summary = "Historial cronologico de estados de un recurso")
    @GetMapping("/recursos/{tipoRecurso}/{recursoId}/historial")
    public Flux<EventoAuditoriaResponse> historial(@PathVariable String tipoRecurso,
                                                   @PathVariable UUID recursoId) {
        TipoRecurso tipo = TipoRecurso.desde(tipoRecurso);
        return servicio.historial(tipo, recursoId);
    }
}
