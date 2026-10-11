package pe.edu.cibertec.vidasalud.notificaciones.messaging;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import pe.edu.cibertec.vidasalud.notificaciones.domain.PlantillasNotificacion;

class BindingsNotificacionesTest {

    @Test
    void todosSonFaseAMasFaseBSinRepetidosNiComodines() {
        List<String> esperado = new ArrayList<>(BindingsNotificaciones.FASE_A);
        esperado.addAll(BindingsNotificaciones.FASE_B);

        assertThat(BindingsNotificaciones.TODOS).containsExactlyElementsOf(esperado).hasSize(8).doesNotHaveDuplicates();
        assertThat(BindingsNotificaciones.TODOS).noneMatch(k -> k.contains("*") || k.contains("#"));
    }

    @Test
    void cadaBindingTienePlantillaYViceversa() {
        assertThat(Set.copyOf(BindingsNotificaciones.TODOS)).isEqualTo(PlantillasNotificacion.tiposSoportados());
    }

    @Test
    void noEnlazaEventosSinAviso() {
        assertThat(BindingsNotificaciones.TODOS)
                .doesNotContain("cita.atendida", "cita.ausente", "paciente.estado-cambiado");
    }
}
