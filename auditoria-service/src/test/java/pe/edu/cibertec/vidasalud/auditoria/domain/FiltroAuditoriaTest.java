package pe.edu.cibertec.vidasalud.auditoria.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.OffsetDateTime;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import pe.edu.cibertec.vidasalud.commons.error.ErrorNegocio;

class FiltroAuditoriaTest {

    private static final OffsetDateTime T1 = OffsetDateTime.parse("2026-10-01T00:00:00-05:00");
    private static final OffsetDateTime T2 = OffsetDateTime.parse("2026-10-31T00:00:00-05:00");

    @Test
    void desdePosteriorAHastaEsInvalido() {
        assertThatThrownBy(() -> FiltroAuditoria.de(null, null, null, null, T2, T1))
                .isInstanceOfSatisfying(ErrorNegocio.class, e -> {
                    assertThat(e.getStatus()).isEqualTo(HttpStatus.BAD_REQUEST);
                    assertThat(e.getCodigo()).isEqualTo("VALIDACION_FALLIDA");
                    assertThat(e.getErrores()).anyMatch(c -> c.campo().equals("desde"));
                });
    }

    @Test
    void desdeIgualAHastaYSoloUnLimiteSonValidos() {
        assertThat(FiltroAuditoria.de(null, null, null, null, T1, T1).desde()).isEqualTo(T1);
        assertThat(FiltroAuditoria.de(null, null, null, null, T1, null).hasta()).isNull();
        assertThat(FiltroAuditoria.de(null, null, null, null, null, T2).desde()).isNull();
    }

    @Test
    void textosEnBlancoPasanANulo() {
        FiltroAuditoria f = FiltroAuditoria.de("CITA", null, "  ", " ", null, null);
        assertThat(f.tipoEvento()).isNull();
        assertThat(f.origen()).isNull();
        assertThat(f.tipoRecurso()).isEqualTo(TipoRecurso.CITA);
    }
}
