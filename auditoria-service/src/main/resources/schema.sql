CREATE TABLE IF NOT EXISTS evento_auditoria (
  id               UUID PRIMARY KEY,
  event_id         UUID         NOT NULL UNIQUE,
  fecha_evento     TIMESTAMPTZ  NOT NULL,
  tipo_evento      VARCHAR(50)  NOT NULL,
  tipo_recurso     VARCHAR(30)  NOT NULL,
  recurso_id       UUID         NOT NULL,
  estado_anterior  VARCHAR(20),
  estado_nuevo     VARCHAR(20),
  origen           VARCHAR(40)  NOT NULL,
  fecha_registro   TIMESTAMPTZ  NOT NULL DEFAULT now()
)@@

CREATE INDEX IF NOT EXISTS ix_auditoria_recurso ON evento_auditoria (tipo_recurso, recurso_id)@@

CREATE INDEX IF NOT EXISTS ix_auditoria_fecha ON evento_auditoria (fecha_evento)@@

CREATE OR REPLACE FUNCTION fn_auditoria_inmutable() RETURNS trigger AS $$
BEGIN
  RAISE EXCEPTION 'evento_auditoria es inmutable: % no permitido', TG_OP
    USING ERRCODE = 'P0001';
END;
$$ LANGUAGE plpgsql@@

CREATE OR REPLACE TRIGGER tg_auditoria_sin_update_delete
  BEFORE UPDATE OR DELETE ON evento_auditoria
  FOR EACH ROW EXECUTE FUNCTION fn_auditoria_inmutable()@@

CREATE OR REPLACE TRIGGER tg_auditoria_sin_truncate
  BEFORE TRUNCATE ON evento_auditoria
  FOR EACH STATEMENT EXECUTE FUNCTION fn_auditoria_inmutable()@@
