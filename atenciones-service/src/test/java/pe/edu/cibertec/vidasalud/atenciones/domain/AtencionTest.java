package pe.edu.cibertec.vidasalud.atenciones.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import pe.edu.cibertec.vidasalud.commons.error.ErrorNegocio;

class AtencionTest {

    private static final ZoneId LIMA = ZoneId.of("America/Lima");
    private static final Clock RELOJ = Clock.fixed(Instant.parse("2026-10-14T14:00:00Z"), LIMA);
    private static final Clock RELOJ_POSTERIOR = Clock.fixed(Instant.parse("2026-10-14T15:00:00Z"), LIMA);

    private final CitaResumen cita = new CitaResumen(UUID.randomUUID(), UUID.randomUUID(), "CONFIRMADA",
            OffsetDateTime.parse("2026-10-14T09:30:00-05:00"));

    private Atencion nueva() {
        return Atencion.registrar(cita, AreaAtencion.CONSULTA_EXTERNA, "ATE-20261014-000001", RELOJ);
    }

    @Test
    void registrarInicializaLaAtencion() {
        Atencion a = nueva();
        assertThat(a.getEstado()).isEqualTo(EstadoAtencion.REGISTRADA);
        assertThat(a.getPacienteId()).isEqualTo(cita.pacienteId());
        assertThat(a.getCitaId()).isEqualTo(cita.id());
        assertThat(a.getFechaAtencion().toInstant()).isEqualTo(RELOJ.instant());
        assertThat(a.isNew()).isTrue();
        assertThat(a.getVersion()).isNull();
        assertThat(a.getId()).isNotNull();
    }

    @Test
    void cambiarEstadoDevuelveElAnteriorYActualizaLaFecha() {
        Atencion a = nueva();
        EstadoAtencion anterior = a.cambiarEstado(EstadoAtencion.FINALIZADA, RELOJ_POSTERIOR);
        assertThat(anterior).isEqualTo(EstadoAtencion.REGISTRADA);
        assertThat(a.getEstado()).isEqualTo(EstadoAtencion.FINALIZADA);
        assertThat(a.getFechaActualizacion().toInstant()).isEqualTo(RELOJ_POSTERIOR.instant());
    }

    @Test
    void finalizadaNoPuedeAnularse() {
        Atencion a = nueva();
        a.cambiarEstado(EstadoAtencion.FINALIZADA, RELOJ);
        assertThatThrownBy(() -> a.cambiarEstado(EstadoAtencion.ANULADA, RELOJ))
                .isInstanceOfSatisfying(ErrorNegocio.class, e -> {
                    assertThat(e.getCodigo()).isEqualTo("TRANSICION_NO_PERMITIDA");
                    assertThat(e.getStatus()).isEqualTo(HttpStatus.CONFLICT);
                });
        assertThat(a.getEstado()).isEqualTo(EstadoAtencion.FINALIZADA);
    }

    @Test
    void anuladaNoPuedeFinalizarse() {
        Atencion a = nueva();
        a.cambiarEstado(EstadoAtencion.ANULADA, RELOJ);
        assertThatThrownBy(() -> a.cambiarEstado(EstadoAtencion.FINALIZADA, RELOJ))
                .isInstanceOfSatisfying(ErrorNegocio.class,
                        e -> assertThat(e.getStatus()).isEqualTo(HttpStatus.CONFLICT));
    }

    @Test
    void formateaElCodigo() {
        assertThat(Atencion.formatearCodigo(LocalDate.of(2026, 10, 14), 1)).isEqualTo("ATE-20261014-000001");
        assertThat(Atencion.formatearCodigo(LocalDate.of(2026, 10, 14), 1234567)).isEqualTo("ATE-20261014-1234567");
    }
}
