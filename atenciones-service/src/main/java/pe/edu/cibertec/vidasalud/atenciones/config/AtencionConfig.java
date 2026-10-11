package pe.edu.cibertec.vidasalud.atenciones.config;

import pe.edu.cibertec.vidasalud.atenciones.domain.VentanaAtencion;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.cloud.client.loadbalancer.LoadBalanced;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.reactive.function.client.WebClient;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(AtencionPropiedades.class)
public class AtencionConfig {

    @Bean
    VentanaAtencion ventanaAtencion(AtencionPropiedades props) {
        return new VentanaAtencion(props.minutosAntes());
    }

    @Bean
    @LoadBalanced
    WebClient.Builder webClientBalanceado() {
        return WebClient.builder();
    }
}
