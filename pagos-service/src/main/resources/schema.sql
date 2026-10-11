CREATE TABLE IF NOT EXISTS pago (
  id                  UUID PRIMARY KEY,
  numero_operacion    VARCHAR(30)   NOT NULL UNIQUE,
  paciente_id         UUID          NOT NULL,
  cita_id             UUID,
  concepto            VARCHAR(120)  NOT NULL,
  monto               NUMERIC(12,2) NOT NULL CHECK (monto > 0 AND monto <= 100000),
  moneda              CHAR(3)       NOT NULL DEFAULT 'PEN' CHECK (moneda = 'PEN'),
  fecha_pago          TIMESTAMPTZ   NOT NULL DEFAULT now(),
  medio_pago          VARCHAR(15)   NOT NULL CHECK (medio_pago IN ('EFECTIVO','TARJETA','TRANSFERENCIA','BILLETERA')),
  estado              VARCHAR(10)   NOT NULL DEFAULT 'REGISTRADO' CHECK (estado IN ('REGISTRADO','ANULADO')),
  motivo_anulacion    VARCHAR(200),
  fecha_actualizacion TIMESTAMPTZ   NOT NULL DEFAULT now(),
  version             BIGINT        NOT NULL DEFAULT 0,
  clave_idempotencia  VARCHAR(64)   NOT NULL UNIQUE,
  huella_solicitud    CHAR(64)      NOT NULL
);
CREATE SEQUENCE IF NOT EXISTS pago_operacion_seq;
CREATE INDEX IF NOT EXISTS ix_pago_paciente ON pago (paciente_id);
CREATE INDEX IF NOT EXISTS ix_pago_fecha ON pago (fecha_pago);
