package pe.edu.cibertec.vidasalud.eureka;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "eureka.client.service-url.defaultZone=http://localhost:0/eureka")
class EurekaServerApplicationTest {

    @Autowired
    TestRestTemplate http;

    @Test
    void elRegistroResponde() {
        ResponseEntity<String> respuesta = http.getForEntity("/eureka/apps", String.class);
        assertThat(respuesta.getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    @Test
    void elPanelResponde() {
        ResponseEntity<String> respuesta = http.getForEntity("/", String.class);
        assertThat(respuesta.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(respuesta.getBody()).contains("Eureka");
    }

    @Test
    void healthEstaUp() {
        ResponseEntity<String> respuesta = http.getForEntity("/actuator/health", String.class);
        assertThat(respuesta.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(respuesta.getBody()).contains("\"status\":\"UP\"");
    }
}
