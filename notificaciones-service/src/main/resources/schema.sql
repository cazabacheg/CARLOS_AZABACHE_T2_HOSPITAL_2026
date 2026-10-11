CREATE TABLE IF NOT EXISTS notificacion (
  id           UUID PRIMARY KEY,
  event_id     UUID         NOT NULL UNIQUE,
  paciente_id  UUID,
  tipo_evento  VARCHAR(50)  NOT NULL,
  recurso_id   UUID         NOT NULL,
  canal        VARCHAR(15)  NOT NULL DEFAULT 'SIMULADO',
  mensaje      VARCHAR(300) NOT NULL,
  fecha_envio  TIMESTAMPTZ  NOT NULL DEFAULT now()
);
CREATE INDEX IF NOT EXISTS ix_notificacion_paciente ON notificacion (paciente_id);
CREATE INDEX IF NOT EXISTS ix_notificacion_fecha ON notificacion (fecha_envio);
