package pe.edu.cibertec.vidasalud.citas.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static pe.edu.cibertec.vidasalud.citas.domain.EstadoCita.ATENDIDA;
import static pe.edu.cibertec.vidasalud.citas.domain.EstadoCita.AUSENTE;
import static pe.edu.cibertec.vidasalud.citas.domain.EstadoCita.CANCELADA;
import static pe.edu.cibertec.vidasalud.citas.domain.EstadoCita.CONFIRMADA;
import static pe.edu.cibertec.vidasalud.citas.domain.EstadoCita.PENDIENTE;

import java.util.List;
import java.util.Set;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

class EstadoCitaTest {

    private record Par(EstadoCita origen, EstadoCita destino) {
    }

    private static final Set<Par> PERMITIDAS = Set.of(
            new Par(PENDIENTE, CONFIRMADA), new Par(PENDIENTE, CANCELADA),
            new Par(CONFIRMADA, ATENDIDA), new Par(CONFIRMADA, AUSENTE), new Par(CONFIRMADA, CANCELADA),
            new Par(ATENDIDA, CANCELADA));

    static Stream<Arguments> pares() {
        return Stream.of(EstadoCita.values()).flatMap(o -> Stream.of(EstadoCita.values())
                .map(d -> Arguments.of(o, d, PERMITIDAS.contains(new Par(o, d)))));
    }

    @ParameterizedTest
    @MethodSource("pares")
    void matrizCompletaDeTransiciones(EstadoCita origen, EstadoCita destino, boolean esperado) {
        assertThat(origen.puedeCambiarA(destino)).isEqualTo(esperado);
    }

    @Test
    void hayExactamenteSeisTransiciones() {
        long permitidas = pares().filter(a -> (boolean) a.get()[2]).count();
        assertThat(permitidas).isEqualTo(6);
    }

    @Test
    void nuloNoEsDestinoValido() {
        assertThat(PENDIENTE.puedeCambiarA(null)).isFalse();
    }

    @Test
    void activoYFinal() {
        assertThat(List.of(EstadoCita.values())).filteredOn(EstadoCita::esActivo)
                .containsExactlyInAnyOrder(PENDIENTE, CONFIRMADA);
        assertThat(List.of(EstadoCita.values())).filteredOn(EstadoCita::esFinal)
                .containsExactlyInAnyOrder(CANCELADA, AUSENTE);
    }

    @Test
    void permitidoPorApi() {
        assertThat(PENDIENTE.permitidoPorApi(CONFIRMADA)).isTrue();
        assertThat(CONFIRMADA.permitidoPorApi(AUSENTE)).isTrue();
        assertThat(CONFIRMADA.permitidoPorApi(ATENDIDA)).isFalse();
        assertThat(ATENDIDA.permitidoPorApi(CANCELADA)).isFalse();
        for (EstadoCita origen : EstadoCita.values()) {
            assertThat(origen.permitidoPorApi(PENDIENTE)).isFalse();
        }
    }
}
