package pe.edu.cibertec.vidasalud.citas.domain;

public enum Especialidad {
    MEDICINA_GENERAL("Medicina General"),
    PEDIATRIA("Pediatría"),
    CARDIOLOGIA("Cardiología"),
    GINECOLOGIA("Ginecología"),
    TRAUMATOLOGIA("Traumatología"),
    DERMATOLOGIA("Dermatología"),
    OFTALMOLOGIA("Oftalmología");

    private final String nombre;

    Especialidad(String nombre) {
        this.nombre = nombre;
    }

    public String getNombre() {
        return nombre;
    }
}
