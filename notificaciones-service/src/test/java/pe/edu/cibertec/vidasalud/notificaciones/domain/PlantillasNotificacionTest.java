package pe.edu.cibertec.vidasalud.notificaciones.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class PlantillasNotificacionTest {

    private static final UUID PACIENTE = UUID.fromString("11111111-1111-4111-8111-111111111111");
    private static final UUID CITA = UUID.fromString("22222222-2222-4222-8222-222222222222");
    private static final OffsetDateTime FECHA = OffsetDateTime.parse("2026-10-15T15:00:00Z");
    private static final OffsetDateTime ANTERIOR = OffsetDateTime.parse("2026-10-14T14:30:00Z");

    private static DatosEvento cita(String codigo, String especialidad) {
        return new DatosEvento(PACIENTE, codigo, especialidad, FECHA, ANTERIOR, null, null, null, null, null);
    }

    private static String texto(String tipo, DatosEvento d) {
        return PlantillasNotificacion.generar(tipo, d).orElseThrow();
    }

    private static final String COD = "CIT-20261012-000001";

    @Test
    void pacienteRegistrado() {
        assertThat(texto("paciente.registrado", cita(null, null))).isEqualTo(
                "Bienvenido/a al Hospital Vida Salud. Su registro fue exitoso (paciente "
                        + "11111111-1111-4111-8111-111111111111).");
    }

    @Test
    void citaCreada() {
        assertThat(texto("cita.creada", cita(COD, "Medicina General"))).isEqualTo(
                "Su cita CIT-20261012-000001 de Medicina General fue registrada para el 15/10/2026 10:00. "
                        + "Está pendiente de confirmación.");
    }

    @Test
    void citaConfirmada() {
        assertThat(texto("cita.confirmada", cita(COD, "Medicina General"))).isEqualTo(
                "Su cita CIT-20261012-000001 de Medicina General del 15/10/2026 10:00 fue confirmada.");
    }

    @Test
    void citaReprogramada() {
        assertThat(texto("cita.reprogramada", cita(COD, "Medicina General"))).isEqualTo(
                "Su cita CIT-20261012-000001 de Medicina General fue reprogramada del 14/10/2026 09:30 "
                        + "al 15/10/2026 10:00.");
    }

    @Test
    void citaCancelada() {
        assertThat(texto("cita.cancelada", cita(COD, "Medicina General"))).isEqualTo(
                "Su cita CIT-20261012-000001 de Medicina General del 15/10/2026 10:00 fue cancelada.");
    }

    @Test
    void atencionRegistrada() {
        DatosEvento d = new DatosEvento(PACIENTE, "ATE-20261015-000001", null, null, null, "Consulta Externa",
                OffsetDateTime.parse("2026-10-15T15:05:00Z"), CITA, null, null);

        assertThat(texto("atencion.registrada", d)).isEqualTo(
                "Su atención ATE-20261015-000001 en Consulta Externa fue registrada el 15/10/2026 10:05 "
                        + "(cita 22222222-2222-4222-8222-222222222222).");
    }

    @Test
    void pagoRegistradoConDosDecimales() {
        DatosEvento d = new DatosEvento(PACIENTE, null, null, null, null, null, null, null,
                new BigDecimal("80"), "OP-20261012-000001");

        assertThat(texto("pago.registrado", d))
                .isEqualTo("Se registró su pago de S/ 80.00 (operación OP-20261012-000001).");
    }

    @Test
    void pagoAnulado() {
        DatosEvento d = new DatosEvento(PACIENTE, null, null, null, null, null, null, null, null,
                "OP-20261012-000001");

        assertThat(texto("pago.anulado", d)).isEqualTo("Su pago con operación OP-20261012-000001 fue anulado.");
    }

    @Test
    void tipoDesconocidoONuloNoTienePlantilla() {
        assertThat(PlantillasNotificacion.generar("cita.atendida", cita(COD, "x"))).isEmpty();
        assertThat(PlantillasNotificacion.generar(null, cita(COD, "x"))).isEmpty();
    }

    @Test
    void campoAusenteSeEscribeComoNoDisponible() {
        assertThat(texto("cita.confirmada", cita(null, "Medicina General"))).contains("N/D");
    }

    @Test
    void textoNuncaSuperaElLargoMaximo() {
        String texto = texto("cita.confirmada", cita(COD, "E".repeat(400)));

        assertThat(texto).hasSize(PlantillasNotificacion.LARGO_MAXIMO);
    }
}
