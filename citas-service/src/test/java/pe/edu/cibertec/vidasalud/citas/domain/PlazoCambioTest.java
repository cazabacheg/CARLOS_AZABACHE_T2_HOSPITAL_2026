package pe.edu.cibertec.vidasalud.citas.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.DayOfWeek;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.util.EnumSet;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.HttpStatus;
import pe.edu.cibertec.vidasalud.commons.error.ErrorNegocio;

class PlazoCambioTest {

    private final ReglasAgenda reglas = new ReglasAgenda(ReglasAgendaTest.RELOJ, ParametrosAgenda.porDefecto());

    @Test
    void permiteACanceladoCon25Horas() {
        assertThatCode(() -> reglas.validarPlazoCambio(OffsetDateTime.parse("2026-10-15T10:00:00-05:00")))
                .doesNotThrowAnyException();
    }

    @ParameterizedTest
    @ValueSource(strings = {"2026-10-15T08:59:00-05:00", "2026-10-15T09:00:00-05:00", "2026-10-14T11:00:00-05:00"})
    void rechazaDentroDelPlazo(String fecha) {
        assertThatThrownBy(() -> reglas.validarPlazoCambio(OffsetDateTime.parse(fecha)))
                .isInstanceOfSatisfying(ErrorNegocio.class, e -> {
                    assertThat(e.getStatus()).isEqualTo(HttpStatus.CONFLICT);
                    assertThat(e.getCodigo()).isEqualTo("FUERA_DE_PLAZO");
                });
    }

    @Test
    void domingoHabilitadoPorConfiguracion() {
        ParametrosAgenda todos = new ParametrosAgenda(EnumSet.allOf(DayOfWeek.class), LocalTime.of(8, 0),
                LocalTime.of(17, 30), 30, 90, 24);
        ReglasAgenda siete = new ReglasAgenda(ReglasAgendaTest.RELOJ, todos);
        assertThatCode(() -> siete.validar(OffsetDateTime.parse("2026-10-18T10:00:00-05:00")))
                .doesNotThrowAnyException();
    }
}
