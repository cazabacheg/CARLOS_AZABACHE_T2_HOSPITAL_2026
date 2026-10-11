package pe.edu.cibertec.vidasalud.notificaciones.domain;

import java.time.LocalDate;
import java.util.UUID;

/** Filtros opcionales del listado; null = no filtra. */
public record FiltroNotificaciones(UUID pacienteId, String tipoEvento, LocalDate fecha) {

    public FiltroNotificaciones {
        tipoEvento = (tipoEvento == null || tipoEvento.isBlank()) ? null : tipoEvento.strip();
    }
}
