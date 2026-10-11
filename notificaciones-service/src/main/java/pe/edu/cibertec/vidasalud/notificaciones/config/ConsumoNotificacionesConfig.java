package pe.edu.cibertec.vidasalud.notificaciones.config;

import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import pe.edu.cibertec.vidasalud.commons.messaging.ConsumidorEventos;
import pe.edu.cibertec.vidasalud.commons.messaging.TopologiaHospital;
import pe.edu.cibertec.vidasalud.notificaciones.messaging.BindingsNotificaciones;
import pe.edu.cibertec.vidasalud.notificaciones.messaging.NotificacionesConsumer;

@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(prefix = "hospital.rabbit", name = "enabled", havingValue = "true", matchIfMissing = true)
public class ConsumoNotificacionesConfig {

    @Bean
    public ApplicationRunner iniciarConsumoNotificaciones(ConsumidorEventos consumidor,
                                                          NotificacionesConsumer consumer) {
        return args -> consumidor.iniciar(TopologiaHospital.COLA_NOTIFICACIONES, BindingsNotificaciones.TODOS,
                consumer::manejar);
    }
}
