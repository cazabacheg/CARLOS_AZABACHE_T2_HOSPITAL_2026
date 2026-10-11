package pe.edu.cibertec.vidasalud.pacientes.domain;

import java.time.Clock;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.temporal.ChronoUnit;
import java.util.UUID;
import org.springframework.data.annotation.Id;
import org.springframework.data.annotation.PersistenceCreator;
import org.springframework.data.annotation.Transient;
import org.springframework.data.domain.Persistable;
import org.springframework.data.relational.core.mapping.Column;
import org.springframework.data.relational.core.mapping.Table;
import pe.edu.cibertec.vidasalud.commons.error.ErrorNegocio;

@Table("paciente")
public class Paciente implements Persistable<UUID> {

    private static final int EDAD_MAXIMA_ANIOS = 120;

    @Id
    private final UUID id;
    @Column("tipo_documento")
    private final TipoDocumento tipoDocumento;
    @Column("numero_documento")
    private final String numeroDocumento;
    @Column("nombres")
    private String nombres;
    @Column("apellidos")
    private String apellidos;
    @Column("fecha_nacimiento")
    private LocalDate fechaNacimiento;
    @Column("telefono")
    private String telefono;
    @Column("correo")
    private String correo;
    @Column("direccion")
    private String direccion;
    @Column("fecha_registro")
    private final OffsetDateTime fechaRegistro;
    @Column("estado")
    private EstadoPaciente estado;

    @Transient
    private boolean nuevo;

    @PersistenceCreator
    Paciente(UUID id, TipoDocumento tipoDocumento, String numeroDocumento, String nombres, String apellidos,
             LocalDate fechaNacimiento, String telefono, String correo, String direccion,
             OffsetDateTime fechaRegistro, EstadoPaciente estado) {
        this.id = id;
        this.tipoDocumento = tipoDocumento;
        this.numeroDocumento = numeroDocumento;
        this.nombres = nombres;
        this.apellidos = apellidos;
        this.fechaNacimiento = fechaNacimiento;
        this.telefono = telefono;
        this.correo = correo;
        this.direccion = direccion;
        this.fechaRegistro = fechaRegistro;
        this.estado = estado;
        this.nuevo = false;
    }

    public static Paciente registrar(TipoDocumento tipo, String numeroDocumento, DatosPersonales datos, Clock reloj) {
        String numero = ReglasDocumento.validar(tipo, numeroDocumento);
        validarFechaNacimiento(datos.fechaNacimiento(), reloj);
        // PostgreSQL guarda microsegundos; se trunca para que la entidad en memoria coincida con la leída.
        OffsetDateTime registro = OffsetDateTime.now(reloj).truncatedTo(ChronoUnit.MICROS);
        Paciente paciente = new Paciente(UUID.randomUUID(), tipo, numero, texto(datos.nombres()),
                texto(datos.apellidos()), datos.fechaNacimiento(), texto(datos.telefono()),
                texto(datos.correo()), texto(datos.direccion()), registro, EstadoPaciente.ACTIVO);
        paciente.nuevo = true;
        return paciente;
    }

    public void actualizarDatos(DatosPersonales datos, Clock reloj) {
        validarFechaNacimiento(datos.fechaNacimiento(), reloj);
        this.nombres = texto(datos.nombres());
        this.apellidos = texto(datos.apellidos());
        this.fechaNacimiento = datos.fechaNacimiento();
        this.telefono = texto(datos.telefono());
        this.correo = texto(datos.correo());
        this.direccion = texto(datos.direccion());
    }

    /** Cambia el estado y devuelve el anterior. */
    public EstadoPaciente cambiarEstado(EstadoPaciente nuevoEstado) {
        if (nuevoEstado == estado) {
            throw ErroresPaciente.transicionNoPermitida(estado);
        }
        EstadoPaciente anterior = estado;
        this.estado = nuevoEstado;
        return anterior;
    }

    public Paciente marcarComoExistente() {
        this.nuevo = false;
        return this;
    }

    private static void validarFechaNacimiento(LocalDate fecha, Clock reloj) {
        if (fecha.isBefore(LocalDate.now(reloj).minusYears(EDAD_MAXIMA_ANIOS))) {
            throw ErrorNegocio.validacion("fechaNacimiento", "no puede ser de hace más de 120 años");
        }
    }

    private static String texto(String valor) {
        if (valor == null || valor.isBlank()) {
            return null;
        }
        return valor.trim();
    }

    @Override
    public UUID getId() {
        return id;
    }

    @Override
    public boolean isNew() {
        return nuevo;
    }

    public TipoDocumento getTipoDocumento() {
        return tipoDocumento;
    }

    public String getNumeroDocumento() {
        return numeroDocumento;
    }

    public String getNombres() {
        return nombres;
    }

    public String getApellidos() {
        return apellidos;
    }

    public LocalDate getFechaNacimiento() {
        return fechaNacimiento;
    }

    public String getTelefono() {
        return telefono;
    }

    public String getCorreo() {
        return correo;
    }

    public String getDireccion() {
        return direccion;
    }

    public OffsetDateTime getFechaRegistro() {
        return fechaRegistro;
    }

    public EstadoPaciente getEstado() {
        return estado;
    }
}
