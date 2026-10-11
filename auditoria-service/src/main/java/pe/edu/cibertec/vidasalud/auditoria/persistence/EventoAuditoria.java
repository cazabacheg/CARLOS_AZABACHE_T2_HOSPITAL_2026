package pe.edu.cibertec.vidasalud.auditoria.persistence;

import java.time.OffsetDateTime;
import java.util.UUID;
import org.springframework.data.annotation.Id;
import org.springframework.data.annotation.PersistenceCreator;
import org.springframework.data.annotation.Transient;
import org.springframework.data.domain.Persistable;
import org.springframework.data.relational.core.mapping.Column;
import org.springframework.data.relational.core.mapping.Table;

@Table("evento_auditoria")
public class EventoAuditoria implements Persistable<UUID> {

    @Id
    private final UUID id;
    @Column("event_id")
    private final UUID eventId;
    @Column("fecha_evento")
    private final OffsetDateTime fechaEvento;
    @Column("tipo_evento")
    private final String tipoEvento;
    @Column("tipo_recurso")
    private final String tipoRecurso;
    @Column("recurso_id")
    private final UUID recursoId;
    @Column("estado_anterior")
    private final String estadoAnterior;
    @Column("estado_nuevo")
    private final String estadoNuevo;
    @Column("origen")
    private final String origen;
    @Column("fecha_registro")
    private final OffsetDateTime fechaRegistro;
    @Transient
    private boolean nuevo;

    @PersistenceCreator
    public EventoAuditoria(UUID id, UUID eventId, OffsetDateTime fechaEvento, String tipoEvento,
                           String tipoRecurso, UUID recursoId, String estadoAnterior,
                           String estadoNuevo, String origen, OffsetDateTime fechaRegistro) {
        this.id = id;
        this.eventId = eventId;
        this.fechaEvento = fechaEvento;
        this.tipoEvento = tipoEvento;
        this.tipoRecurso = tipoRecurso;
        this.recursoId = recursoId;
        this.estadoAnterior = estadoAnterior;
        this.estadoNuevo = estadoNuevo;
        this.origen = origen;
        this.fechaRegistro = fechaRegistro;
        this.nuevo = false;
    }

    public static EventoAuditoria nueva(UUID eventId, OffsetDateTime fechaEvento, String tipoEvento,
                                        String tipoRecurso, UUID recursoId, String estadoAnterior,
                                        String estadoNuevo, String origen, OffsetDateTime fechaRegistro) {
        EventoAuditoria evento = new EventoAuditoria(UUID.randomUUID(), eventId, fechaEvento, tipoEvento,
                tipoRecurso, recursoId, estadoAnterior, estadoNuevo, origen, fechaRegistro);
        evento.nuevo = true;
        return evento;
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

    public OffsetDateTime getFechaEvento() {
        return fechaEvento;
    }

    public String getTipoEvento() {
        return tipoEvento;
    }

    public String getTipoRecurso() {
        return tipoRecurso;
    }

    public UUID getRecursoId() {
        return recursoId;
    }

    public String getEstadoAnterior() {
        return estadoAnterior;
    }

    public String getEstadoNuevo() {
        return estadoNuevo;
    }

    public String getOrigen() {
        return origen;
    }

    public OffsetDateTime getFechaRegistro() {
        return fechaRegistro;
    }
}
