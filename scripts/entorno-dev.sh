#!/bin/bash
# Uso: source scripts/entorno-dev.sh
# Exporta las variables que esperan los servicios, leyendo infra/.env.
raiz="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
if [ ! -f "${raiz}/infra/.env" ]; then
  echo "Falta infra/.env: ejecute scripts/preparar-entorno.sh" >&2
  return 1 2>/dev/null || exit 1
fi
set -a
# shellcheck disable=SC1091
source "${raiz}/infra/.env"
set +a
export DB_HOST="${DB_HOST:-localhost}"
export DB_PORT="${DB_PORT:-5433}"
export RABBIT_HOST="${RABBIT_HOST:-localhost}"
export RABBIT_PORT="${RABBIT_PORT:-5672}"
export EUREKA_URL="${EUREKA_URL:-http://localhost:8761/eureka}"
