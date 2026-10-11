package pe.edu.cibertec.vidasalud.pagos.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.JsonNode;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.http.HttpStatus;
import pe.edu.cibertec.vidasalud.commons.error.ErrorNegocio;
import pe.edu.cibertec.vidasalud.commons.messaging.EventoEnvelope;
import pe.edu.cibertec.vidasalud.commons.messaging.PublicadorEventos;
import pe.edu.cibertec.vidasalud.pagos.api.dto.AnularPagoRequest;
import pe.edu.cibertec.vidasalud.pagos.api.dto.PagoCrearRequest;
import pe.edu.cibertec.vidasalud.pagos.client.PacienteResumen;
import pe.edu.cibertec.vidasalud.pagos.client.PacientesClient;
import pe.edu.cibertec.vidasalud.pagos.domain.DatosPago;
import pe.edu.cibertec.vidasalud.pagos.domain.EstadoPago;
import pe.edu.cibertec.vidasalud.pagos.domain.HuellaSolicitud;
import pe.edu.cibertec.vidasalud.pagos.domain.MedioPago;
import pe.edu.cibertec.vidasalud.pagos.domain.Pago;
import pe.edu.cibertec.vidasalud.pagos.messaging.EventosPago;
import pe.edu.cibertec.vidasalud.pagos.persistence.PagoConsultas;
import pe.edu.cibertec.vidasalud.pagos.persistence.PagoRepository;
import pe.edu.cibertec.vidasalud.pagos.persistence.SecuenciaOperacion;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

@ExtendWith(MockitoExtension.class)
class PagoServiceTest {

    private static final Clock RELOJ = Clock.fixed(Instant.parse("2026-10-12T14:00:00Z"), ZoneId.of("America/Lima"));
    private static final String CLAVE = "3f6c2a1e-7b1d-4a8e-9c55-0d2f1b7a9e10";

    @Mock PagoRepository repositorio;
    @Mock PagoConsultas consultas;
    @Mock SecuenciaOperacion secuencia;
    @Mock PacientesClient pacientes;
    @Mock PublicadorEventos publicador;

    private PagoService servicio;
    private final UUID pacienteId = UUID.randomUUID();

    @BeforeEach
    void preparar() {
        servicio = new PagoService(repositorio, consultas, secuencia, pacientes,
                new EventosPago(Optional.of(publicador), RELOJ), RELOJ);
    }

    private PagoCrearRequest peticion(String monto) {
        return new PagoCrearRequest(pacienteId, null, "Reserva de cita", new BigDecimal(monto), MedioPago.TARJETA);
    }

    private Pago existente(String monto) {
        PagoCrearRequest r = peticion(monto);
        String huella = HuellaSolicitud.calcular(r.pacienteId(), r.citaId(), r.concepto(), r.monto(), r.medioPago());
        return Pago.registrar(new DatosPago(r.pacienteId(), r.citaId(), r.concepto(), r.monto(), r.medioPago()),
                "OP-20261012-000007", CLAVE, huella, RELOJ).marcarComoExistente();
    }

    private void pacienteConEstado(String estado) {
        when(pacientes.obtener(pacienteId)).thenReturn(Mono.just(new PacienteResumen(pacienteId, estado)));
    }

    private void guardaEcoDelArgumento() {
        when(repositorio.save(any(Pago.class))).thenAnswer(inv -> Mono.just(inv.getArgument(0)));
    }

    private void errorEsperado(Mono<?> mono, HttpStatus status, String codigo) {
        StepVerifier.create(mono).expectErrorSatisfies(e -> {
            assertThat(e).isInstanceOf(ErrorNegocio.class);
            assertThat(((ErrorNegocio) e).getStatus()).isEqualTo(status);
            assertThat(((ErrorNegocio) e).getCodigo()).isEqualTo(codigo);
        }).verify();
    }

    @Test
    void registroOkPublicaPagoRegistrado() {
        when(repositorio.findByClaveIdempotencia(CLAVE)).thenReturn(Mono.empty());
        pacienteConEstado("ACTIVO");
        when(secuencia.siguiente()).thenReturn(Mono.just(1L));
        guardaEcoDelArgumento();
        when(publicador.publicar(anyString(), any())).thenReturn(Mono.empty());

        StepVerifier.create(servicio.registrar(CLAVE, peticion("80")))
                .assertNext(r -> {
                    assertThat(r.repetido()).isFalse();
                    assertThat(r.pago().numeroOperacion()).isEqualTo("OP-20261012-000001").matches("^OP-\\d{8}-\\d{6}$");
                    assertThat(r.pago().estado()).isEqualTo(EstadoPago.REGISTRADO);
                    assertThat(r.pago().monto()).isEqualByComparingTo("80.00");
                    assertThat(r.pago().monto().scale()).isEqualTo(2);
                }).verifyComplete();

        ArgumentCaptor<EventoEnvelope> captor = ArgumentCaptor.forClass(EventoEnvelope.class);
        verify(publicador).publicar(eq("pago.registrado"), captor.capture());
        EventoEnvelope e = captor.getValue();
        assertThat(e.tipo()).isEqualTo("pago.registrado");
        assertThat(e.origen()).isEqualTo("pagos-service");
        assertThat(e.tipoRecurso()).isEqualTo("PAGO");
        assertThat(e.estadoAnterior()).isNull();
        assertThat(e.estadoNuevo()).isEqualTo("REGISTRADO");
        JsonNode datos = e.datos();
        assertThat(e.recursoId().toString()).isEqualTo(datos.get("pagoId").asText());
        assertThat(datos.get("monto").decimalValue()).isEqualByComparingTo("80.00");
        assertThat(datos.get("numeroOperacion").asText()).isEqualTo("OP-20261012-000001");
        assertThat(datos.get("pacienteId").asText()).isEqualTo(pacienteId.toString());
        assertThat(datos.has("nombres")).isFalse();
        assertThat(datos.has("documento")).isFalse();
        assertThat(datos.has("correo")).isFalse();
    }

    @Test
    void montoCeroNoTocaNada() {
        errorEsperado(servicio.registrar(CLAVE, peticion("0")), HttpStatus.BAD_REQUEST, "MONTO_INVALIDO");
        verifyNoInteractions(pacientes, secuencia, publicador, repositorio);
    }

    @Test
    void pacienteInactivoEs422() {
        when(repositorio.findByClaveIdempotencia(CLAVE)).thenReturn(Mono.empty());
        pacienteConEstado("INACTIVO");
        errorEsperado(servicio.registrar(CLAVE, peticion("80")), HttpStatus.UNPROCESSABLE_ENTITY, "PACIENTE_INACTIVO");
        verifyNoInteractions(secuencia, publicador);
        verify(repositorio, never()).save(any());
    }

    @Test
    void pacienteInexistenteSePropaga() {
        when(repositorio.findByClaveIdempotencia(CLAVE)).thenReturn(Mono.empty());
        when(pacientes.obtener(pacienteId)).thenReturn(Mono.error(
                new ErrorNegocio(HttpStatus.NOT_FOUND, "PACIENTE_NO_ENCONTRADO", "No existe")));
        errorEsperado(servicio.registrar(CLAVE, peticion("80")), HttpStatus.NOT_FOUND, "PACIENTE_NO_ENCONTRADO");
        verify(repositorio, never()).save(any());
    }

    @Test
    void formatoDelNumeroConSecuencia42() {
        when(repositorio.findByClaveIdempotencia(CLAVE)).thenReturn(Mono.empty());
        pacienteConEstado("ACTIVO");
        when(secuencia.siguiente()).thenReturn(Mono.just(42L));
        guardaEcoDelArgumento();
        when(publicador.publicar(anyString(), any())).thenReturn(Mono.empty());
        StepVerifier.create(servicio.registrar(CLAVE, peticion("80")))
                .assertNext(r -> assertThat(r.pago().numeroOperacion()).isEqualTo("OP-20261012-000042"))
                .verifyComplete();
    }

    @Test
    void duplicadoPorNumeroEs409() {
        when(repositorio.findByClaveIdempotencia(CLAVE)).thenReturn(Mono.empty(), Mono.empty());
        pacienteConEstado("ACTIVO");
        when(secuencia.siguiente()).thenReturn(Mono.just(1L));
        when(repositorio.save(any(Pago.class))).thenReturn(Mono.error(new DuplicateKeyException("dup")));
        errorEsperado(servicio.registrar(CLAVE, peticion("80")), HttpStatus.CONFLICT, "OPERACION_DUPLICADA");
        verifyNoInteractions(publicador);
    }

    @Test
    void carreraConMismaClaveYHuellaDevuelveElExistente() {
        Pago existente = existente("80");
        when(repositorio.findByClaveIdempotencia(CLAVE)).thenReturn(Mono.empty(), Mono.just(existente));
        pacienteConEstado("ACTIVO");
        when(secuencia.siguiente()).thenReturn(Mono.just(1L));
        when(repositorio.save(any(Pago.class))).thenReturn(Mono.error(new DuplicateKeyException("dup")));
        StepVerifier.create(servicio.registrar(CLAVE, peticion("80")))
                .assertNext(r -> {
                    assertThat(r.repetido()).isTrue();
                    assertThat(r.pago().id()).isEqualTo(existente.getId());
                }).verifyComplete();
        verifyNoInteractions(publicador);
    }

    @Test
    void carreraConHuellaDistintaEs422() {
        when(repositorio.findByClaveIdempotencia(CLAVE)).thenReturn(Mono.empty(), Mono.just(existente("99")));
        pacienteConEstado("ACTIVO");
        when(secuencia.siguiente()).thenReturn(Mono.just(1L));
        when(repositorio.save(any(Pago.class))).thenReturn(Mono.error(new DuplicateKeyException("dup")));
        errorEsperado(servicio.registrar(CLAVE, peticion("80")), HttpStatus.UNPROCESSABLE_ENTITY,
                "CLAVE_IDEMPOTENCIA_REUTILIZADA");
    }

    @Test
    void mismaClaveYMismoCuerpoDevuelveElExistenteSinEfectos() {
        Pago existente = existente("80");
        when(repositorio.findByClaveIdempotencia(CLAVE)).thenReturn(Mono.just(existente));
        StepVerifier.create(servicio.registrar(CLAVE, peticion("80.00")))
                .assertNext(r -> {
                    assertThat(r.repetido()).isTrue();
                    assertThat(r.pago().id()).isEqualTo(existente.getId());
                }).verifyComplete();
        verify(repositorio, never()).save(any());
        verifyNoInteractions(pacientes, secuencia, publicador);
    }

    @Test
    void mismaClaveConOtroMontoEs422() {
        when(repositorio.findByClaveIdempotencia(CLAVE)).thenReturn(Mono.just(existente("80")));
        errorEsperado(servicio.registrar(CLAVE, peticion("90")), HttpStatus.UNPROCESSABLE_ENTITY,
                "CLAVE_IDEMPOTENCIA_REUTILIZADA");
        verify(repositorio, never()).save(any());
        verifyNoInteractions(pacientes);
    }

    @Test
    void claveInvalidaEs400() {
        errorEsperado(servicio.registrar("corta", peticion("80")), HttpStatus.BAD_REQUEST,
                "CLAVE_IDEMPOTENCIA_REQUERIDA");
        verifyNoInteractions(repositorio);
    }

    @Test
    void anularPublicaPagoAnulado() {
        Pago pago = existente("80");
        when(repositorio.findById(pago.getId())).thenReturn(Mono.just(pago));
        guardaEcoDelArgumento();
        when(publicador.publicar(anyString(), any())).thenReturn(Mono.empty());

        StepVerifier.create(servicio.anular(pago.getId(), new AnularPagoRequest("Pago registrado por error")))
                .assertNext(r -> assertThat(r.estado()).isEqualTo(EstadoPago.ANULADO)).verifyComplete();

        ArgumentCaptor<EventoEnvelope> captor = ArgumentCaptor.forClass(EventoEnvelope.class);
        verify(publicador).publicar(eq("pago.anulado"), captor.capture());
        EventoEnvelope e = captor.getValue();
        assertThat(e.estadoAnterior()).isEqualTo("REGISTRADO");
        assertThat(e.estadoNuevo()).isEqualTo("ANULADO");
        assertThat(e.datos().get("motivo").asText()).isEqualTo("Pago registrado por error");
    }

    @Test
    void anularInexistenteEs404() {
        UUID id = UUID.randomUUID();
        when(repositorio.findById(id)).thenReturn(Mono.empty());
        errorEsperado(servicio.anular(id, new AnularPagoRequest("motivo")), HttpStatus.NOT_FOUND,
                "PAGO_NO_ENCONTRADO");
    }

    @Test
    void anularYaAnuladoEs409() {
        Pago pago = existente("80");
        pago.anular("antes", RELOJ);
        when(repositorio.findById(pago.getId())).thenReturn(Mono.just(pago));
        errorEsperado(servicio.anular(pago.getId(), new AnularPagoRequest("otra vez")), HttpStatus.CONFLICT,
                "PAGO_YA_ANULADO");
        verify(repositorio, never()).save(any());
        verifyNoInteractions(publicador);
    }

    @Test
    void anularConConflictoDeVersionEs409() {
        Pago pago = existente("80");
        when(repositorio.findById(pago.getId())).thenReturn(Mono.just(pago));
        when(repositorio.save(any(Pago.class))).thenReturn(Mono.error(new OptimisticLockingFailureException("v")));
        errorEsperado(servicio.anular(pago.getId(), new AnularPagoRequest("motivo")), HttpStatus.CONFLICT,
                "CONFLICTO_CONCURRENTE");
        verifyNoInteractions(publicador);
    }

    @Test
    void obtenerInexistenteEs404() {
        UUID id = UUID.randomUUID();
        when(repositorio.findById(id)).thenReturn(Mono.empty());
        errorEsperado(servicio.obtener(id), HttpStatus.NOT_FOUND, "PAGO_NO_ENCONTRADO");
    }
}
