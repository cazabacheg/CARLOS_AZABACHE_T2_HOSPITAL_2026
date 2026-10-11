package pe.edu.cibertec.vidasalud.pacientes.service;

import java.time.Clock;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import pe.edu.cibertec.vidasalud.commons.paginacion.ParametrosPagina;
import pe.edu.cibertec.vidasalud.pacientes.api.dto.CambioEstadoRequest;
import pe.edu.cibertec.vidasalud.pacientes.api.dto.PacienteActualizarRequest;
import pe.edu.cibertec.vidasalud.pacientes.api.dto.PacienteCrearRequest;
import pe.edu.cibertec.vidasalud.pacientes.api.dto.PacienteResponse;
import pe.edu.cibertec.vidasalud.pacientes.domain.ErroresPaciente;
import pe.edu.cibertec.vidasalud.pacientes.domain.EstadoPaciente;
import pe.edu.cibertec.vidasalud.pacientes.domain.FiltroPacientes;
import pe.edu.cibertec.vidasalud.pacientes.domain.Paciente;
import pe.edu.cibertec.vidasalud.pacientes.messaging.EventosPaciente;
import pe.edu.cibertec.vidasalud.pacientes.persistence.BusquedaPacientes;
import pe.edu.cibertec.vidasalud.pacientes.persistence.PacienteRepository;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

@Service
public class PacienteService {

    private static final Logger log = LoggerFactory.getLogger(PacienteService.class);

    private final PacienteRepository repositorio;
    private final BusquedaPacientes busqueda;
    private final EventosPaciente eventos;
    private final Clock reloj;

    public PacienteService(PacienteRepository repositorio, BusquedaPacientes busqueda,
                           EventosPaciente eventos, Clock reloj) {
        this.repositorio = repositorio;
        this.busqueda = busqueda;
        this.eventos = eventos;
        this.reloj = reloj;
    }

    public Mono<PacienteResponse> registrar(PacienteCrearRequest req) {
        return Mono.fromCallable(() -> Paciente.registrar(req.tipoDocumento(), req.numeroDocumento(),
                        req.datosPersonales(), reloj))
                .flatMap(paciente -> repositorio
                        .existsByTipoDocumentoAndNumeroDocumento(paciente.getTipoDocumento(),
                                paciente.getNumeroDocumento())
                        .flatMap(existe -> existe
                                ? Mono.<Paciente>error(ErroresPaciente.duplicado(paciente.getTipoDocumento(),
                                        paciente.getNumeroDocumento()))
                                : repositorio.save(paciente))
                        // La restricción de BD es la garantía ante registros simultáneos.
                        .onErrorMap(DuplicateKeyException.class, e -> ErroresPaciente.duplicado(
                                paciente.getTipoDocumento(), paciente.getNumeroDocumento())))
                .map(Paciente::marcarComoExistente)
                .flatMap(paciente -> eventos.publicarRegistrado(paciente).thenReturn(paciente))
                .doOnNext(paciente -> log.info("paciente registrado id={}", paciente.getId()))
                .map(PacienteResponse::desde);
    }

    public Mono<PacienteResponse> obtener(UUID id) {
        return buscarExistente(id).map(PacienteResponse::desde);
    }

    public Flux<PacienteResponse> listar(FiltroPacientes filtro, ParametrosPagina pagina) {
        return busqueda.buscar(filtro, pagina).map(PacienteResponse::desde);
    }

    public Mono<Long> contar(FiltroPacientes filtro) {
        return busqueda.contar(filtro);
    }

    public Mono<PacienteResponse> actualizar(UUID id, PacienteActualizarRequest req) {
        return buscarExistente(id)
                .doOnNext(paciente -> paciente.actualizarDatos(req.datosPersonales(), reloj))
                .flatMap(repositorio::save)
                .doOnNext(paciente -> log.info("paciente actualizado id={}", paciente.getId()))
                .map(PacienteResponse::desde);
    }

    public Mono<PacienteResponse> cambiarEstado(UUID id, CambioEstadoRequest req) {
        return buscarExistente(id)
                .flatMap(paciente -> {
                    EstadoPaciente anterior = paciente.cambiarEstado(req.estado());
                    return repositorio.save(paciente)
                            .flatMap(guardado -> eventos.publicarEstadoCambiado(guardado, anterior, req.motivo())
                                    .thenReturn(guardado))
                            .doOnNext(guardado -> log.info("paciente id={} cambio de estado {} -> {}",
                                    guardado.getId(), anterior, guardado.getEstado()));
                })
                .map(PacienteResponse::desde);
    }

    private Mono<Paciente> buscarExistente(UUID id) {
        return repositorio.findById(id).switchIfEmpty(Mono.error(() -> ErroresPaciente.noEncontrado(id)));
    }
}
