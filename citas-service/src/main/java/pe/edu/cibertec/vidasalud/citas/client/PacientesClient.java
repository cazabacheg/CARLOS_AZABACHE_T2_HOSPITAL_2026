package pe.edu.cibertec.vidasalud.citas.client;

import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.web.reactive.function.client.WebClient;
import pe.edu.cibertec.vidasalud.commons.error.ErrorNegocio;
import pe.edu.cibertec.vidasalud.commons.http.PoliticaResiliencia;
import reactor.core.publisher.Mono;

public class PacientesClient {

    public static final String CODIGO_NO_DISPONIBLE = "SERVICIO_PACIENTES_NO_DISPONIBLE";

    private static final Logger log = LoggerFactory.getLogger(PacientesClient.class);

    private final WebClient webClient;

    public PacientesClient(WebClient webClient) {
        this.webClient = webClient;
    }

    public Mono<PacienteResumen> obtener(UUID pacienteId) {
        Mono<PacienteResumen> llamada = webClient.get()
                .uri("/api/pacientes/{id}", pacienteId)
                .retrieve()
                .onStatus(s -> s.value() == 404, r -> r.releaseBody().then(Mono.error(
                        new ErrorNegocio(HttpStatus.NOT_FOUND, "PACIENTE_NO_ENCONTRADO",
                                "No existe el paciente " + pacienteId + "."))))
                .bodyToMono(PacienteResumen.class)
                .doOnSubscribe(s -> log.info("consultando pacientes-service paciente id={}", pacienteId));
        return PoliticaResiliencia.proteger(llamada, CODIGO_NO_DISPONIBLE, "pacientes");
    }
}
