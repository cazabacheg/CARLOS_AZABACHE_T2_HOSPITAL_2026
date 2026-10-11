# Spec 05 — `pagos-service`

> Depende de: spec 00, spec 01, spec 02 (contrato `GET /api/pacientes/{id}`).
> Documento técnico: §5.6, §8.4, RN-09, RN-13; enunciado §12 (Proceso 7), §5 (regla de pagos) y §24 (prueba "validación de monto de pago no permitido").
> Cubre rúbrica: reglas de negocio, pruebas (monto inválido), tercer productor de eventos.

## 1. Responsabilidad

Registrar y anular **pagos simulados** de pacientes. Versión reducida del Proceso 7: cada pago lleva su concepto de cobro; no hay entidad de cargo separada (documento §14).

* Valida al paciente con una llamada **síncrona** a Pacientes (mismo contrato que Citas).
* Garantiza **monto > 0**, **número de operación único** (generado por el propio servicio) e **idempotencia** del registro mediante la cabecera `Idempotency-Key` (§5.5).
* El pago se registra normalmente **antes** de la atención, para consolidar la reserva; por eso se referencia opcionalmente a la **cita**, no a la atención.
* Publica `pago.registrado` y `pago.anulado`.

**No hace:** cobros reales, integración con pasarelas, facturación electrónica ni cálculo de tarifas.

## 2. Módulo

| Aspecto | Valor |
|---|---|
| `artifactId` / `spring.application.name` | `pagos-service` |
| Puerto | 8086 |
| Paquete base | `pe.edu.cibertec.vidasalud.pagos` |
| Base de datos | `pagos_db` (usuario `pagos_user`) |
| Dependencias | Iguales a `citas-service` (spec 03 §2) |
| `application.yml` | Plantilla de la spec 02 §2.1 con puerto `8086`, variables `PAGOS_DB_*` y `hospital.clientes.pacientes.url: http://pacientes-service` |

## 3. Modelo de dominio

### 3.1 Enumeraciones

* `MedioPago`: `EFECTIVO`, `TARJETA`, `TRANSFERENCIA`, `BILLETERA`.
* `EstadoPago`: `REGISTRADO → ANULADO`; `ANULADO` es final.

### 3.2 Entidad `Pago`

Tabla `pago` (documento §8.4) con estos **ajustes** (se propagan al aprobar):

```sql
cita_id              UUID,                    -- reemplaza a atencion_id; referencia lógica opcional
moneda               CHAR(3)      NOT NULL DEFAULT 'PEN' CHECK (moneda = 'PEN'),
monto                NUMERIC(12,2) NOT NULL CHECK (monto > 0 AND monto <= 100000),
motivo_anulacion     VARCHAR(200),
fecha_actualizacion  TIMESTAMPTZ  NOT NULL DEFAULT now(),
version              BIGINT       NOT NULL DEFAULT 0
-- + índice: CREATE INDEX ix_pago_fecha ON pago (fecha_pago);
-- + secuencia: CREATE SEQUENCE IF NOT EXISTS pago_operacion_seq;
clave_idempotencia   VARCHAR(64)  NOT NULL UNIQUE,   -- cabecera Idempotency-Key
huella_solicitud     CHAR(64)     NOT NULL           -- SHA-256 (hex) del cuerpo normalizado
```

| Campo Java | Columna | Tipo |
|---|---|---|
| `id` | `id` | `UUID` |
| `numeroOperacion` | `numero_operacion` | `String` (único, `OP-yyyyMMdd-NNNNNN`, generado) |
| `pacienteId` | `paciente_id` | `UUID` |
| `citaId` | `cita_id` | `UUID` (nullable, informativo) |
| `concepto` | `concepto` | `String` |
| `monto` | `monto` | `BigDecimal` (escala 2) |
| `moneda` | `moneda` | `String` (`PEN`) |
| `fechaPago` | `fecha_pago` | `OffsetDateTime` (asignada por el servidor) |
| `medioPago` | `medio_pago` | `MedioPago` |
| `estado` | `estado` | `EstadoPago` |
| `motivoAnulacion` | `motivo_anulacion` | `String` (nullable) |
| `fechaActualizacion` | `fecha_actualizacion` | `OffsetDateTime` |
| `version` | `version` | `Long` con `@Version` |
| `claveIdempotencia` | `clave_idempotencia` | `String` (única) |
| `huellaSolicitud` | `huella_solicitud` | `String` (SHA-256 hex) |

Métodos de dominio:

* `static Pago registrar(datos, String numeroOperacion, Clock reloj)` → aplica `ReglasPago`, estado `REGISTRADO`, `fechaPago = ahora`.
* `void anular(String motivo, Clock reloj)` → si ya está `ANULADO`: `409 PAGO_YA_ANULADO`, "El pago ya fue anulado."

### 3.3 `ReglasPago` (RN-09) — clase pura

| Regla | Error |
|---|---|
| `monto` obligatorio | `400 VALIDACION_FALLIDA` (anotación `@NotNull`) |
| `monto > 0` | `400 MONTO_INVALIDO`, "El monto debe ser mayor que cero." |
| máximo 2 decimales | `400 MONTO_INVALIDO`, "El monto admite como máximo 2 decimales." |
| `monto ≤ 100 000.00` | `400 MONTO_INVALIDO`, "El monto excede el máximo permitido (100000.00)." |

La regla de monto vive en el **dominio** (no solo en anotaciones) para devolver el código específico `MONTO_INVALIDO` que pide el enunciado y poder probarla con JUnit simple. La restricción `CHECK` de la base es la última línea de defensa.

El **número de operación lo genera el servicio** con la secuencia `pago_operacion_seq`: `OP-<yyyyMMdd en Lima>-<NNNNNN>` (p. ej. `OP-20261012-000001`). La restricción `UNIQUE` sigue siendo la garantía de no duplicación exigida por el enunciado. Un pago anulado **conserva** su número.

**Reintentos del cliente:** como el número lo asigna el servidor, un reenvío del mismo `POST` (p. ej. tras un timeout de red) registraría dos pagos. Se evita con la cabecera **`Idempotency-Key`** (§5.5, D-38).

## 4. Cliente síncrono a Pacientes

Mismo diseño y tabla de errores que `PacientesClient` de Citas (spec 03 §4), con `PacienteResumen(id, estado)` propio de este servicio. Paciente `INACTIVO` → `422 PACIENTE_INACTIVO`, "El paciente está inactivo y no puede registrar pagos."

> Se duplica el cliente en lugar de moverlo a `hospital-commons`: cada servicio es dueño de su contrato con Pacientes y puede evolucionar sin arrastrar a los demás (spec 00, D-2).

## 5. Casos de uso (`service/PagoService.java`)

### 5.1 Registrar — `Mono<PagoResponse> registrar(PagoCrearRequest)`

Orden de más barato a más caro:

0. **Idempotencia** (§5.5): validar la cabecera; si ya existe un pago con esa clave → devolverlo (o `422` si el cuerpo difiere) y **terminar aquí**.
1. `ReglasPago.validarMonto` → `400 MONTO_INVALIDO`, sin E/S.
2. `pacientesClient.obtener(pacienteId)` → `404` / `503` / `504`; `INACTIVO` → `422`.
3. `SELECT nextval('pago_operacion_seq')` → número de operación.
4. `Pago.registrar(...)` con `claveIdempotencia` y `huellaSolicitud`; `save`. Ante `DuplicateKeyException`: se busca por `clave_idempotencia`; si existe → era una petición **simultánea** con la misma clave → se aplica la regla de repetición de §5.5; si no existe → la violación fue de `numero_operacion` → `409 OPERACION_DUPLICADA` (no debería ocurrir con la secuencia).
5. Publicar `pago.registrado`; `201`.

### 5.2 Obtener — `404 PAGO_NO_ENCONTRADO`.

### 5.3 Listar — paginado

Filtros: `pacienteId`, `citaId`, `numeroOperacion`, `estado`, `medioPago`, `fecha` (día en Lima sobre `fecha_pago`). Orden `fecha_pago DESC, id`.

### 5.4 Anular — `Mono<PagoResponse> anular(UUID id, AnularPagoRequest)`

1. `obtener` (404).
2. `pago.anular(motivo)` (409 `PAGO_YA_ANULADO`).
3. `save` (409 `CONFLICTO_CONCURRENTE` por `@Version`).
4. Publicar `pago.anulado`.

Sin plazo para anular (D-31). Anular ya es seguro ante reintentos: un segundo intento devuelve `409 PAGO_YA_ANULADO` y el estado no cambia.

### 5.5 Idempotencia del registro (`Idempotency-Key`, D-38)

Patrón del borrador IETF *The Idempotency-Key HTTP Header Field*:

| Situación | Respuesta |
|---|---|
| Falta la cabecera, o no cumple `^[A-Za-z0-9-]{8,64}$` | `400 CLAVE_IDEMPOTENCIA_REQUERIDA`, "La cabecera Idempotency-Key es obligatoria (8–64 caracteres alfanuméricos o guiones)." |
| Clave nueva | Flujo normal; la clave y la huella se guardan **con** el pago (mismo `INSERT`, sin tabla aparte) |
| Clave existente y **misma huella** | `201` con el **mismo** pago y cabecera `Idempotent-Replayed: true`; **no** se registra otro pago **ni** se publica otro evento |
| Clave existente y **huella distinta** | `422 CLAVE_IDEMPOTENCIA_REUTILIZADA`, "La clave de idempotencia ya se usó con datos diferentes." |
| Dos peticiones simultáneas con la misma clave | La segunda choca con `UNIQUE(clave_idempotencia)`, relee el pago y responde como repetición |

* **Huella:** SHA-256 en hexadecimal de `pacienteId|citaId|concepto(trim)|monto(escala 2)|medioPago`. Clase pura `HuellaSolicitud`.
* Solo se guardan las claves de pagos **creados**: si una petición falla por validación o porque Pacientes no responde, el reintento con la misma clave vuelve a ejecutarse normalmente (no hubo efecto).
* Las claves no expiran (simplificación académica; en producción se depuran tras 24–72 h).
* El Gateway reenvía la cabecera sin cambios.
* **Solo Pagos la necesita:** los demás `POST` ya tienen una clave natural que impide duplicados (documento del paciente, índice de agenda de la cita, una atención por cita).

## 6. API

### 6.1 DTOs

```text
// + cabecera obligatoria: Idempotency-Key
PagoCrearRequest(@NotNull UUID pacienteId, UUID citaId,
                 @NotBlank @Size(max=120) String concepto,
                 @NotNull BigDecimal monto,
                 @NotNull MedioPago medioPago)
AnularPagoRequest(@NotBlank @Size(max=200) String motivo)
PagoResponse(id, numeroOperacion, pacienteId, citaId, concepto, monto, moneda,
             fechaPago, medioPago, estado, motivoAnulacion, fechaActualizacion)
```

`monto` se serializa como número JSON con 2 decimales (`80.00`).

### 6.2 Endpoints (base `/api/pagos`)

| Método y ruta | OK | Errores |
|---|---|---|
| `POST /api/pagos` + cabecera `Idempotency-Key` | `201` + `Location` (incluye el `numeroOperacion` generado); repetición → `201` + `Idempotent-Replayed: true` | `400 VALIDACION_FALLIDA` / `MONTO_INVALIDO` / `CLAVE_IDEMPOTENCIA_REQUERIDA`; `422 CLAVE_IDEMPOTENCIA_REUTILIZADA`; `404 PACIENTE_NO_ENCONTRADO`; `422 PACIENTE_INACTIVO`; `503`/`504 SERVICIO_PACIENTES_NO_DISPONIBLE` |
| `GET /api/pagos/{id}` | `200` | `400`, `404 PAGO_NO_ENCONTRADO` |
| `GET /api/pagos` | `200` + `X-Total-Count` | `400` |
| `PATCH /api/pagos/{id}/anular` | `200` | `400` (sin motivo), `404`, `409 PAGO_YA_ANULADO` / `CONFLICTO_CONCURRENTE` |

## 7. Eventos publicados

`origen = "pagos-service"`, `tipoRecurso = "PAGO"`, `recursoId = id del pago`. `datos`: `{ "pagoId", "pacienteId", "citaId", "numeroOperacion", "concepto", "monto", "moneda", "medioPago", "fechaPago" }`.

| Routing key | Anterior → Nuevo | `datos` adicional | Consumidores |
|---|---|---|---|
| `pago.registrado` | `null` → `REGISTRADO` | — | Auditoría; Notificaciones (baja prioridad) |
| `pago.anulado` | `REGISTRADO` → `ANULADO` | `motivo` | Auditoría; Notificaciones (baja prioridad) |

Este servicio **no consume** eventos.

## 8. Pruebas

| Clase | Tipo | Casos |
|---|---|---|
| `ReglasPagoTest` | Unitaria (parametrizada) | **`0` → `MONTO_INVALIDO`; `-50.00` → `MONTO_INVALIDO`**; `0.001` → 3 decimales; `0.01` OK; `100000.00` OK; `100000.01` → excede |
| `PagoTest` | Unitaria | `registrar` queda `REGISTRADO` con `fechaPago` del `Clock` y el número recibido; `anular` dos veces → 409 `PAGO_YA_ANULADO` |
| `HuellaSolicitudTest` | Unitaria | Mismo cuerpo → misma huella; `80` y `80.00` → misma huella; cambiar el monto o el paciente → huella distinta |
| `PacientesClientTest` | `MockWebServer` | Igual que en Citas (200, 404, 500, retraso, reintento) |
| `PagoServiceTest` | `StepVerifier` + Mockito | Registro OK publica `pago.registrado` con monto; **monto 0 → 400 sin llamar a Pacientes**; paciente inactivo → 422; el número generado tiene formato `OP-yyyyMMdd-NNNNNN`; `DuplicateKeyException` → 409 `OPERACION_DUPLICADA`; anular publica `pago.anulado` con motivo; **misma clave y mismo cuerpo → devuelve el pago existente, sin guardar, sin llamar a Pacientes y sin publicar**; misma clave y otro monto → 422; `DuplicateKeyException` por clave (carrera) → devuelve el existente |
| `PagoControllerTest` | `@WebFluxTest` | `POST` OK 201; **`monto: -10` → 400 `problem+json` con `codigo=MONTO_INVALIDO`**; `medioPago` desconocido → 400; sin `concepto` → 400; `GET` inexistente → 404; anular sin motivo → 400; **sin cabecera `Idempotency-Key` → 400 `CLAVE_IDEMPOTENCIA_REQUERIDA`**; repetición → 201 con `Idempotent-Replayed: true` |
| `PagoRepositoryIT` | PostgreSQL | `CHECK (monto > 0)` rechaza un insert directo con 0; `numero_operacion` duplicado (insert directo) → `DuplicateKeyException`; la secuencia genera números distintos; filtros y paginación |
| `PagoFlujoIT` | PostgreSQL + RabbitMQ + `MockWebServer` (Pacientes) | `POST` → fila en BD → mensaje `pago.registrado` en una cola de prueba; **el mismo `POST` con la misma clave enviado dos veces → una sola fila y un solo mensaje**; dos `POST` simultáneos con la misma clave → una sola fila |

Caso del enunciado §24 cubierto aquí: **validación de monto de pago no permitido**. Además: no duplicación de pagos ante reintentos.

## 9. Ejemplos

```json
POST /api/pagos
Idempotency-Key: 3f6c2a1e-7b1d-4a8e-9c55-0d2f1b7a9e10
{
  "pacienteId": "…",
  "citaId": "…",
  "concepto": "Reserva de cita - Medicina General",
  "monto": 80.00,
  "medioPago": "TARJETA"
}
→ 201 { "numeroOperacion": "OP-20261012-000001", … }

PATCH /api/pagos/{id}/anular
{ "motivo": "Pago registrado por error" }
```

## 10. Criterios de aceptación

- [ ] `./mvnw -pl pagos-service -am verify` en verde.
- [ ] `PAGOS-SERVICE` en Eureka; llamada a Pacientes por nombre.
- [ ] Vía Gateway: pago válido → `201` con número `OP-…`; `monto: 0` → `400 MONTO_INVALIDO`; paciente inexistente → `404`.
- [ ] Reenviar el mismo pago con la misma `Idempotency-Key` → mismo `numeroOperacion`, `Idempotent-Replayed: true` y una sola fila en `pago`; misma clave con otro monto → `422`; sin clave → `400`.
- [ ] Anular → `200`; anular otra vez → `409 PAGO_YA_ANULADO`.
- [ ] Fila en `pagos_db.pago` y mensaje `pago.registrado` en RabbitMQ.

## 11. Ajustes propagados al documento técnico (aplicados)

1. §8.4: columnas `moneda`, `motivo_anulacion`, `fecha_actualizacion`, `version`; `CHECK` con máximo; índice por fecha.
2. §5.6: endpoint de anulación con motivo obligatorio; número de operación generado por secuencia; `citaId` en lugar de `atencionId`; tope de monto; limitación de reintentos.
4. §14: mejora "exigir pago para confirmar la cita"; la idempotencia pasa a estar **implementada** (§5.6, §9.3: códigos `CLAVE_IDEMPOTENCIA_REQUERIDA`, `CLAVE_IDEMPOTENCIA_REUTILIZADA`).
3. §9.3: (los códigos ya están: `MONTO_INVALIDO`, `OPERACION_DUPLICADA`, `PAGO_NO_ENCONTRADO`, `PAGO_YA_ANULADO`).

## 12. Decisiones (todas aprobadas)

| # | Decisión tomada | Alternativa |
|---|---|---|
| D-30 | **Aprobado (revisado):** el pago referencia opcionalmente a la **cita** (`citaId`), sin validarla, porque se paga antes de la atención para consolidar la reserva. Confirmar una cita **no** exige pago (mejora futura) | Validar la cita / exigir pago para confirmar |
| D-31 | **Aprobado:** Anular no tiene plazo; requiere motivo | Permitir anular solo el mismo día |
| D-32 | **Aprobado (revisado):** número de operación generado por secuencia (`OP-yyyyMMdd-NNNNNN`); reintentos duplicados del cliente documentados como limitación | Número enviado por el cliente |
| D-38 | **Aprobado:** `Idempotency-Key` **obligatoria** en `POST /api/pagos`, guardada con el pago junto a la huella del cuerpo; repetición devuelve el mismo pago | Clave opcional / sin idempotencia |
| D-33 | **Aprobado:** moneda única `PEN` y monto máximo 100 000.00 | Multimoneda / sin tope |
