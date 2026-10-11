# Spec 00 — Convenciones comunes y módulo `hospital-commons`

> Esta spec aplica a **todos** los servicios. Las specs 01–07 solo describen lo que es propio de cada servicio y remiten aquí para lo común.
> Referencia: `docs/documento-tecnico.md`.

## 1. Stack y versiones fijadas

| Componente | Versión | Nota |
|---|---|---|
| Java | 21 (Temurin en CI) | Instalado localmente: OpenJDK 21.0.12 |
| Spring Boot | **3.5.16** | Se descarta Boot 4.x (disponible) por riesgo: cambios de módulos, Jackson 3 y pruebas, sin margen de tiempo |
| Spring Cloud | **2025.0.3** | Compatible con Boot 3.5.x |
| PostgreSQL | 16 (imagen `postgres:16`) | Driver `org.postgresql:r2dbc-postgresql` (versión gestionada por Boot) |
| RabbitMQ | 3.13 (imagen `rabbitmq:3.13-management`) | Cliente reactivo `io.projectreactor.rabbitmq:reactor-rabbitmq:1.5.6` |
| springdoc-openapi | **2.8.17** (`springdoc-openapi-starter-webflux-ui`) | |
| Testcontainers | gestionado por el BOM de Boot (1.21.x) | PostgreSQL y RabbitMQ |
| Maven | Wrapper `mvnw` (Maven 3.9.x) | Generado en la raíz; el Maven del sistema (3.8.7) solo se usa para generarlo |

## 2. Estructura Maven

Monorepo multi-módulo. El `pom.xml` raíz (`hospital-vida-salud-parent`) hereda de `spring-boot-starter-parent:3.5.16` e importa el BOM `spring-cloud-dependencies:2025.0.3` en `dependencyManagement`.

```text
hospital-vida-salud/
├── pom.xml                  # packaging pom, <modules> en este orden:
├── hospital-commons/        # librería técnica compartida (NO es un servicio)
├── eureka-server/
├── api-gateway/
├── pacientes-service/
├── citas-service/
├── atenciones-service/
├── pagos-service/
├── notificaciones-service/
└── auditoria-service/
```

* `groupId`: `pe.edu.cibertec.vidasalud`. `artifactId`: nombre del módulo. `version`: `1.0.0-SNAPSHOT`.
* Paquete base de cada servicio: `pe.edu.cibertec.vidasalud.<servicio>` (p. ej. `...pacientes`). `hospital-commons` usa `pe.edu.cibertec.vidasalud.commons`.
* Los servicios de negocio (pacientes, citas, atenciones, pagos, notificaciones, auditoría) dependen de `hospital-commons`. **Eureka y Gateway no.**
* Propiedades del padre: `java.version=21`, `project.build.sourceEncoding=UTF-8`.
* Cada servicio ejecutable usa `spring-boot-maven-plugin`; `hospital-commons` **no** (es librería).

> **Ajuste al documento técnico:** el documento (§6.4) dice que los servicios "no comparten clases". Se precisa: `hospital-commons` comparte solo **infraestructura técnica** (manejo de errores, sobre genérico de eventos, cliente de RabbitMQ). Las clases de **dominio y los DTO de cada evento no se comparten**: cada consumidor define su propio DTO tolerante (`@JsonIgnoreProperties(ignoreUnknown = true)`).

## 3. Arquitectura interna de cada servicio de negocio

Paquetes, de afuera hacia adentro:

```text
pe.edu.cibertec.vidasalud.<servicio>
├── <Servicio>Application.java       # @SpringBootApplication, @EnableDiscoveryClient no es necesario (autoconfig)
├── api/                             # @RestController + DTOs de entrada/salida (records)
├── domain/                          # entidades, enums de estado, reglas puras (sin Spring), excepciones
├── service/                         # casos de uso: orquestan dominio + repositorio + clientes + eventos
├── persistence/                     # repositorios ReactiveCrudRepository
├── client/                          # clientes WebClient a otros servicios (si aplica)
├── messaging/                       # publicación/consumo de eventos (si aplica)
└── config/                          # beans y propiedades
```

Reglas:

1. El **controlador** solo valida entrada (`@Valid`), llama al servicio y arma la respuesta. Sin reglas de negocio.
2. Las **reglas de negocio puras** (transiciones de estado, validación de horario) viven en `domain/` como clases sin dependencia de Spring, para probarlas con JUnit simple.
3. Los DTO son `record` de Java. Las entidades de persistencia **nunca** salen por la API: se mapea siempre a un DTO de respuesta.
4. Constructor injection; sin `@Autowired` en campos. Sin Lombok (no es necesario con records y reduce riesgo).
5. Nada de `.block()`, `.subscribe()` dentro del flujo ni `Thread.sleep`. JDBC/JPA prohibidos.

## 4. Convenciones de API

| Aspecto | Convención |
|---|---|
| JSON | `camelCase`; fechas ISO-8601; `fechaHora` con offset (`2026-10-15T10:00:00-05:00`) |
| IDs | UUID v4 generados **en el servicio** (`UUID.randomUUID()`) |
| Zona horaria | `America/Lima`; un bean `Clock` (`Clock.system(ZoneId.of("America/Lima"))`) se inyecta en todo lo que necesite la hora, para poder fijarlo en pruebas |
| Fechas en entidades | `OffsetDateTime` para `TIMESTAMPTZ`, `LocalDate` para `DATE` |
| `201 Created` | Incluye cabecera `Location: /api/<recurso>/{id}` y el recurso en el cuerpo |
| Listados | Devuelven `Flux<DTO>` (arreglo JSON). Sin paginación (se documenta como límite académico), tope de seguridad `LIMIT 200` |
| Filtros | Parámetros de consulta opcionales; si no se envían, no filtran |
| Códigos de estado | Los del documento §9.2 |
| Swagger | `springdoc` en cada servicio; `@Operation` solo en los endpoints principales, sin llenar de anotaciones |

### 4.1 Formato de error (RFC 7807)

Todas las respuestas de error son `application/problem+json` con los campos de `ProblemDetail` de Spring más `codigo` y `timestamp`:

```json
{
  "type": "about:blank",
  "title": "Conflicto",
  "status": 409,
  "detail": "Ya existe un paciente con el documento DNI 00000001.",
  "instance": "/api/pacientes",
  "codigo": "PACIENTE_DUPLICADO",
  "timestamp": "2026-10-12T09:30:00-05:00"
}
```

Para errores de validación (`400`, `codigo = VALIDACION_FALLIDA`) se añade `errores`:

```json
{ "...": "...", "errores": [ { "campo": "correo", "mensaje": "debe ser un correo válido" } ] }
```

## 5. Módulo `hospital-commons`

Contenido **exacto** (nada más):

### 5.1 Errores

| Clase | Descripción |
|---|---|
| `ErrorNegocio extends RuntimeException` | Campos: `HttpStatus status`, `String codigo`, `String detalle`. Constructor `(HttpStatus, String codigo, String detalle)`. Es la **única** excepción que usan los servicios para errores esperados. Sin `printStackTrace` ni stack trace en la respuesta |
| `ManejadorErrores` | `@RestControllerAdvice` que traduce: `ErrorNegocio` → `ProblemDetail` con su estado y código; `WebExchangeBindException` (validación del cuerpo) → `400 VALIDACION_FALLIDA` con `errores`; `ServerWebInputException` (JSON ilegible, parámetro inválido) → `400 VALIDACION_FALLIDA`; `DuplicateKeyException` **no** se maneja aquí (cada servicio la traduce a su código concreto); cualquier otra `Throwable` → `500 ERROR_INTERNO` con detalle genérico y registro del error en el log |
| `ErroresAutoConfiguration` | Registrada en `META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports`; solo se activa en aplicaciones web reactivas (`@ConditionalOnWebApplication(type = REACTIVE)`) |

### 5.2 Mensajería (reactor-rabbitmq)

| Clase | Descripción |
|---|---|
| `EventoEnvelope` | `record`: `UUID eventId`, `String tipo`, `OffsetDateTime ocurrioEn`, `String origen`, `String tipoRecurso`, `UUID recursoId`, `String estadoAnterior`, `String estadoNuevo`, `JsonNode datos`. Es el sobre del documento §6.4. `datos` es genérico (`JsonNode`) y lo construye el productor |
| `RabbitPropiedades` | `@ConfigurationProperties("hospital.rabbit")`: `host`, `port`, `username`, `password`, `virtualHost` (`/`) |
| `RabbitAutoConfiguration` | Crea el `ConnectionFactory`, un `Mono<Connection>` compartido (`.cache()`), `Sender` y `Receiver`. Se activa solo si `hospital.rabbit.enabled=true` (por defecto `true`; las pruebas de controlador lo ponen en `false`) |
| `TopologiaHospital` | Constantes: nombre del exchange (`hospital.eventos`), DLX (`hospital.eventos.dlx`) y routing keys (`PACIENTE_REGISTRADO = "paciente.registrado"`, etc.) |
| `PublicadorEventos` | Interfaz con `Mono<Void> publicar(String routingKey, EventoEnvelope evento)`. Implementación `PublicadorRabbit`: declara el exchange (idempotente) y publica con `Sender` en modo *publisher confirms*, mensaje persistente (`deliveryMode=2`), `contentType=application/json`. **Si publicar falla, no hace fallar la operación HTTP**: registra `ERROR` con `eventId` y `routingKey` y devuelve `Mono.empty()` (limitación documentada: sin *outbox*, §6.4 del documento) |
| `ConsumidorEventos` | Utilidad: `Flux<Void> consumir(String cola, List<String> bindings, Function<EventoEnvelope, Mono<Void>> manejador)`. Declara la DLX, la cola `<cola>` (durable, con `x-dead-letter-exchange=hospital.eventos.dlx` y `x-dead-letter-routing-key=<cola>.dlq`), su cola `<cola base>.dlq` y los *bindings*. Consume con `ackMode` manual: éxito → `ack`; `DuplicateKeyException` → `ack` (idempotencia); otro error → **hasta 3 intentos con espera exponencial de 500 ms**, luego `nack(requeue=false)` (pasa a la DLQ) |
| `ConsumidoresArranque` | Cada servicio consumidor declara un `@Bean ApplicationRunner` que suscribe su `ConsumidorEventos.consumir(...)` al arrancar; la suscripción **es la única `subscribe()` permitida** y es de ciclo de vida de la aplicación, no del *pipeline* de una petición |

Nombre de colas y DLQ de la spec: `notificaciones.cola` → `notificaciones.dlq`, `auditoria.cola` → `auditoria.dlq`, `citas.atenciones.cola` → `citas.atenciones.dlq`.

### 5.3 Cliente HTTP síncrono

| Clase | Descripción |
|---|---|
| `ClienteHttpFactory` | Método `WebClient crear(WebClient.Builder balanceado, String baseUrl)`. Cada servicio declara su `@Bean @LoadBalanced WebClient.Builder` y obtiene el cliente con `baseUrl = http://<nombre-servicio>` |
| `PoliticaResiliencia` | Utilidad con `<T> Mono<T> aplicar(Mono<T>)`: `.timeout(Duration.ofSeconds(2))` y `.retryWhen(Retry.fixedDelay(1, Duration.ofMillis(200)).filter(esErrorDeRed))`, donde `esErrorDeRed` = `WebClientRequestException`/`ConnectException`, **nunca** respuestas 4xx/5xx. Un `TimeoutException` no se reintenta |

Traducción de fallos de llamadas síncronas (la hace el cliente de cada servicio, no el controlador):

| Situación | `ErrorNegocio` |
|---|---|
| Respuesta `404` | el que defina cada spec (`PACIENTE_NO_ENCONTRADO`, `CITA_NO_ENCONTRADA`) |
| `5xx`, error de conexión o sin instancias en Eureka | `503`, `SERVICIO_<X>_NO_DISPONIBLE` |
| `TimeoutException` | `504`, mismo código `SERVICIO_<X>_NO_DISPONIBLE` |

## 6. Persistencia R2DBC

* Dependencias: `spring-boot-starter-data-r2dbc`, `org.postgresql:r2dbc-postgresql`.
* Entidades: clases con `@Table`, `@Id UUID id`. Como el id lo asigna la aplicación, la entidad **implementa `Persistable<UUID>`** con un campo `@Transient boolean nuevo` para que `save()` haga `INSERT` y no `UPDATE`. Se usa un constructor estático `Entidad.nueva(...)` y un método `marcarComoExistente()` en `@PersistenceCreator`.
* Esquema: archivo `src/main/resources/schema.sql` con `CREATE TABLE IF NOT EXISTS` y `CREATE [UNIQUE] INDEX IF NOT EXISTS`; se ejecuta al arrancar con `spring.sql.init.mode=always` (soportado para R2DBC por Boot). No se usa Flyway/Liquibase (requieren JDBC). El **DDL de cada servicio es el del documento §8**.
* Traducción de violaciones de unicidad: `DuplicateKeyException` (Spring la produce a partir del `SQLSTATE 23505`). El servicio la captura con `onErrorMap` y la convierte en su `ErrorNegocio` (`PACIENTE_DUPLICADO`, etc.). **La restricción de BD es la garantía; la consulta previa solo mejora el mensaje.**
* No hay transacciones multi-servicio. Dentro de un servicio, si una operación escribe en más de una tabla, se usa `@Transactional` (R2DBC lo soporta con `TransactionalOperator`/`@Transactional` en métodos que devuelven `Mono`).

## 7. Configuración y secretos

Todos los valores sensibles vienen de **variables de entorno**; el repositorio solo contiene `infra/.env.example`.

| Variable | Uso | Defecto en `application.yml` |
|---|---|---|
| `DB_HOST` | host de PostgreSQL | `localhost` |
| `DB_PORT` | puerto | `5433` (el Compose publica 5433 para no chocar con el PostgreSQL nativo de la máquina) |
| `<SVC>_DB_NAME`, `<SVC>_DB_USER`, `<SVC>_DB_PASSWORD` | `<SVC>` ∈ `PACIENTES`, `CITAS`, `ATENCIONES`, `PAGOS`, `NOTIFICACIONES`, `AUDITORIA` | nombre y usuario por defecto (`pacientes_db`/`pacientes_user`…); **la contraseña no tiene defecto** |
| `RABBIT_HOST`, `RABBIT_PORT` | RabbitMQ | `localhost`, `5672` |
| `RABBIT_USER`, `RABBIT_PASSWORD` | credenciales | usuario por defecto `hospital`; **contraseña sin defecto** |
| `EUREKA_URL` | `eureka.client.service-url.defaultZone` | `http://localhost:8761/eureka` |

* `application.yml` de cada servicio: `spring.application.name` (igual al nombre del módulo), `server.port` (del documento §3.2), `eureka.client...`, `spring.r2dbc.url=r2dbc:postgresql://${DB_HOST}:${DB_PORT}/${...}`, `hospital.rabbit.*`, `management.endpoints.web.exposure.include=health,info`.
* Un script `scripts/entorno-dev.sh` (en el repositorio, **sin** contraseñas reales) hace `source infra/.env` y exporta las variables para ejecutar los servicios localmente.
* Perfil `test`: `application-test.yml` con valores dummy; las pruebas de integración inyectan la URL de los contenedores por `@DynamicPropertySource`.

## 8. Registro de eventos (logs)

* `slf4j` (SLF4J + Logback por defecto). Nivel `INFO` para eventos de negocio (`cita confirmada id=… `), `ERROR` para fallos.
* **Prohibido** registrar: número de documento, nombres, apellidos, teléfono, correo, dirección. Se registran solo ids (UUID) y códigos.
* Los consumidores registran el `eventId` y el `tipo` de cada evento procesado.

## 9. Estrategia de pruebas (común)

| Nivel | Cómo | Notas |
|---|---|---|
| Dominio | JUnit 5 + AssertJ, sin Spring | Transiciones, horarios, validaciones |
| Servicio reactivo | `StepVerifier`, repositorios y clientes con Mockito | `Mono.error` y `Mono.empty` incluidos |
| Controlador | `@WebFluxTest(Controlador.class)` + `WebTestClient`, servicio mockeado, importando `ManejadorErrores` | Verifica códigos HTTP y forma de `ProblemDetail` |
| Repositorio / BD | `@DataR2dbcTest` + `PostgreSQLContainer` (`postgres:16`) con `@ServiceConnection` | Verifica restricciones, índices parciales, `schema.sql` |
| Mensajería | `GenericContainer`/`RabbitMQContainer` (`rabbitmq:3.13-management`) | Publicar y consumir, idempotencia, DLQ |
| Cliente síncrono | `MockWebServer` (OkHttp) respondiendo `200`/`404`/`500`/lento | Sin Eureka en pruebas (`eureka.client.enabled=false`, cliente sin `@LoadBalanced`) |

* Convención de nombres: `<Clase>Test` (unitarias), `<Clase>IT` (integración con contenedores). Surefire ejecuta `*Test`; **Failsafe** ejecuta `*IT` en `./mvnw verify`.
* Datos de prueba: documentos `0000xxxx`, correos `@example.com`, nombres genéricos.
* Cada servicio debe poder probarse **sin** que otros estén levantados.

## 10. CI

Definido en el documento §11. Esta spec añade:

* `./mvnw --batch-mode verify` debe ejecutar `*Test` (Surefire) y `*IT` (Failsafe).
* Sin secretos ni variables privadas: los contenedores de pruebas usan credenciales efímeras.
* Los reportes se suben como artefacto aunque falle el job.

## 11. Infraestructura local (`infra/`)

* `infra/docker-compose.yml`: servicios `postgres` (`postgres:16`, puerto host `5433`, volumen persistente, *healthcheck* `pg_isready`) y `rabbitmq` (`rabbitmq:3.13-management`, puertos `5672` y `15672`, *healthcheck* `rabbitmq-diagnostics ping`). Credenciales desde `infra/.env`.
* `infra/postgres/init/01-bases.sh`: crea, **para cada servicio**, un usuario y una base (`pacientes_db`/`pacientes_user`, `citas_db`/`citas_user`, `atenciones_db`/`atenciones_user`, `pagos_db`/`pagos_user`, `notificaciones_db`/`notificaciones_user`, `auditoria_db`/`auditoria_user`), con la contraseña tomada de variables de entorno; cada usuario es dueño **solo** de su base y se le revoca `CONNECT` en las demás.
* `infra/.env.example`: nombres de variables con valores de ejemplo (`cambiar-esto`), sin contraseñas reales.
* La topología de RabbitMQ no se define en `infra/`: la declaran los propios servicios al arrancar (idempotente) mediante `hospital-commons`.

## 12. Criterios de aceptación de esta spec

- [ ] `./mvnw -q -pl hospital-commons verify` compila y pasa pruebas del módulo.
- [ ] `ManejadorErrores` produce el formato de §4.1 para `ErrorNegocio`, validación y error interno (prueba con `WebTestClient`).
- [ ] `PublicadorRabbit` y `ConsumidorEventos` pasan una prueba de integración con RabbitMQ real: publicar → consumir → `ack`; fallo → 3 intentos → DLQ; `DuplicateKeyException` → `ack`.
- [ ] `PoliticaResiliencia` pasa pruebas de: éxito, timeout (≥ 2 s), reintento solo en error de red.
- [ ] `docker compose up` deja ambos servicios *healthy* y las 6 bases creadas, con aislamiento entre usuarios.

## 13. Decisiones abiertas para revisión

| # | Decisión tomada | Alternativa |
|---|---|---|
| D-1 | `reactor-rabbitmq` (100 % reactivo, sin `.block()` en consumidores) | `spring-boot-starter-amqp` + `@RabbitListener`: más simple pero obliga a bloquear en el consumidor |
| D-2 | Módulo `hospital-commons` con infraestructura técnica compartida | Duplicar el código en cada servicio: sin dependencia, pero 6 copias |
| D-3 | Si falla la publicación del evento, la operación HTTP **no** falla (se registra el error) | Fallar la operación: más consistente, pero la cita no se confirmaría por una caída de RabbitMQ |
| D-4 | Boot 3.5.16 / Cloud 2025.0.3 | Boot 4.1 / Cloud 2025.1.x |
| D-5 | Sin paginación en listados (tope 200) | Paginación con `limit`/`offset` |
