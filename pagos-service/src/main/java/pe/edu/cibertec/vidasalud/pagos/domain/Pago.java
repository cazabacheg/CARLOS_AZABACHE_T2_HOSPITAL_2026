package pe.edu.cibertec.vidasalud.pagos.domain;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.time.temporal.ChronoUnit;
import java.util.UUID;
import org.springframework.data.annotation.Id;
import org.springframework.data.annotation.PersistenceCreator;
import org.springframework.data.annotation.Transient;
import org.springframework.data.annotation.Version;
import org.springframework.data.domain.Persistable;
import org.springframework.data.relational.core.mapping.Column;
import org.springframework.data.relational.core.mapping.Table;
import org.springframework.http.HttpStatus;
import pe.edu.cibertec.vidasalud.commons.error.ErrorNegocio;

@Table("pago")
public class Pago implements Persistable<UUID> {

    @Id
    private final UUID id;
    @Column("numero_operacion")
    private final String numeroOperacion;
    @Column("paciente_id")
    private final UUID pacienteId;
    @Column("cita_id")
    private final UUID citaId;
    @Column("concepto")
    private final String concepto;
    @Column("monto")
    private final BigDecimal monto;
    @Column("moneda")
    private final String moneda;
    @Column("fecha_pago")
    private final OffsetDateTime fechaPago;
    @Column("medio_pago")
    private final MedioPago medioPago;
    @Column("estado")
    private EstadoPago estado;
    @Column("motivo_anulacion")
    private String motivoAnulacion;
    @Column("fecha_actualizacion")
    private OffsetDateTime fechaActualizacion;
    @Version
    @Column("version")
    private Long version;
    @Column("clave_idempotencia")
    private final String claveIdempotencia;
    @Column("huella_solicitud")
    private final String huellaSolicitud;
    @Transient
    private boolean nuevo;

    @PersistenceCreator
    Pago(UUID id, String numeroOperacion, UUID pacienteId, UUID citaId, String concepto, BigDecimal monto,
         String moneda, OffsetDateTime fechaPago, MedioPago medioPago, EstadoPago estado, String motivoAnulacion,
         OffsetDateTime fechaActualizacion, Long version, String claveIdempotencia, String huellaSolicitud) {
        this.id = id;
        this.numeroOperacion = numeroOperacion;
        this.pacienteId = pacienteId;
        this.citaId = citaId;
        this.concepto = concepto;
        this.monto = monto;
        this.moneda = moneda;
        this.fechaPago = fechaPago;
        this.medioPago = medioPago;
        this.estado = estado;
        this.motivoAnulacion = motivoAnulacion;
        this.fechaActualizacion = fechaActualizacion;
        this.version = version;
        this.claveIdempotencia = claveIdempotencia;
        this.huellaSolicitud = huellaSolicitud;
        this.nuevo = false;
    }

    public static Pago registrar(DatosPago datos, String numeroOperacion, String claveIdempotencia,
                                 String huellaSolicitud, Clock reloj) {
        ReglasPago.validarMonto(datos.monto());
        OffsetDateTime ahora = ahora(reloj);
        Pago pago = new Pago(UUID.randomUUID(), numeroOperacion, datos.pacienteId(), datos.citaId(),
                datos.concepto().trim(), ReglasPago.normalizar(datos.monto()), "PEN", ahora, datos.medioPago(),
                EstadoPago.REGISTRADO, null, ahora, null, claveIdempotencia, huellaSolicitud);
        pago.nuevo = true;
        return pago;
    }

    public void anular(String motivo, Clock reloj) {
        if (estado.esFinal()) {
            throw new ErrorNegocio(HttpStatus.CONFLICT, "PAGO_YA_ANULADO", "El pago ya fue anulado.");
        }
        estado = EstadoPago.ANULADO;
        motivoAnulacion = motivo.trim();
        fechaActualizacion = ahora(reloj);
    }

    public Pago marcarComoExistente() {
        this.nuevo = false;
        return this;
    }

    private static OffsetDateTime ahora(Clock reloj) {
        return OffsetDateTime.now(reloj).truncatedTo(ChronoUnit.MICROS);
    }

    @Override
    public UUID getId() {
        return id;
    }

    @Override
    public boolean isNew() {
        return nuevo;
    }

    public String getNumeroOperacion() {
        return numeroOperacion;
    }

    public UUID getPacienteId() {
        return pacienteId;
    }

    public UUID getCitaId() {
        return citaId;
    }

    public String getConcepto() {
        return concepto;
    }

    public BigDecimal getMonto() {
        return monto;
    }

    public String getMoneda() {
        return moneda;
    }

    public OffsetDateTime getFechaPago() {
        return fechaPago;
    }

    public MedioPago getMedioPago() {
        return medioPago;
    }

    public EstadoPago getEstado() {
        return estado;
    }

    public String getMotivoAnulacion() {
        return motivoAnulacion;
    }

    public OffsetDateTime getFechaActualizacion() {
        return fechaActualizacion;
    }

    public Long getVersion() {
        return version;
    }

    public String getClaveIdempotencia() {
        return claveIdempotencia;
    }

    public String getHuellaSolicitud() {
        return huellaSolicitud;
    }
}
