package pe.edu.cibertec.vidasalud.commons.error;

/** Detalle de un campo invalido dentro de un error de validacion. */
public record ErrorCampo(String campo, String mensaje) {
}
