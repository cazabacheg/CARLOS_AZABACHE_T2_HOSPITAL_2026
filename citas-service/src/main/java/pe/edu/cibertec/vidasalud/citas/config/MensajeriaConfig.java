package pe.edu.cibertec.vidasalud.citas.config;

import java.util.List;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import pe.edu.cibertec.vidasalud.citas.messaging.AtencionEventosConsumer;
import pe.edu.cibertec.vidasalud.commons.messaging.ConsumidorEventos;
import pe.edu.cibertec.vidasalud.commons.messaging.TopologiaHospital;

@Configuration
@ConditionalOnProperty(prefix = "hospital.rabbit", name = "enabled", havingValue = "true", matchIfMissing = true)
public class MensajeriaConfig {

    @Bean
    ApplicationRunner consumidorAtenciones(ConsumidorEventos consumidor, AtencionEventosConsumer manejador) {
        return args -> consumidor.iniciar(TopologiaHospital.COLA_CITAS_ATENCIONES,
                List.of(TopologiaHospital.ATENCION_REGISTRADA, TopologiaHospital.ATENCION_ANULADA),
                manejador::manejar);
    }
}
