package pe.edu.cibertec.vidasalud.citas.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.http.HttpStatus;
import pe.edu.cibertec.vidasalud.commons.error.ErrorNegocio;

class ReglasAgendaTest {

    static final Clock RELOJ = Clock.fixed(Instant.parse("2026-10-14T14:00:00Z"), ZoneId.of("America/Lima"));

    private final ReglasAgenda reglas = new ReglasAgenda(RELOJ, ParametrosAgenda.porDefecto());

    static Stream<String> validas() {
        return Stream.of("2026-10-15T10:00:00-05:00", "2026-10-15T17:30:00-05:00", "2027-01-12T10:00:00-05:00",
                "2026-10-15T22:00:00Z");
    }

    @ParameterizedTest
    @MethodSource("validas")
    void aceptaFechasValidas(String fecha) {
        assertThatCode(() -> reglas.validar(OffsetDateTime.parse(fecha))).doesNotThrowAnyException();
    }

    static Stream<Arguments> invalidas() {
        String futura = "La cita debe programarse en una fecha futura.";
        String dia = "No se atiende ese día.";
        String horario = "Horario de atención: 08:00 a 17:30.";
        String bloques = "Las citas se programan en bloques de 30 minutos.";
        return Stream.of(
                Arguments.of("2026-10-14T08:30:00-05:00", futura),
                Arguments.of("2026-10-14T09:00:00-05:00", futura),
                Arguments.of("2026-10-18T10:00:00-05:00", dia),
                Arguments.of("2026-10-15T07:30:00-05:00", horario),
                Arguments.of("2026-10-15T18:00:00-05:00", horario),
                Arguments.of("2026-10-15T10:15:00-05:00", bloques),
                Arguments.of("2026-10-15T10:00:30-05:00", bloques),
                Arguments.of("2027-01-13T10:00:00-05:00", "Solo se programan citas con hasta 90 días de anticipación."),
                Arguments.of("2026-10-19T02:00:00Z", dia));
    }

    @ParameterizedTest
    @MethodSource("invalidas")
    void rechazaFechasInvalidas(String fecha, String detalle) {
        assertThatThrownBy(() -> reglas.validar(OffsetDateTime.parse(fecha)))
                .isInstanceOfSatisfying(ErrorNegocio.class, e -> {
                    assertThat(e.getStatus()).isEqualTo(HttpStatus.BAD_REQUEST);
                    assertThat(e.getCodigo()).isEqualTo("HORARIO_FUERA_DE_AGENDA");
                    assertThat(e.getDetalle()).isEqualTo(detalle);
                });
    }
}
