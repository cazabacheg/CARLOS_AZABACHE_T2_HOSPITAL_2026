#!/bin/bash
# Descarga el agente Java de OpenTelemetry (version fija) a tools/ y verifica su SHA-256.
set -euo pipefail
raiz="$(cd "$(dirname "$0")/.." && pwd)"
version="2.31.1"
sha256="bbf83c151b6400709e2f225bdd07a04f839d9d13b8b93464241333fd25d3e3ba"
destino="${raiz}/tools/opentelemetry-javaagent-${version}.jar"
mkdir -p "${raiz}/tools"
if [ -f "${destino}" ] && echo "${sha256}  ${destino}" | sha256sum -c --quiet - 2>/dev/null; then
  echo "agente OpenTelemetry ${version} ya descargado"
  exit 0
fi
curl -fsSL -o "${destino}" \
  "https://repo.maven.apache.org/maven2/io/opentelemetry/javaagent/opentelemetry-javaagent/${version}/opentelemetry-javaagent-${version}.jar"
echo "${sha256}  ${destino}" | sha256sum -c --quiet - || { rm -f "${destino}"; echo "SHA-256 no coincide" >&2; exit 1; }
echo "agente OpenTelemetry ${version} descargado y verificado"
