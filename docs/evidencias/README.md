# Evidencias de ejecucion - Hospital Vida Salud

Capturas y salidas del sistema en ejecución (2026-10-11).

| Archivo | Descripcion |
|---|---|
| `01-eureka-instancias-registradas.png` | Panel de Eureka (:8761) con las 7 instancias (API-GATEWAY y 6 servicios) en UP. |
| `02-gateway-rutas.png` | Respuesta real de `GET /actuator/gateway/routes`: las 6 rutas `lb://` del Gateway. |
| `03-salud-citas-service.png` | `/actuator/health` de citas-service con r2dbc, rabbit y discoveryComposite en UP. |
| `04-newman-resumen.png` | Resumen final de Newman (49 requests, 86 assertions, 0 fallos). |
| `05a-swagger-pacientes.png` | Swagger UI de pacientes-service (:8081) con sus endpoints. |
| `05b-swagger-citas.png` | Swagger UI de citas-service (:8082) con sus endpoints. |
| `06a-jaeger-traza-crear-cita.png` | Traza Jaeger de POST /api/citas: Gateway -> citas -> pacientes (spans SQL) y publicacion de evento. |
| `06b-jaeger-traza-confirmar-cita-rabbitmq.png` | Traza continua de PATCH estado=CONFIRMADA: publicacion a RabbitMQ y consumo en notificaciones y auditoria. |
| `06c-jaeger-traza-fallo-pacientes-503.png` | Traza con spans fallidos (503) al estar pacientes-service detenido (luego reiniciado y en UP). |
| `07-seq-logs-por-traceid.png` | Seq filtrado por `@TraceId` de la traza 06b: logs de citas, notificaciones y auditoria. |
| `08a-rabbitmq-exchanges.png` | RabbitMQ, pestana Exchanges, con `hospital.eventos` resaltado. |
| `08b-rabbitmq-colas.png` | RabbitMQ, pestana Queues (colas y .dlq) con totales publicados/entregados/ack. |
| `09-postgres-persistencia.png` | Conteos y filas por tabla y trigger de inmutabilidad ("evento_auditoria es inmutable"). |
| `10-mvnw-verify-resumen.png` | Resultado de `./mvnw verify`: BUILD SUCCESS en los 9 módulos y 456 pruebas, 0 fallos, 0 errores. |

## Integración continua y políticas de rama

| Archivo | Descripción |
|---|---|
| `ci-01-pr-fallo-controlado-rojo.png` | Pull request con la prueba rota a propósito: el CI queda en rojo. |
| `ci-02-run-fallido.png` | Ejecución fallida de GitHub Actions (`expected: 25 but was: 20`). |
| `ci-03-pr-corregido-verde.png` | Pull request tras la corrección: el CI en verde habilita el merge. |
| `ci-04-run-exitoso.png` | Ejecución exitosa de GitHub Actions. |
| `ci-05-actions-historial.png` | Historial de ejecuciones: 1 roja (fallo controlado) y el resto verdes. |
| `ci-06-pull-requests-fusionados.png` | Pull requests fusionados por el flujo `dev` → `stage` → `main`. |
| `ci-07-ramas-protegidas.png` | Ramas `main`, `stage` y `dev` protegidas. |
