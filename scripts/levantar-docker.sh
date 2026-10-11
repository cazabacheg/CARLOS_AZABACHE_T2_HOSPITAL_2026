#!/bin/bash
# Levanta infraestructura + los 8 servicios en contenedores con un solo comando.
#  - Crea infra/.env con contrasenas aleatorias si no existe (nunca lo sobrescribe).
#  - Detiene procesos del host que usarian los mismos puertos.
#  - Construye las imagenes y espera a que todos los servicios esten "healthy".
# Opcional: AGENDA_DIAS_ATENCION="MONDAY,...,SUNDAY" scripts/levantar-docker.sh   (probar fuera de horario)
set -euo pipefail
raiz="$(cd "$(dirname "$0")/.." && pwd)"
compose_file="${raiz}/infra/docker-compose.yml"

DOCKER="docker"
if ! docker info >/dev/null 2>&1; then
  echo "AVISO: sin permiso directo sobre Docker; usando sudo (abra una terminal nueva para activar el grupo docker)" >&2
  DOCKER="sudo docker"
fi

"${raiz}/scripts/preparar-entorno.sh"
"${raiz}/scripts/detener-todo.sh" >/dev/null 2>&1 || true

echo "Construyendo y levantando (la primera vez tarda varios minutos)..."
${DOCKER} compose -f "${compose_file}" --profile apps up -d --build

total=8
echo "Esperando a que los ${total} servicios esten healthy..."
for _ in $(seq 1 60); do
  sanos=$(${DOCKER} ps --filter "label=com.docker.compose.project=hospital-vida-salud" \
          --filter "health=healthy" --format '{{.Names}}' | grep -cE 'hvs-.*(-service|api-gateway|eureka-server)' || true)
  [ "${sanos}" -ge "${total}" ] && break
  sleep 5
done
${DOCKER} compose -f "${compose_file}" --profile apps ps --format 'table {{.Name}}\t{{.Status}}'
if [ "${sanos}" -lt "${total}" ]; then
  echo "AVISO: solo ${sanos}/${total} servicios healthy; revise con: ${DOCKER} compose -f infra/docker-compose.yml --profile apps logs <servicio>" >&2
  exit 1
fi
cat <<'TXT'

Listo.
  Gateway (API)   http://localhost:8080
  Eureka          http://localhost:8761
  RabbitMQ        http://localhost:15672   (usuario/clave en infra/.env)
  Jaeger          http://localhost:16686
  Seq             http://localhost:8090    (admin / SEQ_ADMIN_PASSWORD de infra/.env; pide cambiarla)
  Swagger         http://localhost:8081 ... :8086 /swagger-ui.html
Detener: scripts/bajar-docker.sh   (con -v borra tambien los datos)
TXT
