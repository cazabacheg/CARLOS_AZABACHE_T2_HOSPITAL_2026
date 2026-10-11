# Spec 03 — `citas-service`

> Depende de: spec 00, spec 01, spec 02 (contrato `GET /api/pacientes/{id}`).
> Documento técnico: §2.3 (RN-03…RN-08, RN-12), §2.4 (supuestos A-01…A-06), §5.4, §6.2, §6.3, §8.2.
> Cubre rúbrica: reglas de negocio, comunicación síncrona, RabbitMQ (productor **y** consumidor), reactiva/R2DBC, pruebas (cita, transición no permitida, evento).

## 1. Responsabilidad

Dueño del **ciclo de vida de la cita**: programar, consultar, reprogramar y cambiar estado, aplicando la agenda simplificada y la máquina de estados. Es el servicio central del flujo mínimo del enunciado (§27).

* Consulta a **Pacientes** de forma síncrona antes de crear una cita.
* Publica los eventos de cita (entre ellos `cita.confirmada`, el evento exigido por el enunciado).
* Consume `atencion.registrada` (cita → `ATENDIDA`) y `atencion.anulada` (cita → `CANCELADA` si aún no ocurrió).

## 2. Módulo

| Aspecto | Valor |
|---|---|
| `artifactId` / `spring.application.name` | `citas-service` |
| Puerto | 8082 |
| Paquete base | `pe.edu.cibertec.vidasalud.citas` |
| Base de datos | `citas_db` (usuario `citas_user`) |
| Dependencias | Las de la spec 02 §2, más `spring-cloud-starter-loadbalancer` (para `@LoadBalanced WebClient`) |
| Pruebas | Las de la spec 02 §2, más `com.squareup.okhttp3:mockwebserver` |
| `application.yml` | Plantilla de la spec 02 §2.1 con puerto `8082`, variables `CITAS_DB_*`, y además `hospital.clientes.pacientes.url: http://pacientes-service` |

## 3. Modelo de dominio

### 3.1 `EstadoCita` (enum con su tabla de transiciones)

```text
PENDIENTE  → CONFIRMADA, CANCELADA
CONFIRMADA → ATENDIDA*, AUSENTE, CANCELADA
ATENDIDA   → CANCELADA*        (solo si la atención se anula antes de la hora de la cita)
CANCELADA  → (final)
AUSENTE    → (final)

* Transiciones de sistema: solo las ejecuta el consumidor de eventos de Atenciones, nunca la API.
```

* `CANCELADA` y `AUSENTE` son finales. Para volver a agendar una cita cancelada se **crea una cita nueva** (D-26).
* `ATENDIDA` es final **para la API**; la única salida es la cancelación automática por atención anulada (§5.7).

* Método `boolean puedeCambiarA(EstadoCita destino)` (incluye las transiciones de sistema), `boolean permitidoPorApi(EstadoCita destino)` (`CONFIRMADA`, `CANCELADA`, `AUSENTE`) y `boolean esActivo()` (`PENDIENTE` o `CONFIRMADA`) y `boolean esFinal()`.
* La **reprogramación** no es una transición de este enum: es una operación aparte permitida solo si `esActivo()`, y deja la cita en `PENDIENTE` (documento §6.3).
* Toda transición no permitida → `ErrorNegocio(409, TRANSICION_NO_PERMITIDA, "No se puede pasar una cita de <ACTUAL> a <DESTINO>.")`.

### 3.2 `Especialidad` (enum, catálogo cerrado — D-16)

`MEDICINA_GENERAL`, `PEDIATRIA`, `CARDIOLOGIA`, `GINECOLOGIA`, `TRAUMATOLOGIA`, `DERMATOLOGIA`, `OFTALMOLOGIA`.

Cada valor tiene un `nombre` legible (`"Medicina General"`), que se incluye en la respuesta y en los eventos para que Notificaciones no dependa del catálogo.

### 3.3 Entidad `Cita`

Tabla `cita` (documento §8.2) con **dos ajustes** (se propagan al documento al aprobar):

```sql
ALTER ... -- (en schema.sql se crea así directamente)
version  BIGINT NOT NULL DEFAULT 0      -- bloqueo optimista (D-18)
CREATE SEQUENCE IF NOT EXISTS cita_codigo_seq;  -- para el código legible
```

| Campo Java | Columna | Tipo |
|---|---|---|
| `id` | `id` | `UUID` |
| `codigo` | `codigo` | `String` (`CIT-yyyyMMdd-NNNNNN`) |
| `pacienteId` | `paciente_id` | `UUID` |
| `especialidad` | `especialidad` | `Especialidad` |
| `fechaHora` | `fecha_hora` | `OffsetDateTime` |
| `profesionalCodigo` | `profesional_codigo` | `String` |
| `motivo` | `motivo` | `String` (nullable) |
| `estado` | `estado` | `EstadoCita` |
| `fechaCreacion` | `fecha_creacion` | `OffsetDateTime` |
| `fechaActualizacion` | `fecha_actualizacion` | `OffsetDateTime` |
| `version` | `version` | `Long` con `@Version` |

Métodos de dominio:

* `static Cita programar(datos, String codigo, Clock reloj)` → `PENDIENTE`, fechas de creación/actualización.
* `EstadoCita cambiarEstado(EstadoCita destino, Clock reloj)` → valida con `puedeCambiarA`, actualiza `fechaActualizacion`, devuelve el estado anterior.
* `OffsetDateTime reprogramar(OffsetDateTime nueva, String nuevoProfesional, Clock reloj)` → exige `esActivo()` (si no: `409 TRANSICION_NO_PERMITIDA`, "Solo se reprograman citas pendientes o confirmadas."); exige que la nueva fecha-hora sea distinta (si no: `409 MISMA_FECHA_HORA`); deja `PENDIENTE`; devuelve la fecha anterior.

### 3.4 `ReglasAgenda` (supuestos A-01…A-07 y A-10 del documento §2.4)

Clase pura, recibe `Clock` y `ParametrosAgenda`. Los parámetros son **configurables** (D-28) con `@ConfigurationProperties("hospital.agenda")`, para poder adaptar horarios sin cambiar código:

| Propiedad | Defecto | Variable de entorno |
|---|---|---|
| `dias-atencion` | `MONDAY,TUESDAY,WEDNESDAY,THURSDAY,FRIDAY,SATURDAY` | `AGENDA_DIAS_ATENCION` |
| `hora-inicio` | `08:00` | `AGENDA_HORA_INICIO` |
| `hora-ultimo-inicio` | `17:30` | `AGENDA_HORA_ULTIMO_INICIO` |
| `bloque-minutos` | `30` | — |
| `max-dias-anticipacion` | `90` | — |
| `plazo-cambio-horas` | `24` (cancelar/reprogramar) | — |

Para capturar evidencias fuera del horario (p. ej. un domingo) se arranca con `AGENDA_DIAS_ATENCION` incluyendo `SUNDAY` y/o otro horario; se documenta en el README.

`void validar(OffsetDateTime fechaHora)`:

| Regla | Error (`400 HORARIO_FUERA_DE_AGENDA`) |
|---|---|
| Se convierte a `America/Lima` antes de evaluar | — |
| Debe ser **futura** respecto al `Clock` | "La cita debe programarse en una fecha futura." |
| Día dentro de `dias-atencion` | "No se atiende ese día." |
| Hora de inicio entre `hora-inicio` y `hora-ultimo-inicio` inclusive | "Horario de atención: 08:00 a 17:30." (con los valores configurados) |
| Minuto `00` o `30`, segundos y nanos en 0 | "Las citas se programan en bloques de 30 minutos." |
| No más de 90 días a futuro (A-07, nuevo) | "Solo se programan citas con hasta 90 días de anticipación." |

La fecha se **normaliza** a la zona de Lima antes de guardarse, para que el índice único compare siempre el mismo instante.

`void validarPlazoCambio(OffsetDateTime fechaHoraCita)` (RN-15, D-27): cancelar o reprogramar **por API** solo si `ahora < fechaHoraCita − 24 h`. Si no → `409 FUERA_DE_PLAZO`, "Solo se puede cancelar o reprogramar hasta 24 horas antes de la cita." Pasado ese plazo la cita **se da por efectuada**: no cambia de estado sola (no hay proceso programado), y solo puede terminar `ATENDIDA` (vía atención) o `AUSENTE`.

### 3.5 Reglas de choque de agenda (RN-06)

Garantizadas por los **índices únicos parciales** del documento §8.2 (`ux_cita_profesional_horario`, `ux_cita_paciente_horario`, solo sobre estados activos):

* Antes de guardar, el servicio consulta `existsActivaByProfesionalAndFechaHora` y `existsActivaByPacienteAndFechaHora` para dar un mensaje preciso:
  * profesional ocupado → `409 CITA_CHOQUE_AGENDA`, "El profesional <código> ya tiene una cita en ese horario."
  * paciente ocupado → `409 CITA_CHOQUE_AGENDA`, "El paciente ya tiene una cita en ese horario."
* Si aun así el `INSERT`/`UPDATE` viola un índice (carrera) → `DuplicateKeyException` → `409 CITA_CHOQUE_AGENDA` con mensaje genérico "El horario ya fue ocupado."
* Una cita `CANCELADA` (o en otro estado final) **libera** el horario (los índices son parciales).

## 4. Cliente síncrono a Pacientes (`client/PacientesClient.java`)

* `WebClient` construido con `@LoadBalanced WebClient.Builder` y `baseUrl = hospital.clientes.pacientes.url` (`http://pacientes-service`, resuelto por Eureka).
* Método `Mono<PacienteResumen> obtener(UUID pacienteId)` → `GET /api/pacientes/{id}`.
* `PacienteResumen` es un `record(UUID id, String estado)` **propio de Citas** con `@JsonIgnoreProperties(ignoreUnknown = true)`: el contrato depende solo de `id` y `estado`.
* Aplica `PoliticaResiliencia` (timeout 2 s, 1 reintento solo en errores de red; spec 00 §5.3).
* La traza (`traceparent`) la propaga el agente OpenTelemetry automáticamente (spec 08).

| Respuesta de Pacientes | Resultado |
|---|---|
| `200`, `estado=ACTIVO` | `Mono<PacienteResumen>` |
| `200`, `estado=INACTIVO` | `ErrorNegocio(422, PACIENTE_INACTIVO, "El paciente está inactivo y no puede recibir citas.")` (lo decide el **servicio**, no el cliente) |
| `404` | `ErrorNegocio(404, PACIENTE_NO_ENCONTRADO, "No existe el paciente <id>.")` |
| `5xx`, conexión rechazada, sin instancias en Eureka | `ErrorNegocio(503, SERVICIO_PACIENTES_NO_DISPONIBLE, "El servicio de pacientes no está disponible. Intente más tarde.")` |
| Sin respuesta en 2 s | `ErrorNegocio(504, SERVICIO_PACIENTES_NO_DISPONIBLE, "El servicio de pacientes no respondió a tiempo.")` |

## 5. Casos de uso (`service/CitaService.java`)

### 5.1 Programar — `Mono<CitaResponse> programar(CitaCrearRequest)`

Orden pensado para fallar barato primero:

1. `ReglasAgenda.validar(fechaHora)` (local, sin E/S) → `400`.
2. `pacientesClient.obtener(pacienteId)` → `404` / `503` / `504`; si `INACTIVO` → `422`.
3. Consultas de choque (§3.5) → `409`.
4. Obtener código: `SELECT nextval('cita_codigo_seq')` con `DatabaseClient` → `CIT-<yyyyMMdd de hoy en Lima>-<número con 6 dígitos>`.
5. `Cita.programar(...)`; `save` (con `onErrorMap(DuplicateKeyException → 409)`).
6. Publicar `cita.creada`; devolver DTO (`201`).

### 5.2 Obtener — `Mono<CitaResponse> obtener(UUID id)` → `404 CITA_NO_ENCONTRADA`.

### 5.3 Listar — paginado (spec 00 §4.2)

| Parámetro | Condición |
|---|---|
| `pacienteId` | igualdad |
| `estado` | igualdad |
| `especialidad` | igualdad |
| `profesionalCodigo` | igualdad |
| `fecha` (`LocalDate`) | `fecha_hora` dentro de ese día en Lima (`[00:00, 24:00)`) |

Orden estable: `fecha_hora, id`.

### 5.4 Cambiar estado — `Mono<CitaResponse> cambiarEstado(UUID id, CambioEstadoCitaRequest)`

1. `obtener` (404).
2. Destinos permitidos **por API**: `CONFIRMADA`, `CANCELADA`, `AUSENTE`. Si piden `ATENDIDA` → `409 TRANSICION_NO_PERMITIDA`, "Una cita pasa a ATENDIDA solo al registrar su atención." (D-17). Si piden `PENDIENTE` → mismo error (se vuelve a `PENDIENTE` solo reprogramando).
3. Si `destino == CANCELADA` → `ReglasAgenda.validarPlazoCambio` (409 `FUERA_DE_PLAZO`).
4. `cita.cambiarEstado(destino)` (409 si la máquina no lo permite: p. ej. confirmar una `CANCELADA`, regla RN-05).
5. `save`; `OptimisticLockingFailureException` → `409 CONFLICTO_CONCURRENTE`, "La cita fue modificada por otra operación; vuelva a consultarla."
6. Publicar el evento que corresponde al destino (§7).

### 5.5 Reprogramar — `Mono<CitaResponse> reprogramar(UUID id, ReprogramarCitaRequest)`

1. `obtener` (404).
2. `ReglasAgenda.validarPlazoCambio(fechaHora actual de la cita)` (409 `FUERA_DE_PLAZO`).
3. `ReglasAgenda.validar(nuevaFechaHora)` (400).
4. `cita.reprogramar(...)` (409 si no es activa o la fecha es la misma).
5. Consultas de choque para el nuevo horario (409).
6. `save` (409 por índice o por concurrencia).
7. Publicar `cita.reprogramada` con `datos.fechaHoraAnterior`.

No se vuelve a consultar a Pacientes al reprogramar (el paciente ya fue validado al crear; se documenta).

### 5.6 Marcar atendida (uso interno del consumidor) — `Mono<Void> marcarAtendida(UUID citaId, UUID eventId)`

1. `findById` → si no existe: error (va a DLQ tras reintentos).
2. Si ya está `ATENDIDA` → `Mono.empty()` (**idempotente**: el evento se repitió).
3. `cambiarEstado(ATENDIDA)` → si no está `CONFIRMADA`: `ErrorNegocio 409` → reintentos → **DLQ** `citas.atenciones.dlq`. Con las reglas de plazo (RN-15) y de ventana de atención (RN-16, spec 04) este caso **no puede ocurrir** por la vía normal: la cita deja de ser cancelable 24 h antes, y la atención solo se registra desde 30 min antes. La DLQ queda como red de seguridad.
4. `save`; publicar `cita.atendida`.

### 5.7 Cancelar por atención anulada (uso interno) — `Mono<Void> cancelarPorAtencionAnulada(UUID citaId, UUID atencionId)`

1. `findById` → si no existe: error (DLQ tras reintentos).
2. Si ya está `CANCELADA` → `Mono.empty()` (idempotente).
3. Si está `ATENDIDA` **y** `ahora < fechaHora` (la cita aún no ocurrió) → `ATENDIDA → CANCELADA` (transición de sistema, **sin** regla de 24 h); `save`; publicar `cita.cancelada` con `datos.motivo = "Atención anulada"` y `datos.atencionId`.
4. Si está `ATENDIDA` y la hora ya pasó → **sin cambio**; registra `INFO` "anulación administrativa, la cita ya ocurrió" y confirma el mensaje.
5. Cualquier otro estado → error (DLQ tras reintentos).

## 6. API

### 6.1 DTOs (`record`)

```text
CitaCrearRequest(@NotNull UUID pacienteId, @NotNull Especialidad especialidad,
                 @NotNull OffsetDateTime fechaHora,
                 @NotBlank @Pattern("^[A-Z]{3}-\\d{3}$") String profesionalCodigo,
                 @Size(max=200) String motivo)
ReprogramarCitaRequest(@NotNull OffsetDateTime fechaHora,
                       @Pattern("^[A-Z]{3}-\\d{3}$") String profesionalCodigo /* opcional: si no llega, se mantiene */)
CambioEstadoCitaRequest(@NotNull EstadoCita estado, @Size(max=200) String motivo)
CitaResponse(id, codigo, pacienteId, especialidad, especialidadNombre, fechaHora,
             profesionalCodigo, motivo, estado, fechaCreacion, fechaActualizacion)
```

`motivo` es **administrativo** (p. ej. "Control", "Primera consulta"); el Swagger lo advierte: no ingresar datos clínicos.

`CitaResponse` es el **contrato** que consume Atenciones (usa `id`, `pacienteId`, `estado`).

### 6.2 Endpoints (base `/api/citas`)

| Método y ruta | Respuesta OK | Errores |
|---|---|---|
| `POST /api/citas` | `201` + `Location` | `400 VALIDACION_FALLIDA` / `HORARIO_FUERA_DE_AGENDA`; `404 PACIENTE_NO_ENCONTRADO`; `422 PACIENTE_INACTIVO`; `409 CITA_CHOQUE_AGENDA`; `503`/`504 SERVICIO_PACIENTES_NO_DISPONIBLE` |
| `GET /api/citas/{id}` | `200` | `400`, `404 CITA_NO_ENCONTRADA` |
| `GET /api/citas` | `200` + `X-Total-Count` | `400` |
| `PUT /api/citas/{id}/reprogramar` | `200` | `400`, `404`, `409 TRANSICION_NO_PERMITIDA` / `MISMA_FECHA_HORA` / `CITA_CHOQUE_AGENDA` / `FUERA_DE_PLAZO` / `CONFLICTO_CONCURRENTE` |
| `PATCH /api/citas/{id}/estado` | `200` | `400`, `404`, `409 TRANSICION_NO_PERMITIDA` / `FUERA_DE_PLAZO` / `CONFLICTO_CONCURRENTE` |

## 7. Eventos

### 7.1 Publicados

Sobre de la spec 00 §5.2, `origen = "citas-service"`, `tipoRecurso = "CITA"`, `recursoId = id de la cita`.

`datos` común a todos: `{ "codigo", "pacienteId", "especialidad", "especialidadNombre", "fechaHora", "profesionalCodigo" }`.

| Routing key | Cuándo | Anterior → Nuevo | `datos` adicional |
|---|---|---|---|
| `cita.creada` | programar | `null` → `PENDIENTE` | — |
| `cita.confirmada` | estado `CONFIRMADA` | `PENDIENTE` → `CONFIRMADA` | `motivo` |
| `cita.cancelada` | estado `CANCELADA` (API) o atención anulada (sistema) | `PENDIENTE`/`CONFIRMADA`/`ATENDIDA` → `CANCELADA` | `motivo`; `atencionId` si fue por anulación |
| `cita.ausente` | estado `AUSENTE` | `CONFIRMADA` → `AUSENTE` | `motivo` |
| `cita.reprogramada` | reprogramar | `PENDIENTE`/`CONFIRMADA` → `PENDIENTE` | `fechaHoraAnterior`, `profesionalAnterior` |
| `cita.atendida` | consumo de `atencion.registrada` | `CONFIRMADA` → `ATENDIDA` | `atencionId` |

### 7.2 Consumido

| Cola | Binding | Manejador |
|---|---|---|
| `citas.atenciones.cola` (DLQ `citas.atenciones.dlq`) | `atencion.registrada`, `atencion.anulada` | `AtencionEventosConsumer`: según `tipo` del sobre → `marcarAtendida` (§5.6) o `cancelarPorAtencionAnulada` (§5.7) |

* DTO propio `AtencionEventoDatos(UUID atencionId, UUID citaId)` leído desde `datos` del sobre, tolerante a campos desconocidos.
* Suscripción al arrancar con `ApplicationRunner` (spec 00 §5.2). Se desactiva en pruebas de controlador (`hospital.rabbit.enabled=false`).

## 8. Pruebas

| Clase | Tipo | Casos |
|---|---|---|
| `EstadoCitaTest` | Unitaria | **Matriz completa 5×5** de transiciones (parametrizada): permitidas = 6, el resto prohibidas; `esActivo`/`esFinal` |
| `ReglasAgendaTest` | Unitaria, `Clock` fijo (miércoles 2026-10-14 09:00 Lima) | Válida (jueves 10:00); pasada; domingo; 07:30; 17:30 válida; 18:00; 10:15; con segundos; +91 días; fecha enviada con offset UTC que en Lima cae en domingo |
| `CitaTest` | Unitaria | `programar` deja `PENDIENTE`; confirmar una `CANCELADA` → 409 (**RN-05**); `reprogramar` desde `ATENDIDA` → 409; misma fecha → 409; reprogramar desde `CONFIRMADA` deja `PENDIENTE` |
| `PlazoCambioTest` | Unitaria, `Clock` fijo | Cancelar a 25 h → OK; a 23 h 59 min → 409 `FUERA_DE_PLAZO`; reprogramar a 2 h → 409; con `dias-atencion` incluyendo `SUNDAY`, una cita en domingo es válida |
| `PacientesClientTest` | `MockWebServer` | `200 ACTIVO`; `404` → `PACIENTE_NO_ENCONTRADO`; `500` → `503`; respuesta con 3 s de retraso → `504`; error de conexión → reintenta **una** vez (2 peticiones) y luego `503`; `404` **no** reintenta (1 petición) |
| `CitaServiceTest` | `StepVerifier` + Mockito | Programar OK publica `cita.creada`; agenda inválida → 400 **sin** llamar a Pacientes; paciente inactivo → 422; choque de profesional → 409 y no guarda; `DuplicateKeyException` → 409; confirmar publica `cita.confirmada` con anterior y nuevo; pedir `ATENDIDA` por API → 409; `OptimisticLockingFailureException` → 409 `CONFLICTO_CONCURRENTE`; `marcarAtendida` sobre `ATENDIDA` no publica (idempotente); sobre `CANCELADA` → error; `cancelarPorAtencionAnulada` antes de la hora → `CANCELADA` y publica `cita.cancelada` con `atencionId`; después de la hora → sin cambio ni evento; sobre `CANCELADA` → idempotente; cancelar por API a menos de 24 h → 409 `FUERA_DE_PLAZO` |
| `CitaControllerTest` | `@WebFluxTest` | `POST` OK → 201 + `Location`; `profesionalCodigo` inválido → 400 con `errores`; `especialidad` desconocida → 400; `GET` inexistente → 404; `PATCH` con transición prohibida → 409 `TRANSICION_NO_PERMITIDA`; listado con `X-Total-Count` |
| `CitaRepositoryIT` | PostgreSQL | Dos citas activas mismo profesional y hora → `DuplicateKeyException`; si la primera está `CANCELADA` → permitido; mismo paciente y hora → error; secuencia de código; `@Version` detecta actualización concurrente |
| `AtencionRegistradaConsumerIT` | PostgreSQL + RabbitMQ | Publicar `atencion.registrada` para una cita `CONFIRMADA` → queda `ATENDIDA` y se publica `cita.atendida`; publicar el **mismo** evento dos veces → un solo `cita.atendida`; luego `atencion.anulada` (cita futura) → `CANCELADA`; para una cita `CANCELADA`, `atencion.registrada` → mensaje termina en `citas.atenciones.dlq` |
| `CitaFlujoIT` | PostgreSQL + RabbitMQ + `MockWebServer` en lugar de Pacientes | `POST` → `PATCH CONFIRMADA` → mensaje `cita.confirmada` recibido en una cola de prueba con el sobre correcto |

Casos del enunciado §24 cubiertos aquí: **registro y consulta de cita**, **transición de estado no permitida**, **publicación y consumo de un evento**, **recurso inexistente**.

## 9. Ejemplos

```json
POST /api/citas
{
  "pacienteId": "…",
  "especialidad": "MEDICINA_GENERAL",
  "fechaHora": "2026-10-15T10:00:00-05:00",
  "profesionalCodigo": "MED-001",
  "motivo": "Control administrativo"
}

PATCH /api/citas/{id}/estado
{ "estado": "CONFIRMADA", "motivo": "Confirmada por teléfono" }

PUT /api/citas/{id}/reprogramar
{ "fechaHora": "2026-10-16T11:30:00-05:00" }
```

## 10. Criterios de aceptación

- [ ] `./mvnw -pl citas-service -am verify` en verde.
- [ ] `CITAS-SERVICE` registrado en Eureka; la llamada a Pacientes funciona **por nombre** (log del cliente muestra `pacientes-service`, no un puerto).
- [ ] Vía Gateway: crear cita para paciente válido → `201`; para UUID inexistente → `404`; para paciente inactivo → `422`.
- [ ] Con `pacientes-service` detenido (y ya fuera de Eureka): crear cita → `503 SERVICIO_PACIENTES_NO_DISPONIBLE`, y `GET /api/citas` sigue funcionando.
- [ ] Segunda cita del mismo profesional en el mismo horario → `409`; tras cancelar la primera → se permite.
- [ ] `PENDIENTE → CONFIRMADA` → `200` y evento `cita.confirmada` en RabbitMQ; `CANCELADA → CONFIRMADA` → `409`.
- [ ] Atención registrada (spec 04) → la cita pasa a `ATENDIDA` en menos de 2 s; atención anulada antes de la hora → la cita pasa a `CANCELADA`.
- [ ] Cancelar o reprogramar una cita a menos de 24 h → `409 FUERA_DE_PLAZO`.
- [ ] Ningún `.block()` en el código (`git grep -n "\.block()" -- '*.java' ':!*Test.java' ':!*IT.java'` vacío).

## 11. Ajustes propagados al documento técnico (aplicados)

1. §8.2: columna `version BIGINT NOT NULL DEFAULT 0` y secuencia `cita_codigo_seq`.
2. §5.4 y §9.4: `especialidad` como código de catálogo (`MEDICINA_GENERAL`) y formato de código `CIT-yyyyMMdd-NNNNNN`.
3. §6.3: `ATENDIDA` solo se alcanza por el evento de Atenciones, no por `PATCH`.
4. §2.4: supuesto **A-07** (máximo 90 días de anticipación).
5. §9.3: códigos `MISMA_FECHA_HORA` y `CONFLICTO_CONCURRENTE`.

## 11.1 Ajustes adicionales (revisión de spec 04), propagados al documento

6. Transición de sistema `ATENDIDA → CANCELADA` por atención anulada antes de la hora; evento consumido `atencion.anulada`.
7. RN-15: cancelar/reprogramar solo hasta 24 h antes (`409 FUERA_DE_PLAZO`).
8. A-10: parámetros de agenda configurables.

## 12. Decisiones (todas aprobadas)

| # | Decisión tomada | Alternativa |
|---|---|---|
| D-16 | **Aprobado:** `especialidad` es un **catálogo cerrado** (enum de 7 valores) | Texto libre validado por longitud |
| D-17 | **Aprobado:** `ATENDIDA` **no** se puede poner por `PATCH`; solo vía atención registrada (un único camino, RN-11/RN-12) | Permitirlo también por `PATCH` (dos caminos, riesgo de citas atendidas sin atención) |
| D-18 | **Aprobado:** Bloqueo optimista con `@Version` para cambios concurrentes de estado | Sin control: el último en escribir gana |
| D-19 | **Aprobado:** No se valida tiempo para `AUSENTE`/`CONFIRMADA` (p. ej. marcar ausente antes de la hora) — simplificación para poder demostrarlo sin esperar | Exigir que `AUSENTE` sea posterior a `fechaHora` (imposible de demostrar con citas futuras) |
| D-20 | **Aprobado:** Reprogramar no vuelve a consultar a Pacientes | Revalidar el paciente también al reprogramar |
| D-26 | **Aprobado:** `CANCELADA` es final; para reagendar se crea una cita nueva | Reactivar la cancelada al reprogramar |
| D-27 | **Aprobado:** cancelar/reprogramar solo hasta 24 h antes; luego la cita se da por efectuada | Sin plazo |
| D-28 | **Aprobado:** parámetros de agenda configurables (permite demostrar fuera de horario) | Valores fijos en código |
| D-21 | **Aprobado:** Máximo 90 días de anticipación (supuesto A-07) | Sin límite |
