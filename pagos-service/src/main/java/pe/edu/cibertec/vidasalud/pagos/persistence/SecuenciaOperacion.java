package pe.edu.cibertec.vidasalud.pagos.persistence;

import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

@Component
public class SecuenciaOperacion {

    private final DatabaseClient db;

    public SecuenciaOperacion(DatabaseClient db) {
        this.db = db;
    }

    /** Cada llamada consume un número aunque el INSERT posterior falle: puede haber huecos. */
    public Mono<Long> siguiente() {
        return db.sql("SELECT nextval('pago_operacion_seq')")
                .map(fila -> fila.get(0, Long.class))
                .one();
    }
}
