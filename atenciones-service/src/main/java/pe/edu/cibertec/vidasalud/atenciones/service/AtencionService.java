package pe.edu.cibertec.vidasalud.atenciones.service;

import java.time.Clock;
import java.util.UUID;
import pe.edu.cibertec.vidasalud.atenciones.api.dto.AtencionCrearRequest;
import pe.edu.cibertec.vidasalud.atenciones.api.dto.AtencionResponse;
import pe.edu.cibertec.vidasalud.atenciones.api.dto.CambioEstadoAtencionRequest;
import pe.edu.cibertec.vidasalud.atenciones.client.CitasClient;
import pe.edu.cibertec.vidasalud.atenciones.domain.Atencion;
import pe.edu.cibertec.vidasalud.atenciones.domain.ErroresAtencion;
import pe.edu.cibertec.vidasalud.atenciones.domain.EstadoAtencion;
import pe.edu.cibertec.vidasalud.atenciones.domain.FiltroAtenciones;
import pe.edu.cibertec.vidasalud.atenciones.domain.VentanaAtencion;
import pe.edu.cibertec.vidasalud.atenciones.messaging.EventosAtencion;
import pe.edu.cibertec.vidasalud.atenciones.persistence.AtencionConsultas;
import pe.edu.cibertec.vidasalud.atenciones.persistence.AtencionRepository;
import pe.edu.cibertec.vidasalud.atenciones.persistence.GeneradorCodigoAtencion;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.stereotype.Service;
import pe.edu.cibertec.vidasalud.commons.messaging.PublicadorEventos;
import pe.edu.cibertec.vidasalud.commons.messaging.TopologiaHospital;
import pe.edu.cibertec.vidasalud.commons.paginacion.ParametrosPagina;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

@Service
public class AtencionService {

    private static final Logger log = LoggerFactory.getLogger(AtencionService.class);

    private final AtencionRepository repositorio;
    private final AtencionConsultas consultas;
    private final GeneradorCodigoAtencion generador;
    private final CitasClient citas;
    private final PublicadorEventos publicador;
    private final VentanaAtencion ventana;
    private final Clock reloj;

    public AtencionService(AtencionRepository repositorio, AtencionConsultas consultas,
                           GeneradorCodigoAtencion generador, CitasClient citas,
                           PublicadorEventos publicador, VentanaAtencion ventana, Clock reloj) {
        this.repositorio = repositorio;
        this.consultas = consultas;
        this.generador = generador;
        this.citas = citas;
        this.publicador = publicador;
        this.ventana = ventana;
        this.reloj = reloj;
    }

    public Mono<AtencionResponse> registrar(AtencionCrearRequest solicitud) {
        return citas.obtener(solicitud.citaId())
                .flatMap(cita -> {
                    if (!cita.estaConfirmada()) {
                        return Mono.<Atencion>error(ErroresAtencion.citaNoConfirmada(cita.estado()));
                    }
                    ventana.validar(cita.fechaHora(), reloj);
                    return repositorio.existsActivaByCitaId(cita.id())
                            .flatMap(existe -> existe
                                    ? Mono.<String>error(ErroresAtencion.atencionDuplicada())
                                    : generador.siguiente())
                            .map(codigo -> Atencion.registrar(cita, solicitud.area(), codigo, reloj));
                })
                .flatMap(atencion -> repositorio.save(atencion)
                        .onErrorMap(DuplicateKeyException.class, e -> ErroresAtencion.atencionDuplicada()))
                .flatMap(guardada -> publicador.publicar(TopologiaHospital.ATENCION_REGISTRADA,
                                EventosAtencion.registrada(guardada, reloj))
                        .thenReturn(guardada))
                .doOnNext(a -> log.info("atencion registrada id={} citaId={} codigo={}",
                        a.getId(), a.getCitaId(), a.getCodigo()))
                .map(AtencionResponse::de);
    }

    public Mono<AtencionResponse> obtener(UUID id) {
        return buscar(id).map(AtencionResponse::de);
    }

    public Flux<AtencionResponse> listar(FiltroAtenciones filtro, ParametrosPagina pagina) {
        return consultas.buscar(filtro, pagina).map(AtencionResponse::de);
    }

    public Mono<Long> contar(FiltroAtenciones filtro) {
        return consultas.contar(filtro);
    }

    public Mono<AtencionResponse> cambiarEstado(UUID id, CambioEstadoAtencionRequest solicitud) {
        return buscar(id)
                .flatMap(atencion -> {
                    EstadoAtencion anterior = atencion.cambiarEstado(solicitud.estado(), reloj);
                    return repositorio.save(atencion)
                            .onErrorMap(OptimisticLockingFailureException.class,
                                    e -> ErroresAtencion.conflictoConcurrente())
                            .flatMap(g -> publicador.publicar(EventosAtencion.routingKeyDe(g.getEstado()),
                                            EventosAtencion.cambioEstado(g, anterior, solicitud.motivo(), reloj))
                                    .thenReturn(g))
                            .doOnNext(g -> log.info("atencion id={} cambia de {} a {}",
                                    g.getId(), anterior, g.getEstado()));
                })
                .map(AtencionResponse::de);
    }

    private Mono<Atencion> buscar(UUID id) {
        return repositorio.findById(id)
                .switchIfEmpty(Mono.error(() -> ErroresAtencion.atencionNoEncontrada(id)));
    }
}
