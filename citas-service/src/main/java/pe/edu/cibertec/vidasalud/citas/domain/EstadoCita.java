package pe.edu.cibertec.vidasalud.citas.domain;

public enum EstadoCita {
    PENDIENTE, CONFIRMADA, ATENDIDA, CANCELADA, AUSENTE;

    /** Incluye las transiciones de sistema (ATENDIDA a CANCELADA). */
    public boolean puedeCambiarA(EstadoCita destino) {
        if (destino == null) {
            return false;
        }
        return switch (this) {
            case PENDIENTE -> destino == CONFIRMADA || destino == CANCELADA;
            case CONFIRMADA -> destino == ATENDIDA || destino == AUSENTE || destino == CANCELADA;
            case ATENDIDA -> destino == CANCELADA;
            case CANCELADA, AUSENTE -> false;
        };
    }

    public static boolean esDestinoDeApi(EstadoCita destino) {
        return destino == CONFIRMADA || destino == CANCELADA || destino == AUSENTE;
    }

    public boolean permitidoPorApi(EstadoCita destino) {
        return this != ATENDIDA && esDestinoDeApi(destino) && puedeCambiarA(destino);
    }

    public boolean esActivo() {
        return this == PENDIENTE || this == CONFIRMADA;
    }

    public boolean esFinal() {
        return this == CANCELADA || this == AUSENTE;
    }
}
