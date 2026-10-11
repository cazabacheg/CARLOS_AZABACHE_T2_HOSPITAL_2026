package pe.edu.cibertec.vidasalud.auditoria.config;

import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import pe.edu.cibertec.vidasalud.auditoria.service.AuditoriaService;
import pe.edu.cibertec.vidasalud.commons.messaging.ConsumidorEventos;
import pe.edu.cibertec.vidasalud.commons.messaging.TopologiaHospital;

@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(prefix = "hospital.rabbit", name = "enabled", havingValue = "true", matchIfMissing = true)
public class ConsumoAuditoriaConfig {

    public static final String BINDING_TODOS = "#";
    private static final Logger log = LoggerFactory.getLogger(ConsumoAuditoriaConfig.class);

    @Bean
    public ApplicationRunner consumoAuditoria(ConsumidorEventos consumidor, AuditoriaService servicio) {
        return args -> {
            consumidor.iniciar(TopologiaHospital.COLA_AUDITORIA, List.of(BINDING_TODOS), servicio::registrar);
            log.info("consumo iniciado cola={} binding={}", TopologiaHospital.COLA_AUDITORIA, BINDING_TODOS);
        };
    }
}
