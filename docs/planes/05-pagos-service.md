# Plan de implementación 05 — `pagos-service`

> Ejecutor: agente implementador. Este plan es **autosuficiente**: no hace falta ver ninguna conversación previa.
> Fuentes (ya leídas al preparar el plan; consúltalas solo ante una duda puntual): `docs/specs/00-convenciones-comunes.md`, `docs/specs/05-pagos-service.md`, `docs/specs/02-pacientes-service.md` (contrato `GET /api/pacientes/{id}`), `docs/specs/03-citas-service.md` §4 (diseño de `PacientesClient`), `docs/specs/08-observabilidad.md`, `docs/documento-tecnico.md` §2.3, §5.6, §8.4, §9, §10, y el código real de `hospital-commons`.
> Alcance: **solo** el módulo `pagos-service/`. No modifiques `hospital-commons`, el `pom.xml` raíz ni otros módulos. No hagas commits.

## DUDAS CRUCIALES

Ninguna. Las ambigüedades encontradas son menores y se resolvieron abajo (§ Decisiones menores tomadas).

## Decisiones menores tomadas

| # | Tema | Decisión | Motivo |
|---|---|---|---|
| m-1 | Firma de registrar | `PagoService.registrar(String claveIdempotencia, PagoCrearRequest req)` devuelve `Mono<ResultadoRegistro>` (`record ResultadoRegistro(PagoResponse pago, boolean repetido)`) en vez de `Mono<PagoResponse>` | El controlador necesita saber si es repetición para poner `Idempotent-Replayed: true` |
| m-2 | Dónde se valida la cabecera y el monto | El **controlador** llama a `ClaveIdempotencia.validar(clave)` y `ReglasPago.validarMonto(req.monto())` (reglas puras del dominio, sin E/S) **antes** de llamar al servicio; el **servicio** las vuelve a llamar al inicio (defensa en profundidad, coste nulo) | La spec exige que `PagoControllerTest` (servicio mockeado) devuelva `MONTO_INVALIDO` con `monto: -10` y `CLAVE_IDEMPOTENCIA_REQUERIDA` sin cabecera. Es validación de entrada que reutiliza la regla de dominio; el controlador sigue sin reglas propias |
| m-3 | Orden dentro de `registrar` | 1) cabecera, 2) monto (ambas puras), 3) búsqueda por clave (idempotencia), 4) Pacientes, 5) secuencia, 6) `INSERT`, 7) evento | Spec §5.1 pide "de más barato a más caro"; validar el monto antes de leer la BD es más barato. Consecuencia documentada: una petición con monto inválido recibe `400` aunque la clave ya exista (no tuvo efecto, coherente con el borrador IETF) |
| m-4 | Repetición de un pago ya anulado | Misma clave + misma huella sobre un pago `ANULADO` → `201` con el pago (estado `ANULADO`) e `Idempotent-Replayed: true` | La repetición devuelve "el mismo pago", sea cual sea su estado actual |
| m-5 | Estado del paciente | Cualquier `estado` distinto de `"ACTIVO"` → `422 PACIENTE_INACTIVO` | Más seguro que comparar solo con `"INACTIVO"` |
| m-6 | `200` sin cuerpo de Pacientes | `switchIfEmpty` → `503 SERVICIO_PACIENTES_NO_DISPONIBLE` | Evita que un vacío siga el flujo sin paciente validado |
| m-7 | Fechas en respuesta y eventos | `fechaPago`/`fechaActualizacion` se devuelven convertidas a `America/Lima` (`-05:00`); en `datos` del evento `fechaPago` va como texto `ISO_OFFSET_DATE_TIME` en Lima | Convención spec 00 §4 (offset de Lima). PostgreSQL devuelve `TIMESTAMPTZ` en UTC |
| m-8 | Precisión de fechas | `Pago.registrar`/`anular` truncan `now` a microsegundos (`ChronoUnit.MICROS`) | PostgreSQL guarda microsegundos; así la respuesta del `POST` y un `GET` posterior coinciden |
| m-9 | Huella con `citaId` nulo | En la cadena de la huella, `null` se representa como `""` | Determinista y simple |
| m-10 | Normalización del monto en la huella | `stripTrailingZeros()` y, si la escala queda < 2, `setScale(2)`; luego `toPlainString()` (`80`, `80.0`, `80.000` → `"80.00"`; `80.001` → `"80.001"`) | Nunca lanza `ArithmeticException` aunque el monto sea inválido, y `80` y `80.00` dan la misma huella |
| m-11 | Consultas dinámicas | Clase `persistence/PagoConsultas` (con `R2dbcEntityTemplate` + `Criteria`) separada del servicio | Permite probar filtros y paginación en `PagoRepositoryIT` importando solo esa clase |
| m-12 | Secuencia | `persistence/SecuenciaOperacion.siguiente()` devuelve `Mono<Long>`; el **formato** (`NumeroOperacion.formatear`) lo aplica el servicio con el `Clock` | `PagoServiceTest` puede verificar el formato `OP-yyyyMMdd-NNNNNN` aunque la secuencia esté mockeada |
| m-13 | Construcción de eventos | Clase `messaging/EventosPago` (envuelve `Optional<PublicadorEventos>` + `Clock`, como `EventosPaciente` del plan 02); `datos` se arma con `JsonNodeFactory.instance.objectNode()` (no requiere `ObjectMapper`) | `PagoServiceTest` usa un `EventosPago` real con `PublicadorEventos` mockeado y captura el `EventoEnvelope` |
| m-14 | `tipo` del sobre | `tipo` = routing key (`"pago.registrado"`, `"pago.anulado"`) | Mismo criterio que las pruebas de `hospital-commons` |
| m-15 | `PagoFlujoIT` y Pacientes | Se reemplaza el `PacientesClient` con un `@TestConfiguration` que define un `@Bean @Primary PacientesClient` construido con `WebClient.builder()` **sin** `@LoadBalanced` apuntando a `MockWebServer` | Requisito: MockWebServer sin Eureka y sin `@LoadBalanced` |
| m-16 | Dependencia de prueba nueva | Se agrega `org.testcontainers:r2dbc` (scope `test`, versión del BOM) al `pom.xml` de `pagos-service` | **Verificado**: `@ServiceConnection` para R2DBC (`PostgresR2dbcContainerConnectionDetailsFactory`) usa `PostgreSQLR2DBCDatabaseContainer`, que implementa `org.testcontainers.r2dbc.R2DBCDatabaseContainer` (módulo `r2dbc`, hoy ausente) → sin él: `NoClassDefFoundError` |
| m-17 | Perfil de pruebas | `src/test/resources/application-test.yml` con valores dummy y `@ActiveProfiles("test")` en todas las pruebas con Spring | Spec 00 §7 |
| m-18 | Mensajes de error | Textos de la spec con tildes (fuentes en UTF-8). Los de `PoliticaResiliencia` (sin tildes) se dejan como los genera `hospital-commons` | |
| m-19 | Comando de verificación | `sg docker -c "./mvnw -q -pl pagos-service verify"` (sin `-am`; `hospital-commons` ya está instalado en `~/.m2`) en lugar del `-am` de la spec §10 | Instrucción del proyecto |

---

## 1. Resumen y orden de implementación

`pagos-service` registra y anula pagos simulados. Valida al paciente por HTTP (Pacientes vía Eureka), garantiza `monto > 0` (≤ 100 000.00, ≤ 2 decimales) con código `MONTO_INVALIDO`, genera el número de operación `OP-yyyyMMdd-NNNNNN` con una secuencia, hace el `POST` idempotente con la cabecera `Idempotency-Key` y publica `pago.registrado` / `pago.anulado`.

Pasos (cada uno deja el módulo compilando; ejecuta la verificación indicada antes de seguir). Todos los comandos se ejecutan desde `/home/cazabache/hospital-vida-salud`.

| Paso | Qué | Verificación |
|---|---|---|
| 0 | `pom.xml`: agregar `org.testcontainers:r2dbc` (test). Crear `src/test/resources/application-test.yml`. Revisar `application.yml` (ya está completo, no cambiar) | `./mvnw -q -pl pagos-service compile` |
| 1 | `domain/MedioPago`, `domain/EstadoPago` | compile |
| 2 | `domain/ReglasPago` + `ReglasPagoTest` | `./mvnw -q -pl pagos-service test` |
| 3 | `domain/ClaveIdempotencia` + `ClaveIdempotenciaTest` | test |
| 4 | `domain/HuellaSolicitud` + `HuellaSolicitudTest` | test |
| 5 | `domain/NumeroOperacion` + `NumeroOperacionTest` | test |
| 6 | `domain/DatosPago`, `domain/Pago` + `PagoTest` | test |
| 7 | `domain/FiltroPagos` | compile |
| 8 | `schema.sql`, `persistence/PagoRepository`, `persistence/SecuenciaOperacion`, `persistence/PagoConsultas` + `PagoRepositoryIT` | `sg docker -c "./mvnw -q -pl pagos-service verify"` |
| 9 | `client/PacienteResumen`, `client/PacientesClient`, `config/ClientesConfig` + `PacientesClientTest` | test |
| 10 | `messaging/EventosPago` | compile |
| 11 | `api/dto/PagoCrearRequest`, `AnularPagoRequest`, `PagoResponse` | compile |
| 12 | `service/ResultadoRegistro`, `service/PagoService` + `PagoServiceTest` | test |
| 13 | `api/PagoController` + `PagoControllerTest` | test |
| 14 | `PagoFlujoIT` | verify (docker) |
| 15 | Verificación final y revisiones de §7 | verify + greps |

---

## 2. Archivos exactos

Paquete base: `pe.edu.cibertec.vidasalud.pagos` (abreviado `…pagos`). Rutas relativas a `pagos-service/`.

### 2.0 Ya existen (no tocar salvo lo indicado)

* `src/main/java/…/pagos/PagosServiceApplication.java` — `@SpringBootApplication` sin más anotaciones. **No** agregar nada.
* `src/main/resources/application.yml` — ya tiene puerto 8086, `spring.r2dbc.*` con `PAGOS_DB_*`, `spring.sql.init.mode: always`, `hospital.rabbit.*`, `hospital.clientes.pacientes.url: http://pacientes-service`, Eureka, actuator `health,info,metrics` con `show-details: always`, patrón de log con `trace_id`/`span_id`, Swagger. **No cambiar.**
* `pom.xml` — agregar solo esto dentro de `<dependencies>` (junto a las otras de Testcontainers):

```xml
    <dependency>
      <groupId>org.testcontainers</groupId>
      <artifactId>r2dbc</artifactId>
      <scope>test</scope>
    </dependency>
```

  No agregar el driver JDBC de PostgreSQL (JDBC prohibido; `PostgreSQLContainer` 1.21.4 espera por log, no por JDBC — verificado). No agregar Lombok.

### 2.1 `src/test/resources/application-test.yml` (nuevo)

```yaml
spring:
  r2dbc:
    password: prueba            # dummy; @ServiceConnection lo reemplaza en las IT
hospital:
  rabbit:
    enabled: false              # PagoFlujoIT lo pone en true con @DynamicPropertySource
    password: prueba
eureka:
  client:
    enabled: false
```

> Importante: debe llamarse `application-test.yml` (perfil), **no** `application.yml`, porque un `application.yml` en `src/test/resources` ocultaría al de `main`.

### 2.2 `src/main/resources/schema.sql` (nuevo) — DDL del documento §8.4 con `IF NOT EXISTS`

```sql
CREATE TABLE IF NOT EXISTS pago (
  id                  UUID PRIMARY KEY,
  numero_operacion    VARCHAR(30)   NOT NULL UNIQUE,
  paciente_id         UUID          NOT NULL,
  cita_id             UUID,
  concepto            VARCHAR(120)  NOT NULL,
  monto               NUMERIC(12,2) NOT NULL CHECK (monto > 0 AND monto <= 100000),
  moneda              CHAR(3)       NOT NULL DEFAULT 'PEN' CHECK (moneda = 'PEN'),
  fecha_pago          TIMESTAMPTZ   NOT NULL DEFAULT now(),
  medio_pago          VARCHAR(15)   NOT NULL CHECK (medio_pago IN ('EFECTIVO','TARJETA','TRANSFERENCIA','BILLETERA')),
  estado              VARCHAR(10)   NOT NULL DEFAULT 'REGISTRADO' CHECK (estado IN ('REGISTRADO','ANULADO')),
  motivo_anulacion    VARCHAR(200),
  fecha_actualizacion TIMESTAMPTZ   NOT NULL DEFAULT now(),
  version             BIGINT        NOT NULL DEFAULT 0,
  clave_idempotencia  VARCHAR(64)   NOT NULL UNIQUE,
  huella_solicitud    CHAR(64)      NOT NULL
);
CREATE SEQUENCE IF NOT EXISTS pago_operacion_seq;
CREATE INDEX IF NOT EXISTS ix_pago_paciente ON pago (paciente_id);
CREATE INDEX IF NOT EXISTS ix_pago_fecha ON pago (fecha_pago);
```

Sin funciones PL/pgSQL → separador `;` por defecto (no tocar `spring.sql.init.separator`).

### 2.3 Dominio — `…pagos.domain` (sin Spring salvo `ErrorNegocio`/`HttpStatus` de commons)

**`MedioPago`** (enum): `EFECTIVO, TARJETA, TRANSFERENCIA, BILLETERA`.

**`EstadoPago`** (enum): `REGISTRADO, ANULADO`. Método `boolean esFinal()` → `this == ANULADO`.

**`ReglasPago`** (`final class`, constructor privado):

```java
public static final BigDecimal MAXIMO = new BigDecimal("100000.00");
public static void validarMonto(BigDecimal monto)
```

Orden de comprobación (exacto):
1. `monto == null` → `ErrorNegocio.validacion("monto", "no debe ser nulo")` (400 `VALIDACION_FALLIDA`; normalmente lo atrapa antes `@NotNull`).
2. `monto.signum() <= 0` → `new ErrorNegocio(BAD_REQUEST, "MONTO_INVALIDO", "El monto debe ser mayor que cero.")`.
3. `monto.stripTrailingZeros().scale() > 2` → `MONTO_INVALIDO`, `"El monto admite como máximo 2 decimales."`.
4. `monto.compareTo(MAXIMO) > 0` → `MONTO_INVALIDO`, `"El monto excede el máximo permitido (100000.00)."`.

Usa siempre `compareTo`/`signum`, **nunca** `equals` (en `BigDecimal`, `80.0` no es `equals` a `80.00`).

Además: `public static BigDecimal normalizar(BigDecimal monto)` → `monto.setScale(2, RoundingMode.UNNECESSARY)` (solo se llama tras `validarMonto`, así que nunca redondea; `80` → `80.00`, `80.000` → `80.00`).

**`ClaveIdempotencia`** (`final class`):

```java
public static final String CABECERA = "Idempotency-Key";
public static final String CABECERA_REPETIDA = "Idempotent-Replayed";
private static final Pattern PATRON = Pattern.compile("^[A-Za-z0-9-]{8,64}$");
public static String validar(String clave)   // devuelve la misma clave si es válida
```

`null` o sin coincidencia → `new ErrorNegocio(BAD_REQUEST, "CLAVE_IDEMPOTENCIA_REQUERIDA", "La cabecera Idempotency-Key es obligatoria (8–64 caracteres alfanuméricos o guiones).")`. No hacer `trim` (una clave con espacios es inválida).

**`HuellaSolicitud`** (`final class`):

```java
public static String calcular(UUID pacienteId, UUID citaId, String concepto, BigDecimal monto, MedioPago medioPago)
```

* Cadena: `texto(pacienteId) + "|" + texto(citaId) + "|" + (concepto == null ? "" : concepto.trim()) + "|" + montoCanonico(monto) + "|" + (medioPago == null ? "" : medioPago.name())`, donde `texto(null) = ""` y `texto(uuid) = uuid.toString()`.
* `montoCanonico`: `null` → `""`; si no, `BigDecimal n = monto.stripTrailingZeros(); if (n.scale() < 2) n = n.setScale(2); return n.toPlainString();` (m-10).
* `MessageDigest.getInstance("SHA-256")` sobre `getBytes(StandardCharsets.UTF_8)`; resultado `HexFormat.of().formatHex(bytes)` (64 caracteres hex en minúsculas). `NoSuchAlgorithmException` → envolver en `IllegalStateException` (SHA-256 siempre existe).

**`NumeroOperacion`** (`final class`):

```java
public static final String PREFIJO = "OP-";
public static String formatear(LocalDate fechaLima, long secuencia)
// → "OP-" + fechaLima.format(DateTimeFormatter.BASIC_ISO_DATE) + "-" + String.format("%06d", secuencia)
public static LocalDate fechaEnLima(Clock reloj)
// → LocalDate.ofInstant(reloj.instant(), RelojAutoConfiguration.ZONA_HOSPITAL)   (NO LocalDate.now(reloj): el Clock de una prueba puede estar en UTC)
```

`BASIC_ISO_DATE` da `20261012` (sin offset porque es `LocalDate`). Con más de 999 999 el número simplemente crece (`%06d` es ancho mínimo); cabe en `VARCHAR(30)`.

**`DatosPago`** (`record`): `UUID pacienteId, UUID citaId, String concepto, BigDecimal monto, MedioPago medioPago`.

**`FiltroPagos`** (`record`): `UUID pacienteId, UUID citaId, String numeroOperacion, EstadoPago estado, MedioPago medioPago, LocalDate fecha`. Todos opcionales (nulos = no filtran).

**`Pago`** — entidad de dominio y de persistencia a la vez (como en las specs 02/03):

```java
@Table("pago")
public class Pago implements Persistable<UUID> {
    @Id private final UUID id;
    @Column("numero_operacion") private final String numeroOperacion;
    @Column("paciente_id") private final UUID pacienteId;
    @Column("cita_id") private final UUID citaId;
    @Column("concepto") private final String concepto;
    @Column("monto") private final BigDecimal monto;
    @Column("moneda") private final String moneda;
    @Column("fecha_pago") private final OffsetDateTime fechaPago;
    @Column("medio_pago") private final MedioPago medioPago;
    @Column("estado") private EstadoPago estado;                          // mutable
    @Column("motivo_anulacion") private String motivoAnulacion;           // mutable
    @Column("fecha_actualizacion") private OffsetDateTime fechaActualizacion; // mutable
    @Version @Column("version") private Long version;                     // mutable: la asigna Spring Data
    @Column("clave_idempotencia") private final String claveIdempotencia;
    @Column("huella_solicitud") private final String huellaSolicitud;
    @Transient private boolean nuevo;   // org.springframework.data.annotation.Transient

    @PersistenceCreator
    Pago(UUID id, String numeroOperacion, UUID pacienteId, UUID citaId, String concepto, BigDecimal monto,
         String moneda, OffsetDateTime fechaPago, MedioPago medioPago, EstadoPago estado, String motivoAnulacion,
         OffsetDateTime fechaActualizacion, Long version, String claveIdempotencia, String huellaSolicitud) {
        // asigna todo; nuevo queda false (= marcarComoExistente, spec 00 §6)
    }

    public static Pago registrar(DatosPago datos, String numeroOperacion, String claveIdempotencia,
                                 String huellaSolicitud, Clock reloj)
    public void anular(String motivo, Clock reloj)
    public Pago marcarComoExistente()   // nuevo = false; return this  (mismo patrón que el plan 02)
    @Override public UUID getId()
    @Override public boolean isNew()   // return nuevo;
    // getters públicos de todos los campos (sin setters)
}
```

Reglas de implementación:
* Los campos que cambian (`estado`, `motivoAnulacion`, `fechaActualizacion`) y **`version` NO son `final`**: Spring Data asigna `version` tras el `INSERT`/`UPDATE` por acceso a campo; si fuera `final` habría que implementar un *wither*. El resto puede ser `final` (el `id` lo asigna la aplicación y Spring Data no lo toca). Mismo criterio que la entidad `Paciente` del plan 02.
* Imports: `org.springframework.data.annotation.{Id, Version, Transient, PersistenceCreator}`, `org.springframework.data.domain.Persistable`, `org.springframework.data.relational.core.mapping.{Table, Column}`. **Ojo:** `Transient` de Spring Data, no `jakarta.persistence` ni `java.beans`.
* `registrar`: llama `ReglasPago.validarMonto(datos.monto())`; `id = UUID.randomUUID()`; `concepto = datos.concepto().trim()`; `monto = ReglasPago.normalizar(...)`; `moneda = "PEN"`; `ahora = OffsetDateTime.now(reloj).truncatedTo(ChronoUnit.MICROS)`; `fechaPago = fechaActualizacion = ahora`; `estado = REGISTRADO`; `version = null` (el `INSERT` de Spring Data la pone en `0`); `nuevo = true`.
* `anular`: si `estado == ANULADO` → `new ErrorNegocio(CONFLICT, "PAGO_YA_ANULADO", "El pago ya fue anulado.")`; si no: `estado = ANULADO`, `motivoAnulacion = motivo.trim()`, `fechaActualizacion = now truncado a MICROS`. El número de operación **no cambia**.

### 2.4 Persistencia — `…pagos.persistence`

**`PagoRepository`**: `public interface PagoRepository extends ReactiveCrudRepository<Pago, UUID>` con `Mono<Pago> findByClaveIdempotencia(String claveIdempotencia);`

**`SecuenciaOperacion`** (`@Component`):

```java
private final DatabaseClient db;   // org.springframework.r2dbc.core.DatabaseClient
public Mono<Long> siguiente() {
    return db.sql("SELECT nextval('pago_operacion_seq')")
             .map(fila -> fila.get(0, Long.class))
             .one();
}
```

(`nextval` devuelve `bigint` → `Long`.) Cada llamada consume un número aunque el `INSERT` falle después: habrá huecos (aceptado; la unicidad, no la continuidad, es lo exigido).

**`PagoConsultas`** (`@Component`, consultas dinámicas, spec 00 §4.2):

```java
static final Sort ORDEN = Sort.by(Sort.Order.desc("fechaPago"), Sort.Order.asc("id"));
private final R2dbcEntityTemplate template;
public Flux<Pago> buscar(FiltroPagos filtro, ParametrosPagina pagina) {
    return template.select(Pago.class)
            .matching(Query.query(criterio(filtro)).with(pagina.aPageable(ORDEN)))
            .all();
}
public Mono<Long> contar(FiltroPagos filtro) {
    return template.count(Query.query(criterio(filtro)), Pago.class);
}
static Criteria criterio(FiltroPagos f) {
    Criteria c = Criteria.empty();
    if (f.pacienteId() != null)      c = c.and("pacienteId").is(f.pacienteId());
    if (f.citaId() != null)          c = c.and("citaId").is(f.citaId());
    if (f.numeroOperacion() != null) c = c.and("numeroOperacion").is(f.numeroOperacion().trim());
    if (f.estado() != null)          c = c.and("estado").is(f.estado().name());
    if (f.medioPago() != null)       c = c.and("medioPago").is(f.medioPago().name());
    if (f.fecha() != null) {
        OffsetDateTime inicio = f.fecha().atStartOfDay(ZONA_HOSPITAL).toOffsetDateTime();
        OffsetDateTime fin = f.fecha().plusDays(1).atStartOfDay(ZONA_HOSPITAL).toOffsetDateTime();
        c = c.and("fechaPago").greaterThanOrEquals(inicio).and("fechaPago").lessThan(fin);
    }
    return c;
}
```

Clases: `org.springframework.data.relational.core.query.{Criteria, Query}`, `org.springframework.data.r2dbc.core.R2dbcEntityTemplate`, `org.springframework.data.domain.Sort`. Se usan **nombres de propiedad** (el `QueryMapper` los traduce a columnas, también en el `Sort`). Los enums se pasan con `.name()` (columna `VARCHAR`).

### 2.5 Cliente — `…pagos.client`

**`PacienteResumen`**: `@JsonIgnoreProperties(ignoreUnknown = true) public record PacienteResumen(UUID id, String estado)`. Solo depende de `id` y `estado` del `PacienteResponse` de la spec 02 §5.1.

**`PacientesClient`** (clase normal, **no** `@Component`; la crea `ClientesConfig`):

```java
public class PacientesClient {
    static final String CODIGO_NO_DISPONIBLE = "SERVICIO_PACIENTES_NO_DISPONIBLE";
    private final WebClient webClient;
    public PacientesClient(WebClient webClient) { this.webClient = webClient; }

    public Mono<PacienteResumen> obtener(UUID pacienteId) {
        Mono<PacienteResumen> llamada = webClient.get()
                .uri("/api/pacientes/{id}", pacienteId)
                .accept(MediaType.APPLICATION_JSON)
                .retrieve()
                .onStatus(s -> s.value() == 404, r -> r.releaseBody().then(Mono.error(
                        new ErrorNegocio(HttpStatus.NOT_FOUND, "PACIENTE_NO_ENCONTRADO",
                                "No existe el paciente " + pacienteId + "."))))
                .bodyToMono(PacienteResumen.class);
        return PoliticaResiliencia.proteger(llamada, CODIGO_NO_DISPONIBLE, "pacientes")
                .switchIfEmpty(Mono.error(new ErrorNegocio(HttpStatus.SERVICE_UNAVAILABLE, CODIGO_NO_DISPONIBLE,
                        "El servicio de pacientes no esta disponible. Intente mas tarde.")));
    }
}
```

`PoliticaResiliencia.proteger` (commons, verificado) ya hace: timeout 2 s → `504`; 5xx y errores de red → `503` (ambos con el código dado); 1 reintento a los 200 ms **solo** ante `WebClientRequestException`/`ConnectException`; los `ErrorNegocio` (el 404 traducido) pasan sin cambios y no se reintentan. "Sin instancias en Eureka": el `LoadBalancer` responde `503` → 5xx → `503`. El cliente **no** decide `INACTIVO` (lo hace el servicio).

### 2.6 Configuración — `…pagos.config`

**`ClientesConfig`** (`@Configuration(proxyBeanMethods = false)`):

```java
@Bean
@LoadBalanced                       // org.springframework.cloud.client.loadbalancer.LoadBalanced
WebClient.Builder webClientBalanceado() { return WebClient.builder(); }

@Bean
PacientesClient pacientesClient(@LoadBalanced WebClient.Builder balanceado,
                                @Value("${hospital.clientes.pacientes.url}") String url) {
    return new PacientesClient(ClienteHttpFactory.crear(balanceado, url));
}
```

`ClienteHttpFactory.crear` hace `clone().baseUrl(url).build()` (commons). Al declarar este `WebClient.Builder`, Boot no crea el suyo (`@ConditionalOnMissingBean`); nada más en el servicio lo necesita.

### 2.7 Mensajería — `…pagos.messaging`

**`EventosPago`** (`@Component`), constructor `(Optional<PublicadorEventos> publicador, Clock reloj)` (mismo patrón que `EventosPaciente` del plan 02: si `hospital.rabbit.enabled=false` no hay publicador y no publica; nunca declarar un `PublicadorEventos` de respaldo en el servicio). Método privado común `publicar(String routingKey, EventoEnvelope e)`: si `publicador` está vacío → `Mono.empty()`; si no → `publicador.get().publicar(routingKey, e).onErrorResume(ex -> { log.warn("no se publico {} eventId={}: {}", routingKey, e.eventId(), ex.toString()); return Mono.empty(); })`.

```java
static final String ORIGEN = "pagos-service";
static final String TIPO_RECURSO = "PAGO";
public Mono<Void> registrado(Pago p)   // publicar(PAGO_REGISTRADO, EventoEnvelope.nuevo(reloj, PAGO_REGISTRADO, ORIGEN, TIPO_RECURSO, p.getId(), null, "REGISTRADO", datos(p)))
public Mono<Void> anulado(Pago p)      // igual con PAGO_ANULADO, "REGISTRADO" → "ANULADO", datos(p) + "motivo"
static ObjectNode datos(Pago p)
```

`datos` con `JsonNodeFactory.instance.objectNode()`: `pagoId` (texto), `pacienteId` (texto), `citaId` (texto o `null` → `put(String, (String) null)` genera `NullNode`), `numeroOperacion`, `concepto`, `monto` (`put("monto", p.getMonto())` → número `DecimalNode`, conserva `80.00`; verificado en Jackson 2.21), `moneda`, `medioPago` (`name()`), `fechaPago` (`DateTimeFormatter.ISO_OFFSET_DATE_TIME.format(p.getFechaPago().atZoneSameInstant(ZONA_HOSPITAL).toOffsetDateTime())`). **Sin** datos personales del paciente. Constantes de routing key: `TopologiaHospital.PAGO_REGISTRADO` / `PAGO_ANULADO`. `PublicadorEventos.publicar` nunca falla (commons, D-3).

### 2.8 API — DTOs `…pagos.api.dto` (`record`)

```java
public record PagoCrearRequest(
        @NotNull UUID pacienteId,
        UUID citaId,
        @NotBlank @Size(max = 120) String concepto,
        @NotNull BigDecimal monto,          // SOLO @NotNull: nada de @Positive/@DecimalMin/@DecimalMax/@Digits
        @NotNull MedioPago medioPago) { }

public record AnularPagoRequest(@NotBlank @Size(max = 200) String motivo) { }

public record PagoResponse(UUID id, String numeroOperacion, UUID pacienteId, UUID citaId, String concepto,
                           BigDecimal monto, String moneda, OffsetDateTime fechaPago, MedioPago medioPago,
                           EstadoPago estado, String motivoAnulacion, OffsetDateTime fechaActualizacion) {
    public static PagoResponse desde(Pago p)   // fechas → atZoneSameInstant(ZONA_HOSPITAL).toOffsetDateTime(); moneda.trim()
}
```

`monto` se serializa con su escala (`80.00`) porque siempre llega con escala 2 (normalizado en el dominio o leído de `NUMERIC(12,2)`).

### 2.9 Servicio — `…pagos.service`

**`ResultadoRegistro`**: `public record ResultadoRegistro(PagoResponse pago, boolean repetido)`.

**`PagoService`** (`@Service`), constructor `(PagoRepository repositorio, PagoConsultas consultas, SecuenciaOperacion secuencia, PacientesClient pacientes, EventosPago eventos, Clock reloj)`. Logger SLF4J.

Firmas:

```java
public Mono<ResultadoRegistro> registrar(String claveIdempotencia, PagoCrearRequest req)
public Mono<PagoResponse> obtener(UUID id)
public Flux<PagoResponse> listar(FiltroPagos filtro, ParametrosPagina pagina)
public Mono<Long> contar(FiltroPagos filtro)
public Mono<PagoResponse> anular(UUID id, AnularPagoRequest req)
```

**Flujo exacto de `registrar`** (todo dentro de `Mono.defer` para que las excepciones síncronas de las reglas puras se conviertan en `Mono.error`):

```java
public Mono<ResultadoRegistro> registrar(String clave, PagoCrearRequest req) {
    return Mono.defer(() -> {
        ClaveIdempotencia.validar(clave);                       // 400 CLAVE_IDEMPOTENCIA_REQUERIDA
        ReglasPago.validarMonto(req.monto());                   // 400 MONTO_INVALIDO (sin E/S)
        String huella = HuellaSolicitud.calcular(req.pacienteId(), req.citaId(), req.concepto(),
                req.monto(), req.medioPago());
        return repositorio.findByClaveIdempotencia(clave)
                .map(existente -> repeticion(existente, huella))        // 201 repetido o 422
                .switchIfEmpty(Mono.defer(() -> registrarNuevo(clave, huella, req)));
    });
}

private Mono<ResultadoRegistro> registrarNuevo(String clave, String huella, PagoCrearRequest req) {
    return pacientes.obtener(req.pacienteId())                 // 404 / 503 / 504
            .flatMap(p -> "ACTIVO".equals(p.estado())
                    ? secuencia.siguiente()
                    : Mono.error(new ErrorNegocio(HttpStatus.UNPROCESSABLE_ENTITY, "PACIENTE_INACTIVO",
                            "El paciente está inactivo y no puede registrar pagos.")))
            .map(n -> Pago.registrar(
                    new DatosPago(req.pacienteId(), req.citaId(), req.concepto(), req.monto(), req.medioPago()),
                    NumeroOperacion.formatear(NumeroOperacion.fechaEnLima(reloj), n),
                    clave, huella, reloj))
            .flatMap(pago -> repositorio.save(pago)
                    .map(Pago::marcarComoExistente)
                    .flatMap(guardado -> eventos.registrado(guardado)
                            .thenReturn(new ResultadoRegistro(PagoResponse.desde(guardado), false)))
                    .doOnNext(r -> log.info("pago registrado id={} numeroOperacion={}",
                            r.pago().id(), r.pago().numeroOperacion()))
                    .onErrorResume(DuplicateKeyException.class, e -> resolverDuplicado(clave, huella)));
}

private Mono<ResultadoRegistro> resolverDuplicado(String clave, String huella) {
    return repositorio.findByClaveIdempotencia(clave)
            .map(existente -> repeticion(existente, huella))   // carrera con la misma clave
            .switchIfEmpty(Mono.error(new ErrorNegocio(HttpStatus.CONFLICT, "OPERACION_DUPLICADA",
                    "El número de operación generado ya existe; intente nuevamente.")));
}

private ResultadoRegistro repeticion(Pago existente, String huella) {
    if (!existente.getHuellaSolicitud().equals(huella)) {
        throw new ErrorNegocio(HttpStatus.UNPROCESSABLE_ENTITY, "CLAVE_IDEMPOTENCIA_REUTILIZADA",
                "La clave de idempotencia ya se usó con datos diferentes.");
    }
    log.info("pago repetido por idempotencia id={}", existente.getId());
    return new ResultadoRegistro(PagoResponse.desde(existente), true);
}
```

Puntos clave del flujo:
* **Cómo se distingue qué `UNIQUE` se violó:** no se analiza el mensaje del driver. Tras `DuplicateKeyException` se **relee por `clave_idempotencia`**: si existe una fila con esa clave, la violación fue de la clave (petición simultánea) → regla de repetición (misma huella → repetido, sin evento; distinta → `422`); si no existe, la violación fue de `numero_operacion` → `409 OPERACION_DUPLICADA`. (El `id` es UUID aleatorio: colisión despreciable.) Esto funciona porque cada `INSERT` es auto-commit: cuando la segunda inserción falla por la clave, la primera ya está confirmada y visible.
* `DuplicateKeyException` la produce Spring (`ConnectionFactoryUtils.convertR2dbcException` con SQLSTATE `23505`, verificado en spring-r2dbc 6.2.19). Una violación de `CHECK` (`23514`) llega como `DataIntegrityViolationException` (no se captura: no debería ocurrir tras `ReglasPago`; daría `500`).
* El `onErrorResume` envuelve el `save` y la publicación; la publicación nunca emite error (commons), así que solo atrapa el error del `save`.
* En una repetición **no** se llama a Pacientes, ni a la secuencia, ni se guarda, ni se publica.
* Solo las claves de pagos **creados** quedan guardadas: si falla la validación o Pacientes, no hay fila y el reintento con la misma clave se ejecuta normalmente.

**`obtener`**: `repositorio.findById(id).map(PagoResponse::desde).switchIfEmpty(Mono.error(new ErrorNegocio(NOT_FOUND, "PAGO_NO_ENCONTRADO", "No existe el pago " + id + ".")))`.

**`listar` / `contar`**: delegan en `consultas.buscar(...).map(PagoResponse::desde)` y `consultas.contar(...)`.

**`anular`**:

```java
return repositorio.findById(id)
        .switchIfEmpty(Mono.error(noEncontrado(id)))
        .flatMap(pago -> {
            pago.anular(req.motivo(), reloj);                 // 409 PAGO_YA_ANULADO (lanza dentro de flatMap → Mono.error)
            return repositorio.save(pago);                    // UPDATE ... WHERE id=? AND version=?
        })
        .onErrorMap(OptimisticLockingFailureException.class, e -> new ErrorNegocio(HttpStatus.CONFLICT,
                "CONFLICTO_CONCURRENTE", "El pago fue modificado por otra operación; vuelva a consultarlo."))
        .flatMap(guardado -> eventos.anulado(guardado).thenReturn(PagoResponse.desde(guardado)))
        .doOnNext(r -> log.info("pago anulado id={}", r.id()));
```

El `onErrorMap` va **antes** del `flatMap` de publicación. El pago leído tiene `nuevo=false` → `save` hace `UPDATE` con control de versión (R2DBC lanza `OptimisticLockingFailureException` si actualiza 0 filas). Sin plazo para anular (D-31).

**Logs**: solo ids (UUID), `numeroOperacion` y códigos. **Nunca** `concepto`, nombres, documento ni el cuerpo de la petición.

### 2.10 Controlador — `…pagos.api.PagoController`

`@RestController @RequestMapping("/api/pagos")`, constructor `(PagoService servicio)`.

```java
@PostMapping
@Operation(summary = "Registra un pago simulado (requiere cabecera Idempotency-Key)")
public Mono<ResponseEntity<PagoResponse>> registrar(
        @RequestHeader(name = ClaveIdempotencia.CABECERA, required = false) String clave,
        @Valid @RequestBody PagoCrearRequest req) {
    return Mono.defer(() -> {
                ClaveIdempotencia.validar(clave);
                ReglasPago.validarMonto(req.monto());
                return servicio.registrar(clave, req);
            })
            .map(r -> {
                ResponseEntity.BodyBuilder b = ResponseEntity.created(URI.create("/api/pagos/" + r.pago().id()));
                if (r.repetido()) {
                    b.header(ClaveIdempotencia.CABECERA_REPETIDA, "true");
                }
                return b.body(r.pago());
            });
}

@GetMapping("/{id}")
public Mono<PagoResponse> obtener(@PathVariable UUID id)

@GetMapping
public Mono<ResponseEntity<Flux<PagoResponse>>> listar(
        @RequestParam(required = false) UUID pacienteId,
        @RequestParam(required = false) UUID citaId,
        @RequestParam(required = false) String numeroOperacion,
        @RequestParam(required = false) EstadoPago estado,
        @RequestParam(required = false) MedioPago medioPago,
        @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate fecha,
        @RequestParam(required = false) Integer pagina,
        @RequestParam(required = false) Integer tamano)
// Mono.defer(() -> { ParametrosPagina p = ParametrosPagina.de(pagina, tamano); FiltroPagos f = new FiltroPagos(...);
//                    return RespuestaPaginada.de(servicio.listar(f, p), servicio.contar(f)); })

@PatchMapping("/{id}/anular")
public Mono<PagoResponse> anular(@PathVariable UUID id, @Valid @RequestBody AnularPagoRequest req)
```

* `required = false` es **obligatorio** en `@RequestHeader`: con `required = true` WebFlux lanza `MissingRequestValueException` (subclase de `ServerWebInputException`) y `ManejadorErrores` respondería `400 VALIDACION_FALLIDA` genérico, no `CLAVE_IDEMPOTENCIA_REQUERIDA`.
* Si el cuerpo es inválido **y** falta la cabecera, gana `VALIDACION_FALLIDA` (`@Valid` se resuelve antes de entrar al método). Aceptado.
* La repetición responde `201` (no `200`) con `Location` y `Idempotent-Replayed: true`; el `201` normal **no** lleva esa cabecera.
* `medioPago` desconocido o JSON ilegible → `ServerWebInputException` → `400 VALIDACION_FALLIDA` (lo hace `ManejadorErrores`). `id` no UUID → `400`.
* El manejo de errores es el de `hospital-commons` (`ManejadorErrores`, autoconfigurado). No crear otro `@RestControllerAdvice`.

---

## 3. Decisiones técnicas verificadas (Boot 3.5.16, Spring Data R2DBC 3.5.13, spring-r2dbc 6.2.19, r2dbc-postgresql 1.0.9, Jackson 2.21.4, Testcontainers 1.21.4, okhttp/mockwebserver 4.12.0)

1. **`NUMERIC(12,2)` ↔ `BigDecimal`**: r2dbc-postgresql lee `NUMERIC` como `BigDecimal` con la escala de la columna (`80.00`). Se escribe `BigDecimal` ya normalizado a escala 2. Comparar siempre con `compareTo`.
2. **Validación de monto**: en `ReglasPago` (dominio), invocada por controlador y servicio. La anotación es solo `@NotNull`. Si se agregara `@Positive`/`@DecimalMin`/`@Digits`, el `400` saldría con `VALIDACION_FALLIDA` y fallaría el requisito `MONTO_INVALIDO`.
3. **`Persistable<UUID>` + `@Version Long`**: `isNew()` devuelve el `@Transient nuevo` (true solo en `Pago.registrar`). En el `INSERT`, `R2dbcEntityTemplate.setVersionIfNecessary` pone `version = 0` (tipo envoltorio → `0L`; verificado). En el `UPDATE` añade `WHERE version = ?`, incrementa y, si no actualiza filas, lanza `OptimisticLockingFailureException`.
4. **Enums**: Spring Data R2DBC los guarda como `name()` (texto) y los relee al enum; compatible con los `CHECK ... IN (...)`.
5. **`CHAR(3)`/`CHAR(64)`**: los valores tienen exactamente 3 y 64 caracteres; no hay relleno. Aun así `PagoResponse.desde` hace `moneda.trim()`.
6. **Secuencia**: `DatabaseClient.sql("SELECT nextval('pago_operacion_seq')").map(fila -> fila.get(0, Long.class)).one()`; formato en el servicio con `NumeroOperacion.formatear(fechaEnLima(reloj), n)` → `OP-20261012-000001`.
7. **Bean `Clock`**: lo aporta `RelojAutoConfiguration` de commons (`Clock.system(America/Lima)`, `@ConditionalOnMissingBean`). No declarar otro en `main`.
8. **Cabeceras**: `Idempotency-Key` (entrada, `required=false`), `Idempotent-Replayed: true` (salida solo en repetición), `Location: /api/pagos/{id}` en ambos `201`.
9. **Cliente Pacientes**: `@LoadBalanced WebClient.Builder` + `ClienteHttpFactory.crear(builder, "http://pacientes-service")`; `onStatus(404)` → `PACIENTE_NO_ENCONTRADO`; `PoliticaResiliencia.proteger(…, "SERVICIO_PACIENTES_NO_DISPONIBLE", "pacientes")`.
10. **Consultas dinámicas**: `R2dbcEntityTemplate` + `Criteria` (solo las condiciones con parámetro), `Query.with(PageRequest)` vía `ParametrosPagina.aPageable(ORDEN)`, total con `template.count`. Orden estable `fechaPago DESC, id ASC`. Filtro `fecha` = día en Lima `[00:00, 24:00)`.
11. **Paginación HTTP**: `RespuestaPaginada.de(flux, total)` (cabecera `X-Total-Count`); parámetros inválidos → `ParametrosPagina.de` lanza `ErrorNegocio` `400 VALIDACION_FALLIDA`.
12. **Eventos**: `EventoEnvelope.nuevo(reloj, tipo, "pagos-service", "PAGO", id, anterior, nuevo, datos)`; `PublicadorEventos` solo existe si `hospital.rabbit.enabled=true` (por defecto sí).
13. **`ManejadorErrores`** (commons) cubre `ErrorNegocio`, `WebExchangeBindException`, `HandlerMethodValidationException`, `ServerWebInputException`, `ResponseStatusException` y `Throwable`. **No** cubre `DuplicateKeyException` ni `OptimisticLockingFailureException`: el servicio las traduce.

---

## 4. Pruebas

Ubicación: `src/test/java/pe/edu/cibertec/vidasalud/pagos/<paquete>/`. Surefire ejecuta `*Test`; Failsafe ejecuta `*IT` (configurado en el pom raíz). AssertJ + JUnit 5; `StepVerifier` para flujos. Datos de prueba ficticios (UUID aleatorios, conceptos genéricos). En las pruebas sí se permite `.block()`.

Reloj fijo de referencia: `Clock.fixed(Instant.parse("2026-10-12T14:00:00Z"), ZoneId.of("America/Lima"))` (= 09:00 del 12-10-2026 en Lima).

### 4.1 Dominio (JUnit puro, sin Spring)

**`domain/ReglasPagoTest`** — `@ParameterizedTest` con `@CsvSource`:

| Monto | Resultado |
|---|---|
| `0` | `MONTO_INVALIDO`, "El monto debe ser mayor que cero." |
| `-10` | `MONTO_INVALIDO`, mayor que cero |
| `-50.00` | `MONTO_INVALIDO`, mayor que cero |
| `0.001` | `MONTO_INVALIDO`, "El monto admite como máximo 2 decimales." |
| `100000.01` | `MONTO_INVALIDO`, "El monto excede el máximo permitido (100000.00)." |
| `0.01`, `80`, `80.00`, `80.000`, `100000.00` | sin excepción |

Más: `null` → `VALIDACION_FALLIDA`; el `status` es `400` en todos los inválidos; `normalizar(new BigDecimal("80"))` tiene `scale() == 2` y `toPlainString()` = `"80.00"`.

**`domain/ClaveIdempotenciaTest`**: válida (UUID con guiones; 8 caracteres; 64 caracteres) → devuelve la clave; `null`, `""`, 7 caracteres, 65 caracteres, con espacio, con `_` → `400 CLAVE_IDEMPOTENCIA_REQUERIDA`.

**`domain/HuellaSolicitudTest`**: mismo cuerpo → misma huella; `80` y `80.00` → misma huella; `concepto` con espacios alrededor → misma huella; cambiar monto (`80.00` vs `80.01`) → distinta; cambiar `pacienteId` → distinta; cambiar `medioPago` → distinta; `citaId` nulo vs presente → distinta; la huella tiene 64 caracteres `[0-9a-f]`; un monto inválido (`0.001`) no lanza excepción.

**`domain/NumeroOperacionTest`**: `formatear(2026-10-12, 1)` = `"OP-20261012-000001"`; `formatear(…, 1234567)` = `"OP-20261012-1234567"`; `fechaEnLima` con `Clock.fixed(Instant.parse("2026-10-13T03:00:00Z"), ZoneOffset.UTC)` = `2026-10-12` (en Lima aún es 12, aunque en UTC sea 13).

**`domain/PagoTest`**: `registrar` deja `REGISTRADO`, `moneda "PEN"`, `fechaPago` y `fechaActualizacion` = `now` del reloj fijo, el número recibido, `monto` con escala 2, `concepto` recortado, `isNew() == true`, `version == null`; `marcarComoExistente()` deja `isNew() == false`; `registrar` con monto `0` → `MONTO_INVALIDO`; `anular` deja `ANULADO` con motivo recortado y conserva el número; `anular` dos veces → `409 PAGO_YA_ANULADO`.

### 4.2 Cliente — `client/PacientesClientTest` (MockWebServer, sin Spring, sin Eureka, sin `@LoadBalanced`)

`@BeforeEach`: `servidor = new MockWebServer(); servidor.start(); cliente = new PacientesClient(WebClient.builder().baseUrl(servidor.url("/").toString()).build());` — `@AfterEach servidor.shutdown()`.

| Caso | Montaje | Esperado |
|---|---|---|
| 200 ACTIVO | `MockResponse` `200`, `Content-Type: application/json`, cuerpo `{"id":"<uuid>","estado":"ACTIVO","nombres":"Ana","tipoDocumento":"DNI"}` | `PacienteResumen` con id y `ACTIVO` (campos extra ignorados); la petición fue `GET /api/pacientes/<uuid>` (`takeRequest().getPath()`) |
| 200 INACTIVO | igual con `INACTIVO` | devuelve el resumen (el cliente no decide) |
| 404 | `setResponseCode(404)` | `ErrorNegocio` 404 `PACIENTE_NO_ENCONTRADO`; `getRequestCount() == 1` (no reintenta) |
| 500 | `setResponseCode(500)` | `ErrorNegocio` 503 `SERVICIO_PACIENTES_NO_DISPONIBLE`; 1 petición |
| Lento | `200` con `setHeadersDelay(3, TimeUnit.SECONDS)` | `ErrorNegocio` 504 `SERVICIO_PACIENTES_NO_DISPONIBLE` (tarda ~2 s); 1 petición |
| Conexión rechazada | guardar la URL, `servidor.shutdown()`, crear un cliente con `WebClient.builder().baseUrl(url).filter((req, next) -> { intentos.incrementAndGet(); return next.exchange(req); })`; al final reasignar `servidor = new MockWebServer()` para que `@AfterEach` no falle | `503 SERVICIO_PACIENTES_NO_DISPONIBLE` y `intentos == 2` (inicial + 1 reintento) |

### 4.3 Servicio — `service/PagoServiceTest` (`@ExtendWith(MockitoExtension.class)`, `StepVerifier`)

Mocks: `PagoRepository`, `PagoConsultas`, `SecuenciaOperacion`, `PacientesClient`, `PublicadorEventos`. Reales: `new EventosPago(Optional.of(publicadorMock), relojFijo)` y `PagoService(…, relojFijo)`. `when(publicador.publicar(anyString(), any())).thenReturn(Mono.empty())` solo en las pruebas que publican (con `MockitoExtension` los *stubs* no usados fallan: `UnnecessaryStubbingException`; usa `lenient()` solo si hace falta). `save` responde con su argumento: `when(repositorio.save(any(Pago.class))).thenAnswer(inv -> Mono.just(inv.getArgument(0)))`.

Casos:
1. **Registro OK**: clave nueva (`findByClaveIdempotencia` → `Mono.empty()`), paciente `ACTIVO`, secuencia `1L` → `repetido=false`, `numeroOperacion = "OP-20261012-000001"` y cumple `^OP-\d{8}-\d{6}$`, `estado REGISTRADO`, `monto` `80.00`; `ArgumentCaptor<EventoEnvelope>` con routing key `pago.registrado`: `tipo "pago.registrado"`, `origen "pagos-service"`, `tipoRecurso "PAGO"`, `recursoId` = id, `estadoAnterior null`, `estadoNuevo "REGISTRADO"`, `datos.monto.decimalValue()` compara igual a `80.00`, `datos.numeroOperacion`, `datos.pacienteId`; `datos` no tiene campos de nombre/documento/correo.
2. **Monto 0** → `400 MONTO_INVALIDO`; `verifyNoInteractions(pacientes, secuencia, publicador)` y `verify(repositorio, never()).save(any())` y tampoco `findByClaveIdempotencia`.
3. **Paciente INACTIVO** → `422 PACIENTE_INACTIVO`; no llama a la secuencia, no guarda, no publica.
4. **Paciente inexistente** (`pacientes.obtener` → `Mono.error(ErrorNegocio 404 PACIENTE_NO_ENCONTRADO)`) → se propaga 404; no guarda.
5. **Formato del número**: secuencia `42L` → `"OP-20261012-000042"`.
6. **`DuplicateKeyException` por número** (`save` → `Mono.error(new DuplicateKeyException("dup"))`, `findByClaveIdempotencia` → `Mono.empty()` las dos veces) → `409 OPERACION_DUPLICADA`; no publica.
7. **`DuplicateKeyException` por clave (carrera)**: `findByClaveIdempotencia` → `thenReturn(Mono.empty(), Mono.just(existente))` donde `existente` es un `Pago.registrar(...)` con la **misma** huella → `repetido=true`, mismo id que `existente`; `publicador` sin interacciones.
8. **Carrera con huella distinta** (igual que 7 pero `existente` con otro monto) → `422 CLAVE_IDEMPOTENCIA_REUTILIZADA`.
9. **Misma clave y mismo cuerpo** (la primera búsqueda ya devuelve `existente` con la misma huella) → `repetido=true`, el mismo pago; `verify(repositorio, never()).save(any())`, `verifyNoInteractions(pacientes, secuencia, publicador)`.
10. **Misma clave, otro monto** → `422 CLAVE_IDEMPOTENCIA_REUTILIZADA`; no guarda, no llama a Pacientes.
11. **Clave inválida** (`"corta"`) → `400 CLAVE_IDEMPOTENCIA_REQUERIDA`; `verifyNoInteractions(repositorio)`.
12. **Anular OK**: `findById` → pago `REGISTRADO` creado con `Pago.registrar(...)` (el constructor `@PersistenceCreator` es de paquete; `isNew` no importa con el repositorio mockeado) → respuesta `ANULADO`; evento `pago.anulado` con `estadoAnterior "REGISTRADO"`, `estadoNuevo "ANULADO"`, `datos.motivo`.
13. **Anular inexistente** → `404 PAGO_NO_ENCONTRADO`.
14. **Anular ya anulado** → `409 PAGO_YA_ANULADO`; no guarda ni publica.
15. **Anular con `OptimisticLockingFailureException`** en `save` → `409 CONFLICTO_CONCURRENTE`; no publica.
16. **Obtener inexistente** → `404 PAGO_NO_ENCONTRADO`.

### 4.4 Controlador — `api/PagoControllerTest`

```java
@WebFluxTest(controllers = PagoController.class,
        properties = {"hospital.rabbit.enabled=false", "eureka.client.enabled=false"})
@Import(ManejadorErrores.class)          // está en commons, fuera del escaneo del slice
@ActiveProfiles("test")
class PagoControllerTest {
    @Autowired WebTestClient cliente;
    @MockitoBean PagoService servicio;    // org.springframework.test.context.bean.override.mockito.MockitoBean (@MockBean está deprecado en Boot 3.4+)
}
```

Cuerpo válido de referencia: `{"pacienteId":"<uuid>","citaId":"<uuid>","concepto":"Reserva de cita - Medicina General","monto":80.00,"medioPago":"TARJETA"}`; cabecera `Idempotency-Key: 3f6c2a1e-7b1d-4a8e-9c55-0d2f1b7a9e10`.

Casos (en los de error verificar `contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON)` y `$.codigo`):
1. `POST` OK (servicio → `ResultadoRegistro(resp, false)`) → `201`, `Location: /api/pagos/<id>`, `$.numeroOperacion`, **sin** cabecera `Idempotent-Replayed` (`expectHeader().doesNotExist(...)`); el cuerpo crudo contiene `"monto":80.00`.
2. **`monto: -10`** → `400` `problem+json`, `codigo = MONTO_INVALIDO`; `verify(servicio, never()).registrar(any(), any())`.
3. **`monto: 0`** → `400 MONTO_INVALIDO`.
4. `monto: 0.001` → `400 MONTO_INVALIDO`.
5. `medioPago: "CHEQUE"` → `400 VALIDACION_FALLIDA`.
6. Sin `concepto` → `400 VALIDACION_FALLIDA` con `$.errores[0].campo = "concepto"`.
7. **Sin cabecera `Idempotency-Key`** → `400 CLAVE_IDEMPOTENCIA_REQUERIDA`; servicio no invocado. Con clave `"abc"` → mismo código.
8. **Repetición** (servicio → `ResultadoRegistro(resp, true)`) → `201` y `Idempotent-Replayed: true`.
9. Servicio → `Mono.error(ErrorNegocio 422 CLAVE_IDEMPOTENCIA_REUTILIZADA)` → `422` con ese código.
10. `GET /api/pagos/<uuid>` inexistente (servicio → 404) → `404 PAGO_NO_ENCONTRADO`; `GET /api/pagos/no-es-uuid` → `400`.
11. `PATCH /api/pagos/<id>/anular` con `{}` → `400 VALIDACION_FALLIDA`; con motivo → `200`; servicio → `409 PAGO_YA_ANULADO` → `409`.
12. `GET /api/pagos?estado=REGISTRADO` → `200` y `X-Total-Count` igual al `Mono<Long>` del mock; `?tamano=500` → `400 VALIDACION_FALLIDA`.

### 4.5 Repositorio/BD — `persistence/PagoRepositoryIT` (Docker)

```java
@DataR2dbcTest
@Import({PagoConsultas.class, SecuenciaOperacion.class})
@Testcontainers
@ActiveProfiles("test")
class PagoRepositoryIT {
    @Container @ServiceConnection
    static PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16");
    @Autowired PagoRepository repositorio;
    @Autowired PagoConsultas consultas;
    @Autowired SecuenciaOperacion secuencia;
    @Autowired DatabaseClient db;
    @BeforeEach void limpiar() { db.sql("DELETE FROM pago").then().block(); }
}
```

`@DataR2dbcTest` incluye `SqlInitializationAutoConfiguration` (verificado) → `schema.sql` se aplica. Para inserciones directas usa `db.sql("INSERT INTO pago (id, numero_operacion, paciente_id, concepto, monto, medio_pago, clave_idempotencia, huella_solicitud) VALUES (:id, :num, :pac, 'Prueba', :monto, 'EFECTIVO', :clave, :huella)")` con `.bind(...)` y `.then()`.

Casos:
1. **`CHECK` monto**: insert directo con `monto = 0` → `StepVerifier ... expectError(DataIntegrityViolationException.class)` y **no** `DuplicateKeyException`; igual con `-10`.
2. **`numero_operacion` duplicado** (dos inserts directos con el mismo número y claves distintas) → `DuplicateKeyException`.
3. **`clave_idempotencia` duplicada** (mismo clave, números distintos) → `DuplicateKeyException`.
4. **Secuencia**: dos llamadas a `secuencia.siguiente()` → valores distintos y crecientes.
5. **Ida y vuelta**: `repositorio.save(Pago.registrar(...))` → `findById` devuelve `monto` con `scale() == 2` y `compareTo(80.00) == 0`, `medioPago`/`estado` como enums, `version == 0`, `findByClaveIdempotencia` lo encuentra.
6. **`@Version`**: leer el mismo pago dos veces (`p1`, `p2`), `p1.anular` + `save` OK; `p2.anular` + `save` → `OptimisticLockingFailureException`.
7. **Filtros y paginación** (`PagoConsultas`): insertar 5 pagos con relojes fijos distintos (dos días en Lima), dos pacientes, medios y un anulado. Comprobar: sin filtros `contar` = 5 y `buscar` en orden `fechaPago` descendente; `pacienteId`; `estado=ANULADO`; `medioPago`; `numeroOperacion`; `fecha` (incluye un pago a las 23:30 de Lima, que en UTC es el día siguiente, y debe contarse en su día de Lima); página `ParametrosPagina.de(1, 2)` devuelve los elementos 3 y 4 del orden total.

### 4.6 Flujo completo — `PagoFlujoIT` (Docker; en `…pagos`)

```java
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
@ActiveProfiles("test")
class PagoFlujoIT {
    @Container @ServiceConnection
    static PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16");
    @Container
    static RabbitMQContainer RABBIT = new RabbitMQContainer("rabbitmq:3.13-management");
    static final MockWebServer PACIENTES = new MockWebServer();
    static { try { PACIENTES.start(); } catch (IOException e) { throw new UncheckedIOException(e); } }

    @DynamicPropertySource
    static void propiedades(DynamicPropertyRegistry r) {
        r.add("hospital.rabbit.enabled", () -> "true");
        r.add("hospital.rabbit.host", RABBIT::getHost);
        r.add("hospital.rabbit.port", RABBIT::getAmqpPort);
        r.add("hospital.rabbit.username", RABBIT::getAdminUsername);
        r.add("hospital.rabbit.password", RABBIT::getAdminPassword);
        r.add("eureka.client.enabled", () -> "false");
    }

    @TestConfiguration
    static class PacientesFalso {
        @Bean @Primary
        PacientesClient pacientesClientPrueba() {
            return new PacientesClient(WebClient.builder().baseUrl(PACIENTES.url("/").toString()).build());
        }
    }
    @AfterAll static void cerrar() throws IOException { PACIENTES.shutdown(); }
}
```

* `PACIENTES.setDispatcher(...)` en `@BeforeAll`: responde a cualquier `GET /api/pacientes/{id}` con `200`, `Content-Type: application/json`, `{"id":"<id del path>","estado":"ACTIVO"}` y `setHeadersDelay(500, TimeUnit.MILLISECONDS)` (el retraso hace que dos POST simultáneos pasen ambos la búsqueda inicial por clave y choquen en el `INSERT`).
* Cola de prueba: en `@BeforeEach`, con `com.rabbitmq.client.ConnectionFactory` directa al contenedor: `exchangeDeclare("hospital.eventos", "topic", true)`, `queueDeclare("pagos.prueba.cola", false, false, false, null)`, `queueBind(cola, "hospital.eventos", "pago.registrado")`, `queuePurge(cola)`. Contar con `queueDeclarePassive(cola).getMessageCount()` y Awaitility (incluido en `spring-boot-starter-test`). Leer el mensaje con `basicGet`.
* Filas: `db.sql("SELECT count(*) FROM pago WHERE clave_idempotencia = :c").bind("c", clave).map(f -> f.get(0, Long.class)).one().block()`.
* Cada prueba usa una clave nueva `UUID.randomUUID().toString()`.

Casos:
1. **POST → BD → mensaje**: `201`, `numeroOperacion` con formato `OP-…`, 1 fila, 1 mensaje en la cola con `tipo = "pago.registrado"`, `recursoId` = id, `datos.numeroOperacion` igual.
2. **Mismo POST dos veces seguidas con la misma clave**: ambos `201`, mismo `id` y `numeroOperacion`; la segunda con `Idempotent-Replayed: true`; **1 fila** y **1 mensaje** (esperar hasta 1 y comprobar que tras ~1 s sigue en 1).
3. **Dos POST simultáneos con la misma clave**: con `WebClient.create("http://localhost:" + puerto)` (`@LocalServerPort`), `Flux.merge(post(), post()).collectList().block(Duration.ofSeconds(15))` usando `.retrieve().toEntity(String.class)` → ambos `201`, mismo `id`, al menos una respuesta con `Idempotent-Replayed: true`, **1 fila**, **1 mensaje**.
4. Misma clave con otro monto → `422 CLAVE_IDEMPOTENCIA_REUTILIZADA` (y sigue habiendo 1 fila).
5. `monto: 0` → `400 MONTO_INVALIDO` y 0 filas para esa clave.

`WebTestClient` está disponible por `@Autowired` con `RANDOM_PORT` (no añadir `@AutoConfigureWebTestClient`).

---

## 5. Cómo ejecutar y verificar

Desde `/home/cazabache/hospital-vida-salud`:

* Solo unitarias (sin Docker): `./mvnw -q -pl pagos-service test`
* Una clase: `./mvnw -q -pl pagos-service test -Dtest=PagoServiceTest -Dsurefire.failIfNoSpecifiedTests=false`
* Todo (unitarias + IT con Testcontainers): `sg docker -c "./mvnw -q -pl pagos-service verify"`
  * `sg docker` es necesario: el grupo `docker` no está activo en la shell (sin él: *permission denied* en `/var/run/docker.sock`).
  * Si el reactor raíz no se puede leer porque algún módulo de `<modules>` aún no existe en disco (otros agentes los crean en paralelo), usar el equivalente `sg docker -c "./mvnw -q -f pagos-service/pom.xml verify"`.
  * **No** usar `-am`: `hospital-commons` y el pom padre ya están instalados en `~/.m2`. Si cambiara commons (no debería), habría que reinstalarlo con `./mvnw -q -pl hospital-commons install`, fuera de este plan.
* Con `-q` solo se ve la salida en caso de fallo; los detalles están en `pagos-service/target/surefire-reports/` y `pagos-service/target/failsafe-reports/`.

Revisiones finales (deben salir vacías):

```bash
grep -rn "\.block()\|\.subscribe(" pagos-service/src/main/java
grep -rn "lombok\|javax.persistence\|jakarta.persistence\|java.sql\|JdbcTemplate" pagos-service/src pagos-service/pom.xml
grep -rn "@Autowired" pagos-service/src/main/java
grep -rn "log\.\(info\|warn\|error\|debug\).*\(concepto\|nombre\|documento\|correo\|telefono\)" pagos-service/src/main/java
```

---

## 6. Trampas conocidas

1. **`@RequestHeader` con `required = true`** (el valor por defecto) produce `400 VALIDACION_FALLIDA` genérico: usar `required = false` y validar con `ClaveIdempotencia`.
2. **Anotaciones de rango en `monto`** (`@Positive`, `@DecimalMin`, `@Digits`…) roban el caso a `MONTO_INVALIDO`. Solo `@NotNull`.
3. **`BigDecimal.equals`** distingue escala (`80.0 ≠ 80.00`): usar `compareTo`. `setScale(2)` sin modo de redondeo lanza excepción si hay que redondear; por eso se valida antes y se usa `RoundingMode.UNNECESSARY`. En la huella, **no** usar `setScale(2)` sobre un monto aún no validado (m-10).
4. **`Persistable`**: sin `isNew()` correcto, `save()` de una entidad con id asignado hace `UPDATE` de 0 filas (o `OptimisticLockingFailureException`). `nuevo = true` solo en `registrar`; el constructor `@PersistenceCreator` lo deja en `false`. `@Transient` debe ser `org.springframework.data.annotation.Transient`.
5. **`version` (y los campos que cambian al anular) no pueden ser `final`**: Spring Data asigna `version` tras el `INSERT`/`UPDATE`. Tras el primer `save` llamar `marcarComoExistente()`; guardar dos veces la misma instancia con `nuevo=true` provocaría un segundo `INSERT` (`DuplicateKeyException`).
6. **`LocalDate.now(reloj)`** usa la zona del `Clock`; un reloj de prueba en UTC daría otro día. Usar `LocalDate.ofInstant(reloj.instant(), ZONA_HOSPITAL)`.
7. **Distinguir la restricción violada**: no parsear mensajes del driver; releer por `clave_idempotencia` (§2.9).
8. **Publicar en la repetición**: la rama de repetición (búsqueda inicial o carrera) **no** debe llamar a `eventos.registrado`.
9. **`@WebFluxTest` no carga `ManejadorErrores`** (está en commons, se registra por autoconfiguración que el slice no incluye): `@Import(ManejadorErrores.class)`. Tampoco carga `@Configuration` propias ni `@Component` de persistencia/mensajería: por eso `PagoService` va con `@MockitoBean`.
10. **`@ServiceConnection` + R2DBC** necesita `org.testcontainers:r2dbc` (paso 0); sin él: `NoClassDefFoundError: org/testcontainers/r2dbc/R2DBCDatabaseContainer`.
11. **`application.yml` en `src/test/resources`** taparía el de `main`; usar `application-test.yml` + `@ActiveProfiles("test")`.
12. **`PublicadorEventos` no existe con `hospital.rabbit.enabled=false`**: por eso `EventosPago` recibe `Optional<PublicadorEventos>`. `PagoFlujoIT` necesita los mensajes, así que activa RabbitMQ con `@DynamicPropertySource` (prioridad sobre `application-test.yml`). No declarar un `@Bean @ConditionalOnMissingBean PublicadorEventos` propio: las configuraciones de usuario se procesan antes que las autoconfiguraciones y anularía el publicador real.
13. **`PagoFlujoIT` y `@LoadBalanced`**: no se puede apuntar `hospital.clientes.pacientes.url` a `MockWebServer` (el balanceador trataría `localhost` como nombre de servicio). Por eso el `@Bean @Primary PacientesClient` de prueba. No redefinir un bean con el **mismo nombre** (`pacientesClient`): Boot prohíbe sobrescribir definiciones.
14. **`MockWebServer` debe arrancar antes que el contexto** (bloque `static`), porque el bean de prueba lee su URL al crearse.
15. **`TIMESTAMPTZ` vuelve en UTC** y con microsegundos: convertir a Lima en `PagoResponse.desde` y truncar `now` a `MICROS` al crear/anular.
16. **Mockito estricto**: *stubs* no usados → `UnnecessaryStubbingException`. Para respuestas sucesivas de `findByClaveIdempotencia` usar `thenReturn(Mono.empty(), Mono.just(existente))`; la segunda llamada ocurre dentro del `onErrorResume`, así que recibe el segundo valor.
17. **Excepciones síncronas en el flujo**: las reglas puras lanzan excepciones; envolver en `Mono.defer` (o lanzar dentro de `map`/`flatMap`) para que lleguen como `Mono.error` y no rompan el ensamblado.
18. **Sin `.block()`/`.subscribe()`** en `src/main` (solo en pruebas).
19. **Logs**: nunca el cuerpo de la petición, `concepto` ni datos del paciente; solo ids, `numeroOperacion` y códigos.

---

## 7. Criterios de terminado

- [ ] Existen todos los archivos de §2 con los paquetes indicados; `PagosServiceApplication` y `application.yml` sin cambios; único cambio en `pom.xml`: `org.testcontainers:r2dbc` (test).
- [ ] `./mvnw -q -pl pagos-service test` en verde (`ReglasPagoTest`, `ClaveIdempotenciaTest`, `HuellaSolicitudTest`, `NumeroOperacionTest`, `PagoTest`, `PacientesClientTest`, `PagoServiceTest`, `PagoControllerTest`).
- [ ] `sg docker -c "./mvnw -q -pl pagos-service verify"` en verde (además `PagoRepositoryIT`, `PagoFlujoIT`).
- [ ] Monto `0` y `-10` rechazados con `MONTO_INVALIDO` en dominio y controlador, y por el `CHECK` en BD (`DataIntegrityViolationException`).
- [ ] Sin cabecera → `400 CLAVE_IDEMPOTENCIA_REQUERIDA`; repetición → `201` + `Idempotent-Replayed: true`, misma fila, un solo evento; misma clave con otros datos → `422 CLAVE_IDEMPOTENCIA_REUTILIZADA`; dos POST simultáneos → una fila.
- [ ] Número de operación con formato `OP-yyyyMMdd-NNNNNN` (fecha de Lima) generado con `pago_operacion_seq`.
- [ ] Anular → `200` y evento `pago.anulado` con motivo; segunda anulación → `409 PAGO_YA_ANULADO`; conflicto de versión → `409 CONFLICTO_CONCURRENTE`.
- [ ] Errores de Pacientes: `404 PACIENTE_NO_ENCONTRADO`, `422 PACIENTE_INACTIVO`, `503`/`504 SERVICIO_PACIENTES_NO_DISPONIBLE`.
- [ ] Los greps de §5 salen vacíos (sin `.block()`/`.subscribe()` en `main`, sin JDBC/JPA/Lombok, sin `@Autowired` en `main`, sin datos personales en logs).
- [ ] No se modificó nada fuera de `pagos-service/` y no se hizo commit.
