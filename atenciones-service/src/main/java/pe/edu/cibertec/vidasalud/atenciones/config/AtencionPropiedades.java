package pe.edu.cibertec.vidasalud.atenciones.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

@ConfigurationProperties("hospital.atencion")
public record AtencionPropiedades(@DefaultValue("30") int minutosAntes) {
}
