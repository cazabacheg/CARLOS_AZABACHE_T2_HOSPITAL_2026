package pe.edu.cibertec.vidasalud.pacientes.domain;

import java.time.LocalDate;

public record DatosPersonales(String nombres, String apellidos, LocalDate fechaNacimiento,
                              String telefono, String correo, String direccion) {
}
