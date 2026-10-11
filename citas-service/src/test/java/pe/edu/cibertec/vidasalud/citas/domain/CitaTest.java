package pe.edu.cibertec.vidasalud.citas.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import pe.edu.cibertec.vidasalud.commons.error.ErrorNegocio;

class CitaTest {

    static final Clock RELOJ = ReglasAgendaTest.RELOJ;
    static final OffsetDateTime FECHA = OffsetDateTime.parse("2026-10-15T10:00:00-05:00");

    static Cita nueva(OffsetDateTime fecha) {
        return Cita.programar(new DatosNuevaCita(UUID.randomUUID(), Especialidad.MEDICINA_GENERAL, fecha,
                "MED-001", "Control"), "CIT-20261014-000001", RELOJ);
    }

    private static void esTransicionNoPermitida(Throwable e, String detalle) {
        assertThat(e).isInstanceOfSatisfying(ErrorNegocio.class, n -> {
            assertThat(n.getStatus()).isEqualTo(HttpStatus.CONFLICT);
            assertThat(n.getCodigo()).isEqualTo("TRANSICION_NO_PERMITIDA");
            assertThat(n.getDetalle()).isEqualTo(detalle);
        });
    }

    @Test
    void programarDejaPendiente() {
        Cita cita = nueva(OffsetDateTime.parse("2026-10-15T15:00:00Z"));
        assertThat(cita.getEstado()).isEqualTo(EstadoCita.PENDIENTE);
        assertThat(cita.isNew()).isTrue();
        assertThat(cita.getVersion()).isNull();
        assertThat(cita.getId()).isNotNull();
        assertThat(cita.getFechaCreacion()).isEqualTo(OffsetDateTime.parse("2026-10-14T09:00:00-05:00"));
        assertThat(cita.getFechaActualizacion()).isEqualTo(cita.getFechaCreacion());
        assertThat(cita.getFechaHora().getOffset().getTotalSeconds()).isEqualTo(-5 * 3600);
        assertThat(cita.getFechaHora().isEqual(FECHA)).isTrue();
    }

    @Test
    void cambiarEstadoDevuelveElAnteriorYActualizaFecha() {
        Cita cita = nueva(FECHA);
        Clock masTarde = Clock.fixed(Instant.parse("2026-10-14T15:00:00Z"), ZoneId.of("America/Lima"));
        assertThat(cita.cambiarEstado(EstadoCita.CONFIRMADA, masTarde)).isEqualTo(EstadoCita.PENDIENTE);
        assertThat(cita.getEstado()).isEqualTo(EstadoCita.CONFIRMADA);
        assertThat(cita.getFechaActualizacion()).isEqualTo(OffsetDateTime.parse("2026-10-14T10:00:00-05:00"));
    }

    @Test
    void confirmarUnaCanceladaEsConflicto() {
        Cita cita = nueva(FECHA);
        cita.cambiarEstado(EstadoCita.CANCELADA, RELOJ);
        assertThatThrownBy(() -> cita.cambiarEstado(EstadoCita.CONFIRMADA, RELOJ))
                .satisfies(e -> esTransicionNoPermitida(e, "No se puede pasar una cita de CANCELADA a CONFIRMADA."));
    }

    @Test
    void validarCambioPorApi() {
        Cita pendiente = nueva(FECHA);
        assertThatThrownBy(() -> pendiente.validarCambioPorApi(EstadoCita.ATENDIDA)).satisfies(e ->
                esTransicionNoPermitida(e, "Una cita pasa a ATENDIDA solo al registrar su atención."));
        assertThatThrownBy(() -> pendiente.validarCambioPorApi(EstadoCita.PENDIENTE))
                .isInstanceOf(ErrorNegocio.class);
        Cita atendida = nueva(FECHA);
        atendida.cambiarEstado(EstadoCita.CONFIRMADA, RELOJ);
        atendida.cambiarEstado(EstadoCita.ATENDIDA, RELOJ);
        assertThatThrownBy(() -> atendida.validarCambioPorApi(EstadoCita.CANCELADA))
                .isInstanceOf(ErrorNegocio.class);
    }

    @Test
    void reprogramarDesdeAtendidaEsConflicto() {
        Cita cita = nueva(FECHA);
        cita.cambiarEstado(EstadoCita.CONFIRMADA, RELOJ);
        cita.cambiarEstado(EstadoCita.ATENDIDA, RELOJ);
        assertThatThrownBy(() -> cita.reprogramar(FECHA.plusDays(1), null, RELOJ)).satisfies(e ->
                esTransicionNoPermitida(e, "Solo se reprograman citas pendientes o confirmadas."));
    }

    @Test
    void reprogramarALaMismaFechaEsConflicto() {
        Cita cita = nueva(FECHA);
        assertThatThrownBy(() -> cita.reprogramar(OffsetDateTime.parse("2026-10-15T15:00:00Z"), null, RELOJ))
                .isInstanceOfSatisfying(ErrorNegocio.class,
                        e -> assertThat(e.getCodigo()).isEqualTo("MISMA_FECHA_HORA"));
    }

    @Test
    void reprogramarDesdeConfirmadaDejaPendiente() {
        Cita cita = nueva(FECHA);
        cita.cambiarEstado(EstadoCita.CONFIRMADA, RELOJ);
        OffsetDateTime nueva = FECHA.plusDays(1);
        assertThat(cita.reprogramar(nueva, "MED-002", RELOJ).isEqual(FECHA)).isTrue();
        assertThat(cita.getEstado()).isEqualTo(EstadoCita.PENDIENTE);
        assertThat(cita.getProfesionalCodigo()).isEqualTo("MED-002");
        cita.reprogramar(nueva.plusDays(1), null, RELOJ);
        assertThat(cita.getProfesionalCodigo()).isEqualTo("MED-002");
    }

    @Test
    void yaOcurrio() {
        assertThat(nueva(OffsetDateTime.parse("2026-10-14T08:30:00-05:00")).yaOcurrio(RELOJ)).isTrue();
        assertThat(nueva(OffsetDateTime.parse("2026-10-14T09:00:00-05:00")).yaOcurrio(RELOJ)).isTrue();
        assertThat(nueva(FECHA).yaOcurrio(RELOJ)).isFalse();
    }
}
