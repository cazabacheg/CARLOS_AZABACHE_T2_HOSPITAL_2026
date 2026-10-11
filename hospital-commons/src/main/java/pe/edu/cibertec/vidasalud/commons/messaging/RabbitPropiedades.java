package pe.edu.cibertec.vidasalud.commons.messaging;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

@ConfigurationProperties("hospital.rabbit")
public record RabbitPropiedades(
        @DefaultValue("true") boolean enabled,
        @DefaultValue("localhost") String host,
        @DefaultValue("5672") int port,
        @DefaultValue("hospital") String username,
        String password,
        @DefaultValue("/") String virtualHost) {
}
