# Spec 02 — `pacientes-service`

> Depende de: spec 00 (convenciones, `hospital-commons`) y spec 01 (infraestructura, Eureka).
> Documento técnico: §5.3, §8.1, RN-01, RN-02. Cubre rúbrica: reglas de negocio, reactiva/R2DBC, pruebas (registro válido, duplicado, recurso inexistente).

## 1. Responsabilidad

Fuente de verdad de la **identidad del paciente**: registrar, consultar, actualizar datos de contacto y cambiar el estado (`ACTIVO`/`INACTIVO`). Detecta duplicados por documento. Publica eventos de registro y de cambio de estado.

**No hace:** citas, pagos, ni guarda información clínica. Otros servicios lo consultan por API (`GET /api/pacientes/{id}`) y **nunca** leen su tabla.

## 2. Módulo

| Aspecto | Valor |
|---|---|
| `artifactId` / `spring.application.name` | `pacientes-service` |
| Puerto | 8081 |
| Paquete base | `pe.edu.cibertec.vidasalud.pacientes` |
| Base de datos | `pacientes_db` (usuario `pacientes_user`) |
| Dependencias | `spring-boot-starter-webflux`, `spring-boot-starter-data-r2dbc`, `org.postgresql:r2dbc-postgresql`, `spring-boot-starter-validation`, `spring-boot-starter-actuator`, `spring-cloud-starter-netflix-eureka-client`, `springdoc-openapi-starter-webflux-ui`, `hospital-commons` |
| Dependencias de prueba | `spring-boot-starter-test`, `reactor-test`, `spring-boot-testcontainers`, `org.testcontainers:postgresql`, `org.testcontainers:rabbitmq`, `org.testcontainers:junit-jupiter` |

### 2.1 `application.yml`

```yaml
server:
  port: 8081
spring:
  application:
    name: pacientes-service
  r2dbc:
    url: r2dbc:postgresql://${DB_HOST:localhost}:${DB_PORT:5433}/${PACIENTES_DB_NAME:pacientes_db}
    username: ${PACIENTES_DB_USER:pacientes_user}
    password: ${PACIENTES_DB_PASSWORD}
  sql:
    init:
      mode: always
hospital:
  rabbit:
    host: ${RABBIT_HOST:localhost}
    port: ${RABBIT_PORT:5672}
    username: ${RABBIT_USER:hospital}
    password: ${RABBIT_PASSWORD}
eureka:
  client:
    service-url:
      defaultZone: ${EUREKA_URL:http://localhost:8761/eureka}
    registry-fetch-interval-seconds: 5
  instance:
    prefer-ip-address: true
    lease-renewal-interval-in-seconds: 5
    lease-expiration-duration-in-seconds: 10
management:
  endpoints:
    web:
      exposure:
        include: health,info
springdoc:
  swagger-ui:
    path: /swagger-ui.html
```

Este bloque es la **plantilla** que repiten las specs 03–07 cambiando nombre, puerto y variables de BD.

## 3. Modelo de dominio

### 3.1 Enumeraciones

* `TipoDocumento`: `DNI`, `CE`, `PASAPORTE`.
* `EstadoPaciente`: `ACTIVO`, `INACTIVO`.

### 3.2 Entidad `Paciente` (`domain/Paciente.java`)

Mapeada a la tabla `paciente` (DDL del documento §8.1, copiado tal cual a `schema.sql` con `IF NOT EXISTS`). Implementa `Persistable<UUID>` (spec 00 §6).

| Campo Java | Columna | Tipo Java |
|---|---|---|
| `id` | `id` | `UUID` |
| `tipoDocumento` | `tipo_documento` | `TipoDocumento` (se guarda como texto) |
| `numeroDocumento` | `numero_documento` | `String` |
| `nombres` | `nombres` | `String` |
| `apellidos` | `apellidos` | `String` |
| `fechaNacimiento` | `fecha_nacimiento` | `LocalDate` |
| `telefono` | `telefono` | `String` (nullable) |
| `correo` | `correo` | `String` (nullable) |
| `direccion` | `direccion` | `String` (nullable) |
| `fechaRegistro` | `fecha_registro` | `OffsetDateTime` |
| `estado` | `estado` | `EstadoPaciente` |

Métodos de dominio (sin Spring, probables de forma unitaria):

* `static Paciente registrar(datos, Clock reloj)`: genera `id`, `fechaRegistro = now(reloj)`, `estado = ACTIVO`, normaliza el documento (§4.1).
* `void actualizarDatos(nombres, apellidos, fechaNacimiento, telefono, correo, direccion)`: **no** toca documento, estado ni fecha de registro.
* `EstadoPaciente cambiarEstado(EstadoPaciente nuevo)`: si `nuevo == estado` lanza `ErrorNegocio(409, TRANSICION_NO_PERMITIDA, "El paciente ya está <ESTADO>.")`; si no, cambia y **devuelve el estado anterior** (lo necesita el evento).

### 3.3 Reglas de validación (`domain/ReglasDocumento.java`)

| Tipo | Formato del número (tras normalizar) |
|---|---|
| `DNI` | exactamente 8 dígitos: `^\d{8}$` |
| `CE` | 9 a 12 caracteres alfanuméricos: `^[A-Z0-9]{9,12}$` |
| `PASAPORTE` | 6 a 12 caracteres alfanuméricos: `^[A-Z0-9]{6,12}$` |

Normalización: `trim()` y mayúsculas. Un número que no cumple el formato de su tipo → `400 VALIDACION_FALLIDA`, con `errores=[{campo:"numeroDocumento", mensaje:"formato inválido para <TIPO>"}]`.

Otras validaciones (anotaciones en el DTO de entrada):

| Campo | Regla |
|---|---|
| `tipoDocumento` | obligatorio |
| `numeroDocumento` | obligatorio, 6–20 caracteres (la regla por tipo se aplica en el dominio) |
| `nombres`, `apellidos` | obligatorios, 1–80 caracteres, sin solo espacios (`@NotBlank`) |
| `fechaNacimiento` | obligatoria, `@Past`; además no más de 120 años atrás (regla en dominio → `400`) |
| `telefono` | opcional, `^[0-9+ ]{7,15}$` |
| `correo` | opcional, `@Email`, ≤ 120 |
| `direccion` | opcional, ≤ 200 |

## 4. Casos de uso (`service/PacienteService.java`)

Todos devuelven `Mono`/`Flux`; sin `.block()`.

### 4.1 Registrar — `Mono<PacienteResponse> registrar(PacienteCrearRequest)`

1. Construir `Paciente.registrar(...)` (normaliza y valida formato del documento).
2. `repositorio.existsByTipoDocumentoAndNumeroDocumento(tipo, numero)`:
   * `true` → `Mono.error(ErrorNegocio(409, PACIENTE_DUPLICADO, "Ya existe un paciente con el documento <TIPO> <NUMERO>."))`.
3. `repositorio.save(paciente)`.
   * `onErrorMap(DuplicateKeyException → mismo ErrorNegocio 409)`: cubre la carrera entre dos registros simultáneos (la restricción `uq_paciente_documento` es la garantía).
4. Publicar `paciente.registrado` (§6) y devolver el DTO.

> El mensaje de error contiene el documento **del solicitante** (que ya lo conoce); el log no lo registra.

### 4.2 Obtener — `Mono<PacienteResponse> obtener(UUID id)`

`findById(id)` → si vacío: `ErrorNegocio(404, PACIENTE_NO_ENCONTRADO, "No existe el paciente <id>.")`.

### 4.3 Listar — `Mono<ResponseEntity<Flux<PacienteResponse>>> listar(filtros, pagina)`

Filtros opcionales (spec 00 §4.2, con `Criteria`):

| Parámetro | Condición |
|---|---|
| `estado` | igualdad |
| `tipoDocumento` + `numeroDocumento` | igualdad (normalizado); si llega uno sin el otro → `400` |
| `apellidos` | contiene, sin distinguir mayúsculas (`ILIKE '%valor%'`) |

Orden estable: `apellidos, nombres, id`.

### 4.4 Actualizar — `Mono<PacienteResponse> actualizar(UUID id, PacienteActualizarRequest)`

1. `obtener` (404 si no existe).
2. `actualizarDatos(...)`; `save`.
3. **No** publica evento (no es cambio de estado; documento §2.3 RN-08 habla de cambios de estado). Se registra `INFO` con el id.

El **documento es inmutable** por esta vía (D-11).

### 4.5 Cambiar estado — `Mono<PacienteResponse> cambiarEstado(UUID id, CambioEstadoRequest)`

1. `obtener` (404).
2. `anterior = paciente.cambiarEstado(nuevo)` (409 si es el mismo).
3. `save`; publicar `paciente.estado-cambiado` con `estadoAnterior`, `estadoNuevo` y `datos.motivo`.

Efecto en otros servicios: un paciente `INACTIVO` no puede recibir citas ni pagos nuevos (Citas y Pagos lo validan al consultarlo, documento §6.2). Las citas existentes **no** se cancelan automáticamente (fuera de alcance; se documenta).

## 5. API

### 5.1 DTOs (`api/dto`, todos `record`)

```text
PacienteCrearRequest(tipoDocumento, numeroDocumento, nombres, apellidos, fechaNacimiento,
                     telefono, correo, direccion)
PacienteActualizarRequest(nombres, apellidos, fechaNacimiento, telefono, correo, direccion)
CambioEstadoRequest(@NotNull estado, @Size(max=200) motivo)
PacienteResponse(id, tipoDocumento, numeroDocumento, nombres, apellidos, fechaNacimiento,
                 telefono, correo, direccion, fechaRegistro, estado)
```

`PacienteResponse` es también el **contrato** que consumen Citas y Pagos (ellos solo leen `id` y `estado` e ignoran el resto).

### 5.2 Endpoints (`api/PacienteController.java`, base `/api/pacientes`)

| Método y ruta | Entrada | Respuesta OK | Errores |
|---|---|---|---|
| `POST /api/pacientes` | `PacienteCrearRequest` | `201` + `Location: /api/pacientes/{id}` + cuerpo | `400 VALIDACION_FALLIDA`, `409 PACIENTE_DUPLICADO` |
| `GET /api/pacientes/{id}` | — | `200` | `400` si `id` no es UUID, `404 PACIENTE_NO_ENCONTRADO` |
| `GET /api/pacientes` | `estado`, `tipoDocumento`, `numeroDocumento`, `apellidos`, `pagina`, `tamano` | `200` arreglo + `X-Total-Count` | `400` |
| `PUT /api/pacientes/{id}` | `PacienteActualizarRequest` | `200` | `400`, `404` |
| `PATCH /api/pacientes/{id}/estado` | `CambioEstadoRequest` | `200` | `400`, `404`, `409 TRANSICION_NO_PERMITIDA` |

No existe `DELETE`: la baja lógica es `INACTIVO` (D-12).

## 6. Eventos publicados

Exchange `hospital.eventos`. Sobre de la spec 00 §5.2, con `origen = "pacientes-service"`, `tipoRecurso = "PACIENTE"`, `recursoId = id del paciente`.

| Routing key | Cuándo | `estadoAnterior` → `estadoNuevo` | `datos` |
|---|---|---|---|
| `paciente.registrado` | tras registrar | `null` → `ACTIVO` | `{ "pacienteId", "fechaRegistro" }` |
| `paciente.estado-cambiado` | tras cambiar estado | `ACTIVO` ↔ `INACTIVO` | `{ "pacienteId", "motivo" }` |

**Datos mínimos (D-13):** los eventos **no** llevan documento, nombres, teléfono ni correo. Notificaciones usa mensajes genéricos ("Bienvenido/a…") y Auditoría solo necesita ids y estados. Así los datos personales no se replican a otras bases ni quedan en las colas.

La publicación ocurre **después** de que `save` se completa y su falla no afecta la respuesta HTTP (spec 00, D-3).

Este servicio **no consume** eventos.

## 7. Pruebas

| Clase | Tipo | Casos |
|---|---|---|
| `ReglasDocumentoTest` | Unitaria | DNI válido/8 dígitos; DNI con letras o 7 dígitos → error; CE y PASAPORTE en límites; normalización (espacios, minúsculas) |
| `PacienteTest` | Unitaria | `registrar` deja `ACTIVO` y fecha del `Clock` fijo; `cambiarEstado` ACTIVO→INACTIVO devuelve anterior; mismo estado → 409 `TRANSICION_NO_PERMITIDA`; `actualizarDatos` no cambia documento; fecha de nacimiento > 120 años → error |
| `PacienteServiceTest` | Servicio (`StepVerifier`, Mockito) | **Registro válido** publica `paciente.registrado` con `estadoNuevo=ACTIVO` y sin datos personales en `datos`; **duplicado** por consulta previa → 409 y **no** guarda ni publica; `DuplicateKeyException` al guardar → 409; `obtener` inexistente → 404; cambio de estado publica evento con anterior y nuevo; falla del publicador no rompe el registro |
| `PacienteControllerTest` | Controlador (`@WebFluxTest`) | `POST` válido → 201 + `Location`; cuerpo inválido (sin nombres, correo mal formado) → 400 con `errores`; servicio lanza duplicado → 409 `problem+json` con `codigo`; `GET` inexistente → 404 `PACIENTE_NO_ENCONTRADO`; `GET` con id no UUID → 400; listado devuelve `X-Total-Count`; `tamano=500` → 400 |
| `PacienteRepositoryIT` | Integración PostgreSQL | `schema.sql` crea la tabla; insertar dos pacientes con el mismo tipo+número → `DuplicateKeyException`; mismo número con distinto tipo → permitido; filtros y paginación con `X-Total-Count` correcto |
| `PacienteFlujoIT` | Integración PostgreSQL + RabbitMQ (`@SpringBootTest`) | `POST` real → fila en BD → mensaje recibido en una cola de prueba enlazada a `paciente.registrado`, con sobre correcto |

Casos del enunciado §24 cubiertos aquí: **registro de paciente válido**, **rechazo de paciente duplicado**, **manejo de errores ante recurso inexistente**.

## 8. Datos de prueba y ejemplos

```json
{
  "tipoDocumento": "DNI",
  "numeroDocumento": "00000001",
  "nombres": "Ana María",
  "apellidos": "Prueba Ficticia",
  "fechaNacimiento": "1990-05-20",
  "telefono": "999000111",
  "correo": "ana.prueba@example.com"
}
```

Duplicado: mismo cuerpo dos veces → segunda respuesta `409 PACIENTE_DUPLICADO`.

## 9. Criterios de aceptación

- [ ] `./mvnw -pl pacientes-service -am verify` en verde (unitarias + IT).
- [ ] Con infraestructura y Eureka arriba, el servicio aparece como `PACIENTES-SERVICE` en el panel de Eureka.
- [ ] `POST http://localhost:8080/api/pacientes` (vía Gateway) → `201`; repetirlo → `409`.
- [ ] `GET http://localhost:8080/api/pacientes/{id}` → `200`; con UUID aleatorio → `404` en formato `problem+json`.
- [ ] `PATCH .../estado` a `INACTIVO` → `200`; repetirlo → `409`.
- [ ] La fila existe en `pacientes_db.paciente` (evidencia de persistencia R2DBC).
- [ ] Mensaje `paciente.registrado` visible en el exchange `hospital.eventos` (panel RabbitMQ).
- [ ] Swagger en `http://localhost:8081/swagger-ui.html`.
- [ ] Ningún log contiene documento, nombres, teléfono o correo.

## 10. Decisiones abiertas para revisión

| # | Decisión tomada | Alternativa |
|---|---|---|
| D-11 | El documento (tipo + número) es **inmutable** por `PUT`; para corregirlo se inactiva y se registra de nuevo | Permitir cambiarlo, con la misma validación de duplicado |
| D-12 | Sin `DELETE`; la baja es `INACTIVO` | `DELETE` físico (rompería referencias en citas y pagos) |
| D-13 | Los eventos no llevan datos personales (solo ids y estados) | Incluir nombre y correo para notificaciones "reales" |
| D-14 | Actualizar datos de contacto **no** publica evento | Publicar `paciente.actualizado` para auditoría |
| D-15 | Inactivar a un paciente no cancela sus citas existentes | Cancelarlas automáticamente vía evento consumido por Citas |
