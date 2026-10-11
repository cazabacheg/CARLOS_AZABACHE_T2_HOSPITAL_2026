package pe.edu.cibertec.vidasalud.citas.config;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.cloud.client.loadbalancer.LoadBalanced;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.boot.web.reactive.function.client.WebClientCustomizer;
import org.springframework.web.reactive.function.client.WebClient;
import pe.edu.cibertec.vidasalud.citas.client.PacientesClient;
import pe.edu.cibertec.vidasalud.commons.http.ClienteHttpFactory;

@Configuration
public class ClientesConfig {

    @Bean
    @LoadBalanced
    WebClient.Builder webClientBalanceado(ObjectProvider<WebClientCustomizer> personalizadores) {
        WebClient.Builder builder = WebClient.builder();
        personalizadores.orderedStream().forEach(p -> p.customize(builder));
        return builder;
    }

    @Bean
    PacientesClient pacientesClient(@LoadBalanced WebClient.Builder balanceado,
                                    @Value("${hospital.clientes.pacientes.url}") String url) {
        return new PacientesClient(ClienteHttpFactory.crear(balanceado, url));
    }
}
