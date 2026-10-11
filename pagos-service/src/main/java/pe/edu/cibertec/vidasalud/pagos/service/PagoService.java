package pe.edu.cibertec.vidasalud.pagos.service;

import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import pe.edu.cibertec.vidasalud.commons.error.ErrorNegocio;
import pe.edu.cibertec.vidasalud.commons.paginacion.ParametrosPagina;
import pe.edu.cibertec.vidasalud.pagos.api.dto.AnularPagoRequest;
import pe.edu.cibertec.vidasalud.pagos.api.dto.PagoCrearRequest;
import pe.edu.cibertec.vidasalud.pagos.api.dto.PagoResponse;
import pe.edu.cibertec.vidasalud.pagos.client.PacientesClient;
import pe.edu.cibertec.vidasalud.pagos.domain.ClaveIdempotencia;
import pe.edu.cibertec.vidasalud.pagos.domain.DatosPago;
import pe.edu.cibertec.vidasalud.pagos.domain.FiltroPagos;
import pe.edu.cibertec.vidasalud.pagos.domain.HuellaSolicitud;
import pe.edu.cibertec.vidasalud.pagos.domain.NumeroOperacion;
import pe.edu.cibertec.vidasalud.pagos.domain.Pago;
import pe.edu.cibertec.vidasalud.pagos.domain.ReglasPago;
import pe.edu.cibertec.vidasalud.pagos.messaging.EventosPago;
import pe.edu.cibertec.vidasalud.pagos.persistence.PagoConsultas;
import pe.edu.cibertec.vidasalud.pagos.persistence.PagoRepository;
import pe.edu.cibertec.vidasalud.pagos.persistence.SecuenciaOperacion;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import java.time.Clock;

@Service
public class PagoService {

    private static final Logger log = LoggerFactory.getLogger(PagoService.class);

    private final PagoRepository repositorio;
    private final PagoConsultas consultas;
    private final SecuenciaOperacion secuencia;
    private final PacientesClient pacientes;
    private final EventosPago eventos;
    private final Clock reloj;

    public PagoService(PagoRepository repositorio, PagoConsultas consultas, SecuenciaOperacion secuencia,
                       PacientesClient pacientes, EventosPago eventos, Clock reloj) {
        this.repositorio = repositorio;
        this.consultas = consultas;
        this.secuencia = secuencia;
        this.pacientes = pacientes;
        this.eventos = eventos;
        this.reloj = reloj;
    }

    public Mono<ResultadoRegistro> registrar(String clave, PagoCrearRequest req) {
        return Mono.defer(() -> {
            ClaveIdempotencia.validar(clave);
            ReglasPago.validarMonto(req.monto());
            String huella = HuellaSolicitud.calcular(req.pacienteId(), req.citaId(), req.concepto(),
                    req.monto(), req.medioPago());
            return repositorio.findByClaveIdempotencia(clave)
                    .map(existente -> repeticion(existente, huella))
                    .switchIfEmpty(Mono.defer(() -> registrarNuevo(clave, huella, req)));
        });
    }

    private Mono<ResultadoRegistro> registrarNuevo(String clave, String huella, PagoCrearRequest req) {
        return pacientes.obtener(req.pacienteId())
                .flatMap(p -> "ACTIVO".equals(p.estado())
                        ? secuencia.siguiente()
                        : Mono.<Long>error(new ErrorNegocio(HttpStatus.UNPROCESSABLE_ENTITY, "PACIENTE_INACTIVO",
                                "El paciente está inactivo y no puede registrar pagos.")))
                .map(n -> Pago.registrar(
                        new DatosPago(req.pacienteId(), req.citaId(), req.concepto(), req.monto(), req.medioPago()),
                        NumeroOperacion.formatear(NumeroOperacion.fechaEnLima(reloj), n),
                        clave, huella, reloj))
                .flatMap(pago -> repositorio.save(pago)
                        .map(Pago::marcarComoExistente)
                        .flatMap(guardado -> eventos.registrado(guardado)
                                .thenReturn(new ResultadoRegistro(PagoResponse.desde(guardado), false)))
                        .doOnNext(r -> log.info("pago registrado id={} numeroOperacion={}",
                                r.pago().id(), r.pago().numeroOperacion()))
                        .onErrorResume(DuplicateKeyException.class, e -> resolverDuplicado(clave, huella)));
    }

    /** Se relee por clave en vez de interpretar el mensaje del driver para saber qué UNIQUE falló. */
    private Mono<ResultadoRegistro> resolverDuplicado(String clave, String huella) {
        return repositorio.findByClaveIdempotencia(clave)
                .map(existente -> repeticion(existente, huella))
                .switchIfEmpty(Mono.error(new ErrorNegocio(HttpStatus.CONFLICT, "OPERACION_DUPLICADA",
                        "El número de operación generado ya existe; intente nuevamente.")));
    }

    private ResultadoRegistro repeticion(Pago existente, String huella) {
        if (!existente.getHuellaSolicitud().equals(huella)) {
            throw new ErrorNegocio(HttpStatus.UNPROCESSABLE_ENTITY, "CLAVE_IDEMPOTENCIA_REUTILIZADA",
                    "La clave de idempotencia ya se usó con datos diferentes.");
        }
        log.info("pago repetido por idempotencia id={}", existente.getId());
        return new ResultadoRegistro(PagoResponse.desde(existente), true);
    }

    public Mono<PagoResponse> obtener(UUID id) {
        return repositorio.findById(id)
                .map(PagoResponse::desde)
                .switchIfEmpty(Mono.error(() -> noEncontrado(id)));
    }

    public Flux<PagoResponse> listar(FiltroPagos filtro, ParametrosPagina pagina) {
        return consultas.buscar(filtro, pagina).map(PagoResponse::desde);
    }

    public Mono<Long> contar(FiltroPagos filtro) {
        return consultas.contar(filtro);
    }

    public Mono<PagoResponse> anular(UUID id, AnularPagoRequest req) {
        return repositorio.findById(id)
                .switchIfEmpty(Mono.error(() -> noEncontrado(id)))
                .flatMap(pago -> {
                    pago.anular(req.motivo(), reloj);
                    return repositorio.save(pago);
                })
                .onErrorMap(OptimisticLockingFailureException.class, e -> new ErrorNegocio(HttpStatus.CONFLICT,
                        "CONFLICTO_CONCURRENTE", "El pago fue modificado por otra operación; vuelva a consultarlo."))
                .flatMap(guardado -> eventos.anulado(guardado).thenReturn(PagoResponse.desde(guardado)))
                .doOnNext(r -> log.info("pago anulado id={}", r.id()));
    }

    private static ErrorNegocio noEncontrado(UUID id) {
        return new ErrorNegocio(HttpStatus.NOT_FOUND, "PAGO_NO_ENCONTRADO", "No existe el pago " + id + ".");
    }
}
