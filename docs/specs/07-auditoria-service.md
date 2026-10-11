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
| `application.yml` | Plantilla de la spec 02 §2.1 con puerto `8084`, variables `AUDITORIA_DB_*` y `spring.sql.init.separator: "@@"` (ver §4.1) |

## 3. Cola

| Elemento | Valor |
|---|---|
| Cola | `auditoria.cola` (durable), DLQ `auditoria.dlq` |
| Binding | `#` sobre `hospital.eventos`: **todo** evento publicado, presente o futuro, se audita sin cambiar este servicio |

Eventos que llegan hoy: `paciente.registrado`, `paciente.estado-cambiado`, `cita.creada`, `cita.confirmada`, `cita.reprogramada`, `cita.cancelada`, `cita.atendida`, `cita.ausente`, `atencion.registrada`, `atencion.finalizada`, `atencion.anulada`, `pago.registrado`, `pago.anulado`.

## 4. Modelo

Tabla `evento_auditoria` (documento §8.3) con un ajuste: índice por `fecha_evento`. **No** se guarda el campo `datos` del sobre (D-39): Auditoría registra solo lo que pide el enunciado, para no almacenar ninguna información potencialmente sensible.

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
| `fechaRegistro` | `fecha_registro` | momento de la recepción |

Cubre exactamente lo que pide el enunciado §14: **fecha, identificador del recurso, estado anterior, estado nuevo y origen**.

## 5. Procesamiento

Por cada mensaje (`ConsumidorEventos`, spec 00):

1. Deserializar el sobre (si falla → DLQ inmediata, D-36).
2. Validar que tenga `tipoRecurso`, `recursoId`, `estadoNuevo` y `origen`; si falta alguno → DLQ inmediata (evento mal formado del productor).
3. Insertar `EventoAuditoria`. `DuplicateKeyException` sobre `event_id` → `ack` (idempotencia).
4. Registrar `INFO`: `auditoria registrada eventId=… tipo=… recurso=<tipo>/<id> <anterior>→<nuevo>`.

**Orden de llegada.** Los eventos pueden llegar desordenados (reintentos, varios productores). Por eso el historial se ordena por `fecha_evento` (momento del hecho) y no por llegada.

**Inmutabilidad (D-40), en dos niveles:**

1. **Aplicación:** el servicio solo **inserta**; no hay endpoints de modificación ni de borrado, y el repositorio no expone `update`/`delete` (interfaz propia con inserción y consultas, en lugar de heredar `ReactiveCrudRepository` completo).
2. **Base de datos:** *triggers* de PostgreSQL que rechazan cualquier `UPDATE`, `DELETE` o `TRUNCATE` sobre `evento_auditoria`, venga de donde venga (este servicio, un cliente SQL o un error de programación). Ver §4.1.

### 4.1 `schema.sql` con el trigger

El inicializador de Spring (`spring.sql.init`) separa sentencias por `;` y no reconoce los bloques `$$` de PL/pgSQL. Por eso este servicio usa el separador `@@` y cada sentencia de su `schema.sql` termina en `@@`:

```sql
CREATE TABLE IF NOT EXISTS evento_auditoria ( … )@@
CREATE INDEX IF NOT EXISTS ix_auditoria_recurso ON evento_auditoria (tipo_recurso, recurso_id)@@
CREATE INDEX IF NOT EXISTS ix_auditoria_fecha ON evento_auditoria (fecha_evento)@@

CREATE OR REPLACE FUNCTION fn_auditoria_inmutable() RETURNS trigger AS $$
BEGIN
  RAISE EXCEPTION 'evento_auditoria es inmutable: % no permitido', TG_OP
    USING ERRCODE = 'P0001';
END;
$$ LANGUAGE plpgsql@@

CREATE OR REPLACE TRIGGER tg_auditoria_sin_update_delete
  BEFORE UPDATE OR DELETE ON evento_auditoria
  FOR EACH ROW EXECUTE FUNCTION fn_auditoria_inmutable()@@

CREATE OR REPLACE TRIGGER tg_auditoria_sin_truncate
  BEFORE TRUNCATE ON evento_auditoria
  FOR EACH STATEMENT EXECUTE FUNCTION fn_auditoria_inmutable()@@
```

* `CREATE OR REPLACE TRIGGER` requiere PostgreSQL ≥ 14 (se usa 16); hace el script repetible en cada arranque.
* **Límite conocido:** el usuario dueño de la tabla (`auditoria_user`, que la crea) podría eliminar el trigger con `DROP TRIGGER`. La protección completa requiere que la tabla sea de otro rol y que el servicio solo tenga `INSERT`/`SELECT`; queda como mejora en el documento técnico (§14).

## 6. API (solo lectura)

| Método y ruta | OK | Errores |
|---|---|---|
| `GET /api/auditoria?tipoRecurso=&recursoId=&tipoEvento=&origen=&desde=&hasta=&pagina=&tamano=` | `200` arreglo + `X-Total-Count`, orden `fecha_evento DESC, id` | `400` (p. ej. `desde > hasta`) |
| `GET /api/auditoria/{id}` | `200` | `400`, `404 EVENTO_AUDITORIA_NO_ENCONTRADO` |
| `GET /api/auditoria/recursos/{tipoRecurso}/{recursoId}/historial` | `200` arreglo **cronológico** (`fecha_evento ASC`) con todos los eventos del recurso; vacío si no hay | `400` (`tipoRecurso` inválido) |

`desde`/`hasta` son `OffsetDateTime` ISO-8601. `EventoAuditoriaResponse` expone todos los campos de §4.

El endpoint de **historial** es la evidencia directa de trazabilidad (D-41). Ejemplo para una cita: `cita.creada (null→PENDIENTE)` → `cita.confirmada (PENDIENTE→CONFIRMADA)` → `cita.atendida (CONFIRMADA→ATENDIDA)`.

## 7. Pruebas

| Clase | Tipo | Casos |
|---|---|---|
| `EventoAuditoriaMapperTest` | Unitaria | Sobre → entidad: `fechaEvento = ocurrioEn`; `estadoAnterior` nulo en creaciones; `datos` **no** se copia; sobre sin `recursoId` → error de validación |
| `AuditoriaServiceTest` | `StepVerifier` + Mockito | Inserta; `DuplicateKeyException` → completa sin error; historial ordenado ascendente |
| `AuditoriaControllerTest` | `@WebFluxTest` | Filtros con `X-Total-Count`; `desde > hasta` → 400; historial con `tipoRecurso` inválido → 400; `GET` inexistente → 404 |
| `AuditoriaConsumerIT` | PostgreSQL + RabbitMQ | **Publicar `cita.confirmada` → fila con `PENDIENTE → CONFIRMADA`, origen `citas-service`** (enunciado §14/§20); evento repetido → una sola fila; un evento de cualquier routing key (p. ej. `pago.anulado`) también llega (binding `#`); tres eventos de una cita publicados **en desorden** → el historial los devuelve en orden cronológico; JSON inválido → `auditoria.dlq` |
| `AuditoriaRepositoryIT` | PostgreSQL | `schema.sql` con separador `@@` se ejecuta (y se puede volver a ejecutar); unicidad de `event_id`; **`UPDATE` directo con `DatabaseClient` → error "evento_auditoria es inmutable"**; **`DELETE` → error**; **`TRUNCATE` → error** |

## 8. Criterios de aceptación

- [ ] `./mvnw -pl auditoria-service -am verify` en verde.
- [ ] `AUDITORIA-SERVICE` en Eureka.
- [ ] Panel RabbitMQ: `auditoria.cola` con binding `#` y `auditoria.dlq`.
- [ ] Tras el flujo completo de la demo, `GET /api/auditoria/recursos/CITA/{id}/historial` vía Gateway muestra la secuencia de estados de la cita con fechas y origen.
- [ ] El mismo `cita.confirmada` aparece en Notificaciones **y** en Auditoría (dos consumidores de un evento).
- [ ] No existe ningún endpoint ni método de repositorio para modificar o borrar registros.
- [ ] `UPDATE evento_auditoria …` ejecutado con `psql` como `auditoria_user` falla con "evento_auditoria es inmutable" (evidencia).

## 9. Ajustes propagados al documento técnico (aplicados)

1. §8.3: índice por `fecha_evento`; *triggers* de inmutabilidad.
2. §5.7: endpoint de historial por recurso, filtros por rango de fechas, inmutabilidad en dos niveles, sin `datos`, orden por momento del hecho.
3. §9.3: código `EVENTO_AUDITORIA_NO_ENCONTRADO`.
4. §14: limitaciones/mejoras de retención y de propiedad de la tabla de auditoría.

## 10. Decisiones (todas aprobadas)

| # | Decisión tomada | Alternativa |
|---|---|---|
| D-39 | **Aprobado (revisado):** guardar **solo** los campos del enunciado (fecha, recurso, estados, origen, tipo); **sin** `datos`, para no almacenar información sensible | Guardar también `datos` en JSONB |
| D-40 | **Aprobado (revisado):** inmutabilidad en la aplicación **y** con *triggers* de PostgreSQL (`UPDATE`/`DELETE`/`TRUNCATE`), separador `@@` en `schema.sql` | Solo a nivel de aplicación |
| D-41 | **Aprobado:** Endpoint de historial cronológico por recurso | Solo el listado con filtros |
| D-42 | **Aprobado:** sin política de retención a esta escala; anotado como limitación y mejora en el documento técnico | Depuración/archivado periódico |
