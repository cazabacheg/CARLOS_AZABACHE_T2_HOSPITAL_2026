package pe.edu.cibertec.vidasalud.commons.http;

import org.springframework.web.reactive.function.client.WebClient;

/** Crea el WebClient de un servicio remoto a partir del builder balanceado (Eureka). */
public final class ClienteHttpFactory {

    private ClienteHttpFactory() {
    }

    public static WebClient crear(WebClient.Builder balanceado, String baseUrl) {
        return balanceado.clone().baseUrl(baseUrl).build();
    }
}
