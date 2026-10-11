package pe.edu.cibertec.vidasalud.pagos.api.dto;

import static pe.edu.cibertec.vidasalud.commons.reloj.RelojAutoConfiguration.ZONA_HOSPITAL;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.UUID;
import pe.edu.cibertec.vidasalud.pagos.domain.EstadoPago;
import pe.edu.cibertec.vidasalud.pagos.domain.MedioPago;
import pe.edu.cibertec.vidasalud.pagos.domain.Pago;

public record PagoResponse(UUID id, String numeroOperacion, UUID pacienteId, UUID citaId, String concepto,
                           BigDecimal monto, String moneda, OffsetDateTime fechaPago, MedioPago medioPago,
                           EstadoPago estado, String motivoAnulacion, OffsetDateTime fechaActualizacion) {

    public static PagoResponse desde(Pago p) {
        return new PagoResponse(p.getId(), p.getNumeroOperacion(), p.getPacienteId(), p.getCitaId(),
                p.getConcepto(), p.getMonto(), p.getMoneda().trim(),
                p.getFechaPago().atZoneSameInstant(ZONA_HOSPITAL).toOffsetDateTime(), p.getMedioPago(),
                p.getEstado(), p.getMotivoAnulacion(),
                p.getFechaActualizacion().atZoneSameInstant(ZONA_HOSPITAL).toOffsetDateTime());
    }
}
