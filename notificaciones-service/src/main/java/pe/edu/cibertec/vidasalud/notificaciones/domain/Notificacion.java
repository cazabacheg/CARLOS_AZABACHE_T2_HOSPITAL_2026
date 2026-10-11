package pe.edu.cibertec.vidasalud.notificaciones.domain;

import java.time.OffsetDateTime;
import java.util.UUID;
import org.springframework.data.annotation.Id;
import org.springframework.data.annotation.PersistenceCreator;
import org.springframework.data.annotation.Transient;
import org.springframework.data.domain.Persistable;
import org.springframework.data.relational.core.mapping.Column;
import org.springframework.data.relational.core.mapping.Table;

@Table("notificacion")
public class Notificacion implements Persistable<UUID> {

    public static final String CANAL_SIMULADO = "SIMULADO";

    @Id
    private final UUID id;
    @Column("event_id")
    private final UUID eventId;
    @Column("paciente_id")
    private final UUID pacienteId;
    @Column("tipo_evento")
    private final String tipoEvento;
    @Column("recurso_id")
    private final UUID recursoId;
    @Column("canal")
    private final String canal;
    @Column("mensaje")
    private final String mensaje;
    @Column("fecha_envio")
    private final OffsetDateTime fechaEnvio;
    @Transient
    private boolean nuevo;

    @PersistenceCreator
    public Notificacion(UUID id, UUID eventId, UUID pacienteId, String tipoEvento, UUID recursoId,
                        String canal, String mensaje, OffsetDateTime fechaEnvio) {
        this.id = id;
        this.eventId = eventId;
        this.pacienteId = pacienteId;
        this.tipoEvento = tipoEvento;
        this.recursoId = recursoId;
        this.canal = canal;
        this.mensaje = mensaje;
        this.fechaEnvio = fechaEnvio;
        this.nuevo = false;
    }

    public static Notificacion nueva(UUID eventId, UUID pacienteId, String tipoEvento, UUID recursoId,
                                     String mensaje, OffsetDateTime fechaEnvio) {
        Notificacion n = new Notificacion(UUID.randomUUID(), eventId, pacienteId, tipoEvento, recursoId,
                CANAL_SIMULADO, mensaje, fechaEnvio);
        n.nuevo = true;
        return n;
    }

    @Override
    public UUID getId() {
        return id;
    }

    @Override
    public boolean isNew() {
        return nuevo;
    }

    public UUID getEventId() {
        return eventId;
    }

    public UUID getPacienteId() {
        return pacienteId;
    }

    public String getTipoEvento() {
        return tipoEvento;
    }

    public UUID getRecursoId() {
        return recursoId;
    }

    public String getCanal() {
        return canal;
    }

    public String getMensaje() {
        return mensaje;
    }

    public OffsetDateTime getFechaEnvio() {
        return fechaEnvio;
    }
}
