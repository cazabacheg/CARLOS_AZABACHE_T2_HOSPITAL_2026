package pe.edu.cibertec.vidasalud.auditoria.domain;

import java.time.OffsetDateTime;
import java.util.UUID;
import pe.edu.cibertec.vidasalud.commons.error.ErrorNegocio;

public record FiltroAuditoria(TipoRecurso tipoRecurso, UUID recursoId, String tipoEvento,
                              String origen, OffsetDateTime desde, OffsetDateTime hasta) {

    public FiltroAuditoria {
        tipoEvento = enBlancoANulo(tipoEvento);
        origen = enBlancoANulo(origen);
        if (desde != null && hasta != null && desde.isAfter(hasta)) {
            throw ErrorNegocio.validacion("desde", "debe ser anterior o igual a hasta");
        }
    }

    public static FiltroAuditoria de(String tipoRecurso, UUID recursoId, String tipoEvento,
                                     String origen, OffsetDateTime desde, OffsetDateTime hasta) {
        return new FiltroAuditoria(TipoRecurso.desdeOpcional(tipoRecurso), recursoId, tipoEvento,
                origen, desde, hasta);
    }

    public static FiltroAuditoria vacio() {
        return new FiltroAuditoria(null, null, null, null, null, null);
    }

    private static String enBlancoANulo(String valor) {
        return valor == null || valor.isBlank() ? null : valor.strip();
    }
}
