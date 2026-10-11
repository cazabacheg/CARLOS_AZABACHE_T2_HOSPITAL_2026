package pe.edu.cibertec.vidasalud.commons.error;

import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.context.annotation.Bean;

@AutoConfiguration
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.REACTIVE)
public class ErroresAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    public ManejadorErrores manejadorErrores() {
        return new ManejadorErrores();
    }
}
