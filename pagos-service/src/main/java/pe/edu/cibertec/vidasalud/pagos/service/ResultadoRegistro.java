package pe.edu.cibertec.vidasalud.pagos.service;

import pe.edu.cibertec.vidasalud.pagos.api.dto.PagoResponse;

public record ResultadoRegistro(PagoResponse pago, boolean repetido) {
}
