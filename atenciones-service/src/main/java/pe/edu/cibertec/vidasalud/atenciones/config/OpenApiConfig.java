package pe.edu.cibertec.vidasalud.atenciones.config;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
public class OpenApiConfig {

    @Bean
    OpenAPI atencionesOpenApi() {
        return new OpenAPI().info(new Info()
                .title("atenciones-service")
                .version("1.0.0")
                .description("Registro administrativo de la atención de citas confirmadas de Hospital Vida Salud."));
    }
}
