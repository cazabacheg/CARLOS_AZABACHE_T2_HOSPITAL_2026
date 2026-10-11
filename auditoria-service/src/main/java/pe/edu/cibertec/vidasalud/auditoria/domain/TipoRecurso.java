package pe.edu.cibertec.vidasalud.auditoria.domain;

import pe.edu.cibertec.vidasalud.commons.error.ErrorNegocio;

public enum TipoRecurso {
    PACIENTE, CITA, ATENCION, PAGO;

    private static final String MENSAJE = "debe ser uno de: PACIENTE, CITA, ATENCION, PAGO";

    public static TipoRecurso desdeOpcional(String valor) {
        if (valor == null || valor.isBlank()) {
            return null;
        }
        return desde(valor);
    }

    public static TipoRecurso desde(String valor) {
        if (valor != null) {
            for (TipoRecurso tipo : values()) {
                if (tipo.name().equals(valor)) {
                    return tipo;
                }
            }
        }
        throw ErrorNegocio.validacion("tipoRecurso", MENSAJE);
    }
}
