CREATE TABLE IF NOT EXISTS paciente (
  id                UUID PRIMARY KEY,
  tipo_documento    VARCHAR(15)  NOT NULL CHECK (tipo_documento IN ('DNI','CE','PASAPORTE')),
  numero_documento  VARCHAR(20)  NOT NULL,
  nombres           VARCHAR(80)  NOT NULL,
  apellidos         VARCHAR(80)  NOT NULL,
  fecha_nacimiento  DATE         NOT NULL,
  telefono          VARCHAR(20),
  correo            VARCHAR(120),
  direccion         VARCHAR(200),
  fecha_registro    TIMESTAMPTZ  NOT NULL DEFAULT now(),
  estado            VARCHAR(10)  NOT NULL DEFAULT 'ACTIVO' CHECK (estado IN ('ACTIVO','INACTIVO')),
  CONSTRAINT uq_paciente_documento UNIQUE (tipo_documento, numero_documento)
);
CREATE INDEX IF NOT EXISTS ix_paciente_apellidos ON paciente (apellidos);
