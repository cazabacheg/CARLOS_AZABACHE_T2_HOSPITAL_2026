# Spec 04 — `atenciones-service`

> Depende de: spec 00, spec 01, spec 03 (contrato `GET /api/citas/{id}` y consumo de `atencion.registrada`).
> Documento técnico: §5.5, §8.4, RN-11, RN-12; enunciado §8 (Proceso 3) y §27 (flujo mínimo: "Registro de atención simulada").
> Cubre rúbrica: reglas de negocio, segunda comunicación síncrona, evento que cambia el estado en **otro** servicio (coreografía).

## 1. Responsabilidad

Registrar la **atención administrativa simulada** de una cita confirmada y seguir su estado. No guarda historia clínica, diagnósticos ni tratamientos.

* Valida la cita con una llamada **síncrona** a Citas.
* Publica `atencion.registrada`; **Citas** reacciona y pasa la cita a `ATENDIDA`. Si la atención se anula, publica `atencion.anulada` y Citas decide si cancela la cita. Atenciones **nunca** modifica la cita directamente.

## 2. Módulo

| Aspecto | Valor |
|---|---|
| `artifactId` / `spring.application.name` | `atenciones-service` |
| Puerto | 8085 |
| Paquete base | `pe.edu.cibertec.vidasalud.atenciones` |
| Base de datos | `atenciones_db` (usuario `atenciones_user`) |
| Dependencias | Iguales a `citas-service` (spec 03 §2) |
| `application.yml` | Plantilla de la spec 02 §2.1 con puerto `8085`, variables `ATENCIONES_DB_*`, `hospital.clientes.citas.url: http://citas-service` y `hospital.atencion.minutos-antes: ${ATENCION_MINUTOS_ANTES:30}` |

## 3. Modelo de dominio

### 3.1 Enumeraciones

* `EstadoAtencion` con tabla de transiciones:

```text
REGISTRADA → FINALIZADA, ANULADA
FINALIZADA → (final)
ANULADA    → (final)
```

* `AreaAtencion` (catálogo cerrado, coherente con D-16): `CONSULTA_EXTERNA`, `EMERGENCIA`, `TRIAJE`, `PROCEDIMIENTOS`. Con nombre legible ("Consulta Externa", …).

### 3.2 Entidad `Atencion`

Tabla `atencion` (documento §8.4) con los mismos dos ajustes que Citas: columna `version BIGINT NOT NULL DEFAULT 0` y secuencia `atencion_codigo_seq`.

| Campo Java | Columna | Tipo |
|---|---|---|
| `id` | `id` | `UUID` |
| `codigo` | `codigo` | `String` (`ATE-yyyyMMdd-NNNNNN`) |
| `pacienteId` | `paciente_id` | `UUID` (copiado de la cita) |
| `citaId` | `cita_id` | `UUID` |
| `fechaAtencion` | `fecha_atencion` | `OffsetDateTime` (momento del registro, según `Clock`) |
| `area` | `area` | `AreaAtencion` |
| `estado` | `estado` | `EstadoAtencion` |
| `fechaCreacion`, `fechaActualizacion` | … | `OffsetDateTime` |
| `version` | `version` | `Long` con `@Version` |

Métodos de dominio:

* `static Atencion registrar(CitaResumen cita, AreaAtencion area, String codigo, Clock reloj)` → `REGISTRADA`; toma `pacienteId` de la cita.
* `EstadoAtencion cambiarEstado(EstadoAtencion destino, Clock reloj)` → `409 TRANSICION_NO_PERMITIDA` si no procede; devuelve el anterior.

### 3.3 Reglas (RN-11)

| Regla | Dónde se garantiza | Error |
|---|---|---|
| La cita debe existir | Llamada síncrona a Citas | `404 CITA_NO_ENCONTRADA` |
| La cita debe estar `CONFIRMADA` | Servicio, con la respuesta de Citas | `409 CITA_NO_CONFIRMADA`, "Solo se registra atención para citas confirmadas (estado actual: <ESTADO>)." |
| Una cita tiene como máximo **una** atención no anulada | Consulta previa + índice único parcial `ux_atencion_cita_activa` | `409 ATENCION_DUPLICADA`, "La cita ya tiene una atención registrada." |
| **Ventana de atención (RN-16):** solo desde **30 minutos antes** del inicio de la cita en adelante (sin límite posterior) | Servicio, con `fechaHora` de la cita y el `Clock` | `409 FUERA_DE_VENTANA_ATENCION`, "La atención se puede registrar desde 30 minutos antes de la cita (<hora>)." |

**Por qué la ventana elimina la carrera.** Citas solo permite cancelar hasta 24 h antes (RN-15, spec 03); Atenciones solo registra desde 30 min antes. Las dos ventanas **no se superponen**, así que no puede cancelarse una cita mientras se registra su atención. Los 30 minutos son configurables (`hospital.atencion.minutos-antes`).

## 4. Cliente síncrono a Citas (`client/CitasClient.java`)

Mismo patrón que `PacientesClient` (spec 03 §4):

* `@LoadBalanced WebClient`, `baseUrl = http://citas-service`, `PoliticaResiliencia`, propagación de `X-Request-Id`.
* `Mono<CitaResumen> obtener(UUID citaId)` → `GET /api/citas/{id}`.
* `CitaResumen(UUID id, UUID pacienteId, String estado, OffsetDateTime fechaHora)` propio, tolerante a campos desconocidos.

| Respuesta de Citas | Resultado |
|---|---|
| `200` | `Mono<CitaResumen>` (la regla de estado la aplica el servicio) |
| `404` | `404 CITA_NO_ENCONTRADA` |
| `5xx`, conexión, sin instancias | `503 SERVICIO_CITAS_NO_DISPONIBLE` |
| Timeout 2 s | `504 SERVICIO_CITAS_NO_DISPONIBLE` |

No se llama a Pacientes: la cita ya trae `pacienteId` y Citas validó al paciente al crearla.

## 5. Casos de uso (`service/AtencionService.java`)

### 5.1 Registrar — `Mono<AtencionResponse> registrar(AtencionCrearRequest)`

1. `citasClient.obtener(citaId)` → `404` / `503` / `504`.
2. Si `estado != CONFIRMADA` → `409 CITA_NO_CONFIRMADA`.
3. Si `ahora < fechaHora − 30 min` → `409 FUERA_DE_VENTANA_ATENCION`.
4. `existsActivaByCitaId(citaId)` → `409 ATENCION_DUPLICADA`.
5. Código con `nextval('atencion_codigo_seq')` → `ATE-<yyyyMMdd>-<NNNNNN>`.
6. `Atencion.registrar(...)`; `save` con `onErrorMap(DuplicateKeyException → 409 ATENCION_DUPLICADA)` (dos peticiones simultáneas para la misma cita).
7. Publicar `atencion.registrada`; devolver `201`.

### 5.2 Obtener — `404 ATENCION_NO_ENCONTRADA`.

### 5.3 Listar — paginado; filtros `pacienteId`, `citaId`, `estado`, `area`, `fecha` (día en Lima sobre `fecha_atencion`). Orden `fecha_atencion DESC, id`.

### 5.4 Cambiar estado — `PATCH`; destinos `FINALIZADA` o `ANULADA`

1. `obtener` (404); `cambiarEstado` (409); `save` (409 `CONFLICTO_CONCURRENTE` por `@Version`).
2. Publicar `atencion.finalizada` o `atencion.anulada` según el destino.

**Efecto de anular sobre la cita** (D-22, revisado): lo decide **Citas** al consumir `atencion.anulada` (spec 03 §5.7):

* Si la cita **aún no ocurrió** (`ahora < fechaHora`) → la cita pasa a `CANCELADA` (estado final; para reagendar se crea una cita nueva).
* Si **ya ocurrió** → la cita sigue `ATENDIDA`; la anulación queda como corrección administrativa en Auditoría.

## 6. API

### 6.1 DTOs

```text
AtencionCrearRequest(@NotNull UUID citaId, @NotNull AreaAtencion area)
CambioEstadoAtencionRequest(@NotNull EstadoAtencion estado, @Size(max=200) String motivo)
AtencionResponse(id, codigo, pacienteId, citaId, fechaAtencion, area, areaNombre, estado,
                 fechaCreacion, fechaActualizacion)
```

### 6.2 Endpoints (base `/api/atenciones`)

| Método y ruta | OK | Errores |
|---|---|---|
| `POST /api/atenciones` | `201` + `Location` | `400`; `404 CITA_NO_ENCONTRADA`; `409 CITA_NO_CONFIRMADA` / `ATENCION_DUPLICADA`; `503`/`504 SERVICIO_CITAS_NO_DISPONIBLE` |
| `GET /api/atenciones/{id}` | `200` | `400`, `404 ATENCION_NO_ENCONTRADA` |
| `GET /api/atenciones` | `200` + `X-Total-Count` | `400` |
| `PATCH /api/atenciones/{id}/estado` | `200` | `400`, `404`, `409 TRANSICION_NO_PERMITIDA` / `CONFLICTO_CONCURRENTE` |

## 7. Eventos publicados

`origen = "atenciones-service"`, `tipoRecurso = "ATENCION"`, `recursoId = id de la atención`. `datos` común: `{ "atencionId", "citaId", "pacienteId", "codigo", "area", "areaNombre", "fechaAtencion" }`.

| Routing key | Cuándo | Anterior → Nuevo | Consumidores |
|---|---|---|---|
| `atencion.registrada` | registrar | `null` → `REGISTRADA` | **Citas** (`citas.atenciones.cola`), Auditoría, Notificaciones (baja prioridad) |
| `atencion.finalizada` | `PATCH` a `FINALIZADA` | `REGISTRADA` → `FINALIZADA` (+ `datos.motivo`) | Auditoría |
| `atencion.anulada` | `PATCH` a `ANULADA` | `REGISTRADA` → `ANULADA` (+ `datos.motivo`) | **Citas** (`citas.atenciones.cola`), Auditoría |

Se usan routing keys específicas (en vez de un genérico `atencion.estado-cambiado`) para que la cola de Citas se suscriba **solo** a lo que le afecta.

Este servicio **no consume** eventos.

### 7.1 Consistencia entre Atenciones y Citas

El caso "se cancela la cita mientras se registra su atención" queda **eliminado por regla de negocio**: cancelación hasta 24 h antes (RN-15) y atención desde 30 min antes (RN-16) son ventanas disjuntas. La DLQ `citas.atenciones.dlq` se mantiene como red de seguridad (p. ej. si alguien cambiara los parámetros configurables hasta solapar las ventanas). Una saga con compensación queda como mejora general en §14 del documento técnico.

## 8. Pruebas

| Clase | Tipo | Casos |
|---|---|---|
| `EstadoAtencionTest` | Unitaria | Matriz 3×3 de transiciones |
| `AtencionTest` | Unitaria | `registrar` toma `pacienteId` de la cita y queda `REGISTRADA`; `FINALIZADA → ANULADA` → 409 |
| `CitasClientTest` | `MockWebServer` | `200`; `404` → `CITA_NO_ENCONTRADA`; `500` → `503`; retraso → `504`; error de red reintenta una vez |
| `AtencionServiceTest` | `StepVerifier` + Mockito, `Clock` fijo | Registro OK (30 min antes exactos y 2 h después) publica `atencion.registrada` con `citaId` y `pacienteId`; 31 min antes → 409 `FUERA_DE_VENTANA_ATENCION`; cita `PENDIENTE` → 409 `CITA_NO_CONFIRMADA` sin guardar; cita `CANCELADA` → 409; atención existente → 409 `ATENCION_DUPLICADA`; `DuplicateKeyException` → 409; Citas caído → 503; anular publica `atencion.anulada`, finalizar publica `atencion.finalizada` |
| `AtencionControllerTest` | `@WebFluxTest` | `POST` OK 201; `area` inválida → 400; `citaId` ausente → 400; `GET` inexistente → 404; `PATCH` prohibido → 409 |
| `AtencionRepositoryIT` | PostgreSQL | Dos atenciones `REGISTRADA` misma cita → `DuplicateKeyException`; si la primera está `ANULADA` → permitido; secuencia de código |
| `AtencionFlujoIT` | PostgreSQL + RabbitMQ + `MockWebServer` (Citas) | `POST` → mensaje `atencion.registrada` en una cola de prueba con `datos.citaId` correcto |

La prueba de punta a punta "atención registrada → cita `ATENDIDA`" vive en `citas-service` (`AtencionRegistradaConsumerIT`, spec 03 §8), porque es Citas quien la procesa.

## 9. Ejemplo

```json
POST /api/atenciones
{ "citaId": "…", "area": "CONSULTA_EXTERNA" }
```

## 10. Criterios de aceptación

- [ ] `./mvnw -pl atenciones-service -am verify` en verde.
- [ ] `ATENCIONES-SERVICE` en Eureka; la llamada a Citas usa el nombre `citas-service`.
- [ ] Vía Gateway, con una cita `CONFIRMADA` que empieza en ≤ 30 min: `POST /api/atenciones` → `201`, y al consultar la cita ya está `ATENDIDA`.
- [ ] Con una cita `CONFIRMADA` de mañana → `409 FUERA_DE_VENTANA_ATENCION`.
- [ ] Anular esa atención antes de la hora de la cita → la cita pasa a `CANCELADA`.
- [ ] Con una cita `PENDIENTE` → `409 CITA_NO_CONFIRMADA`.
- [ ] Segunda atención para la misma cita → `409` (`ATENCION_DUPLICADA` o `CITA_NO_CONFIRMADA` si la cita ya pasó a `ATENDIDA`).
- [ ] Con `citas-service` detenido → `503 SERVICIO_CITAS_NO_DISPONIBLE`.
- [ ] Mensajes `atencion.registrada` y `cita.atendida` visibles en Auditoría.

## 11. Ajustes propagados al documento técnico (aplicados)

1. §8.4: columna `version` y secuencia `atencion_codigo_seq` en `atencion`; `area` como catálogo.
2. §5.5: área como catálogo; ventana de 30 min; efecto de la anulación sobre la cita; eventos `atencion.finalizada`/`atencion.anulada`.
3. §2.3: RN-15, RN-16, RN-17; §6.3 y §6.4: transición de sistema y nueva routing key.
4. §9.4: ejemplo con `"area": "CONSULTA_EXTERNA"`.

## 12. Decisiones (todas aprobadas)

| # | Decisión tomada | Alternativa |
|---|---|---|
| D-22 | **Aprobado (revisado):** anular una atención cancela la cita si aún no ocurrió; si ya ocurrió, la cita sigue `ATENDIDA` | No tocar la cita / devolverla a `CONFIRMADA` |
| D-23 | **Aprobado:** `area` como catálogo cerrado de 4 valores | Texto libre |
| D-24 | **Aprobado (revisado):** la carrera se elimina por reglas (24 h para cancelar, 30 min para atender); DLQ como red de seguridad | Saga con compensación |
| D-25 | **Aprobado:** Atenciones no consulta a Pacientes | Verificar también que el paciente siga `ACTIVO` |
| D-29 | **Aprobado:** atención desde 30 min antes de la cita, sin límite posterior (configurable) | Solo el mismo día de la cita |
