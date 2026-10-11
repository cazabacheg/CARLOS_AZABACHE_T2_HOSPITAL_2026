# Plan de implementación — `atenciones-service`

> Fuente de verdad: `docs/specs/04-atenciones-service.md` (spec 04), `docs/specs/00-convenciones-comunes.md` (spec 00), contrato de Citas en `docs/specs/03-citas-service.md` §4–§7, documento técnico §2.3, §5.5, §6.4, §8.4, §9, §10.
> Este plan es **autosuficiente**: quien lo ejecute no necesita otra conversación. Si algo del plan contradice la spec, manda la spec; si contradice el código real de `hospital-commons`, manda el código (aquí ya se verificó contra él).

## DUDAS CRUCIALES

Ninguna. Todo lo necesario se resolvió con las specs, el código real de `hospital-commons` y los jars de las versiones fijadas. Las decisiones pequeñas están en la sección siguiente.

## Decisiones menores tomadas

| # | Decisión | Motivo |
|---|---|---|
| DM-1 | `CitaResumen` vive en `domain/` (no en `client/`) | `Atencion.registrar(CitaResumen, …)` es dominio; así `domain` no depende de `client`. Lleva `@JsonIgnoreProperties(ignoreUnknown = true)` (anotación de Jackson, no de Spring) |
| DM-2 | `FiltroAtenciones` (record de filtros del listado) vive en `domain/` | Lo usan controlador, servicio y persistencia sin crear dependencias cruzadas |
| DM-3 | DTOs en `api/dto/` (como dice spec 02 §5.1 para Pacientes) | Coherencia entre servicios |
| DM-4 | El servicio expone `Flux<AtencionResponse> listar(filtro, pagina)` y `Mono<Long> contar(filtro)`; el **controlador** arma la respuesta con `RespuestaPaginada.de(...)` | El servicio no conoce `ResponseEntity`; el controlador "arma la respuesta" (spec 00 §3) |
| DM-5 | Las fechas de la respuesta y de los eventos se devuelven con offset de Lima (`-05:00`) | PostgreSQL devuelve `TIMESTAMPTZ` en UTC; spec 00 §4 pide `fechaHora` con offset de Lima |
| DM-6 | Texto de `FUERA_DE_VENTANA_ATENCION`: `"La atención se puede registrar desde <N> minutos antes de la cita (<dd/MM/yyyy HH:mm>)."` con `<N>` = valor configurado y la fecha-hora **de la cita** en Lima | La spec deja `<hora>` sin formato; los minutos no se escriben fijos porque son configurables |
| DM-7 | En `atencion.finalizada`/`atencion.anulada`, `datos.motivo` siempre está presente (JSON `null` si no llegó) | Contrato estable para Auditoría |
| DM-8 | `PATCH` con `estado = REGISTRADA` → `409 TRANSICION_NO_PERMITIDA` (lo rechaza la máquina de estados) | `REGISTRADA → REGISTRADA` no es transición válida |
| DM-9 | Errores de Citas distintos de 404 y 5xx (p. ej. 400) **no** se traducen (terminan en `500 ERROR_INTERNO`) | No ocurren en la práctica (el id es UUID validado); igual a `PoliticaResiliencia` |
| DM-10 | Se agrega la dependencia de prueba `org.testcontainers:r2dbc` al `pom.xml` del servicio | **Verificado en los jars:** `PostgresR2dbcContainerConnectionDetailsFactory` (Boot 3.5.16) llama a `PostgreSQLR2DBCDatabaseContainer.getOptions(...)`, clase que implementa `org.testcontainers.r2dbc.R2DBCDatabaseContainer` del módulo `r2dbc`. Sin él, `@ServiceConnection` falla con `NoClassDefFoundError`. Versión gestionada por el BOM (1.21.4) |
| DM-11 | Perfil de pruebas `test` con `src/test/resources/application-test.yml` (contraseñas ficticias, Eureka apagado) y `@ActiveProfiles("test")` en todas las pruebas con contexto Spring | spec 00 §7 ("Perfil `test`") |
| DM-12 | En `AtencionFlujoIT` se reemplaza el `CitasClient` por uno `@Primary` construido con `WebClient.builder()` **sin** `@LoadBalanced` apuntando a `MockWebServer` | spec 00 §9: "Sin Eureka en pruebas, cliente sin `@LoadBalanced`" |
| DM-13 | La consulta de "atención activa" usa `@Query` explícita con `estado <> 'ANULADA'` (mismo predicado que el índice parcial) en lugar de un método derivado con enum | Evita depender de la conversión de enums en consultas derivadas y documenta la regla |
| DM-14 | Las fábricas de errores de negocio del servicio se centralizan en `domain/ErroresAtencion` | Mensajes y códigos en un único lugar; pruebas comparan `codigo` |
| DM-15 | El dominio lanza `ErrorNegocio` (de `hospital-commons`, depende de `HttpStatus`) | Igual que spec 03 (`Cita.cambiarEstado` lanza 409). No usa beans ni anotaciones de Spring |
| DM-16 | Pruebas **adicionales** a las de la spec: `VentanaAtencionTest`, `EventosAtencionTest`, y en `AtencionRepositoryIT` los casos `@Version` y listado con filtros | Verifican las decisiones técnicas más delicadas (Persistable + `@Version`, `Criteria`/`Sort`) |
| DM-17 | El `@Bean @LoadBalanced WebClient.Builder` es `WebClient.builder()` simple; reemplaza al `WebClient.Builder` de Boot (que es `@ConditionalOnMissingBean`) | Único `WebClient` del servicio; el `ObjectMapper` por defecto de `WebClient` ya registra `JavaTimeModule` |
| DM-18 | (Ya hecho por el planificador) se instaló el POM padre en el repositorio local con `./mvnw -q -N install` | Sin él, `-pl atenciones-service` **sin** `-am` falla: "Could not find artifact hospital-vida-salud-parent:pom:1.0.0-SNAPSHOT" (el `hospital-commons` instalado lo referencia). Si reaparece, repetir ese comando (solo instala el `pom.xml` raíz) |

---

## 1. Resumen y orden de implementación

**Qué se construye:** servicio WebFlux + R2DBC (puerto 8085) que registra la atención administrativa de una cita `CONFIRMADA`, consultando a Citas por HTTP (`GET http://citas-service/api/citas/{id}`), aplicando la ventana de 30 min (configurable), con a lo sumo una atención no anulada por cita (índice único parcial), estados `REGISTRADA → FINALIZADA | ANULADA`, y que publica `atencion.registrada`, `atencion.finalizada`, `atencion.anulada` en `hospital.eventos`. **No consume** eventos. **Nunca** modifica la cita.

**Ya existe** (no rehacer): `atenciones-service/pom.xml` (dependencias completas salvo DM-10), `AtencionesServiceApplication.java`, `application.yml` (puerto, BD, rabbit, `hospital.clientes.citas.url`, Eureka, actuator, patrón de log, springdoc). `hospital-commons` está instalado en `~/.m2` y **no se modifica**.

Comando base (desde la raíz `/home/cazabache/hospital-vida-salud`):

```bash
sg docker -c "./mvnw -q -pl atenciones-service verify"     # todo: *Test (Surefire) + *IT (Failsafe)
./mvnw -q -pl atenciones-service test-compile                # solo compilar (sin Docker)
./mvnw -q -pl atenciones-service test -Dtest='EstadoAtencionTest' -Dsurefire.failIfNoSpecifiedTests=false   # una prueba unitaria
```

No usar `-am`. Con `-q` no hay salida si todo va bien; ante un fallo revisar `atenciones-service/target/surefire-reports/` y `target/failsafe-reports/`.

### Pasos (cada uno deja el módulo compilando y con sus pruebas en verde)

| Paso | Contenido | Verificación |
|---|---|---|
| 0 | Línea base: el esqueleto compila y `verify` pasa vacío | `sg docker -c "./mvnw -q -pl atenciones-service verify"` |
| 1 | `pom.xml` (+ `org.testcontainers:r2dbc`), `application.yml` (+ `hospital.atencion.minutos-antes`), `src/test/resources/application-test.yml` | `./mvnw -q -pl atenciones-service test-compile` |
| 2 | `domain/EstadoAtencion` + `EstadoAtencionTest` | `-Dtest=EstadoAtencionTest` |
| 3 | `domain/AreaAtencion`, `domain/ErroresAtencion` | `test-compile` |
| 4 | `domain/CitaResumen`, `domain/VentanaAtencion` + `VentanaAtencionTest` | `-Dtest=VentanaAtencionTest` |
| 5 | `domain/Atencion` (entidad Persistable + `@Version`) + `AtencionTest` | `-Dtest=AtencionTest` |
| 6 | `domain/FiltroAtenciones` | `test-compile` |
| 7 | `schema.sql`, `persistence/AtencionRepository`, `persistence/GeneradorCodigoAtencion`, `persistence/AtencionConsultas` + `AtencionRepositoryIT` | `verify` (Docker) |
| 8 | `config/AtencionPropiedades`, `config/AtencionConfig` | `test-compile` |
| 9 | `client/CitasClient` + `CitasClientTest` | `-Dtest=CitasClientTest` |
| 10 | `messaging/EventosAtencion` + `EventosAtencionTest` | `-Dtest=EventosAtencionTest` |
| 11 | DTOs `api/dto/*` | `test-compile` |
| 12 | `service/AtencionService` + `AtencionServiceTest` | `-Dtest=AtencionServiceTest` |
| 13 | `api/AtencionController` + `AtencionControllerTest` | `-Dtest=AtencionControllerTest` |
| 14 | `AtencionFlujoIT` | `verify` (Docker) |
| 15 | Revisión final (greps de reglas, `verify` completo) | §5 y §7 |

---

## 2. Archivos exactos

Paquete base: `pe.edu.cibertec.vidasalud.atenciones` (abreviado `…atenciones`). Rutas relativas a `atenciones-service/`.

### 2.1 Build y configuración (paso 1)

**`pom.xml`** — agregar, junto a las demás dependencias de prueba (sin versión, la gestiona el BOM):

```xml
<dependency>
  <groupId>org.testcontainers</groupId>
  <artifactId>r2dbc</artifactId>
  <scope>test</scope>
</dependency>
```

Nada más cambia en el `pom.xml` (ya tiene webflux, validation, actuator, data-r2dbc, r2dbc-postgresql, eureka-client, loadbalancer, springdoc, test, reactor-test, spring-boot-testcontainers, testcontainers junit-jupiter/postgresql/rabbitmq, mockwebserver y `spring-boot-maven-plugin`). Awaitility ya viene con `spring-boot-starter-test`. `com.rabbitmq:amqp-client` llega transitivamente por `hospital-commons` → `reactor-rabbitmq`.

**`src/main/resources/application.yml`** — agregar bajo `hospital:` (mantener lo existente):

```yaml
hospital:
  # ... rabbit y clientes ya existen
  atencion:
    minutos-antes: ${ATENCION_MINUTOS_ANTES:30}
```

**`src/test/resources/application-test.yml`** (nuevo):

```yaml
spring:
  r2dbc:
    password: prueba          # dummy; @ServiceConnection lo reemplaza en las IT
hospital:
  rabbit:
    password: prueba          # dummy; @DynamicPropertySource lo reemplaza en AtencionFlujoIT
eureka:
  client:
    enabled: false
```

> No crear `src/test/resources/application.yml`: ocultaría el `application.yml` principal.

### 2.2 `domain/` (pasos 2–6) — sin anotaciones ni beans de Spring (salvo las de mapeo de Spring Data en la entidad, como exige spec 00 §6)

**`…atenciones.domain.EstadoAtencion`** (enum)

```java
public enum EstadoAtencion {
    REGISTRADA, FINALIZADA, ANULADA;

    /** REGISTRADA → FINALIZADA, ANULADA; FINALIZADA y ANULADA son finales. */
    public boolean puedeCambiarA(EstadoAtencion destino)   // switch: REGISTRADA -> destino == FINALIZADA || destino == ANULADA; resto false
    public boolean esFinal()                                // this != REGISTRADA
}
```

**`…atenciones.domain.AreaAtencion`** (enum con nombre legible)

```java
public enum AreaAtencion {
    CONSULTA_EXTERNA("Consulta Externa"), EMERGENCIA("Emergencia"),
    TRIAJE("Triaje"), PROCEDIMIENTOS("Procedimientos");
    private final String nombre;
    AreaAtencion(String nombre)
    public String nombre()
}
```

**`…atenciones.domain.ErroresAtencion`** (clase `final`, constructor privado; todas devuelven `ErrorNegocio` de `pe.edu.cibertec.vidasalud.commons.error`)

| Método estático | Estado | `codigo` | `detalle` |
|---|---|---|---|
| `citaNoEncontrada(UUID citaId)` | 404 | `CITA_NO_ENCONTRADA` | `"No existe la cita " + citaId + "."` |
| `citaNoConfirmada(String estadoActual)` | 409 | `CITA_NO_CONFIRMADA` | `"Solo se registra atención para citas confirmadas (estado actual: " + estadoActual + ")."` |
| `fueraDeVentana(int minutosAntes, OffsetDateTime fechaHoraCita)` | 409 | `FUERA_DE_VENTANA_ATENCION` | DM-6; fecha convertida a `RelojAutoConfiguration.ZONA_HOSPITAL` y formateada `dd/MM/yyyy HH:mm` |
| `atencionDuplicada()` | 409 | `ATENCION_DUPLICADA` | `"La cita ya tiene una atención registrada."` |
| `atencionNoEncontrada(UUID id)` | 404 | `ATENCION_NO_ENCONTRADA` | `"No existe la atención " + id + "."` |
| `transicionNoPermitida(EstadoAtencion actual, EstadoAtencion destino)` | 409 | `TRANSICION_NO_PERMITIDA` | `"No se puede pasar una atención de " + actual + " a " + destino + "."` |
| `conflictoConcurrente()` | 409 | `CONFLICTO_CONCURRENTE` | `"La atención fue modificada por otra operación; vuelva a consultarla."` |

Constante: `public static final String SERVICIO_CITAS_NO_DISPONIBLE = "SERVICIO_CITAS_NO_DISPONIBLE";`

**`…atenciones.domain.CitaResumen`** (record propio, contrato mínimo con Citas)

```java
@JsonIgnoreProperties(ignoreUnknown = true)
public record CitaResumen(UUID id, UUID pacienteId, String estado, OffsetDateTime fechaHora) {
    public static final String ESTADO_CONFIRMADA = "CONFIRMADA";
    public boolean estaConfirmada()   // ESTADO_CONFIRMADA.equals(estado)
}
```

`estado` es `String` (no un enum de Citas): si Citas agrega estados, no rompe la deserialización.

**`…atenciones.domain.VentanaAtencion`** (regla RN-16, pura)

```java
public final class VentanaAtencion {
    private final int minutosAntes;
    public VentanaAtencion(int minutosAntes)          // IllegalArgumentException si < 0
    public int minutosAntes()
    /** Lanza ErroresAtencion.fueraDeVentana(...) si ahora < fechaHoraCita - minutosAntes. Sin límite posterior. */
    public void validar(OffsetDateTime fechaHoraCita, Clock reloj)
}
```

Implementación: `Instant ahora = reloj.instant(); Instant desde = fechaHoraCita.toInstant().minus(Duration.ofMinutes(minutosAntes)); if (ahora.isBefore(desde)) throw ...`. Compara **instantes** (el offset de la cita puede venir en UTC por Jackson). Exactamente 30 min antes → permitido.

**`…atenciones.domain.Atencion`** (entidad R2DBC; ver §3.1 para el detalle técnico)

```java
@Table("atencion")
public class Atencion implements Persistable<UUID> {
    @Id private final UUID id;
    private final String codigo;
    @Column("paciente_id") private final UUID pacienteId;
    @Column("cita_id") private final UUID citaId;
    @Column("fecha_atencion") private final OffsetDateTime fechaAtencion;
    private final AreaAtencion area;
    private EstadoAtencion estado;                                   // mutable
    @Column("fecha_creacion") private final OffsetDateTime fechaCreacion;
    @Column("fecha_actualizacion") private OffsetDateTime fechaActualizacion;   // mutable
    @Version private Long version;                                   // mutable, NO final
    @Transient private boolean nuevo;                                // org.springframework.data.annotation.Transient

    @PersistenceCreator
    Atencion(UUID id, String codigo, UUID pacienteId, UUID citaId, OffsetDateTime fechaAtencion,
             AreaAtencion area, EstadoAtencion estado, OffsetDateTime fechaCreacion,
             OffsetDateTime fechaActualizacion, Long version)        // asigna todo y marcarComoExistente()

    public static Atencion registrar(CitaResumen cita, AreaAtencion area, String codigo, Clock reloj)
        // id = UUID.randomUUID(); pacienteId = cita.pacienteId(); citaId = cita.id();
        // ahora = OffsetDateTime.now(reloj); fechaAtencion = fechaCreacion = fechaActualizacion = ahora;
        // estado = REGISTRADA; version = null; nuevo = true

    public EstadoAtencion cambiarEstado(EstadoAtencion destino, Clock reloj)
        // si !estado.puedeCambiarA(destino) -> throw ErroresAtencion.transicionNoPermitida(estado, destino)
        // anterior = estado; estado = destino; fechaActualizacion = OffsetDateTime.now(reloj); return anterior

    public static String formatearCodigo(LocalDate fecha, long numero)
        // "ATE-" + fecha.format(DateTimeFormatter.BASIC_ISO_DATE) + "-" + String.format("%06d", numero)
        // ej.: ATE-20261014-000001

    void marcarComoExistente()        // nuevo = false
    @Override public UUID getId()
    @Override public boolean isNew()  // return nuevo
    // getters: getCodigo, getPacienteId, getCitaId, getFechaAtencion, getArea, getEstado,
    //          getFechaCreacion, getFechaActualizacion, getVersion
}
```

Imports de anotaciones: `org.springframework.data.annotation.{Id, Version, Transient, PersistenceCreator}`, `org.springframework.data.relational.core.mapping.{Table, Column}`, `org.springframework.data.domain.Persistable`. **Nunca** `jakarta.persistence.*` ni `java.beans.Transient`.

**`…atenciones.domain.FiltroAtenciones`**

```java
public record FiltroAtenciones(UUID pacienteId, UUID citaId, EstadoAtencion estado,
                               AreaAtencion area, LocalDate fecha) { }
```

### 2.3 Persistencia (paso 7)

**`src/main/resources/schema.sql`** — DDL del documento §8.4 con `IF NOT EXISTS` (spec 00 §6):

```sql
CREATE TABLE IF NOT EXISTS atencion (
  id                   UUID PRIMARY KEY,
  codigo               VARCHAR(30) NOT NULL UNIQUE,
  paciente_id          UUID        NOT NULL,
  cita_id              UUID        NOT NULL,
  fecha_atencion       TIMESTAMPTZ NOT NULL DEFAULT now(),
  area                 VARCHAR(20) NOT NULL
                       CHECK (area IN ('CONSULTA_EXTERNA','EMERGENCIA','TRIAJE','PROCEDIMIENTOS')),
  estado               VARCHAR(12) NOT NULL DEFAULT 'REGISTRADA'
                       CHECK (estado IN ('REGISTRADA','FINALIZADA','ANULADA')),
  fecha_creacion       TIMESTAMPTZ NOT NULL DEFAULT now(),
  fecha_actualizacion  TIMESTAMPTZ NOT NULL DEFAULT now(),
  version              BIGINT      NOT NULL DEFAULT 0
);
CREATE SEQUENCE IF NOT EXISTS atencion_codigo_seq;
CREATE UNIQUE INDEX IF NOT EXISTS ux_atencion_cita_activa ON atencion (cita_id) WHERE estado <> 'ANULADA';
CREATE INDEX IF NOT EXISTS ix_atencion_paciente ON atencion (paciente_id);
```

Sin comentarios `--` dentro de sentencias ni bloques `$$` (separador `;` por defecto).

**`…atenciones.persistence.AtencionRepository`**

```java
public interface AtencionRepository extends ReactiveCrudRepository<Atencion, UUID> {
    @Query("SELECT EXISTS (SELECT 1 FROM atencion WHERE cita_id = :citaId AND estado <> 'ANULADA')")
    Mono<Boolean> existsActivaByCitaId(UUID citaId);
}
```

(`org.springframework.data.r2dbc.repository.Query`; el nombre del parámetro se resuelve por `-parameters`, ya activo en el padre.)

**`…atenciones.persistence.GeneradorCodigoAtencion`** (`@Component`)

```java
public GeneradorCodigoAtencion(DatabaseClient databaseClient, Clock reloj)
/** nextval('atencion_codigo_seq') -> ATE-<yyyyMMdd de hoy en Lima>-<NNNNNN>. */
public Mono<String> siguiente() {
    return databaseClient.sql("SELECT nextval('atencion_codigo_seq')")
            .map(fila -> fila.get(0, Long.class))
            .one()
            .map(n -> Atencion.formatearCodigo(LocalDate.ofInstant(reloj.instant(), RelojAutoConfiguration.ZONA_HOSPITAL), n));
}
```

(`org.springframework.r2dbc.core.DatabaseClient`; el bean lo crea Boot.)

**`…atenciones.persistence.AtencionConsultas`** (`@Component`, consultas dinámicas)

```java
static final Sort ORDEN = Sort.by(Sort.Order.desc("fechaAtencion"), Sort.Order.asc("id"));
public AtencionConsultas(R2dbcEntityTemplate template)
public Flux<Atencion> buscar(FiltroAtenciones filtro, ParametrosPagina pagina)
    // template.select(Atencion.class).matching(Query.query(criterio(filtro)).with(pagina.aPageable(ORDEN))).all()
public Mono<Long> contar(FiltroAtenciones filtro)
    // template.count(Query.query(criterio(filtro)), Atencion.class)
static Criteria criterio(FiltroAtenciones f)
```

`criterio`: empezar con `Criteria c = Criteria.empty();` y por cada filtro no nulo `c = c.and("<propiedad>").is(valor)` (`Criteria` es inmutable: reasignar). Usar **nombres de propiedad Java** (`pacienteId`, `citaId`, `estado`, `area`, `fechaAtencion`); el `QueryMapper` los traduce a columnas. Enums: pasar `estado.name()` / `area.name()`. Fecha: `inicio = fecha.atStartOfDay(ZONA_HOSPITAL).toOffsetDateTime()`, `fin = fecha.plusDays(1).atStartOfDay(ZONA_HOSPITAL).toOffsetDateTime()`, `c = c.and("fechaAtencion").greaterThanOrEquals(inicio).and("fechaAtencion").lessThan(fin)`. Clases: `org.springframework.data.relational.core.query.{Criteria, Query}`, `org.springframework.data.r2dbc.core.R2dbcEntityTemplate`.

### 2.4 Configuración (paso 8)

**`…atenciones.config.AtencionPropiedades`**

```java
@ConfigurationProperties("hospital.atencion")
public record AtencionPropiedades(@DefaultValue("30") int minutosAntes) { }
```

**`…atenciones.config.AtencionConfig`** (`@Configuration(proxyBeanMethods = false)`, `@EnableConfigurationProperties(AtencionPropiedades.class)`)

```java
@Bean VentanaAtencion ventanaAtencion(AtencionPropiedades props)    // new VentanaAtencion(props.minutosAntes())
@Bean @LoadBalanced WebClient.Builder webClientBalanceado()          // WebClient.builder()
```

No poner `@EnableConfigurationProperties`/`@ConfigurationPropertiesScan` en `AtencionesServiceApplication` (ver trampa T-9). El `Clock` lo aporta `hospital-commons` (`RelojAutoConfiguration`, bean `relojHospital`, zona Lima): **no** declarar otro.

### 2.5 Cliente a Citas (paso 9)

**`…atenciones.client.CitasClient`** (`@Component`)

```java
private final WebClient webClient;

public CitasClient(@LoadBalanced WebClient.Builder builder,
                   @Value("${hospital.clientes.citas.url}") String urlCitas) {
    this.webClient = ClienteHttpFactory.crear(builder, urlCitas);
}

/** GET /api/citas/{id}: 404 -> CITA_NO_ENCONTRADA; 5xx/red/sin instancias -> 503; timeout 2 s -> 504. */
public Mono<CitaResumen> obtener(UUID citaId) {
    Mono<CitaResumen> llamada = webClient.get()
            .uri("/api/citas/{id}", citaId)
            .accept(MediaType.APPLICATION_JSON)
            .retrieve()
            .onStatus(s -> s.value() == 404, r -> Mono.just(ErroresAtencion.citaNoEncontrada(citaId)))
            .bodyToMono(CitaResumen.class);
    return PoliticaResiliencia.proteger(llamada, ErroresAtencion.SERVICIO_CITAS_NO_DISPONIBLE, "citas");
}
```

- `@LoadBalanced` = `org.springframework.cloud.client.loadbalancer.LoadBalanced` (es `@Qualifier`, válido en parámetro).
- `onStatus` devuelve `Mono.just(error)`: WebClient libera el cuerpo y emite el error. `PoliticaResiliencia.proteger` deja pasar `ErrorNegocio` sin traducir ni reintentar (verificado en su código).
- 5xx: el `onStatus` por defecto genera `WebClientResponseException` → `proteger` → `503`. Sin instancias en Eureka: el filtro del LoadBalancer responde `503` → misma traducción. Conexión rechazada: `WebClientRequestException` → 1 reintento a los 200 ms → `503`. Timeout de 2 s (fijo en `PoliticaResiliencia.TIMEOUT`) → `504`.
- No registrar nada del cuerpo; si se registra, solo `citaId`.

### 2.6 Eventos (paso 10)

**`…atenciones.messaging.EventosAtencion`** (clase `final`, utilidades puras; sin `ObjectMapper`)

```java
public static final String ORIGEN = "atenciones-service";
public static final String TIPO_RECURSO = "ATENCION";

public static EventoEnvelope registrada(Atencion a, Clock reloj)
    // EventoEnvelope.nuevo(reloj, TopologiaHospital.ATENCION_REGISTRADA, ORIGEN, TIPO_RECURSO,
    //                      a.getId(), null, "REGISTRADA", datosComunes(a))
public static EventoEnvelope cambioEstado(Atencion a, EstadoAtencion anterior, String motivo, Clock reloj)
    // tipo = routingKeyDe(a.getEstado()); estadoAnterior = anterior.name(); estadoNuevo = a.getEstado().name()
    // datos = datosComunes(a) + put("motivo", motivo)   (motivo null -> JSON null, DM-7)
public static String routingKeyDe(EstadoAtencion destino)
    // FINALIZADA -> TopologiaHospital.ATENCION_FINALIZADA; ANULADA -> TopologiaHospital.ATENCION_ANULADA;
    // REGISTRADA -> TopologiaHospital.ATENCION_REGISTRADA
static ObjectNode datosComunes(Atencion a)
    // JsonNodeFactory.instance.objectNode():
    //   atencionId, citaId, pacienteId (toString), codigo, area (name()), areaNombre (area.nombre()),
    //   fechaAtencion = DateTimeFormatter.ISO_OFFSET_DATE_TIME.format(enLima(a.getFechaAtencion()))
static OffsetDateTime enLima(OffsetDateTime t)
    // t.atZoneSameInstant(RelojAutoConfiguration.ZONA_HOSPITAL).toOffsetDateTime()
```

Usar `ISO_OFFSET_DATE_TIME` y **no** `OffsetDateTime.toString()` (este omite `:00` de segundos). `EventoEnvelope` (commons) genera `eventId` y `ocurrioEn`. Las routing keys salen **siempre** de `TopologiaHospital` (no escribir literales).

### 2.7 DTOs (paso 11) — `…atenciones.api.dto`

```java
public record AtencionCrearRequest(@NotNull UUID citaId, @NotNull AreaAtencion area) { }

public record CambioEstadoAtencionRequest(@NotNull EstadoAtencion estado, @Size(max = 200) String motivo) { }

public record AtencionResponse(UUID id, String codigo, UUID pacienteId, UUID citaId,
                               OffsetDateTime fechaAtencion, AreaAtencion area, String areaNombre,
                               EstadoAtencion estado, OffsetDateTime fechaCreacion,
                               OffsetDateTime fechaActualizacion) {
    public static AtencionResponse de(Atencion a)   // fechas convertidas a Lima (EventosAtencion.enLima o helper propio), areaNombre = area.nombre()
}
```

`version` y `nuevo` **no** salen por la API. Validaciones: `jakarta.validation.constraints.*`.

### 2.8 Servicio (paso 12) — `…atenciones.service.AtencionService` (`@Service`)

```java
public AtencionService(AtencionRepository repositorio, AtencionConsultas consultas,
                       GeneradorCodigoAtencion generador, CitasClient citas,
                       PublicadorEventos publicador, VentanaAtencion ventana, Clock reloj)

public Mono<AtencionResponse> registrar(AtencionCrearRequest solicitud)
public Mono<AtencionResponse> obtener(UUID id)
public Flux<AtencionResponse> listar(FiltroAtenciones filtro, ParametrosPagina pagina)
public Mono<Long> contar(FiltroAtenciones filtro)
public Mono<AtencionResponse> cambiarEstado(UUID id, CambioEstadoAtencionRequest solicitud)
```

`registrar` (orden de spec 04 §5.1, falla barato primero):

```java
return citas.obtener(solicitud.citaId())                                              // 404 / 503 / 504
    .flatMap(cita -> {
        if (!cita.estaConfirmada()) {
            return Mono.error(ErroresAtencion.citaNoConfirmada(cita.estado()));       // 409
        }
        ventana.validar(cita.fechaHora(), reloj);                                      // 409 (lo lanzado dentro de flatMap se vuelve onError)
        return repositorio.existsActivaByCitaId(cita.id())
            .flatMap(existe -> existe
                    ? Mono.<String>error(ErroresAtencion.atencionDuplicada())          // 409
                    : generador.siguiente())
            .map(codigo -> Atencion.registrar(cita, solicitud.area(), codigo, reloj));
    })
    .flatMap(atencion -> repositorio.save(atencion)
            .onErrorMap(DuplicateKeyException.class, e -> ErroresAtencion.atencionDuplicada()))  // carrera
    .flatMap(guardada -> publicador.publicar(TopologiaHospital.ATENCION_REGISTRADA,
                    EventosAtencion.registrada(guardada, reloj))
            .thenReturn(guardada))
    .doOnNext(a -> log.info("atencion registrada id={} citaId={} codigo={}", a.getId(), a.getCitaId(), a.getCodigo()))
    .map(AtencionResponse::de);
```

`obtener`: `repositorio.findById(id).switchIfEmpty(Mono.error(() -> ErroresAtencion.atencionNoEncontrada(id))).map(AtencionResponse::de)` (usar un método privado `buscar(id)` que devuelve `Mono<Atencion>` para reutilizarlo).

`listar` / `contar`: delegan en `AtencionConsultas` (mapear con `AtencionResponse::de`).

`cambiarEstado`:

```java
return buscar(id)                                                                       // 404
    .flatMap(atencion -> {
        EstadoAtencion anterior = atencion.cambiarEstado(solicitud.estado(), reloj);  // 409 TRANSICION_NO_PERMITIDA
        return repositorio.save(atencion)
            .onErrorMap(OptimisticLockingFailureException.class, e -> ErroresAtencion.conflictoConcurrente())
            .flatMap(g -> publicador.publicar(EventosAtencion.routingKeyDe(g.getEstado()),
                            EventosAtencion.cambioEstado(g, anterior, solicitud.motivo(), reloj))
                    .thenReturn(g))
            .doOnNext(g -> log.info("atencion id={} cambia de {} a {}", g.getId(), anterior, g.getEstado()));
    })
    .map(AtencionResponse::de);
```

- `DuplicateKeyException` y `OptimisticLockingFailureException` son de `org.springframework.dao`.
- `PublicadorEventos.publicar` nunca falla (commons, D-3): no agregar `onErrorResume` alrededor.
- Sin `@Transactional`: solo se escribe una tabla.
- Logs: solo UUID, códigos y estados. **Nada** de `motivo` (texto libre).

### 2.9 API (paso 13) — `…atenciones.api.AtencionController`

```java
@RestController
@RequestMapping("/api/atenciones")
public class AtencionController {
    public AtencionController(AtencionService servicio)

    @Operation(summary = "Registrar la atención de una cita confirmada")
    @PostMapping
    public Mono<ResponseEntity<AtencionResponse>> registrar(@Valid @RequestBody AtencionCrearRequest solicitud)
        // servicio.registrar(s).map(r -> ResponseEntity.created(URI.create("/api/atenciones/" + r.id())).body(r))

    @GetMapping("/{id}")
    public Mono<AtencionResponse> obtener(@PathVariable UUID id)

    @Operation(summary = "Listar atenciones (paginado, filtros opcionales)")
    @GetMapping
    public Mono<ResponseEntity<Flux<AtencionResponse>>> listar(
            @RequestParam(required = false) UUID pacienteId,
            @RequestParam(required = false) UUID citaId,
            @RequestParam(required = false) EstadoAtencion estado,
            @RequestParam(required = false) AreaAtencion area,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate fecha,
            @RequestParam(required = false) Integer pagina,
            @RequestParam(required = false) Integer tamano)
        // Mono.fromCallable(() -> ParametrosPagina.de(pagina, tamano))
        //     .flatMap(p -> { var f = new FiltroAtenciones(pacienteId, citaId, estado, area, fecha);
        //                     return RespuestaPaginada.de(servicio.listar(f, p), servicio.contar(f)); })

    @Operation(summary = "Finalizar o anular una atención")
    @PatchMapping("/{id}/estado")
    public Mono<AtencionResponse> cambiarEstado(@PathVariable UUID id, @Valid @RequestBody CambioEstadoAtencionRequest solicitud)
}
```

Errores de entrada (`UUID` mal formado, enum desconocido en cuerpo o parámetro, JSON ilegible, `@NotNull`) los traduce `ManejadorErrores` de commons a `400 VALIDACION_FALLIDA`. El controlador no tiene reglas de negocio. Swagger: en la descripción de `motivo` advertir "no ingresar datos clínicos" (opcional con `@Schema(description = ...)`).

### 2.10 Pruebas — `src/test/java/pe/edu/cibertec/vidasalud/atenciones/…`

| Archivo | Paquete |
|---|---|
| `EstadoAtencionTest`, `VentanaAtencionTest`, `AtencionTest` | `…atenciones.domain` |
| `AtencionRepositoryIT` | `…atenciones.persistence` |
| `CitasClientTest` | `…atenciones.client` |
| `EventosAtencionTest` | `…atenciones.messaging` |
| `AtencionServiceTest` | `…atenciones.service` |
| `AtencionControllerTest` | `…atenciones.api` |
| `AtencionFlujoIT` | `…atenciones` (raíz, junto a la aplicación) |

Detalle de casos en §4.

---

## 3. Decisiones técnicas (verificadas contra Boot 3.5.16 / Spring Data R2DBC 3.5.13 / r2dbc-postgresql 1.0.9 / Testcontainers 1.21.4)

### 3.1 Entidad con UUID asignado (`Persistable`) + `@Version`

- `SimpleR2dbcRepository.save` decide `INSERT`/`UPDATE` con `RelationalEntityInformation.isNew(...)`; para entidades `Persistable` usa `isNew()` de la entidad. Por eso `registrar(...)` deja `nuevo = true` y el constructor `@PersistenceCreator` (usado al leer de BD) llama `marcarComoExistente()`.
- **Verificado en el bytecode de `R2dbcEntityTemplate`**: en el `INSERT`, `setVersionIfNecessary` fija `version = 0` (tipo `Long` no primitivo) usando el *property accessor*; en el `UPDATE`, `incrementVersion` sube a `version + 1` y agrega `WHERE version = <anterior>`; si no actualiza filas lanza `OptimisticLockingFailureException` (`OptimisticLockingUtils.updateFailed`). Consecuencia: **`version` no puede ser `final`** (tampoco `estado` ni `fechaActualizacion`, que cambian). `id` y demás sí pueden ser `final` (el id no se re-asigna porque no es nulo).
- No reutilizar la misma instancia para dos `save` de alta: tras el `INSERT` sigue con `nuevo = true` (en el flujo no ocurre: los cambios de estado siempre parten de `findById`).
- Enums: Spring Data R2DBC los escribe como `String` (`name()`) y los lee por nombre: compatible con `VARCHAR` + `CHECK`.
- `OffsetDateTime` ↔ `TIMESTAMPTZ`: al leer llega con offset `Z`; por eso DM-5 convierte a Lima en la respuesta y en el evento. En pruebas comparar fechas con `isEqual(...)` o por `toInstant()`, no con `equals`.

### 3.2 Secuencia `atencion_codigo_seq` con `DatabaseClient`

`SELECT nextval('atencion_codigo_seq')` devuelve `bigint` → `row.get(0, Long.class)`. Se pide **después** de las validaciones (paso 5 de spec 04 §5.1) para no consumir números en solicitudes rechazadas (la secuencia puede tener huecos: es aceptable). Fecha del código: hoy en Lima según el `Clock`.

### 3.3 Índice único parcial y `ATENCION_DUPLICADA`

- Garantía real: `ux_atencion_cita_activa ON atencion (cita_id) WHERE estado <> 'ANULADA'`. La consulta previa `existsActivaByCitaId` solo mejora el mensaje (spec 00 §6).
- Carrera (dos `POST` simultáneos): el segundo `INSERT` viola el índice → r2dbc-postgresql lanza `R2dbcDataIntegrityViolationException` con SQLSTATE `23505` → Spring R2DBC la traduce a `org.springframework.dao.DuplicateKeyException` → `onErrorMap` a `409 ATENCION_DUPLICADA`. `ManejadorErrores` **no** maneja `DuplicateKeyException` (iría a 500): la traducción es obligatoria en el servicio.
- Anular libera la cita (el índice es parcial): se puede registrar una nueva atención si la cita sigue `CONFIRMADA` en Citas.

### 3.4 Concurrencia de estado → `CONFLICTO_CONCURRENTE`

`save` de una entidad leída con `version = n` que otro proceso ya movió a `n+1` → `OptimisticLockingFailureException` → `409 CONFLICTO_CONCURRENTE`.

### 3.5 Consultas dinámicas con `R2dbcEntityTemplate` + `Criteria`

Ver §2.3 `AtencionConsultas`. `Query.with(Pageable)` aplica `limit`, `offset` y `sort`; el `sort` por propiedad (`fechaAtencion`) lo traduce el `QueryMapper` a `fecha_atencion`. Orden estable: `fecha_atencion DESC, id ASC`. `count` usa el **mismo** `Criteria` sin paginación. Total en `X-Total-Count` vía `RespuestaPaginada.de` (commons).

### 3.6 Cliente a Citas

`@Bean @LoadBalanced WebClient.Builder` + `ClienteHttpFactory.crear(builder, "http://citas-service")` (hace `clone()` del builder ya instrumentado por el LoadBalancer) + `PoliticaResiliencia.proteger(llamada, "SERVICIO_CITAS_NO_DISPONIBLE", "citas")`. Mensajes resultantes (los arma commons): `"El servicio de citas no esta disponible. Intente mas tarde."` (503) y `"El servicio de citas no respondio a tiempo."` (504). La traza (`traceparent`) la propaga el agente OTel; no hay código de trazas en el servicio.

### 3.7 Eventos

`PublicadorEventos` (bean de commons, existe si `hospital.rabbit.enabled=true`, por defecto) publica en `hospital.eventos` con *publisher confirms*. Se publica **después** del `save` (documento §6.4: sin *outbox*). Routing keys de `TopologiaHospital`: `ATENCION_REGISTRADA`, `ATENCION_FINALIZADA`, `ATENCION_ANULADA`. Sobre: `origen = "atenciones-service"`, `tipoRecurso = "ATENCION"`, `recursoId = id de la atención`, `tipo = routing key`. Citas lee de `datos` solo `atencionId` y `citaId` (spec 03 §7.2). El servicio no declara colas ni consume.

---

## 4. Pruebas (todas obligatorias salvo las marcadas "adicional", que también se implementan)

Datos de prueba: UUID aleatorios, sin datos personales. Reloj fijo común: `Clock.fixed(Instant.parse("2026-10-14T14:00:00Z"), ZoneId.of("America/Lima"))` = miércoles 2026-10-14 **09:00** Lima. Aserciones con AssertJ; flujos con `StepVerifier`. Para errores: `expectErrorSatisfies(e -> { assertThat(e).isInstanceOf(ErrorNegocio.class); assertThat(((ErrorNegocio) e).getCodigo()).isEqualTo("..."); assertThat(((ErrorNegocio) e).getStatus()).isEqualTo(HttpStatus.CONFLICT); })`.

### 4.1 `EstadoAtencionTest` (unitaria)

`@ParameterizedTest` + `@CsvSource` con la matriz 3×3 completa (9 filas `origen,destino,esperado`): permitidas solo `REGISTRADA→FINALIZADA` y `REGISTRADA→ANULADA`; las 7 restantes (incluidas `X→X`) prohibidas. Más `esFinal()`: `REGISTRADA` false, `FINALIZADA`/`ANULADA` true.

### 4.2 `VentanaAtencionTest` (unitaria, adicional)

Con el reloj fijo y `new VentanaAtencion(30)`: cita 09:30 Lima (30 min exactos) → no lanza; cita 07:00 (2 h después) → no lanza; cita 09:31 → `FUERA_DE_VENTANA_ATENCION` 409 y el detalle contiene `"30 minutos"` y `"14/10/2026 09:31"`; cita expresada en UTC (`2026-10-14T14:30:00Z`) → no lanza (compara instantes); `new VentanaAtencion(60)` con cita 09:45 → no lanza; `new VentanaAtencion(-1)` → `IllegalArgumentException`.

### 4.3 `AtencionTest` (unitaria)

- `registrar(cita, CONSULTA_EXTERNA, "ATE-20261014-000001", reloj)`: `estado == REGISTRADA`, `pacienteId == cita.pacienteId()`, `citaId == cita.id()`, `fechaAtencion` = instante del reloj, `isNew() == true`, `getVersion() == null`, `getId() != null`.
- `cambiarEstado(FINALIZADA)` devuelve `REGISTRADA`, deja `FINALIZADA` y actualiza `fechaActualizacion` (usar un segundo reloj fijo posterior).
- `FINALIZADA → ANULADA` → `ErrorNegocio` 409 `TRANSICION_NO_PERMITIDA`, y el estado no cambia.
- `ANULADA → FINALIZADA` → 409.
- `formatearCodigo(LocalDate.of(2026,10,14), 1)` = `"ATE-20261014-000001"`; con `1234567` = `"ATE-20261014-1234567"` (no trunca).

### 4.4 `CitasClientTest` (MockWebServer, sin Spring, sin Eureka, sin `@LoadBalanced`)

`@BeforeEach`: `servidor = new MockWebServer(); servidor.start(); cliente = new CitasClient(WebClient.builder(), servidor.url("/").toString());` — `@AfterEach`: `servidor.shutdown()`.

| Caso | Preparación | Esperado |
|---|---|---|
| `200` | `MockResponse` con `Content-Type: application/json` y cuerpo `{"id":"<c>","pacienteId":"<p>","estado":"CONFIRMADA","fechaHora":"2026-10-14T09:30:00-05:00","codigo":"CIT-20261014-000001","especialidad":"MEDICINA_GENERAL"}` (campos extra a propósito) | `CitaResumen` con `id`, `pacienteId`, `estado`; `fechaHora.isEqual(...)`; `servidor.takeRequest().getPath()` = `/api/citas/<c>` |
| `404` | `setResponseCode(404)` | `ErrorNegocio` 404 `CITA_NO_ENCONTRADA`; `getRequestCount() == 1` (no reintenta) |
| `500` | `setResponseCode(500)` | `ErrorNegocio` 503 `SERVICIO_CITAS_NO_DISPONIBLE`; 1 petición |
| retraso | cuerpo válido con `setHeadersDelay(3, TimeUnit.SECONDS)` | `ErrorNegocio` 504 `SERVICIO_CITAS_NO_DISPONIBLE` (el timeout real es 2 s; usar `.verify(Duration.ofSeconds(6))`) |
| error de red reintenta una vez | `String url = servidor.url("/").toString(); servidor.shutdown();` y cliente con `WebClient.builder().filter((req, next) -> { intentos.incrementAndGet(); return next.exchange(req); })` | `ErrorNegocio` 503 y `intentos.get() == 2`; luego `servidor = new MockWebServer();` para que `@AfterEach` no falle (mismo truco que `PoliticaResilienciaTest` de commons) |

### 4.5 `EventosAtencionTest` (unitaria, adicional)

- `registrada(...)`: `tipo = "atencion.registrada"`, `origen = "atenciones-service"`, `tipoRecurso = "ATENCION"`, `recursoId = id`, `estadoAnterior == null`, `estadoNuevo = "REGISTRADA"`, `datos` con `atencionId`, `citaId`, `pacienteId`, `codigo`, `area = "CONSULTA_EXTERNA"`, `areaNombre = "Consulta Externa"`, `fechaAtencion = "2026-10-14T09:00:00-05:00"` (con segundos y offset de Lima), `eventId != null`.
- `cambioEstado(atencionAnulada, REGISTRADA, "Error de registro", reloj)`: `tipo = "atencion.anulada"`, anterior/nuevo correctos, `datos.motivo`; con `motivo = null` → `datos.has("motivo")` y `datos.get("motivo").isNull()`.
- `routingKeyDe(FINALIZADA) = "atencion.finalizada"`.

### 4.6 `AtencionServiceTest` (`@ExtendWith(MockitoExtension.class)`, `StepVerifier`, reloj fijo)

Mocks: `AtencionRepository`, `AtencionConsultas`, `GeneradorCodigoAtencion`, `CitasClient`, `PublicadorEventos`. Reales: `new VentanaAtencion(30)`, reloj fijo. Stubs típicos: `generador.siguiente()` → `Mono.just("ATE-20261014-000001")`; `repositorio.save(any())` → `thenAnswer(inv -> Mono.just(inv.getArgument(0)))`; `publicador.publicar(anyString(), any())` → `Mono.empty()` (**obligatorio**: un mock sin stub devuelve `null` y rompe con NPE); `repositorio.existsActivaByCitaId(any())` → `Mono.just(false)`. Con `MockitoExtension` (strict stubs) declarar solo los stubs que cada prueba usa, o marcarlos `lenient()` si se comparten en `@BeforeEach`.

| Caso | Esperado |
|---|---|
| Registro OK, cita `CONFIRMADA` a las 09:30 (30 min exactos) | Respuesta `REGISTRADA` con `pacienteId` de la cita y `codigo`; `publicar("atencion.registrada", captor)` una vez; el sobre capturado tiene `datos.citaId` y `datos.pacienteId` correctos y `recursoId` = id de la respuesta |
| Registro OK, cita 2 h antes del reloj (07:00) | OK |
| Cita 31 min después (09:31) | 409 `FUERA_DE_VENTANA_ATENCION`; `verify(repositorio, never()).save(any())`; sin publicar |
| Cita `PENDIENTE` | 409 `CITA_NO_CONFIRMADA`, sin `existsActivaByCitaId`, sin `save`, sin publicar |
| Cita `CANCELADA` | 409 `CITA_NO_CONFIRMADA` |
| Atención activa existente (`existsActivaByCitaId` → `true`) | 409 `ATENCION_DUPLICADA`; `generador.siguiente()` no se llama; sin `save` |
| `save` → `Mono.error(new DuplicateKeyException("ux_atencion_cita_activa"))` | 409 `ATENCION_DUPLICADA`; sin publicar |
| Citas caído: `citas.obtener` → `Mono.error(new ErrorNegocio(SERVICE_UNAVAILABLE, "SERVICIO_CITAS_NO_DISPONIBLE", "..."))` | Mismo error 503; sin `save` |
| Cita inexistente: `citas.obtener` → error 404 `CITA_NO_ENCONTRADA` | Se propaga 404 |
| Anular (`findById` → atención `REGISTRADA`) | Respuesta `ANULADA`; publica `atencion.anulada` con `estadoAnterior = REGISTRADA`, `datos.motivo` |
| Finalizar | Publica `atencion.finalizada` |
| `FINALIZADA → ANULADA` | 409 `TRANSICION_NO_PERMITIDA`; sin `save` |
| `save` → `Mono.error(new OptimisticLockingFailureException("x"))` al cambiar estado | 409 `CONFLICTO_CONCURRENTE`; sin publicar |
| `obtener` / `cambiarEstado` con `findById` → `Mono.empty()` | 404 `ATENCION_NO_ENCONTRADA` |

Para crear atenciones "leídas de BD" en las pruebas: `Atencion.registrar(...)` es suficiente (el servicio no mira `isNew`), o exponer el constructor `@PersistenceCreator` como *package-private* y crear un ayudante de prueba en el mismo paquete `domain` si se necesita un estado concreto; preferir `registrar(...)` + `cambiarEstado(...)` para llegar a `FINALIZADA`.

### 4.7 `AtencionControllerTest` (`@WebFluxTest`)

```java
@WebFluxTest(controllers = AtencionController.class,
             properties = {"hospital.rabbit.enabled=false", "eureka.client.enabled=false"})
@Import(ManejadorErrores.class)
@ActiveProfiles("test")
class AtencionControllerTest {
    @Autowired WebTestClient cliente;
    @MockitoBean AtencionService servicio;   // org.springframework.test.context.bean.override.mockito.MockitoBean (no @MockBean, deprecado en 3.4+)
}
```

| Caso | Esperado |
|---|---|
| `POST` válido (servicio devuelve respuesta) | `201`, cabecera `Location: /api/atenciones/<id>`, cuerpo con `codigo`, `estado = REGISTRADA`, `areaNombre` |
| `POST` con `"area":"QUIROFANO"` | `400`, `application/problem+json`, `$.codigo = VALIDACION_FALLIDA`; servicio no invocado |
| `POST` sin `citaId` | `400`, `$.codigo = VALIDACION_FALLIDA`, `$.errores[0].campo = citaId` |
| `GET /api/atenciones/{id}` inexistente (servicio → `Mono.error(ErroresAtencion.atencionNoEncontrada(id))`) | `404`, `$.codigo = ATENCION_NO_ENCONTRADA` |
| `PATCH /{id}/estado` prohibido (servicio → 409 `TRANSICION_NO_PERMITIDA`) | `409`, `$.codigo = TRANSICION_NO_PERMITIDA` |
| (adicional) `GET /api/atenciones?estado=REGISTRADA` | `200`, cabecera `X-Total-Count` = valor de `servicio.contar(...)`, arreglo JSON |
| (adicional) `GET /api/atenciones?tamano=0` | `400 VALIDACION_FALLIDA` |
| (adicional) `GET /api/atenciones/no-es-uuid` | `400 VALIDACION_FALLIDA` |

### 4.8 `AtencionRepositoryIT` (`@DataR2dbcTest` + PostgreSQL real)

```java
@DataR2dbcTest
@Testcontainers
@ActiveProfiles("test")
@Import({GeneradorCodigoAtencion.class, AtencionConsultas.class, AtencionRepositoryIT.RelojFijo.class})
class AtencionRepositoryIT {
    @Container @ServiceConnection
    static PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16");

    @TestConfiguration
    static class RelojFijo { @Bean Clock reloj() { return Clock.fixed(Instant.parse("2026-10-14T14:00:00Z"), ZoneId.of("America/Lima")); } }
}
```

`schema.sql` se ejecuta porque `@DataR2dbcTest` incluye `SqlInitializationAutoConfiguration` y `application.yml` tiene `spring.sql.init.mode: always`. Las pruebas R2DBC **no** hacen *rollback*: usar `citaId`/códigos aleatorios por prueba (código: `"ATE-T-" + UUID.randomUUID().toString().substring(0, 8)`, cabe en `VARCHAR(30)`).

| Caso | Esperado |
|---|---|
| Dos atenciones `REGISTRADA` con la misma `citaId` | El segundo `save` → `DuplicateKeyException` |
| Primera atención pasada a `ANULADA` (leer con `findById`, `cambiarEstado(ANULADA)`, `save`) y luego otra `REGISTRADA` misma cita | Permitido |
| `existsActivaByCitaId` | `true` con una `REGISTRADA`; `false` si solo hay `ANULADA` |
| Secuencia de código | Dos llamadas a `generador.siguiente()`: ambas cumplen `^ATE-20261014-\d{6}$` y el número de la segunda = primera + 1 |
| (adicional) `@Version` | Guardar una nueva (queda `version = 0` al leerla); leer **dos** instancias con `findById`; `cambiarEstado` y `save` de la primera OK (version 1); `save` de la segunda → `OptimisticLockingFailureException` |
| (adicional) Listado | Insertar 3 atenciones de un mismo `pacienteId` (aleatorio), cada una creada con un `Clock.fixed` distinto del mismo día (09:00, 10:00, 11:00 Lima) y con `citaId` distintos; anular la de 09:00. `consultas.contar(filtro pacienteId + estado REGISTRADA)` = 2; `buscar(filtro solo pacienteId, ParametrosPagina.de(0, 1))` devuelve 1 elemento: la de 11:00 (orden `fecha_atencion DESC`); filtro `pacienteId + fecha = 2026-10-14` cuenta 3 y con `fecha = 2026-10-15` cuenta 0 |

### 4.9 `AtencionFlujoIT` (`@SpringBootTest` + PostgreSQL + RabbitMQ + MockWebServer como Citas)

```java
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
@ActiveProfiles("test")
class AtencionFlujoIT {
    @Container @ServiceConnection
    static PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16");
    @Container
    static RabbitMQContainer RABBIT = new RabbitMQContainer("rabbitmq:3.13-management");
    static final MockWebServer CITAS = new MockWebServer();
    static { try { CITAS.start(); } catch (IOException e) { throw new UncheckedIOException(e); } }

    @DynamicPropertySource
    static void propiedades(DynamicPropertyRegistry r) {
        r.add("hospital.rabbit.host", RABBIT::getHost);
        r.add("hospital.rabbit.port", RABBIT::getAmqpPort);
        r.add("hospital.rabbit.username", RABBIT::getAdminUsername);
        r.add("hospital.rabbit.password", RABBIT::getAdminPassword);
        r.add("eureka.client.enabled", () -> "false");
    }

    @TestConfiguration
    static class CitasDePrueba {
        @Bean @Primary
        CitasClient citasClientPrueba() { return new CitasClient(WebClient.builder(), CITAS.url("/").toString()); }
    }

    @Autowired WebTestClient cliente;
    @Autowired ObjectMapper mapper;
    @AfterAll static void cerrar() throws IOException { CITAS.shutdown(); }
}
```

Caso principal (`registrarPublicaAtencionRegistrada`):

1. Conexión AMQP directa (`com.rabbitmq.client.ConnectionFactory` con host/puerto/credenciales del contenedor): `canal.exchangeDeclare("hospital.eventos", "topic", true)` (mismos atributos que `PublicadorRabbit`: idempotente), `canal.queueDeclare("prueba.atenciones", false, false, false, null)`, `canal.queueBind("prueba.atenciones", "hospital.eventos", "atencion.*")`. **Antes** del `POST`.
2. `CITAS.enqueue(...)` con `Content-Type: application/json` y una cita `CONFIRMADA` cuyo `fechaHora` = `OffsetDateTime.now(ZoneId.of("America/Lima")).plusMinutes(10)` (dentro de la ventana con el reloj real).
3. `POST /api/atenciones` `{"citaId":"<c>","area":"CONSULTA_EXTERNA"}` → `201`, `Location`, cuerpo con `id` y `codigo` que cumple `^ATE-\d{8}-\d{6}$`.
4. `await().atMost(Duration.ofSeconds(10))` hasta que `canal.basicGet("prueba.atenciones", true) != null`; deserializar el cuerpo con `mapper.readValue(bytes, EventoEnvelope.class)`.
5. Verificar: `tipo = "atencion.registrada"`, `origen = "atenciones-service"`, `tipoRecurso = "ATENCION"`, `recursoId` = id devuelto, `estadoNuevo = "REGISTRADA"`, `datos.citaId` = `<c>`, `datos.pacienteId` = el de la cita simulada. `CITAS.takeRequest().getPath()` = `/api/citas/<c>`.
6. (adicional) `PATCH /api/atenciones/{id}/estado` `{"estado":"ANULADA","motivo":"Prueba"}` → `200`; el siguiente mensaje de la cola es `atencion.anulada` con `estadoAnterior = REGISTRADA`.

Cerrar canal y conexión AMQP al final (`try-with-resources` o `@AfterAll`). En pruebas sí se permite `Thread.sleep`/bloqueos; en producción no.

---

## 5. Cómo ejecutar y verificar

Desde `/home/cazabache/hospital-vida-salud`:

```bash
# Compilación rápida (sin Docker)
./mvnw -q -pl atenciones-service test-compile

# Una prueba unitaria
./mvnw -q -pl atenciones-service test -Dtest='AtencionServiceTest' -Dsurefire.failIfNoSpecifiedTests=false

# Todo (unitarias + IT con Testcontainers). El grupo docker no está activo en la shell: usar sg.
sg docker -c "./mvnw -q -pl atenciones-service verify"
```

- **No** usar `-am` (`hospital-commons` y el POM padre ya están en `~/.m2`). Si aparece `Could not find artifact pe.edu.cibertec.vidasalud:hospital-vida-salud-parent:pom`, ejecutar `./mvnw -q -N install` (solo instala el `pom.xml` raíz) y repetir.
- Imágenes ya presentes localmente: `postgres:16`, `rabbitmq:3.13-management`, `testcontainers/ryuk`. Docker Engine 29.1 funciona con Testcontainers 1.21.4 (las IT de commons pasaron).
- El PostgreSQL (5433) y RabbitMQ (5672) del Compose pueden estar levantados: no interfieren (las pruebas usan puertos aleatorios de Testcontainers).
- Revisiones finales (deben salir vacías):

```bash
grep -rnE "\.block\(|\.subscribe\(|Thread\.sleep|System\.out|printStackTrace" atenciones-service/src/main/java
grep -rnE "lombok|jakarta\.persistence|java\.sql|JdbcTemplate|@Autowired" atenciones-service/src/main/java
```

- Contar pruebas: `ls atenciones-service/target/surefire-reports/*.txt atenciones-service/target/failsafe-reports/*.txt` y comprobar 0 fallos/errores.
- (Opcional, manual, no requerido para terminar) Con infraestructura + Eureka + Citas + Pacientes levantados: `ATENCIONES-SERVICE` aparece en `http://localhost:8761`; flujo de spec 04 §10 vía Gateway (`http://localhost:8080/api/atenciones`); Swagger en `http://localhost:8085/swagger-ui.html`.

---

## 6. Trampas conocidas

| # | Trampa | Cómo evitarla |
|---|---|---|
| T-1 | `@ServiceConnection` con `PostgreSQLContainer` para R2DBC falla con `NoClassDefFoundError: org/testcontainers/r2dbc/R2DBCDatabaseContainer` | Dependencia de prueba `org.testcontainers:r2dbc` (paso 1, DM-10) |
| T-2 | `version` declarado `final` → `UnsupportedOperationException`/error de mapeo al insertar o actualizar | `@Version private Long version;` mutable; igual `estado` y `fechaActualizacion` |
| T-3 | Sin `Persistable`, `save` con id asignado hace `UPDATE` de una fila inexistente | `implements Persistable<UUID>` + `@Transient boolean nuevo` (anotación de **Spring Data**) |
| T-4 | `DuplicateKeyException`/`OptimisticLockingFailureException` sin traducir → `500 ERROR_INTERNO` | `onErrorMap` en el servicio (commons no las maneja) |
| T-5 | `Criteria.empty().and(...)` sin reasignar pierde la condición | `c = c.and("x").is(v)` (inmutable) |
| T-6 | Enum en `Criteria` | Pasar `.name()` |
| T-7 | `OffsetDateTime.toString()` omite segundos (`09:00-05:00`) | `DateTimeFormatter.ISO_OFFSET_DATE_TIME` en `datos` del evento |
| T-8 | Comparar `OffsetDateTime` con `equals` tras leer de BD (offset `Z` vs `-05:00`) | `isEqual` o `toInstant()` |
| T-9 | Las *slices* (`@WebFluxTest`, `@DataR2dbcTest`) excluyen clases `@Configuration` del escaneo, pero lo que se ponga en `AtencionesServiceApplication` sí se carga en todas | No agregar anotaciones a la clase principal; la config va en `config/AtencionConfig` |
| T-10 | `@WebFluxTest` no registra `ManejadorErrores` (está en otro paquete y su autoconfig no es parte del *slice*) | `@Import(ManejadorErrores.class)` |
| T-11 | `@MockBean` está deprecado en Boot 3.4+ | `@MockitoBean` (`org.springframework.test.context.bean.override.mockito`) |
| T-12 | `MockResponse` sin `Content-Type` → `UnsupportedMediaTypeException` al decodificar | `.setHeader("Content-Type", "application/json")` |
| T-13 | El timeout de `PoliticaResiliencia.proteger` es fijo (2 s); `aplicar(Mono, Duration)` es *package-private* | En `CitasClientTest` usar un retraso real de 3 s y `verify(Duration.ofSeconds(6))` |
| T-14 | `MockitoExtension` (strict stubs) falla con `UnnecessaryStubbingException` | Stubs por prueba o `lenient()` |
| T-15 | `PublicadorEventos` mockeado sin stub devuelve `null` → NPE en `.thenReturn` | `when(publicador.publicar(anyString(), any())).thenReturn(Mono.empty())` |
| T-16 | En `AtencionFlujoIT`, con `hospital.clientes.citas.url` apuntando a `localhost:<puerto>` y el builder `@LoadBalanced`, el LoadBalancer busca un servicio llamado `localhost` → 503 | Usar el `CitasClient` `@Primary` sin `@LoadBalanced` (DM-12) |
| T-17 | `MockWebServer` debe estar arrancado antes de crear el contexto | Bloque `static { CITAS.start(); }` |
| T-18 | Crear la cola de prueba **después** del `POST` pierde el mensaje (el *exchange* topic descarta lo que no tiene *binding*) | Declarar exchange + cola + *binding* antes del `POST` |
| T-19 | `exchangeDeclare` con atributos distintos a los de `PublicadorRabbit` → `PRECONDITION_FAILED` | `("hospital.eventos", "topic", true)` (durable, no auto-delete, sin argumentos) |
| T-20 | `@DataR2dbcTest` no hace *rollback* | Datos aleatorios por prueba; no contar filas globales |
| T-21 | `GeneradorCodigoAtencion` necesita `Clock`, que no está en el *slice* `@DataR2dbcTest` | `@TestConfiguration` con `Clock` fijo importada (§4.8) |
| T-22 | `src/test/resources/application.yml` taparía el principal | Usar `application-test.yml` + `@ActiveProfiles("test")` |
| T-23 | Mensaje de ventana con "30" fijo | Usar `minutosAntes` configurado |
| T-24 | Registrar `motivo` u otros textos en logs | Solo UUID, códigos y estados (spec 00 §8) |
| T-25 | Llamar a `nextval` antes de validar consume números innecesariamente | Respetar el orden de spec 04 §5.1 |
| T-26 | Inyectar `WebClient.Builder` sin `@LoadBalanced` en producción | Solo existe el bean balanceado; inyectarlo siempre con `@LoadBalanced` |
| T-27 | Modificar `hospital-commons` | Prohibido en esta tarea; si se detecta un defecto en commons, informarlo |

---

## 7. Criterios de terminado

- [ ] Existen todos los archivos de §2 con los paquetes, firmas y responsabilidades indicados; `AtencionesServiceApplication` sin cambios.
- [ ] `application.yml` incluye `hospital.atencion.minutos-antes: ${ATENCION_MINUTOS_ANTES:30}`; `schema.sql` es el DDL del documento §8.4 con `IF NOT EXISTS`.
- [ ] `sg docker -c "./mvnw -q -pl atenciones-service verify"` termina con código 0, ejecutando: `EstadoAtencionTest`, `VentanaAtencionTest`, `AtencionTest`, `CitasClientTest`, `EventosAtencionTest`, `AtencionServiceTest`, `AtencionControllerTest` (Surefire) y `AtencionRepositoryIT`, `AtencionFlujoIT` (Failsafe), todas con 0 fallos y 0 errores.
- [ ] Los greps de §5 salen vacíos (sin `.block()`, `.subscribe()`, `Thread.sleep`, `System.out`, Lombok, JPA/JDBC ni `@Autowired` en `src/main`).
- [ ] Errores de negocio con los códigos exactos: `CITA_NO_ENCONTRADA`, `CITA_NO_CONFIRMADA`, `FUERA_DE_VENTANA_ATENCION`, `ATENCION_DUPLICADA`, `ATENCION_NO_ENCONTRADA`, `TRANSICION_NO_PERMITIDA`, `CONFLICTO_CONCURRENTE`, `SERVICIO_CITAS_NO_DISPONIBLE` (503/504), `VALIDACION_FALLIDA`.
- [ ] Eventos con routing keys de `TopologiaHospital` y sobre `origen = atenciones-service`, `tipoRecurso = ATENCION`, `datos` con `atencionId`, `citaId`, `pacienteId`, `codigo`, `area`, `areaNombre`, `fechaAtencion` (+ `motivo` en finalizada/anulada).
- [ ] No se modificó ningún archivo fuera de `atenciones-service/` (en particular, nada en `hospital-commons/`, `pom.xml` raíz ni otros servicios).
