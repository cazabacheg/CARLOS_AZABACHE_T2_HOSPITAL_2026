package pe.edu.cibertec.vidasalud.pacientes.domain;

import pe.edu.cibertec.vidasalud.commons.error.ErrorNegocio;

public record FiltroPacientes(EstadoPaciente estado, TipoDocumento tipoDocumento, String numeroDocumento,
                              String apellidos) {

    public static FiltroPacientes de(EstadoPaciente estado, TipoDocumento tipo, String numero, String apellidos) {
        String numeroNormalizado = numero == null || numero.isBlank() ? null : ReglasDocumento.normalizar(numero);
        if ((tipo == null) != (numeroNormalizado == null)) {
            throw ErrorNegocio.validacion("numeroDocumento", "tipoDocumento y numeroDocumento deben enviarse juntos");
        }
        String apellidosNormalizados = apellidos == null || apellidos.isBlank() ? null : apellidos.trim();
        return new FiltroPacientes(estado, tipo, numeroNormalizado, apellidosNormalizados);
    }
}
