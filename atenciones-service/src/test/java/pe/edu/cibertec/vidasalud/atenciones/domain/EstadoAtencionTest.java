package pe.edu.cibertec.vidasalud.atenciones.domain;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class EstadoAtencionTest {

    @ParameterizedTest
    @CsvSource({
            "REGISTRADA,REGISTRADA,false",
            "REGISTRADA,FINALIZADA,true",
            "REGISTRADA,ANULADA,true",
            "FINALIZADA,REGISTRADA,false",
            "FINALIZADA,FINALIZADA,false",
            "FINALIZADA,ANULADA,false",
            "ANULADA,REGISTRADA,false",
            "ANULADA,FINALIZADA,false",
            "ANULADA,ANULADA,false"})
    void matrizDeTransiciones(EstadoAtencion origen, EstadoAtencion destino, boolean esperado) {
        assertThat(origen.puedeCambiarA(destino)).isEqualTo(esperado);
    }

    @ParameterizedTest
    @CsvSource({"REGISTRADA,false", "FINALIZADA,true", "ANULADA,true"})
    void estadosFinales(EstadoAtencion estado, boolean esperado) {
        assertThat(estado.esFinal()).isEqualTo(esperado);
    }
}
