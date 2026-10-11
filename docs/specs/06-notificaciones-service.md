# Spec 06 — `notificaciones-service`

> Depende de: spec 00 (`ConsumidorEventos`, sobre de evento), spec 01. Consume eventos definidos en las specs 02–05.
> Documento técnico: §5.7, §8.3; enunciado §13 (Proceso 8) y §20 (CitaConfirmada → Notificaciones).
> Cubre rúbrica: RabbitMQ (consumidor), reactiva/R2DBC.

## 1. Responsabilidad

Generar **notificaciones simuladas** para el paciente a partir de eventos de negocio, guardarlas y permitir consultarlas. No envía mensajes reales (enunciado §13: "pueden simularse mediante registros en base de datos o logs").

* Es un **consumidor puro**: no publica eventos ni llama a otros servicios.
* Si está caído, los eventos se acumulan en su cola durable y se procesan al volver; ningún otro servicio se ve afectado.

## 2. Módulo

| Aspecto | Valor |
|---|---|
| `artifactId` / `spring.application.name` | `notificaciones-service` |
| Puerto | 8083 |
| Paquete base | `pe.edu.cibertec.vidasalud.notificaciones` |
| Base de datos | `notificaciones_db` (usuario `notificaciones_user`) |
| Dependencias | Las de la spec 02 §2 (sin cliente HTTP ni loadbalancer) |
| `application.yml` | Plantilla de la spec 02 §2.1 con puerto `8083` y variables `NOTIFICACIONES_DB_*` |

## 3. Cola y eventos consumidos

Cola `notificaciones.cola` (durable), DLQ `notificaciones.dlq`, exchange `hospital.eventos`.

**Bindings explícitos** (D-34), en dos fases:

| Fase | Routing key | Plantilla del mensaje |
|---|---|---|
| **A — normal** | `paciente.registrado` | "Bienvenido/a al Hospital Vida Salud. Su registro fue exitoso (paciente {pacienteId})." |
| A | `cita.creada` | "Su cita {codigo} de {especialidadNombre} fue registrada para el {fechaHora}. Está pendiente de confirmación." |
| A | `cita.confirmada` | "Su cita {codigo} de {especialidadNombre} del {fechaHora} fue confirmada." |
| A | `cita.reprogramada` | "Su cita {codigo} de {especialidadNombre} fue reprogramada del {fechaHoraAnterior} al {fechaHora}." |
| A | `cita.cancelada` | "Su cita {codigo} de {especialidadNombre} del {fechaHora} fue cancelada." |
| **B — baja prioridad (al final)** | `atencion.registrada` | "Su atención {codigo} en {areaNombre} fue registrada el {fechaAtencion} (cita {citaId})." |
| B | `pago.registrado` | "Se registró su pago de S/ {monto} (operación {numeroOperacion})." |
| B | `pago.anulado` | "Su pago con operación {numeroOperacion} fue anulado." |

* La fase B consiste **solo** en agregar sus bindings y plantillas; no cambia el diseño.
* No se notifican `cita.atendida`, `cita.ausente` ni `paciente.estado-cambiado` (no los pide el enunciado §13).
* Formatos: fechas `dd/MM/yyyy HH:mm` en hora de Lima; monto con 2 decimales.
* Los mensajes **no contienen datos personales** (los eventos no los traen, spec 02 D-13), pero **sí identificadores** legibles del recurso (código de cita `CIT-…`, de atención `ATE-…`, número de operación `OP-…`) para que el aviso sea trazable (D-35).
* Cada notificación guarda además `eventId`, `tipoEvento`, `recursoId` y `pacienteId`: desde una notificación se llega al evento en Auditoría y al recurso en su servicio.

## 4. Modelo

Tabla `notificacion` (documento §8.3) con un ajuste: columna `recurso_id UUID` (id de la cita, pago, etc. que originó el aviso) e índice por `fecha_envio`.

| Campo Java | Columna | Tipo |
|---|---|---|
| `id` | `id` | `UUID` |
| `eventId` | `event_id` | `UUID` (único → idempotencia) |
| `pacienteId` | `paciente_id` | `UUID` (tomado de `datos.pacienteId`) |
| `tipoEvento` | `tipo_evento` | `String` (routing key) |
| `recursoId` | `recurso_id` | `UUID` |
| `canal` | `canal` | `String` (`SIMULADO`) |
| `mensaje` | `mensaje` | `String` |
| `fechaEnvio` | `fecha_envio` | `OffsetDateTime` (momento del procesamiento) |

## 5. Procesamiento (`messaging/NotificacionesConsumer` + `service/NotificacionService`)

Por cada mensaje (`ConsumidorEventos` de la spec 00):

1. Deserializar el sobre (`EventoEnvelope`).
2. `PlantillasNotificacion.generar(tipo, datos)` → texto. Tipo sin plantilla → no debería llegar (bindings explícitos); si llega, se registra `WARN` y se confirma sin guardar.
3. Guardar `Notificacion` con `event_id` del sobre.
   * `DuplicateKeyException` (evento repetido) → se considera procesado → `ack` (idempotencia, documento §5.7).
4. Registrar `INFO`: `notificacion simulada enviada eventId=… tipo=… recursoId=… pacienteId=…` (sin el texto).
5. `ack`.

Errores de BD transitorios → 3 reintentos (spec 00) → DLQ.

**Ajuste a la spec 00 (`ConsumidorEventos`):** un mensaje que **no se puede deserializar** (JSON inválido o sin `eventId`/`tipo`) va **directo a la DLQ** sin reintentos, porque reintentarlo nunca lo arreglará. Se aplica a todos los consumidores.

## 6. API (solo lectura)

| Método y ruta | OK | Errores |
|---|---|---|
| `GET /api/notificaciones?pacienteId=&tipoEvento=&fecha=&pagina=&tamano=` | `200` arreglo + `X-Total-Count`, orden `fecha_envio DESC, id` | `400` |
| `GET /api/notificaciones/{id}` | `200` | `400`, `404 NOTIFICACION_NO_ENCONTRADA` |

`NotificacionResponse(id, eventId, pacienteId, tipoEvento, recursoId, canal, mensaje, fechaEnvio)`.

No hay endpoints de escritura: las notificaciones solo nacen de eventos.

## 7. Pruebas

| Clase | Tipo | Casos |
|---|---|---|
| `PlantillasNotificacionTest` | Unitaria | Un caso por cada tipo de las fases A y B: texto esperado con el código del recurso, fecha en hora de Lima (evento con offset UTC) y monto `80.00`; tipo desconocido → vacío |
| `NotificacionServiceTest` | `StepVerifier` + Mockito | Guarda con `eventId` y `pacienteId` del sobre; `DuplicateKeyException` → completa sin error |
| `NotificacionControllerTest` | `@WebFluxTest` | Listado filtrado con `X-Total-Count`; `GET` inexistente → 404 |
| `NotificacionesConsumerIT` | PostgreSQL + RabbitMQ | **Publicar `cita.confirmada` en `hospital.eventos` → aparece una fila con el mensaje esperado** (evento del enunciado §20); el mismo evento dos veces → **una** sola fila; mensaje con JSON inválido → termina en `notificaciones.dlq`; evento no enlazado (`cita.atendida`) → no llega a la cola |

## 8. Criterios de aceptación

- [ ] `./mvnw -pl notificaciones-service -am verify` en verde.
- [ ] `NOTIFICACIONES-SERVICE` en Eureka.
- [ ] Panel RabbitMQ: cola `notificaciones.cola` con los bindings de la fase A (y B al terminarla), y `notificaciones.dlq`.
- [ ] Al confirmar una cita (spec 03), aparece la notificación en `GET /api/notificaciones?pacienteId=…` vía Gateway y en el log.
- [ ] Con el servicio detenido, confirmar una cita funciona; al arrancarlo, la notificación pendiente se procesa (evidencia de desacople).

## 9. Ajustes propagados al documento técnico (aplicados)

1. §8.3: columna `recurso_id` e índice por `fecha_envio`.
2. §5.7: bindings explícitos por fase y plantillas finales (incluye `cita.creada`, `pago.anulado`).
3. §6.4: bindings de `notificaciones.cola` explícitos en lugar de `cita.*` y `pago.*`.
4. Spec 00 §5.2: mensajes no deserializables van directo a la DLQ.

## 10. Decisiones (todas aprobadas)

| # | Decisión tomada | Alternativa |
|---|---|---|
| D-34 | **Aprobado:** **Bindings explícitos** por routing key (solo lo que se notifica) | Comodines `cita.*`/`pago.*` y descartar en código lo que no interesa |
| D-35 | **Aprobado (revisado):** mensajes sin datos personales pero **con identificadores** (códigos de cita/atención, número de operación, ids) para trazabilidad | Llamar a Pacientes para personalizar (agrega dependencia síncrona a un consumidor) |
| D-36 | **Aprobado:** Mensaje no deserializable → DLQ inmediata, sin reintentos | Reintentar igual que los demás errores |
| D-37 | **Aprobado:** Demostrar el desacople deteniendo Notificaciones y viendo cómo se procesa el pendiente al volver | Solo demostrar el flujo normal |
