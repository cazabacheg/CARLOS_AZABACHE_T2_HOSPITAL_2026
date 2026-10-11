# Spec 04 — `atenciones-service`

> Depende de: spec 00, spec 01, spec 03 (contrato `GET /api/citas/{id}` y consumo de `atencion.registrada`).
> Documento técnico: §5.5, §8.4, RN-11, RN-12; enunciado §8 (Proceso 3) y §27 (flujo mínimo: "Registro de atención simulada").
> Cubre rúbrica: reglas de negocio, segunda comunicación síncrona, evento que cambia el estado en **otro** servicio (coreografía).

## 1. Responsabilidad

Registrar la **atención administrativa simulada** de una cita confirmada y seguir su estado. No guarda historia clínica, diagnósticos ni tratamientos.

* Valida la cita con una llamada **síncrona** a Citas.
* Publica `atencion.registrada`; **Citas** reacciona y pasa la cita a `ATENDIDA` (Atenciones nunca modifica la cita directamente).

## 2. Módulo

| Aspecto | Valor |
|---|---|
| `artifactId` / `spring.application.name` | `atenciones-service` |
| Puerto | 8085 |
| Paquete base | `pe.edu.cibertec.vidasalud.atenciones` |
| Base de datos | `atenciones_db` (usuario `atenciones_user`) |
| Dependencias | Iguales a `citas-service` (spec 03 §2) |
| `application.yml` | Plantilla de la spec 02 §2.1 con puerto `8085`, variables `ATENCIONES_DB_*`, y `hospital.clientes.citas.url: http://citas-service` |

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

## 4. Cliente síncrono a Citas (`client/CitasClient.java`)

Mismo patrón que `PacientesClient` (spec 03 §4):

* `@LoadBalanced WebClient`, `baseUrl = http://citas-service`, `PoliticaResiliencia`, propagación de `X-Request-Id`.
* `Mono<CitaResumen> obtener(UUID citaId)` → `GET /api/citas/{id}`.
* `CitaResumen(UUID id, UUID pacienteId, String estado)` propio, tolerante a campos desconocidos.

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
3. `existsActivaByCitaId(citaId)` → `409 ATENCION_DUPLICADA`.
4. Código con `nextval('atencion_codigo_seq')` → `ATE-<yyyyMMdd>-<NNNNNN>`.
5. `Atencion.registrar(...)`; `save` con `onErrorMap(DuplicateKeyException → 409 ATENCION_DUPLICADA)` (dos peticiones simultáneas para la misma cita).
6. Publicar `atencion.registrada`; devolver `201`.

### 5.2 Obtener — `404 ATENCION_NO_ENCONTRADA`.

### 5.3 Listar — paginado; filtros `pacienteId`, `citaId`, `estado`, `area`, `fecha` (día en Lima sobre `fecha_atencion`). Orden `fecha_atencion DESC, id`.

### 5.4 Cambiar estado — `PATCH`; destinos `FINALIZADA` o `ANULADA`

1. `obtener` (404); `cambiarEstado` (409); `save` (409 `CONFLICTO_CONCURRENTE` por `@Version`).
2. Publicar `atencion.estado-cambiado`.

**Anular no revierte la cita** (D-22): la cita permanece `ATENDIDA`, que es un estado final en Citas. La anulación es una corrección administrativa que queda trazada en Auditoría. Se documenta como simplificación.

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
| `atencion.estado-cambiado` | `PATCH` | `REGISTRADA` → `FINALIZADA`/`ANULADA` (+ `datos.motivo`) | Auditoría |

Este servicio **no consume** eventos.

### 7.1 Caso límite de consistencia (documentado)

Si entre el paso 1 (cita `CONFIRMADA`) y el consumo del evento alguien cancela la cita, Citas rechaza la transición y el mensaje termina en `citas.atenciones.dlq`; la atención queda `REGISTRADA` sobre una cita `CANCELADA`. Es la consecuencia aceptada de la consistencia eventual. La solución completa sería una **saga con compensación** (Citas publica `cita.atencion-rechazada` y Atenciones anula la atención); queda como mejora en §14 del documento técnico.

## 8. Pruebas

| Clase | Tipo | Casos |
|---|---|---|
| `EstadoAtencionTest` | Unitaria | Matriz 3×3 de transiciones |
| `AtencionTest` | Unitaria | `registrar` toma `pacienteId` de la cita y queda `REGISTRADA`; `FINALIZADA → ANULADA` → 409 |
| `CitasClientTest` | `MockWebServer` | `200`; `404` → `CITA_NO_ENCONTRADA`; `500` → `503`; retraso → `504`; error de red reintenta una vez |
| `AtencionServiceTest` | `StepVerifier` + Mockito | Registro OK publica `atencion.registrada` con `citaId` y `pacienteId`; cita `PENDIENTE` → 409 `CITA_NO_CONFIRMADA` sin guardar; cita `CANCELADA` → 409; atención existente → 409 `ATENCION_DUPLICADA`; `DuplicateKeyException` → 409; Citas caído → 503 |
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
- [ ] Vía Gateway, con una cita `CONFIRMADA`: `POST /api/atenciones` → `201`, y al consultar la cita ya está `ATENDIDA`.
- [ ] Con una cita `PENDIENTE` → `409 CITA_NO_CONFIRMADA`.
- [ ] Segunda atención para la misma cita → `409` (`ATENCION_DUPLICADA` o `CITA_NO_CONFIRMADA` si la cita ya pasó a `ATENDIDA`).
- [ ] Con `citas-service` detenido → `503 SERVICIO_CITAS_NO_DISPONIBLE`.
- [ ] Mensajes `atencion.registrada` y `cita.atendida` visibles en Auditoría.

## 11. Ajustes que se propagan al documento técnico al aprobar

1. §8.4: columna `version` y secuencia `atencion_codigo_seq` en `atencion`; `area` como catálogo.
2. §5.5: área como catálogo; anular no revierte la cita; caso límite §7.1.
3. §14: mejora "saga con compensación para atención sobre cita cancelada".
4. §9.4: ejemplo con `"area": "CONSULTA_EXTERNA"`.

## 12. Decisiones abiertas para revisión

| # | Decisión tomada | Alternativa |
|---|---|---|
| D-22 | Anular una atención **no** revierte la cita (sigue `ATENDIDA`) | Revertir la cita a `CONFIRMADA` vía evento (rompe que `ATENDIDA` sea final) |
| D-23 | `area` como catálogo cerrado de 4 valores | Texto libre |
| D-24 | Caso "cita cancelada mientras se registra la atención" se acepta y se documenta (DLQ), sin saga de compensación | Implementar la compensación (más eventos y un consumidor en Atenciones) |
| D-25 | Atenciones no consulta a Pacientes (confía en la validación hecha al crear la cita) | Verificar también que el paciente siga `ACTIVO` |
