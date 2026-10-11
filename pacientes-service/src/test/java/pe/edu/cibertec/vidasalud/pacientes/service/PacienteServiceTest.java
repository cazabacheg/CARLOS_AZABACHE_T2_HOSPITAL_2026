package pe.edu.cibertec.vidasalud.pacientes.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.JsonNode;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import pe.edu.cibertec.vidasalud.commons.error.ErrorNegocio;
import pe.edu.cibertec.vidasalud.commons.messaging.EventoEnvelope;
import pe.edu.cibertec.vidasalud.commons.messaging.PublicadorEventos;
import pe.edu.cibertec.vidasalud.commons.paginacion.ParametrosPagina;
import pe.edu.cibertec.vidasalud.pacientes.api.dto.CambioEstadoRequest;
import pe.edu.cibertec.vidasalud.pacientes.api.dto.PacienteActualizarRequest;
import pe.edu.cibertec.vidasalud.pacientes.api.dto.PacienteCrearRequest;
import pe.edu.cibertec.vidasalud.pacientes.domain.DatosPersonales;
import pe.edu.cibertec.vidasalud.pacientes.domain.ErroresPaciente;
import pe.edu.cibertec.vidasalud.pacientes.domain.EstadoPaciente;
import pe.edu.cibertec.vidasalud.pacientes.domain.FiltroPacientes;
import pe.edu.cibertec.vidasalud.pacientes.domain.Paciente;
import pe.edu.cibertec.vidasalud.pacientes.domain.TipoDocumento;
import pe.edu.cibertec.vidasalud.pacientes.messaging.EventosPaciente;
import pe.edu.cibertec.vidasalud.pacientes.persistence.BusquedaPacientes;
import pe.edu.cibertec.vidasalud.pacientes.persistence.PacienteRepository;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

@ExtendWith(MockitoExtension.class)
class PacienteServiceTest {

    private static final Clock RELOJ = Clock.fixed(Instant.parse("2026-10-12T14:30:00Z"), ZoneId.of("America/Lima"));

    @Mock
    PacienteRepository repositorio;
    @Mock
    BusquedaPacientes busqueda;
    @Mock
    PublicadorEventos publicador;

    PacienteService servicio;

    @BeforeEach
    void preparar() {
        servicio = new PacienteService(repositorio, busqueda, new EventosPaciente(Optional.of(publicador), RELOJ),
                RELOJ);
    }

    private static PacienteCrearRequest crearRequest(String numero) {
        return new PacienteCrearRequest(TipoDocumento.DNI, numero, "Ana María", "Prueba Ficticia",
                LocalDate.of(1990, 5, 20), "999000111", "ana.prueba@example.com", null);
    }

    private static Paciente existente() {
        return Paciente.registrar(TipoDocumento.DNI, "00000001", new DatosPersonales("Ana María",
                "Prueba Ficticia", LocalDate.of(1990, 5, 20), "999000111", "ana.prueba@example.com", null), RELOJ)
                .marcarComoExistente();
    }

    private static void esperaError(Mono<?> mono, HttpStatus status, String codigo) {
        StepVerifier.create(mono)
                .expectErrorSatisfies(e -> {
                    assertThat(e).isInstanceOf(ErrorNegocio.class);
                    ErrorNegocio error = (ErrorNegocio) e;
                    assertThat(error.getStatus()).isEqualTo(status);
                    assertThat(error.getCodigo()).isEqualTo(codigo);
                })
                .verify();
    }

    private void guardarDevuelveArgumento() {
        when(repositorio.save(any(Paciente.class))).thenAnswer(inv -> Mono.just(inv.getArgument(0)));
    }

    @Test
    void registroValidoGuardaYPublicaEventoSinDatosPersonales() {
        when(repositorio.existsByTipoDocumentoAndNumeroDocumento(TipoDocumento.DNI, "00000001"))
                .thenReturn(Mono.just(false));
        guardarDevuelveArgumento();
        when(publicador.publicar(anyString(), any())).thenReturn(Mono.empty());

        var respuesta = servicio.registrar(crearRequest("00000001")).block();

        assertThat(respuesta).isNotNull();
        assertThat(respuesta.estado()).isEqualTo(EstadoPaciente.ACTIVO);
        ArgumentCaptor<EventoEnvelope> captor = ArgumentCaptor.forClass(EventoEnvelope.class);
        verify(publicador).publicar(eq("paciente.registrado"), captor.capture());
        EventoEnvelope evento = captor.getValue();
        assertThat(evento.tipo()).isEqualTo("paciente.registrado");
        assertThat(evento.origen()).isEqualTo("pacientes-service");
        assertThat(evento.tipoRecurso()).isEqualTo("PACIENTE");
        assertThat(evento.recursoId()).isEqualTo(respuesta.id());
        assertThat(evento.estadoAnterior()).isNull();
        assertThat(evento.estadoNuevo()).isEqualTo("ACTIVO");
        JsonNode datos = evento.datos();
        List<String> campos = new ArrayList<>();
        datos.fieldNames().forEachRemaining(campos::add);
        assertThat(campos).containsExactlyInAnyOrder("pacienteId", "fechaRegistro");
        assertThat(datos.get("pacienteId").asText()).isEqualTo(respuesta.id().toString());
        assertThat(datos.toString()).doesNotContain("00000001", "Ana", "example.com");
    }

    @Test
    void registroDuplicadoPorConsultaPreviaNoGuardaNiPublica() {
        when(repositorio.existsByTipoDocumentoAndNumeroDocumento(TipoDocumento.DNI, "00000001"))
                .thenReturn(Mono.just(true));

        esperaError(servicio.registrar(crearRequest("00000001")), HttpStatus.CONFLICT,
                ErroresPaciente.PACIENTE_DUPLICADO);

        verify(repositorio, never()).save(any());
        verifyNoInteractions(publicador);
    }

    @Test
    void registroConViolacionDeUnicidadSeTraduceADuplicado() {
        when(repositorio.existsByTipoDocumentoAndNumeroDocumento(TipoDocumento.DNI, "00000001"))
                .thenReturn(Mono.just(false));
        when(repositorio.save(any(Paciente.class))).thenReturn(Mono.error(new DuplicateKeyException("uq")));

        esperaError(servicio.registrar(crearRequest("00000001")), HttpStatus.CONFLICT,
                ErroresPaciente.PACIENTE_DUPLICADO);

        verifyNoInteractions(publicador);
    }

    @Test
    void registroConDocumentoInvalidoDevuelveValidacionSinTocarNada() {
        esperaError(servicio.registrar(crearRequest("0000000A")), HttpStatus.BAD_REQUEST, "VALIDACION_FALLIDA");

        verifyNoInteractions(repositorio, publicador);
    }

    @Test
    void registroCompletaAunqueFalleElPublicador() {
        when(repositorio.existsByTipoDocumentoAndNumeroDocumento(TipoDocumento.DNI, "00000001"))
                .thenReturn(Mono.just(false));
        guardarDevuelveArgumento();
        when(publicador.publicar(anyString(), any())).thenReturn(Mono.error(new IllegalStateException()));

        StepVerifier.create(servicio.registrar(crearRequest("00000001")))
                .assertNext(r -> assertThat(r.estado()).isEqualTo(EstadoPaciente.ACTIVO))
                .verifyComplete();
    }

    @Test
    void registroCompletaSinPublicadorDisponible() {
        var sinPublicador = new PacienteService(repositorio, busqueda,
                new EventosPaciente(Optional.empty(), RELOJ), RELOJ);
        when(repositorio.existsByTipoDocumentoAndNumeroDocumento(TipoDocumento.DNI, "00000001"))
                .thenReturn(Mono.just(false));
        guardarDevuelveArgumento();

        StepVerifier.create(sinPublicador.registrar(crearRequest("00000001")))
                .expectNextCount(1)
                .verifyComplete();
    }

    @Test
    void obtenerInexistenteDevuelveNoEncontrado() {
        UUID id = UUID.randomUUID();
        when(repositorio.findById(id)).thenReturn(Mono.empty());

        esperaError(servicio.obtener(id), HttpStatus.NOT_FOUND, ErroresPaciente.PACIENTE_NO_ENCONTRADO);
    }

    @Test
    void obtenerExistenteDevuelveElDto() {
        Paciente paciente = existente();
        when(repositorio.findById(paciente.getId())).thenReturn(Mono.just(paciente));

        StepVerifier.create(servicio.obtener(paciente.getId()))
                .assertNext(r -> assertThat(r.id()).isEqualTo(paciente.getId()))
                .verifyComplete();
    }

    @Test
    void actualizarExistenteGuardaDatosNuevosSinPublicar() {
        Paciente paciente = existente();
        when(repositorio.findById(paciente.getId())).thenReturn(Mono.just(paciente));
        guardarDevuelveArgumento();
        var req = new PacienteActualizarRequest("Ana Lucía", "Prueba Ficticia", LocalDate.of(1990, 5, 20),
                "999000222", "nuevo@example.com", "Calle Falsa 123");

        StepVerifier.create(servicio.actualizar(paciente.getId(), req))
                .assertNext(r -> {
                    assertThat(r.nombres()).isEqualTo("Ana Lucía");
                    assertThat(r.correo()).isEqualTo("nuevo@example.com");
                    assertThat(r.numeroDocumento()).isEqualTo("00000001");
                    assertThat(r.tipoDocumento()).isEqualTo(TipoDocumento.DNI);
                })
                .verifyComplete();

        verify(repositorio).save(paciente);
        verifyNoInteractions(publicador);
    }

    @Test
    void actualizarInexistenteDevuelveNoEncontradoSinGuardar() {
        UUID id = UUID.randomUUID();
        when(repositorio.findById(id)).thenReturn(Mono.empty());
        var req = new PacienteActualizarRequest("Ana", "Prueba", LocalDate.of(1990, 5, 20), null, null, null);

        esperaError(servicio.actualizar(id, req), HttpStatus.NOT_FOUND, ErroresPaciente.PACIENTE_NO_ENCONTRADO);

        verify(repositorio, never()).save(any());
    }

    @Test
    void cambioDeEstadoGuardaYPublicaEventoConMotivo() {
        Paciente paciente = existente();
        when(repositorio.findById(paciente.getId())).thenReturn(Mono.just(paciente));
        guardarDevuelveArgumento();
        when(publicador.publicar(anyString(), any())).thenReturn(Mono.empty());

        StepVerifier.create(servicio.cambiarEstado(paciente.getId(),
                        new CambioEstadoRequest(EstadoPaciente.INACTIVO, "Solicitud del paciente")))
                .assertNext(r -> assertThat(r.estado()).isEqualTo(EstadoPaciente.INACTIVO))
                .verifyComplete();

        ArgumentCaptor<EventoEnvelope> captor = ArgumentCaptor.forClass(EventoEnvelope.class);
        verify(publicador).publicar(eq("paciente.estado-cambiado"), captor.capture());
        EventoEnvelope evento = captor.getValue();
        assertThat(evento.estadoAnterior()).isEqualTo("ACTIVO");
        assertThat(evento.estadoNuevo()).isEqualTo("INACTIVO");
        assertThat(evento.datos().get("motivo").asText()).isEqualTo("Solicitud del paciente");
        assertThat(evento.datos().get("pacienteId").asText()).isEqualTo(paciente.getId().toString());
    }

    @Test
    void cambioAlMismoEstadoSeRechazaSinGuardarNiPublicar() {
        Paciente paciente = existente();
        when(repositorio.findById(paciente.getId())).thenReturn(Mono.just(paciente));

        esperaError(servicio.cambiarEstado(paciente.getId(), new CambioEstadoRequest(EstadoPaciente.ACTIVO, null)),
                HttpStatus.CONFLICT, ErroresPaciente.TRANSICION_NO_PERMITIDA);

        verify(repositorio, never()).save(any());
        verifyNoInteractions(publicador);
    }

    @Test
    void listarYContarDelegaEnLaBusqueda() {
        FiltroPacientes filtro = FiltroPacientes.de(null, null, null, null);
        ParametrosPagina pagina = new ParametrosPagina(0, 20);
        when(busqueda.buscar(filtro, pagina)).thenReturn(Flux.just(existente()));
        when(busqueda.contar(filtro)).thenReturn(Mono.just(1L));

        StepVerifier.create(servicio.listar(filtro, pagina)).expectNextCount(1).verifyComplete();
        StepVerifier.create(servicio.contar(filtro)).expectNext(1L).verifyComplete();
    }
}
