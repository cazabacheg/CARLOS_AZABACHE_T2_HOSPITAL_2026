package pe.edu.cibertec.vidasalud.pagos.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.cloud.client.loadbalancer.LoadBalanced;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.reactive.function.client.WebClient;
import pe.edu.cibertec.vidasalud.commons.http.ClienteHttpFactory;
import pe.edu.cibertec.vidasalud.pagos.client.PacientesClient;

@Configuration(proxyBeanMethods = false)
public class ClientesConfig {

    @Bean
    @LoadBalanced
    WebClient.Builder webClientBalanceado() {
        return WebClient.builder();
    }

    @Bean
    PacientesClient pacientesClient(@LoadBalanced WebClient.Builder balanceado,
                                    @Value("${hospital.clientes.pacientes.url}") String url) {
        return new PacientesClient(ClienteHttpFactory.crear(balanceado, url));
    }
}
