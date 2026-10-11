package pe.edu.cibertec.vidasalud.commons.messaging;

import reactor.core.publisher.Mono;

public interface PublicadorEventos {

    /**
     * Publica un evento en el exchange {@code hospital.eventos}. No falla: si el
     * broker no confirma, registra el error y completa vacio (documento tecnico, D-3).
     */
    Mono<Void> publicar(String routingKey, EventoEnvelope evento);
}
