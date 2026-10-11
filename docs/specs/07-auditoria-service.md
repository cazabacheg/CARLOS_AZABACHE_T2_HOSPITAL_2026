# Spec 07 — `auditoria-service`

> Depende de: spec 00 (`ConsumidorEventos`, sobre de evento), spec 01. Consume **todos** los eventos de las specs 02–05.
> Documento técnico: §5.7, §8.3, RN-08; enunciado §14 ("registrar fecha, identificador del recurso, estado anterior, estado nuevo y origen") y §20 (CitaConfirmada → Auditoría).
> Cubre rúbrica: RabbitMQ (segundo consumidor del mismo evento), trazabilidad, reactiva/R2DBC.

## 1. Responsabilidad

Registrar de forma **inmutable** la trazabilidad de todos los cambios importantes de estado del sistema y permitir consultarla, incluido el **historial completo de un recurso** (p. ej. todos los estados por los que pasó una cita).

* Consumidor puro de **todos** los eventos (binding `#`): no publica eventos ni llama a otros servicios.
* Junto con Notificaciones, demuestra que **un mismo evento** (`cita.confirmada`) llega a **dos consumidores independientes** (enunciado §20).

## 2. Módulo

| Aspecto | Valor |
|---|---|
| `artifactId` / `spring.application.name` | `auditoria-service` |
| Puerto | 8084 |
| Paquete base | `pe.edu.cibertec.vidasalud.auditoria` |
| Base de datos | `auditoria_db` (usuario `auditoria_user`) |
| Dependencias | Las de la spec 02 §2 (sin cliente HTTP ni loadbalancer) |
| `application.yml` | Plantilla de la spec 02 §2.1 con puerto `8084` y variables `AUDITORIA_DB_*` |

## 3. Cola

| Elemento | Valor |
|---|---|
| Cola | `auditoria.cola` (durable), DLQ `auditoria.dlq` |
| Binding | `#` sobre `hospital.eventos`: **todo** evento publicado, presente o futuro, se audita sin cambiar este servicio |

Eventos que llegan hoy: `paciente.registrado`, `paciente.estado-cambiado`, `cita.creada`, `cita.confirmada`, `cita.reprogramada`, `cita.cancelada`, `cita.atendida`, `cita.ausente`, `atencion.registrada`, `atencion.finalizada`, `atencion.anulada`, `pago.registrado`, `pago.anulado`.

## 4. Modelo

Tabla `evento_auditoria` (documento §8.3) con un ajuste: columna **`datos JSONB`** con la copia de `datos` del sobre (D-39) e índice por `fecha_evento`.

| Campo Java | Columna | Origen en el sobre |
|---|---|---|
| `id` | `id` | generado |
| `eventId` | `event_id` (único → idempotencia) | `eventId` |
| `fechaEvento` | `fecha_evento` | `ocurrioEn` (momento del hecho, **no** de la recepción) |
| `tipoEvento` | `tipo_evento` | `tipo` |
| `tipoRecurso` | `tipo_recurso` | `tipoRecurso` (`PACIENTE`, `CITA`, `ATENCION`, `PAGO`) |
| `recursoId` | `recurso_id` | `recursoId` |
| `estadoAnterior` | `estado_anterior` | `estadoAnterior` (null en creaciones) |
| `estadoNuevo` | `estado_nuevo` | `estadoNuevo` |
| `origen` | `origen` | `origen` (servicio emisor) |
| `datos` | `datos` (`JSONB`) | `datos` (sin datos personales, por diseño de los productores) |
| `fechaRegistro` | `fecha_registro` | momento de la recepción |

Mapeo de `JSONB` con `io.r2dbc.postgresql.codec.Json` (soportado por `r2dbc-postgresql`).

Cubre exactamente lo que pide el enunciado §14: **fecha, identificador del recurso, estado anterior, estado nuevo y origen**.

## 5. Procesamiento

Por cada mensaje (`ConsumidorEventos`, spec 00):

1. Deserializar el sobre (si falla → DLQ inmediata, D-36).
2. Validar que tenga `tipoRecurso`, `recursoId`, `estadoNuevo` y `origen`; si falta alguno → DLQ inmediata (evento mal formado del productor).
3. Insertar `EventoAuditoria`. `DuplicateKeyException` sobre `event_id` → `ack` (idempotencia).
4. Registrar `INFO`: `auditoria registrada eventId=… tipo=… recurso=<tipo>/<id> <anterior>→<nuevo>`.

**Orden de llegada.** Los eventos pueden llegar desordenados (reintentos, varios productores). Por eso el historial se ordena por `fecha_evento` (momento del hecho) y no por llegada.

**Inmutabilidad (D-40).** El servicio solo **inserta**: no hay endpoints de modificación ni de borrado, y el repositorio no expone `update`/`delete` (se declara una interfaz propia con solo `save` de inserción y consultas, en lugar de heredar `ReactiveCrudRepository` completo).

## 6. API (solo lectura)

| Método y ruta | OK | Errores |
|---|---|---|
| `GET /api/auditoria?tipoRecurso=&recursoId=&tipoEvento=&origen=&desde=&hasta=&pagina=&tamano=` | `200` arreglo + `X-Total-Count`, orden `fecha_evento DESC, id` | `400` (p. ej. `desde > hasta`) |
| `GET /api/auditoria/{id}` | `200` | `400`, `404 EVENTO_AUDITORIA_NO_ENCONTRADO` |
| `GET /api/auditoria/recursos/{tipoRecurso}/{recursoId}/historial` | `200` arreglo **cronológico** (`fecha_evento ASC`) con todos los eventos del recurso; vacío si no hay | `400` (`tipoRecurso` inválido) |

`desde`/`hasta` son `OffsetDateTime` ISO-8601. `EventoAuditoriaResponse` expone todos los campos de §4 (`datos` como objeto JSON).

El endpoint de **historial** es la evidencia directa de trazabilidad (D-41). Ejemplo para una cita: `cita.creada (null→PENDIENTE)` → `cita.confirmada (PENDIENTE→CONFIRMADA)` → `cita.atendida (CONFIRMADA→ATENDIDA)`.

## 7. Pruebas

| Clase | Tipo | Casos |
|---|---|---|
| `EventoAuditoriaMapperTest` | Unitaria | Sobre → entidad: `fechaEvento = ocurrioEn`; `estadoAnterior` nulo en creaciones; `datos` copiado; sobre sin `recursoId` → error de validación |
| `AuditoriaServiceTest` | `StepVerifier` + Mockito | Inserta; `DuplicateKeyException` → completa sin error; historial ordenado ascendente |
| `AuditoriaControllerTest` | `@WebFluxTest` | Filtros con `X-Total-Count`; `desde > hasta` → 400; historial con `tipoRecurso` inválido → 400; `GET` inexistente → 404 |
| `AuditoriaConsumerIT` | PostgreSQL + RabbitMQ | **Publicar `cita.confirmada` → fila con `PENDIENTE → CONFIRMADA`, origen `citas-service`** (enunciado §14/§20); evento repetido → una sola fila; un evento de cualquier routing key (p. ej. `pago.anulado`) también llega (binding `#`); tres eventos de una cita publicados **en desorden** → el historial los devuelve en orden cronológico; JSON inválido → `auditoria.dlq` |
| `AuditoriaRepositoryIT` | PostgreSQL | `datos` JSONB se guarda y se lee; unicidad de `event_id` |

## 8. Criterios de aceptación

- [ ] `./mvnw -pl auditoria-service -am verify` en verde.
- [ ] `AUDITORIA-SERVICE` en Eureka.
- [ ] Panel RabbitMQ: `auditoria.cola` con binding `#` y `auditoria.dlq`.
- [ ] Tras el flujo completo de la demo, `GET /api/auditoria/recursos/CITA/{id}/historial` vía Gateway muestra la secuencia de estados de la cita con fechas y origen.
- [ ] El mismo `cita.confirmada` aparece en Notificaciones **y** en Auditoría (dos consumidores de un evento).
- [ ] No existe ningún endpoint ni método de repositorio para modificar o borrar registros.

## 9. Ajustes que se propagan al documento técnico al aprobar

1. §8.3: columna `datos JSONB` e índice por `fecha_evento`.
2. §5.7: endpoint de historial por recurso, filtros por rango de fechas, inmutabilidad y orden por momento del hecho.
3. §9.3: código `EVENTO_AUDITORIA_NO_ENCONTRADO`.

## 10. Decisiones abiertas para revisión

| # | Decisión tomada | Alternativa |
|---|---|---|
| D-39 | Guardar también `datos` del evento (JSONB) para tener el contexto completo de cada cambio | Guardar solo los campos del enunciado (fecha, recurso, estados, origen) |
| D-40 | Inmutabilidad **en la aplicación** (solo inserción, sin endpoints ni métodos de modificación) | Además, un *trigger* en PostgreSQL que bloquee `UPDATE`/`DELETE` (más fuerte, pero complica `schema.sql` por los bloques `$$`) |
| D-41 | Endpoint de historial cronológico por recurso | Solo el listado con filtros |
| D-42 | Sin política de retención (los registros se conservan indefinidamente) | Depuración periódica |
