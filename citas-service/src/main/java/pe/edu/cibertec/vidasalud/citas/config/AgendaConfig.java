package pe.edu.cibertec.vidasalud.citas.config;

import java.time.Clock;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import pe.edu.cibertec.vidasalud.citas.domain.ReglasAgenda;

@Configuration
@EnableConfigurationProperties(AgendaPropiedades.class)
public class AgendaConfig {

    @Bean
    ReglasAgenda reglasAgenda(AgendaPropiedades propiedades, Clock reloj) {
        return new ReglasAgenda(reloj, propiedades.aParametros());
    }
}
