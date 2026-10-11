package pe.edu.cibertec.vidasalud.citas.service;

import java.time.Clock;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import pe.edu.cibertec.vidasalud.citas.api.dto.CambioEstadoCitaRequest;
import pe.edu.cibertec.vidasalud.citas.api.dto.CitaCrearRequest;
import pe.edu.cibertec.vidasalud.citas.api.dto.CitaResponse;
import pe.edu.cibertec.vidasalud.citas.api.dto.ReprogramarCitaRequest;
import pe.edu.cibertec.vidasalud.citas.client.PacientesClient;
import pe.edu.cibertec.vidasalud.citas.domain.Cita;
import pe.edu.cibertec.vidasalud.citas.domain.EstadoCita;
import pe.edu.cibertec.vidasalud.citas.domain.FiltroCitas;
import pe.edu.cibertec.vidasalud.citas.domain.ReglasAgenda;
import pe.edu.cibertec.vidasalud.citas.domain.ZonaHospital;
import pe.edu.cibertec.vidasalud.citas.messaging.EventosCita;
import pe.edu.cibertec.vidasalud.citas.persistence.CitaConsultas;
import pe.edu.cibertec.vidasalud.citas.persistence.CitaRepository;
import pe.edu.cibertec.vidasalud.citas.persistence.SecuenciaCodigoCita;
import pe.edu.cibertec.vidasalud.commons.error.ErrorNegocio;
import pe.edu.cibertec.vidasalud.commons.messaging.PublicadorEventos;
import pe.edu.cibertec.vidasalud.commons.messaging.TopologiaHospital;
import pe.edu.cibertec.vidasalud.commons.paginacion.ParametrosPagina;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

@Service
public class CitaService {

    private static final Logger log = LoggerFactory.getLogger(CitaService.class);
    private static final String CHOQUE = "CITA_CHOQUE_AGENDA";

    private final CitaRepository repositorio;
    private final CitaConsultas consultas;
    private final SecuenciaCodigoCita secuencia;
    private final PacientesClient pacientes;
    private final PublicadorEventos publicador;
    private final EventosCita eventos;
    private final ReglasAgenda reglas;
    private final Clock reloj;

    public CitaService(CitaRepository repositorio, CitaConsultas consultas, SecuenciaCodigoCita secuencia,
                       PacientesClient pacientes, PublicadorEventos publicador, EventosCita eventos,
                       ReglasAgenda reglas, Clock reloj) {
        this.repositorio = repositorio;
        this.consultas = consultas;
        this.secuencia = secuencia;
        this.pacientes = pacientes;
        this.publicador = publicador;
        this.eventos = eventos;
        this.reglas = reglas;
        this.reloj = reloj;
    }

    public Mono<CitaResponse> programar(CitaCrearRequest solicitud) {
        return Mono.fromRunnable(() -> reglas.validar(solicitud.fechaHora()))
                .then(Mono.defer(() -> pacientes.obtener(solicitud.pacienteId())))
                .flatMap(paciente -> paciente.estaActivo()
                        ? Mono.<Void>empty()
                        : Mono.<Void>error(new ErrorNegocio(HttpStatus.UNPROCESSABLE_ENTITY, "PACIENTE_INACTIVO",
                                "El paciente está inactivo y no puede recibir citas.")))
                .then(Mono.defer(() -> verificarChoque(solicitud.profesionalCodigo(), solicitud.pacienteId(),
                        ZonaHospital.aLima(solicitud.fechaHora()))))
                .then(Mono.defer(secuencia::siguiente))
                .map(numero -> Cita.programar(solicitud.aDatos(), codigo(numero), reloj))
                .flatMap(this::guardar)
                .doOnNext(Cita::marcarComoExistente)
                .flatMap(c -> publicador.publicar(TopologiaHospital.CITA_CREADA, eventos.creada(c)).thenReturn(c))
                .doOnNext(c -> log.info("cita programada id={} codigo={}", c.getId(), c.getCodigo()))
                .map(CitaResponse::de);
    }

    public Mono<CitaResponse> obtener(UUID id) {
        return buscar(id).map(CitaResponse::de);
    }

    public Flux<CitaResponse> listar(FiltroCitas filtro, ParametrosPagina pagina) {
        return consultas.listar(filtro, pagina).map(CitaResponse::de);
    }

    public Mono<Long> contar(FiltroCitas filtro) {
        return consultas.contar(filtro);
    }

    public Mono<CitaResponse> cambiarEstado(UUID id, CambioEstadoCitaRequest solicitud) {
        EstadoCita destino = solicitud.estado();
        return buscar(id)
                .flatMap(cita -> Mono.fromCallable(() -> {
                    cita.validarCambioPorApi(destino);
                    if (destino == EstadoCita.CANCELADA) {
                        reglas.validarPlazoCambio(cita.getFechaHora());
                    }
                    EstadoCita anterior = cita.cambiarEstado(destino, reloj);
                    return new Object[] {cita, anterior};
                }))
                .flatMap(par -> {
                    Cita cita = (Cita) par[0];
                    EstadoCita anterior = (EstadoCita) par[1];
                    return guardar(cita)
                            .flatMap(c -> publicador.publicar(EventosCita.routingKeyPara(destino),
                                    eventos.cambioEstado(c, anterior, solicitud.motivo(), null)).thenReturn(c));
                })
                .doOnNext(c -> log.info("cita cambio de estado id={} estado={}", c.getId(), c.getEstado()))
                .map(CitaResponse::de);
    }

    public Mono<CitaResponse> reprogramar(UUID id, ReprogramarCitaRequest solicitud) {
        return buscar(id)
                .flatMap(cita -> Mono.fromCallable(() -> {
                    reglas.validarPlazoCambio(cita.getFechaHora());
                    reglas.validar(solicitud.fechaHora());
                    EstadoCita estadoAnterior = cita.getEstado();
                    String profesionalAnterior = cita.getProfesionalCodigo();
                    OffsetDateTime fechaAnterior = cita.reprogramar(solicitud.fechaHora(),
                            solicitud.profesionalCodigo(), reloj);
                    return new Object[] {cita, estadoAnterior, profesionalAnterior, fechaAnterior};
                }))
                .flatMap(datos -> {
                    Cita cita = (Cita) datos[0];
                    return verificarChoque(cita.getProfesionalCodigo(), cita.getPacienteId(), cita.getFechaHora())
                            .then(Mono.defer(() -> guardar(cita)))
                            .flatMap(c -> publicador.publicar(TopologiaHospital.CITA_REPROGRAMADA,
                                    eventos.reprogramada(c, (EstadoCita) datos[1], (OffsetDateTime) datos[3],
                                            (String) datos[2])).thenReturn(c));
                })
                .doOnNext(c -> log.info("cita reprogramada id={}", c.getId()))
                .map(CitaResponse::de);
    }

    public Mono<Void> marcarAtendida(UUID citaId, UUID atencionId) {
        return repositorio.findById(citaId)
                .switchIfEmpty(Mono.error(() -> noEncontrada(citaId)))
                .flatMap(cita -> {
                    if (cita.getEstado() == EstadoCita.ATENDIDA) {
                        return Mono.empty();
                    }
                    return Mono.fromCallable(() -> cita.cambiarEstado(EstadoCita.ATENDIDA, reloj))
                            .flatMap(anterior -> repositorio.save(cita)
                                    .flatMap(c -> publicador.publicar(TopologiaHospital.CITA_ATENDIDA,
                                            eventos.cambioEstado(c, anterior, null, atencionId)))
                                    .doOnSuccess(v -> log.info("cita atendida id={} atencionId={}", citaId,
                                            atencionId)));
                });
    }

    public Mono<Void> cancelarPorAtencionAnulada(UUID citaId, UUID atencionId) {
        return repositorio.findById(citaId)
                .switchIfEmpty(Mono.error(() -> noEncontrada(citaId)))
                .flatMap(cita -> {
                    EstadoCita estado = cita.getEstado();
                    if (estado == EstadoCita.CANCELADA) {
                        return Mono.<Void>empty();
                    }
                    if (estado != EstadoCita.ATENDIDA) {
                        return Mono.<Void>error(new ErrorNegocio(HttpStatus.CONFLICT, "TRANSICION_NO_PERMITIDA",
                                "No se puede cancelar por anulación una cita en estado " + estado + "."));
                    }
                    if (cita.yaOcurrio(reloj)) {
                        log.info("anulacion administrativa, la cita ya ocurrio id={}", citaId);
                        return Mono.<Void>empty();
                    }
                    return Mono.fromCallable(() -> cita.cambiarEstado(EstadoCita.CANCELADA, reloj))
                            .flatMap(anterior -> repositorio.save(cita)
                                    .flatMap(c -> publicador.publicar(TopologiaHospital.CITA_CANCELADA,
                                            eventos.cambioEstado(c, anterior, "Atención anulada", atencionId))));
                });
    }

    private Mono<Cita> buscar(UUID id) {
        return repositorio.findById(id).switchIfEmpty(Mono.error(() -> noEncontrada(id)));
    }

    private static ErrorNegocio noEncontrada(UUID id) {
        return new ErrorNegocio(HttpStatus.NOT_FOUND, "CITA_NO_ENCONTRADA", "No existe la cita " + id + ".");
    }

    private Mono<Void> verificarChoque(String profesional, UUID pacienteId, OffsetDateTime fechaHora) {
        return repositorio.existeActivaDeProfesional(profesional, fechaHora)
                .flatMap(ocupado -> ocupado
                        ? Mono.<Void>error(new ErrorNegocio(HttpStatus.CONFLICT, CHOQUE,
                                "El profesional " + profesional + " ya tiene una cita en ese horario."))
                        : repositorio.existeActivaDePaciente(pacienteId, fechaHora)
                                .flatMap(ocupadoPaciente -> ocupadoPaciente
                                        ? Mono.<Void>error(new ErrorNegocio(HttpStatus.CONFLICT, CHOQUE,
                                                "El paciente ya tiene una cita en ese horario."))
                                        : Mono.<Void>empty()));
    }

    private Mono<Cita> guardar(Cita cita) {
        return repositorio.save(cita)
                .onErrorMap(DuplicateKeyException.class, CitaService::traducirDuplicado)
                .onErrorMap(OptimisticLockingFailureException.class, e -> new ErrorNegocio(HttpStatus.CONFLICT,
                        "CONFLICTO_CONCURRENTE", "La cita fue modificada por otra operación; vuelva a consultarla."));
    }

    static Throwable traducirDuplicado(DuplicateKeyException e) {
        Throwable causa = e.getMostSpecificCause();
        String texto = e.getMessage() + " " + (causa == null ? "" : causa.getMessage());
        if (texto.contains("ux_cita_profesional_horario") || texto.contains("ux_cita_paciente_horario")) {
            return new ErrorNegocio(HttpStatus.CONFLICT, CHOQUE, "El horario ya fue ocupado.");
        }
        return e;
    }

    private String codigo(long numero) {
        String fecha = LocalDate.now(reloj.withZone(ZonaHospital.LIMA)).format(DateTimeFormatter.BASIC_ISO_DATE);
        return String.format("CIT-%s-%06d", fecha, numero);
    }
}
