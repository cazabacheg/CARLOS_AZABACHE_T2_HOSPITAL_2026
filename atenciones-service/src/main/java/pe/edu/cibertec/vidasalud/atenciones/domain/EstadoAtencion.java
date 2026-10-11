package pe.edu.cibertec.vidasalud.atenciones.domain;

public enum EstadoAtencion {
    REGISTRADA, FINALIZADA, ANULADA;

    /** REGISTRADA pasa a FINALIZADA o ANULADA; ambos son finales. */
    public boolean puedeCambiarA(EstadoAtencion destino) {
        return switch (this) {
            case REGISTRADA -> destino == FINALIZADA || destino == ANULADA;
            case FINALIZADA, ANULADA -> false;
        };
    }

    public boolean esFinal() {
        return this != REGISTRADA;
    }
}
