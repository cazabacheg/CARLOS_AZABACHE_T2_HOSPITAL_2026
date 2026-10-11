#!/bin/bash
# Detiene los procesos iniciados por iniciar-todo.sh (en orden inverso).
set -euo pipefail
raiz="$(cd "$(dirname "$0")/.." && pwd)"
for nombre in auditoria-service notificaciones-service pagos-service atenciones-service \
              citas-service pacientes-service api-gateway eureka-server; do
  pidfile="${raiz}/logs/${nombre}.pid"
  [ -f "${pidfile}" ] || continue
  if kill -0 "$(cat "${pidfile}")" 2>/dev/null; then
    kill "$(cat "${pidfile}")" && echo "detenido ${nombre}"
  fi
  rm -f "${pidfile}"
done
