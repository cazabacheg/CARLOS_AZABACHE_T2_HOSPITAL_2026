package pe.edu.cibertec.vidasalud.atenciones.client;

import java.util.UUID;
import pe.edu.cibertec.vidasalud.atenciones.domain.CitaResumen;
import pe.edu.cibertec.vidasalud.atenciones.domain.ErroresAtencion;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.cloud.client.loadbalancer.LoadBalanced;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import pe.edu.cibertec.vidasalud.commons.http.ClienteHttpFactory;
import pe.edu.cibertec.vidasalud.commons.http.PoliticaResiliencia;
import reactor.core.publisher.Mono;

@Component
public class CitasClient {

    private final WebClient webClient;

    public CitasClient(@LoadBalanced WebClient.Builder builder,
                       @Value("${hospital.clientes.citas.url}") String urlCitas) {
        this.webClient = ClienteHttpFactory.crear(builder, urlCitas);
    }

    /** 404 pasa a CITA_NO_ENCONTRADA; 5xx, red o sin instancias a 503; timeout a 504. */
    public Mono<CitaResumen> obtener(UUID citaId) {
        Mono<CitaResumen> llamada = webClient.get()
                .uri("/api/citas/{id}", citaId)
                .accept(MediaType.APPLICATION_JSON)
                .retrieve()
                .onStatus(s -> s.value() == 404, r -> Mono.just(ErroresAtencion.citaNoEncontrada(citaId)))
                .bodyToMono(CitaResumen.class);
        return PoliticaResiliencia.proteger(llamada, ErroresAtencion.SERVICIO_CITAS_NO_DISPONIBLE, "citas");
    }
}
