package pe.edu.cibertec.vidasalud.citas.domain;

import java.time.OffsetDateTime;
import java.time.ZoneId;

public final class ZonaHospital {

    public static final ZoneId LIMA = ZoneId.of("America/Lima");

    private ZonaHospital() {
    }

    public static OffsetDateTime aLima(OffsetDateTime valor) {
        return valor == null ? null : valor.atZoneSameInstant(LIMA).toOffsetDateTime();
    }
}
