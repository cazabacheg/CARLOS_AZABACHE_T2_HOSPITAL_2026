#!/bin/bash
# Crea una base de datos y un usuario por servicio. Cada usuario solo puede
# conectarse a su propia base. Las contrasenas vienen de variables de entorno
# del contenedor; este script no las imprime.
set -euo pipefail

for svc in pacientes citas atenciones pagos notificaciones auditoria; do
  var="$(echo "${svc}" | tr '[:lower:]' '[:upper:]')_DB_PASSWORD"
  pwd="${!var:?falta ${var}}"
  psql -v ON_ERROR_STOP=1 --username "$POSTGRES_USER" --dbname postgres \
       -v svc="${svc}" -v pwd="${pwd}" <<'SQL'
SELECT format('CREATE ROLE %I LOGIN PASSWORD %L', :'svc' || '_user', :'pwd') \gexec
SELECT format('CREATE DATABASE %I OWNER %I', :'svc' || '_db', :'svc' || '_user') \gexec
SELECT format('REVOKE CONNECT ON DATABASE %I FROM PUBLIC', :'svc' || '_db') \gexec
SQL
done
