package pe.edu.cibertec.vidasalud.citas.persistence;

import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

@Component
public class SecuenciaCodigoCita {

    private final DatabaseClient databaseClient;

    public SecuenciaCodigoCita(DatabaseClient databaseClient) {
        this.databaseClient = databaseClient;
    }

    public Mono<Long> siguiente() {
        return databaseClient.sql("SELECT nextval('cita_codigo_seq')")
                .map(fila -> fila.get(0, Long.class))
                .one();
    }
}
