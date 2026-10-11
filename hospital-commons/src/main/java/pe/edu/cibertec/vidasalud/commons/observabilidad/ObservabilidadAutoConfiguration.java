package pe.edu.cibertec.vidasalud.commons.observabilidad;

import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.context.annotation.Bean;

@AutoConfiguration
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.REACTIVE)
public class ObservabilidadAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    public TraceIdRespuestaFilter traceIdRespuestaFilter() {
        return new TraceIdRespuestaFilter();
    }
}
