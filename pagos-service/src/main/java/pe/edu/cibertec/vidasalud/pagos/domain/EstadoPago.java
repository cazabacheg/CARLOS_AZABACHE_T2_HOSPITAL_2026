package pe.edu.cibertec.vidasalud.pagos.domain;

public enum EstadoPago {
    REGISTRADO, ANULADO;

    public boolean esFinal() {
        return this == ANULADO;
    }
}
