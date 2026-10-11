package pe.edu.cibertec.vidasalud.pacientes.api.dto;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.UUID;
import pe.edu.cibertec.vidasalud.commons.reloj.RelojAutoConfiguration;
import pe.edu.cibertec.vidasalud.pacientes.domain.EstadoPaciente;
import pe.edu.cibertec.vidasalud.pacientes.domain.Paciente;
import pe.edu.cibertec.vidasalud.pacientes.domain.TipoDocumento;

public record PacienteResponse(UUID id, TipoDocumento tipoDocumento, String numeroDocumento, String nombres,
                               String apellidos, LocalDate fechaNacimiento, String telefono, String correo,
                               String direccion, OffsetDateTime fechaRegistro, EstadoPaciente estado) {

    public static PacienteResponse desde(Paciente p) {
        // El driver devuelve TIMESTAMPTZ en la zona de la JVM; la API siempre responde en la del hospital.
        OffsetDateTime registro = p.getFechaRegistro()
                .atZoneSameInstant(RelojAutoConfiguration.ZONA_HOSPITAL).toOffsetDateTime();
        return new PacienteResponse(p.getId(), p.getTipoDocumento(), p.getNumeroDocumento(), p.getNombres(),
                p.getApellidos(), p.getFechaNacimiento(), p.getTelefono(), p.getCorreo(), p.getDireccion(),
                registro, p.getEstado());
    }
}
