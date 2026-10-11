CREATE TABLE IF NOT EXISTS cita (
  id                  UUID PRIMARY KEY,
  codigo              VARCHAR(30)  NOT NULL UNIQUE,
  paciente_id         UUID         NOT NULL,
  especialidad        VARCHAR(30)  NOT NULL,
  fecha_hora          TIMESTAMPTZ  NOT NULL,
  profesional_codigo  VARCHAR(20)  NOT NULL,
  motivo              VARCHAR(200),
  estado              VARCHAR(12)  NOT NULL DEFAULT 'PENDIENTE'
                      CHECK (estado IN ('PENDIENTE','CONFIRMADA','ATENDIDA','CANCELADA','AUSENTE')),
  fecha_creacion      TIMESTAMPTZ  NOT NULL DEFAULT now(),
  fecha_actualizacion TIMESTAMPTZ  NOT NULL DEFAULT now(),
  version             BIGINT       NOT NULL DEFAULT 0
);
CREATE SEQUENCE IF NOT EXISTS cita_codigo_seq;
CREATE UNIQUE INDEX IF NOT EXISTS ux_cita_profesional_horario
  ON cita (profesional_codigo, fecha_hora) WHERE estado IN ('PENDIENTE','CONFIRMADA');
CREATE UNIQUE INDEX IF NOT EXISTS ux_cita_paciente_horario
  ON cita (paciente_id, fecha_hora) WHERE estado IN ('PENDIENTE','CONFIRMADA');
CREATE INDEX IF NOT EXISTS ix_cita_paciente ON cita (paciente_id);
CREATE INDEX IF NOT EXISTS ix_cita_estado_fecha ON cita (estado, fecha_hora);
