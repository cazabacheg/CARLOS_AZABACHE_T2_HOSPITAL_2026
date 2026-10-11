CREATE TABLE IF NOT EXISTS atencion (
  id                   UUID PRIMARY KEY,
  codigo               VARCHAR(30) NOT NULL UNIQUE,
  paciente_id          UUID        NOT NULL,
  cita_id              UUID        NOT NULL,
  fecha_atencion       TIMESTAMPTZ NOT NULL DEFAULT now(),
  area                 VARCHAR(20) NOT NULL
                       CHECK (area IN ('CONSULTA_EXTERNA','EMERGENCIA','TRIAJE','PROCEDIMIENTOS')),
  estado               VARCHAR(12) NOT NULL DEFAULT 'REGISTRADA'
                       CHECK (estado IN ('REGISTRADA','FINALIZADA','ANULADA')),
  fecha_creacion       TIMESTAMPTZ NOT NULL DEFAULT now(),
  fecha_actualizacion  TIMESTAMPTZ NOT NULL DEFAULT now(),
  version              BIGINT      NOT NULL DEFAULT 0
);
CREATE SEQUENCE IF NOT EXISTS atencion_codigo_seq;
CREATE UNIQUE INDEX IF NOT EXISTS ux_atencion_cita_activa ON atencion (cita_id) WHERE estado <> 'ANULADA';
CREATE INDEX IF NOT EXISTS ix_atencion_paciente ON atencion (paciente_id);
