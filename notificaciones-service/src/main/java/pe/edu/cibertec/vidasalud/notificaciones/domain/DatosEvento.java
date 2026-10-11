package pe.edu.cibertec.vidasalud.notificaciones.domain;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.MissingNode;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeParseException;
import java.util.UUID;

/** Campos de `datos` que usan las plantillas. Tolerante: lo que falta o no se puede leer queda null. */
public record DatosEvento(
        UUID pacienteId,
        String codigo,
        String especialidadNombre,
        OffsetDateTime fechaHora,
        OffsetDateTime fechaHoraAnterior,
        String areaNombre,
        OffsetDateTime fechaAtencion,
        UUID citaId,
        BigDecimal monto,
        String numeroOperacion) {

    static final ZoneId ZONA_LIMA = ZoneId.of("America/Lima");

    public static DatosEvento desde(String tipo, UUID recursoId, JsonNode datos) {
        JsonNode d = (datos == null || !datos.isObject()) ? MissingNode.getInstance() : datos;

        UUID pacienteId = uuid(d, "pacienteId");
        if (pacienteId == null && "paciente.registrado".equals(tipo)) {
            pacienteId = recursoId;
        }
        String especialidad = texto(d, "especialidadNombre");
        if (especialidad == null) {
            especialidad = texto(d, "especialidad");
        }
        String area = texto(d, "areaNombre");
        if (area == null) {
            area = texto(d, "area");
        }
        return new DatosEvento(pacienteId, texto(d, "codigo"), especialidad, fecha(d, "fechaHora"),
                fecha(d, "fechaHoraAnterior"), area, fecha(d, "fechaAtencion"), uuid(d, "citaId"),
                monto(d, "monto"), texto(d, "numeroOperacion"));
    }

    private static String texto(JsonNode n, String campo) {
        JsonNode v = n.path(campo);
        if (v.isMissingNode() || v.isNull()) {
            return null;
        }
        if (v.isTextual()) {
            String t = v.textValue().strip();
            return t.isEmpty() ? null : t;
        }
        return v.asText();
    }

    private static UUID uuid(JsonNode n, String campo) {
        String t = texto(n, campo);
        if (t == null) {
            return null;
        }
        try {
            return UUID.fromString(t);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private static OffsetDateTime fecha(JsonNode n, String campo) {
        String t = texto(n, campo);
        if (t == null) {
            return null;
        }
        try {
            return OffsetDateTime.parse(t);
        } catch (DateTimeParseException e) {
            try {
                return LocalDateTime.parse(t).atZone(ZONA_LIMA).toOffsetDateTime();
            } catch (DateTimeParseException e2) {
                return null;
            }
        }
    }

    private static BigDecimal monto(JsonNode n, String campo) {
        JsonNode v = n.path(campo);
        if (v.isNumber()) {
            return v.decimalValue();
        }
        if (v.isTextual()) {
            try {
                return new BigDecimal(v.textValue().strip());
            } catch (NumberFormatException e) {
                return null;
            }
        }
        return null;
    }
}
