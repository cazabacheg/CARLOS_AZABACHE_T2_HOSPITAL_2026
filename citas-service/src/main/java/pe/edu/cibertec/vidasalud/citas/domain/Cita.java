package pe.edu.cibertec.vidasalud.citas.domain;

import java.time.Clock;
import java.time.OffsetDateTime;
import java.time.temporal.ChronoUnit;
import java.util.UUID;
import org.springframework.data.annotation.Id;
import org.springframework.data.annotation.PersistenceCreator;
import org.springframework.data.annotation.Transient;
import org.springframework.data.annotation.Version;
import org.springframework.data.domain.Persistable;
import org.springframework.data.relational.core.mapping.Column;
import org.springframework.data.relational.core.mapping.Table;
import org.springframework.http.HttpStatus;
import pe.edu.cibertec.vidasalud.commons.error.ErrorNegocio;

@Table("cita")
public class Cita implements Persistable<UUID> {

    private static final String TRANSICION = "TRANSICION_NO_PERMITIDA";

    @Id
    private UUID id;
    @Column("codigo")
    private String codigo;
    @Column("paciente_id")
    private UUID pacienteId;
    @Column("especialidad")
    private Especialidad especialidad;
    @Column("fecha_hora")
    private OffsetDateTime fechaHora;
    @Column("profesional_codigo")
    private String profesionalCodigo;
    @Column("motivo")
    private String motivo;
    @Column("estado")
    private EstadoCita estado;
    @Column("fecha_creacion")
    private OffsetDateTime fechaCreacion;
    @Column("fecha_actualizacion")
    private OffsetDateTime fechaActualizacion;
    @Version
    private Long version;

    @Transient
    private boolean nuevo;

    @PersistenceCreator
    Cita(UUID id, String codigo, UUID pacienteId, Especialidad especialidad, OffsetDateTime fechaHora,
         String profesionalCodigo, String motivo, EstadoCita estado, OffsetDateTime fechaCreacion,
         OffsetDateTime fechaActualizacion, Long version) {
        this.id = id;
        this.codigo = codigo;
        this.pacienteId = pacienteId;
        this.especialidad = especialidad;
        this.fechaHora = ZonaHospital.aLima(fechaHora);
        this.profesionalCodigo = profesionalCodigo;
        this.motivo = motivo;
        this.estado = estado;
        this.fechaCreacion = ZonaHospital.aLima(fechaCreacion);
        this.fechaActualizacion = ZonaHospital.aLima(fechaActualizacion);
        this.version = version;
        this.nuevo = false;
    }

    public static Cita programar(DatosNuevaCita datos, String codigo, Clock reloj) {
        OffsetDateTime ahora = ahora(reloj);
        Cita cita = new Cita(UUID.randomUUID(), codigo, datos.pacienteId(), datos.especialidad(),
                datos.fechaHora(), datos.profesionalCodigo(), datos.motivo(), EstadoCita.PENDIENTE,
                ahora, ahora, null);
        cita.nuevo = true;
        return cita;
    }

    public EstadoCita cambiarEstado(EstadoCita destino, Clock reloj) {
        if (!estado.puedeCambiarA(destino)) {
            throw new ErrorNegocio(HttpStatus.CONFLICT, TRANSICION,
                    "No se puede pasar una cita de " + estado + " a " + destino + ".");
        }
        EstadoCita anterior = estado;
        this.estado = destino;
        this.fechaActualizacion = ahora(reloj);
        return anterior;
    }

    public void validarCambioPorApi(EstadoCita destino) {
        if (destino == EstadoCita.ATENDIDA) {
            throw new ErrorNegocio(HttpStatus.CONFLICT, TRANSICION,
                    "Una cita pasa a ATENDIDA solo al registrar su atención.");
        }
        if (destino == EstadoCita.PENDIENTE) {
            throw new ErrorNegocio(HttpStatus.CONFLICT, TRANSICION,
                    "Una cita vuelve a PENDIENTE solo al reprogramarla.");
        }
        if (!estado.permitidoPorApi(destino)) {
            throw new ErrorNegocio(HttpStatus.CONFLICT, TRANSICION,
                    "No se puede pasar una cita de " + estado + " a " + destino + ".");
        }
    }

    /** Devuelve la fecha anterior. */
    public OffsetDateTime reprogramar(OffsetDateTime nueva, String nuevoProfesional, Clock reloj) {
        if (!estado.esActivo()) {
            throw new ErrorNegocio(HttpStatus.CONFLICT, TRANSICION,
                    "Solo se reprograman citas pendientes o confirmadas.");
        }
        if (nueva.isEqual(fechaHora)) {
            throw new ErrorNegocio(HttpStatus.CONFLICT, "MISMA_FECHA_HORA",
                    "La nueva fecha y hora es igual a la actual.");
        }
        OffsetDateTime anterior = fechaHora;
        this.fechaHora = ZonaHospital.aLima(nueva);
        if (nuevoProfesional != null) {
            this.profesionalCodigo = nuevoProfesional;
        }
        this.estado = EstadoCita.PENDIENTE;
        this.fechaActualizacion = ahora(reloj);
        return anterior;
    }

    public boolean yaOcurrio(Clock reloj) {
        return !OffsetDateTime.now(reloj).isBefore(fechaHora);
    }

    public Cita marcarComoExistente() {
        this.nuevo = false;
        return this;
    }

    // PostgreSQL guarda microsegundos
    private static OffsetDateTime ahora(Clock reloj) {
        return ZonaHospital.aLima(OffsetDateTime.now(reloj).truncatedTo(ChronoUnit.MICROS));
    }

    @Override
    public UUID getId() {
        return id;
    }

    @Override
    public boolean isNew() {
        return nuevo;
    }

    public String getCodigo() {
        return codigo;
    }

    public UUID getPacienteId() {
        return pacienteId;
    }

    public Especialidad getEspecialidad() {
        return especialidad;
    }

    public OffsetDateTime getFechaHora() {
        return fechaHora;
    }

    public String getProfesionalCodigo() {
        return profesionalCodigo;
    }

    public String getMotivo() {
        return motivo;
    }

    public EstadoCita getEstado() {
        return estado;
    }

    public OffsetDateTime getFechaCreacion() {
        return fechaCreacion;
    }

    public OffsetDateTime getFechaActualizacion() {
        return fechaActualizacion;
    }

    public Long getVersion() {
        return version;
    }
}
