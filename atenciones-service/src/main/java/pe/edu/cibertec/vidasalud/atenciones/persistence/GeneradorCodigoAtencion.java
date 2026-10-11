package pe.edu.cibertec.vidasalud.atenciones.persistence;

import java.time.Clock;
import java.time.LocalDate;
import pe.edu.cibertec.vidasalud.atenciones.domain.Atencion;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.stereotype.Component;
import pe.edu.cibertec.vidasalud.commons.reloj.RelojAutoConfiguration;
import reactor.core.publisher.Mono;

@Component
public class GeneradorCodigoAtencion {

    private final DatabaseClient databaseClient;
    private final Clock reloj;

    public GeneradorCodigoAtencion(DatabaseClient databaseClient, Clock reloj) {
        this.databaseClient = databaseClient;
        this.reloj = reloj;
    }

    public Mono<String> siguiente() {
        return databaseClient.sql("SELECT nextval('atencion_codigo_seq')")
                .map(fila -> fila.get(0, Long.class))
                .one()
                .map(n -> Atencion.formatearCodigo(
                        LocalDate.ofInstant(reloj.instant(), RelojAutoConfiguration.ZONA_HOSPITAL), n));
    }
}
