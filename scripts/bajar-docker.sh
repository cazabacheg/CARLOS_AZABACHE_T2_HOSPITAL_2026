#!/bin/bash
# Detiene y elimina los contenedores y la red. Con -v borra tambien los datos
# (PostgreSQL, RabbitMQ y Seq).
set -euo pipefail
raiz="$(cd "$(dirname "$0")/.." && pwd)"
DOCKER="docker"
docker info >/dev/null 2>&1 || DOCKER="sudo docker"
${DOCKER} compose -f "${raiz}/infra/docker-compose.yml" --profile apps down "$@"
