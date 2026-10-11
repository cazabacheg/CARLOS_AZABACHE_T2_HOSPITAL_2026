#!/bin/bash
# Crea infra/.env con contrasenas aleatorias si no existe. Nunca lo sobrescribe
# ni muestra las contrasenas.
set -euo pipefail
raiz="$(cd "$(dirname "$0")/.." && pwd)"
destino="${raiz}/infra/.env"
if [ -f "${destino}" ]; then
  echo "infra/.env ya existe"
  exit 0
fi
while IFS= read -r linea; do
  case "${linea}" in
    *=cambiar-esto) echo "${linea%%=*}=$(openssl rand -hex 16)" ;;
    *) echo "${linea}" ;;
  esac
done < "${raiz}/infra/.env.example" > "${destino}"
chmod 600 "${destino}"
echo "infra/.env creado"
