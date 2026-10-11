package pe.edu.cibertec.vidasalud.atenciones.domain;

public enum AreaAtencion {
    CONSULTA_EXTERNA("Consulta Externa"),
    EMERGENCIA("Emergencia"),
    TRIAJE("Triaje"),
    PROCEDIMIENTOS("Procedimientos");

    private final String nombre;

    AreaAtencion(String nombre) {
        this.nombre = nombre;
    }

    public String nombre() {
        return nombre;
    }
}
