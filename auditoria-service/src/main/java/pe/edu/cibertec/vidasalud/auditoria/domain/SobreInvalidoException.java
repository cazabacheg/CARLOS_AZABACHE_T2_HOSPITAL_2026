package pe.edu.cibertec.vidasalud.auditoria.domain;

public class SobreInvalidoException extends RuntimeException {

    public SobreInvalidoException(String mensaje) {
        super(mensaje, null, false, false);
    }
}
