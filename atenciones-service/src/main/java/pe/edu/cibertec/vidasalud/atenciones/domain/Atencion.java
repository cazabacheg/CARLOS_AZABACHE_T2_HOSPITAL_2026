package pe.edu.cibertec.vidasalud.atenciones.domain;

import java.time.Clock;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.util.UUID;
import org.springframework.data.annotation.Id;
import org.springframework.data.annotation.PersistenceCreator;
import org.springframework.data.annotation.Transient;
import org.springframework.data.annotation.Version;
import org.springframework.data.domain.Persistable;
import org.springframework.data.relational.core.mapping.Column;
import org.springframework.data.relational.core.mapping.Table;

@Table("atencion")
public class Atencion implements Persistable<UUID> {

    @Id
    private final UUID id;
    private final String codigo;
    @Column("paciente_id")
    private final UUID pacienteId;
    @Column("cita_id")
    private final UUID citaId;
    @Column("fecha_atencion")
    private final OffsetDateTime fechaAtencion;
    private final AreaAtencion area;
    private EstadoAtencion estado;
    @Column("fecha_creacion")
    private final OffsetDateTime fechaCreacion;
    @Column("fecha_actualizacion")
    private OffsetDateTime fechaActualizacion;
    @Version
    private Long version;
    @Transient
    private boolean nuevo;

    @PersistenceCreator
    Atencion(UUID id, String codigo, UUID pacienteId, UUID citaId, OffsetDateTime fechaAtencion,
             AreaAtencion area, EstadoAtencion estado, OffsetDateTime fechaCreacion,
             OffsetDateTime fechaActualizacion, Long version) {
        this.id = id;
        this.codigo = codigo;
        this.pacienteId = pacienteId;
        this.citaId = citaId;
        this.fechaAtencion = fechaAtencion;
        this.area = area;
        this.estado = estado;
        this.fechaCreacion = fechaCreacion;
        this.fechaActualizacion = fechaActualizacion;
        this.version = version;
        marcarComoExistente();
    }

    public static Atencion registrar(CitaResumen cita, AreaAtencion area, String codigo, Clock reloj) {
        OffsetDateTime ahora = OffsetDateTime.now(reloj);
        Atencion atencion = new Atencion(UUID.randomUUID(), codigo, cita.pacienteId(), cita.id(), ahora,
                area, EstadoAtencion.REGISTRADA, ahora, ahora, null);
        atencion.nuevo = true;
        return atencion;
    }

    /** Devuelve el estado anterior. */
    public EstadoAtencion cambiarEstado(EstadoAtencion destino, Clock reloj) {
        if (!estado.puedeCambiarA(destino)) {
            throw ErroresAtencion.transicionNoPermitida(estado, destino);
        }
        EstadoAtencion anterior = estado;
        estado = destino;
        fechaActualizacion = OffsetDateTime.now(reloj);
        return anterior;
    }

    public static String formatearCodigo(LocalDate fecha, long numero) {
        return "ATE-" + fecha.format(DateTimeFormatter.BASIC_ISO_DATE) + "-" + String.format("%06d", numero);
    }

    void marcarComoExistente() {
        this.nuevo = false;
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

    public UUID getCitaId() {
        return citaId;
    }

    public OffsetDateTime getFechaAtencion() {
        return fechaAtencion;
    }

    public AreaAtencion getArea() {
        return area;
    }

    public EstadoAtencion getEstado() {
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
