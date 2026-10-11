package pe.edu.cibertec.vidasalud.pagos.domain;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.UUID;

public final class HuellaSolicitud {

    private HuellaSolicitud() {
    }

    public static String calcular(UUID pacienteId, UUID citaId, String concepto, BigDecimal monto,
                                  MedioPago medioPago) {
        String cadena = texto(pacienteId) + "|" + texto(citaId) + "|" + (concepto == null ? "" : concepto.trim())
                + "|" + montoCanonico(monto) + "|" + (medioPago == null ? "" : medioPago.name());
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256").digest(cadena.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 no disponible", e);
        }
    }

    private static String texto(UUID id) {
        return id == null ? "" : id.toString();
    }

    private static String montoCanonico(BigDecimal monto) {
        if (monto == null) {
            return "";
        }
        BigDecimal n = monto.stripTrailingZeros();
        if (n.scale() < 2) {
            n = n.setScale(2);
        }
        return n.toPlainString();
    }
}
