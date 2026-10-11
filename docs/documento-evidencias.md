---
title: "Hospital Vida Salud — Evidencias"
subtitle: "T2 - Desarrollo de Aplicaciones Web II"
lang: es
---

Capturas y salidas del sistema en ejecución que respaldan el documento técnico: servicios registrados, enrutamiento, flujo funcional, persistencia, mensajería, trazabilidad, pruebas y ciclo de integración continua.

# 1. Eureka: las 7 instancias registradas

![Panel de Eureka](evidencias/01-eureka-instancias-registradas.png)

# 2. API Gateway: rutas `lb://` hacia los servicios

![Rutas del Gateway](evidencias/02-gateway-rutas.png)

# 3. Salud del servicio de citas (base de datos, RabbitMQ y Eureka)

![Salud de citas-service](evidencias/03-salud-citas-service.png)

# 4. Flujo funcional completo por el Gateway (Newman: 49 peticiones, 86 aserciones, 0 fallos)

![Resumen de Newman](evidencias/04-newman-resumen.png)

# 5. Documentación interactiva (Swagger UI)

![Swagger de pacientes](evidencias/05a-swagger-pacientes.png)

![Swagger de citas](evidencias/05b-swagger-citas.png)

# 6. Trazas distribuidas en Jaeger

Crear una cita: Gateway → Citas → Pacientes (llamada síncrona) con las consultas SQL de R2DBC.

![Traza de crear cita](evidencias/06a-jaeger-traza-crear-cita.png)

Confirmar una cita: la traza continúa por RabbitMQ hasta los consumidores de Notificaciones y Auditoría.

![Traza de confirmar cita](evidencias/06b-jaeger-traza-confirmar-cita-rabbitmq.png)

Con Pacientes caído: el servicio de citas responde `503` y la traza muestra el *span* fallido.

![Traza del fallo con Pacientes caído](evidencias/06c-jaeger-traza-fallo-pacientes-503.png)

# 7. Logs centralizados en Seq, filtrados por `TraceId`

![Logs en Seq](evidencias/07-seq-logs-por-traceid.png)

# 8. RabbitMQ: exchange `hospital.eventos` y colas

![Exchanges de RabbitMQ](evidencias/08a-rabbitmq-exchanges.png)

![Colas de RabbitMQ](evidencias/08b-rabbitmq-colas.png)

# 9. Persistencia SQL con R2DBC y trigger de inmutabilidad de la auditoría

![Datos en PostgreSQL](evidencias/09-postgres-persistencia.png)

# 10. Integración continua con GitHub Actions y políticas de rama

Repositorio: <https://github.com/cazabacheg/CARLOS_AZABACHE_T2_HOSPITAL_2026>. Las ramas `dev`, `stage` y `main` solo aceptan cambios por *pull request* con el check `build-test` en verde. Un *push* directo a `main` es rechazado:

```text
$ git push origin fix/test-secuencia-secuencial:main   # intento de push directo a main
remote: error: GH006: Protected branch update failed for refs/heads/main.        
remote: 
remote: - Changes must be made through a pull request.        
remote: 
remote: - Required status check "build-test" is expected.        
To https://github.com/cazabacheg/CARLOS_AZABACHE_T2_HOSPITAL_2026.git
 ! [remote rejected] fix/test-secuencia-secuencial -> main (protected branch hook declined)
error: failed to push some refs to 'https://github.com/cazabacheg/CARLOS_AZABACHE_T2_HOSPITAL_2026.git'
```

Fallo controlado: se cambia a propósito el valor esperado de una prueba en la rama `demo/ci-fallo` y se abre el *pull request* hacia `dev`. El pipeline queda **en rojo** y el *merge* queda bloqueado.

![Pull request con el CI en rojo](evidencias/ci-01-pr-fallo-controlado-rojo.png)

![Ejecución fallida de GitHub Actions](evidencias/ci-02-run-fallido.png)

Corrección: se restaura la prueba en la misma rama; el pipeline vuelve a **verde** y el *merge* se habilita.

![Pull request corregido, CI en verde](evidencias/ci-03-pr-corregido-verde.png)

![Ejecución exitosa de GitHub Actions](evidencias/ci-04-run-exitoso.png)

Historial de ejecuciones y ramas protegidas (escudo junto a `main`, `stage` y `dev`): una ejecución en rojo (el fallo controlado) y las demás en verde, incluidas las promociones `dev` → `stage` → `main` por *pull request*.

![Historial de GitHub Actions](evidencias/ci-05-actions-historial.png)

![Pull requests fusionados](evidencias/ci-06-pull-requests-fusionados.png)

![Ramas protegidas](evidencias/ci-07-ramas-protegidas.png)
