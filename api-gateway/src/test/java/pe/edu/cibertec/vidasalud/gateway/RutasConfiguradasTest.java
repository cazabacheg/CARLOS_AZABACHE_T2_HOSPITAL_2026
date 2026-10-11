package pe.edu.cibertec.vidasalud.gateway;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.cloud.gateway.route.RouteDefinition;
import org.springframework.cloud.gateway.route.RouteDefinitionLocator;

@SpringBootTest(properties = "eureka.client.enabled=false")
class RutasConfiguradasTest {

    @Autowired
    RouteDefinitionLocator locator;

    @Test
    void existenLasSeisRutasHaciaServiciosRegistradosEnEureka() {
        List<RouteDefinition> rutas = locator.getRouteDefinitions().collectList().block();
        assertThat(rutas).hasSize(6);
        assertThat(rutas).allSatisfy(r -> assertThat(r.getUri().toString()).startsWith("lb://"));
        assertThat(rutas).extracting(r -> r.getUri().toString()).containsExactlyInAnyOrder(
                "lb://pacientes-service", "lb://citas-service", "lb://atenciones-service",
                "lb://pagos-service", "lb://notificaciones-service", "lb://auditoria-service");
        assertThat(rutas).flatExtracting(r -> r.getPredicates())
                .extracting(p -> p.getArgs().values().iterator().next())
                .containsExactlyInAnyOrder("/api/pacientes/**", "/api/citas/**", "/api/atenciones/**",
                        "/api/pagos/**", "/api/notificaciones/**", "/api/auditoria/**");
    }
}
