#!/bin/bash
# Arranca Eureka, Gateway y los servicios en orden, esperando la salud de cada uno.
# Uso: scripts/iniciar-todo.sh [servicio ...]   (sin argumentos arranca todos)
# Requisitos: infraestructura arriba (docker compose), proyecto empaquetado
# (./mvnw -DskipTests package) e infra/.env creado (scripts/preparar-entorno.sh).
set -euo pipefail
raiz="$(cd "$(dirname "$0")/.." && pwd)"
# shellcheck disable=SC1091
source "${raiz}/scripts/entorno-dev.sh"
mkdir -p "${raiz}/logs"
agente="${raiz}/tools/opentelemetry-javaagent-2.31.1.jar"

# nombre:puerto, en orden de arranque
servicios=(
  "eureka-server:8761" "api-gateway:8080" "pacientes-service:8081" "citas-service:8082"
  "atenciones-service:8085" "pagos-service:8086" "notificaciones-service:8083" "auditoria-service:8084"
)

if [ "$#" -gt 0 ]; then
  seleccion=()
  for entrada in "${servicios[@]}"; do
    for pedido in "$@"; do
      [ "${entrada%%:*}" = "${pedido}" ] && seleccion+=("${entrada}")
    done
  done
  servicios=("${seleccion[@]}")
fi

opciones_agente=()
if [ -f "${agente}" ]; then
  opciones_agente=("-javaagent:${agente}")
else
  echo "AVISO: falta el agente OpenTelemetry (scripts/descargar-otel-agent.sh); se arranca sin trazas" >&2
fi

for entrada in "${servicios[@]}"; do
  nombre="${entrada%%:*}"; puerto="${entrada##*:}"
  jar="${raiz}/${nombre}/target/${nombre}-1.0.0-SNAPSHOT.jar"
  [ -f "${jar}" ] || { echo "falta ${jar}: ejecute ./mvnw -DskipTests package" >&2; exit 1; }
  if [ -f "${raiz}/logs/${nombre}.pid" ] && kill -0 "$(cat "${raiz}/logs/${nombre}.pid")" 2>/dev/null; then
    echo "${nombre} ya esta en ejecucion"; continue
  fi
  echo "iniciando ${nombre} (:${puerto})"
  OTEL_SERVICE_NAME="${nombre}" \
  OTEL_RESOURCE_ATTRIBUTES="service.namespace=hospital-vida-salud,deployment.environment=local" \
  OTEL_PROPAGATORS="tracecontext,baggage" \
  OTEL_TRACES_SAMPLER="always_on" \
  OTEL_EXPORTER_OTLP_PROTOCOL="http/protobuf" \
  OTEL_TRACES_EXPORTER="otlp" \
  OTEL_EXPORTER_OTLP_TRACES_ENDPOINT="${OTLP_TRACES_URL:-http://localhost:4318/v1/traces}" \
  OTEL_LOGS_EXPORTER="otlp" \
  OTEL_EXPORTER_OTLP_LOGS_ENDPOINT="${OTLP_LOGS_URL:-http://localhost:5341/ingest/otlp/v1/logs}" \
  OTEL_METRICS_EXPORTER="none" \
    nohup java "${opciones_agente[@]}" -jar "${jar}" > "${raiz}/logs/${nombre}.log" 2>&1 &
  echo $! > "${raiz}/logs/${nombre}.pid"
  for _ in $(seq 1 90); do
    if curl -fs "http://localhost:${puerto}/actuator/health" 2>/dev/null | grep -q '"status":"UP"'; then
      echo "  ${nombre} UP"; break
    fi
    sleep 2
  done
done
echo "Listo. Eureka: http://localhost:8761  Gateway: http://localhost:8080"
