package pe.edu.cibertec.vidasalud.commons.reloj;

import java.time.Clock;
import java.time.ZoneId;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;

/** Reloj unico en la zona horaria del hospital; se reemplaza en las pruebas. */
@AutoConfiguration
public class RelojAutoConfiguration {

    public static final ZoneId ZONA_HOSPITAL = ZoneId.of("America/Lima");

    @Bean
    @ConditionalOnMissingBean
    public Clock relojHospital() {
        return Clock.system(ZONA_HOSPITAL);
    }
}
