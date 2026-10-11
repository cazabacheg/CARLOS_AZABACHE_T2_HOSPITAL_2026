package pe.edu.cibertec.vidasalud.pagos.client;

import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.reactive.function.client.WebClient;
import pe.edu.cibertec.vidasalud.commons.error.ErrorNegocio;
import pe.edu.cibertec.vidasalud.commons.http.PoliticaResiliencia;
import reactor.core.publisher.Mono;

public class PacientesClient {

    static final String CODIGO_NO_DISPONIBLE = "SERVICIO_PACIENTES_NO_DISPONIBLE";

    private final WebClient webClient;

    public PacientesClient(WebClient webClient) {
        this.webClient = webClient;
    }

    public Mono<PacienteResumen> obtener(UUID pacienteId) {
        Mono<PacienteResumen> llamada = webClient.get()
                .uri("/api/pacientes/{id}", pacienteId)
                .accept(MediaType.APPLICATION_JSON)
                .retrieve()
                .onStatus(s -> s.value() == 404, r -> r.releaseBody().then(Mono.error(
                        new ErrorNegocio(HttpStatus.NOT_FOUND, "PACIENTE_NO_ENCONTRADO",
                                "No existe el paciente " + pacienteId + "."))))
                .bodyToMono(PacienteResumen.class);
        return PoliticaResiliencia.proteger(llamada, CODIGO_NO_DISPONIBLE, "pacientes")
                .switchIfEmpty(Mono.error(new ErrorNegocio(HttpStatus.SERVICE_UNAVAILABLE, CODIGO_NO_DISPONIBLE,
                        "El servicio de pacientes no esta disponible. Intente mas tarde.")));
    }
}
