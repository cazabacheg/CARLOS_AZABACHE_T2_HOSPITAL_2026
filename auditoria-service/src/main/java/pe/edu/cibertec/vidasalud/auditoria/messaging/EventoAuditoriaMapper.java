package pe.edu.cibertec.vidasalud.auditoria.messaging;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import pe.edu.cibertec.vidasalud.auditoria.domain.SobreInvalidoException;
import pe.edu.cibertec.vidasalud.auditoria.persistence.EventoAuditoria;
import pe.edu.cibertec.vidasalud.commons.messaging.EventoEnvelope;

public final class EventoAuditoriaMapper {

    private EventoAuditoriaMapper() {
    }

    /** Valida y convierte; el campo datos del sobre no se copia (D-39). */
    public static EventoAuditoria aEntidad(EventoEnvelope sobre, OffsetDateTime fechaRegistro) {
        List<String> faltantes = new ArrayList<>();
        if (sobre.ocurrioEn() == null) {
            faltantes.add("ocurrioEn");
        }
        if (vacio(sobre.tipoRecurso())) {
            faltantes.add("tipoRecurso");
        }
        if (sobre.recursoId() == null) {
            faltantes.add("recursoId");
        }
        if (vacio(sobre.estadoNuevo())) {
            faltantes.add("estadoNuevo");
        }
        if (vacio(sobre.origen())) {
            faltantes.add("origen");
        }
        if (!faltantes.isEmpty()) {
            throw new SobreInvalidoException("sobre de evento incompleto: faltan " + faltantes);
        }
        limite("tipo", sobre.tipo(), 50);
        limite("tipoRecurso", sobre.tipoRecurso(), 30);
        limite("estadoAnterior", sobre.estadoAnterior(), 20);
        limite("estadoNuevo", sobre.estadoNuevo(), 20);
        limite("origen", sobre.origen(), 40);
        return EventoAuditoria.nueva(sobre.eventId(), sobre.ocurrioEn(), sobre.tipo(), sobre.tipoRecurso(),
                sobre.recursoId(), sobre.estadoAnterior(), sobre.estadoNuevo(), sobre.origen(), fechaRegistro);
    }

    private static boolean vacio(String valor) {
        return valor == null || valor.isBlank();
    }

    private static void limite(String campo, String valor, int maximo) {
        if (valor != null && valor.length() > maximo) {
            throw new SobreInvalidoException("campo " + campo + " excede " + maximo + " caracteres");
        }
    }
}
