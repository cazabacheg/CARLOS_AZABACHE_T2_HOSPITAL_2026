package pe.edu.cibertec.vidasalud.notificaciones.api;

import io.swagger.v3.oas.annotations.Operation;
import java.time.LocalDate;
import java.util.UUID;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import pe.edu.cibertec.vidasalud.commons.paginacion.ParametrosPagina;
import pe.edu.cibertec.vidasalud.commons.paginacion.RespuestaPaginada;
import pe.edu.cibertec.vidasalud.notificaciones.domain.FiltroNotificaciones;
import pe.edu.cibertec.vidasalud.notificaciones.service.NotificacionService;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

@RestController
@RequestMapping("/api/notificaciones")
public class NotificacionController {

    private final NotificacionService servicio;

    public NotificacionController(NotificacionService servicio) {
        this.servicio = servicio;
    }

    @Operation(summary = "Lista notificaciones simuladas (paginado, X-Total-Count)")
    @GetMapping
    public Mono<ResponseEntity<Flux<NotificacionResponse>>> listar(
            @RequestParam(required = false) UUID pacienteId,
            @RequestParam(required = false) String tipoEvento,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate fecha,
            @RequestParam(required = false) Integer pagina,
            @RequestParam(required = false) Integer tamano) {
        ParametrosPagina pag = ParametrosPagina.de(pagina, tamano);
        FiltroNotificaciones filtro = new FiltroNotificaciones(pacienteId, tipoEvento, fecha);
        return RespuestaPaginada.de(servicio.listar(filtro, pag), servicio.contar(filtro));
    }

    @Operation(summary = "Obtiene una notificación por id")
    @GetMapping("/{id}")
    public Mono<NotificacionResponse> obtener(@PathVariable UUID id) {
        return servicio.obtener(id);
    }
}
