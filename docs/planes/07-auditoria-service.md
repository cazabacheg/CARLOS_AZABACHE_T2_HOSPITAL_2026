# Plan de implementación 07 — `auditoria-service`

> Ejecutor: agente que **no** vio la conversación de diseño. Este plan es autosuficiente.
> Fuentes: `docs/specs/00-convenciones-comunes.md`, `docs/specs/07-auditoria-service.md`, `docs/specs/08-observabilidad.md`, specs 02–05 (§6/§7, eventos publicados), `docs/documento-tecnico.md` §5.7, §6.4, §8.3, §9, §10, código real de `hospital-commons`.
> No existe aún `docs/planes/02-pacientes-service.md`; este plan sigue la spec 00 §3. Si al ejecutar ya existe, **no** cambies este diseño por él salvo en detalles cosméticos (nombres de métodos auxiliares de prueba).

## DUDAS CRUCIALES

Ninguna. Hay una **desviación menor** de la spec 07 §5.2 (sobre incompleto → DLQ "inmediata"), resuelta en D-M1: con el `ConsumidorEventos` real de `hospital-commons` el sobre incompleto llega a la DLQ **tras 3 intentos rápidos (~1,5–2,5 s) que no tocan la BD**, sin modificar `hospital-commons`.

## Decisiones menores tomadas

| # | Decisión | Motivo |
|---|---|---|
| D-M1 | Un sobre deserializable pero **incompleto** (sin `tipoRecurso`, `recursoId`, `estadoNuevo`, `origen` u `ocurrioEn`, o con longitudes mayores que las columnas) hace que el manejador devuelva `Mono.error(new SobreInvalidoException(...))` **antes** de tocar la BD. `ConsumidorEventos` lo reintenta 2 veces más (backoff 500 ms) y luego `nack(requeue=false)` → `auditoria.dlq`. | Verificado en `ConsumidorEventos.procesar`: solo el JSON ilegible o sin `eventId`/`tipo` va a la DLQ sin reintentos; cualquier otro error del manejador pasa por `retryWhen(Retry.backoff(2, 500ms))` y luego `nack(false)`. El manejador **no** tiene acceso a la entrega para hacer `nack`. Alternativa descartada: cambiar `hospital-commons` (exige reinstalarlo y re-verificar commons; fuera de alcance). Coste: ~2 s y 3 líneas de log por sobre mal formado; resultado final idéntico (DLQ). Validar antes del `INSERT` evita que los reintentos golpeen la BD. |
| D-M2 | `ocurrioEn` también se valida como obligatorio | `fecha_evento` es `NOT NULL`; sin validarlo el fallo sería un error de BD más tardío (mismo destino, más ruido). |
| D-M3 | El consumidor **no** restringe `tipoRecurso` a `PACIENTE|CITA|ATENCION|PAGO` (solo exige que no esté vacío y quepa en 30 caracteres) | Binding `#`: "todo evento, presente o futuro, se audita sin cambiar este servicio" (spec 07 §3). La restricción a los 4 valores se aplica solo en la **API** (historial y filtro del listado). |
| D-M4 | El filtro `tipoRecurso` del listado también se valida contra el enum (`400` si no es válido) | Coherencia con el historial; un valor inválido nunca devolvería filas. |
| D-M5 | `fechaRegistro` la asigna la aplicación con `OffsetDateTime.now(clock)` (no el `DEFAULT now()` de la BD) | Se puede fijar con `Clock` en pruebas; la columna conserva el `DEFAULT` del documento §8.3. |
| D-M6 | `EventoAuditoriaResponse` expresa las fechas en la zona `America/Lima` (`RelojAutoConfiguration.ZONA_HOSPITAL`) | El driver puede devolver `TIMESTAMPTZ` con offset UTC; para la demo se muestran con `-05:00` (mismo instante). |
| D-M7 | El servicio devuelve DTOs (`EventoAuditoriaResponse`), no entidades | La entidad nunca sale del servicio (spec 00 §3 regla 3); simplifica las pruebas de controlador. |
| D-M8 | El orden cronológico del historial lo hace **SQL** (`ORDER BY fecha_evento ASC, fecha_registro ASC, id ASC`); el servicio conserva ese orden | Se prueba en `AuditoriaRepositoryIT` y `AuditoriaConsumerIT`; `AuditoriaServiceTest` verifica que el servicio no lo altera. Desempates estables para eventos con la misma `fecha_evento`. |
| D-M9 | Las validaciones de parámetros de la API (`ParametrosPagina.de`, `TipoRecurso.desde`, `FiltroAuditoria`) se ejecutan de forma **síncrona** en el controlador y lanzan `ErrorNegocio` | Mismo patrón que `ParametrosPagina.de` de commons; una excepción lanzada por el método del controlador la traduce `ManejadorErrores` (400 `VALIDACION_FALLIDA`). Con el servicio mockeado, las pruebas de controlador siguen viendo los 400. |
| D-M10 | `ConsumidorEventos.iniciar(...)` (API real) en lugar de `consumir(...).subscribe()` | `iniciar` encapsula la única `subscribe()` permitida y la libera en `destroy()`. La spec 00 menciona `consumir`; el código real ofrece ambos. |
| D-M11 | Se agrega la dependencia de prueba `org.testcontainers:r2dbc` | **Verificado** en `spring-boot-testcontainers-3.5.16`: `PostgresR2dbcContainerConnectionDetailsFactory` llama a `PostgreSQLR2DBCDatabaseContainer.getOptions(...)`, que implementa `R2DBCDatabaseContainer` del módulo `org.testcontainers:r2dbc`; ese módulo **no** es transitivo de `org.testcontainers:postgresql` (verificado con `dependency:tree`). Sin él, `@ServiceConnection` falla con `NoClassDefFoundError`. Versión gestionada por el BOM (1.21.4). |
| D-M12 | Comando de verificación sin `-am` (instrucción del proyecto) aunque la spec 07 §8 diga `-am` | `hospital-commons` ya está instalado en `~/.m2`. |
| D-M13 | En logs se usa `->` (ASCII) en lugar de `→` | Evita problemas de codificación en consola/Seq. |
| D-M14 | La columna `estado_nuevo` conserva el DDL del documento §8.3 (nullable); la obligatoriedad la impone la aplicación | Spec 00 §6: "el DDL de cada servicio es el del documento §8". |

---

## 1. Resumen y orden de implementación

`auditoria-service` (puerto 8084, paquete `pe.edu.cibertec.vidasalud.auditoria`) es un **consumidor puro** de `hospital.eventos` con binding `#` sobre `auditoria.cola` (DLQ `auditoria.dlq`) que inserta un registro inmutable por evento (`evento_auditoria`, sin `datos`) y expone una API de **solo lectura** (listado filtrado/paginado, detalle, historial cronológico por recurso). No publica eventos, no usa `WebClient` ni `@LoadBalanced`. La inmutabilidad se garantiza en la aplicación (repositorio sin update/delete) y en PostgreSQL (triggers).

Pasos (cada uno debe quedar compilando; los comandos están en §5):

0. **Prerrequisito Maven.** Ejecutar `./mvnw -q -N install` en la raíz (instala solo el POM padre en `~/.m2`). Verificado: sin esto, `-pl auditoria-service` falla al leer el descriptor de `hospital-commons` ("Could not find artifact hospital-vida-salud-parent:pom:1.0.0-SNAPSHOT"). Idempotente; el planificador ya lo ejecutó una vez, pero repítelo si ves ese error.
1. **POM**: agregar `org.testcontainers:r2dbc` (test). Verificar: `./mvnw -q -pl auditoria-service compile`.
2. **Configuración y esquema**: `application.yml` (`spring.sql.init.separator: "@@"`), `src/main/resources/schema.sql` (§3.2), `src/test/resources/application-test.yml`. Verificar: `compile`.
3. **Dominio**: `TipoRecurso`, `FiltroAuditoria`, `SobreInvalidoException` + `TipoRecursoTest`, `FiltroAuditoriaTest`. Verificar: `./mvnw -q -pl auditoria-service test`.
4. **Persistencia**: entidad `EventoAuditoria`, interfaz `EventoAuditoriaRepository`, implementación `EventoAuditoriaRepositoryR2dbc`. Verificar: `compile`.
5. **`AuditoriaRepositoryIT`** (PostgreSQL Testcontainers). Verificar: `sg docker -c "... verify -Dit.test=AuditoriaRepositoryIT"`.
6. **Mapeo sobre → entidad**: `EventoAuditoriaMapper` + `EventoAuditoriaMapperTest`. Verificar: `test`.
7. **Servicio**: `AuditoriaService` + `AuditoriaServiceTest`. Verificar: `test`.
8. **API**: `EventoAuditoriaResponse`, `AuditoriaController` + `AuditoriaControllerTest`. Verificar: `test`.
9. **Arranque del consumo**: `ConsumoAuditoriaConfig` (`ApplicationRunner`). Verificar: `compile` + `test`.
10. **`AuditoriaConsumerIT`** (PostgreSQL + RabbitMQ). Verificar: `sg docker -c "... verify -Dit.test=AuditoriaConsumerIT"`.
11. **Verificación final** completa y revisión de reglas (§7). Verificar: `sg docker -c "./mvnw -q -pl auditoria-service verify"`.

---

## 2. Archivos exactos

Base: `auditoria-service/`. Paquete raíz `pe.edu.cibertec.vidasalud.auditoria` (abreviado `…auditoria`).

### 2.1 Modificados

| Archivo | Cambio |
|---|---|
| `pom.xml` (del módulo) | Agregar en `<dependencies>` (sección de pruebas): `<dependency><groupId>org.testcontainers</groupId><artifactId>r2dbc</artifactId><scope>test</scope></dependency>` (sin versión). No tocar el resto (ya tiene webflux, validation, actuator, data-r2dbc, r2dbc-postgresql runtime, eureka-client, springdoc, commons, starter-test, reactor-test, spring-boot-testcontainers, testcontainers junit-jupiter/postgresql/rabbitmq, mockwebserver). |
| `src/main/resources/application.yml` | Agregar `separator: "@@"` bajo `spring.sql.init` (queda `mode: always` + `separator: "@@"`). Nada más cambia (puerto 8084, `AUDITORIA_DB_*`, `hospital.rabbit.*`, eureka, management, logging, springdoc ya están). |
| `…auditoria/AuditoriaServiceApplication.java` | Sin cambios. |

`application.yml` resultante (fragmento):

```yaml
spring:
  sql:
    init:
      mode: always
      separator: "@@"
```

### 2.2 Nuevos — main

#### `src/main/resources/schema.sql`
Contenido exacto en §3.2.

#### `…auditoria.domain.TipoRecurso` (enum)
```java
public enum TipoRecurso {
    PACIENTE, CITA, ATENCION, PAGO;

    /** null o en blanco -> null; valor exacto en mayúsculas -> enum; otro -> ErrorNegocio 400. */
    public static TipoRecurso desdeOpcional(String valor)
    /** Obligatorio: null/blanco o inválido -> ErrorNegocio.validacion("tipoRecurso", "debe ser uno de: PACIENTE, CITA, ATENCION, PAGO") */
    public static TipoRecurso desde(String valor)
}
```
Comparación exacta (sin `toUpperCase`): `"cita"` → 400. Implementar con un bucle sobre `values()` (no `valueOf` + catch).

#### `…auditoria.domain.FiltroAuditoria` (record)
```java
public record FiltroAuditoria(TipoRecurso tipoRecurso, UUID recursoId, String tipoEvento,
                              String origen, OffsetDateTime desde, OffsetDateTime hasta) {
    // compact constructor:
    //  - tipoEvento/origen en blanco -> null (strip)
    //  - si desde != null && hasta != null && desde.isAfter(hasta)
    //      -> throw ErrorNegocio.validacion("desde", "debe ser anterior o igual a hasta")
    public static FiltroAuditoria de(String tipoRecurso, UUID recursoId, String tipoEvento,
                                     String origen, OffsetDateTime desde, OffsetDateTime hasta)
    // usa TipoRecurso.desdeOpcional(tipoRecurso)
    public static FiltroAuditoria vacio()
}
```

#### `…auditoria.domain.SobreInvalidoException`
```java
public class SobreInvalidoException extends RuntimeException {
    public SobreInvalidoException(String mensaje) { super(mensaje, null, false, false); }
}
```
El mensaje solo contiene **nombres de campo** (p. ej. `"sobre de evento incompleto: faltan [recursoId, origen]"` o `"campo origen excede 40 caracteres"`), nunca valores.

#### `…auditoria.persistence.EventoAuditoria` (entidad)
```java
@Table("evento_auditoria")
public class EventoAuditoria implements Persistable<UUID> {
    @Id private final UUID id;
    @Column("event_id")        private final UUID eventId;
    @Column("fecha_evento")    private final OffsetDateTime fechaEvento;
    @Column("tipo_evento")     private final String tipoEvento;
    @Column("tipo_recurso")    private final String tipoRecurso;   // String, no enum (D-M3)
    @Column("recurso_id")      private final UUID recursoId;
    @Column("estado_anterior") private final String estadoAnterior;
    @Column("estado_nuevo")    private final String estadoNuevo;
    @Column("origen")          private final String origen;
    @Column("fecha_registro")  private final OffsetDateTime fechaRegistro;
    @Transient private boolean nuevo;   // org.springframework.data.annotation.Transient

    @PersistenceCreator
    public EventoAuditoria(UUID id, UUID eventId, OffsetDateTime fechaEvento, String tipoEvento,
                           String tipoRecurso, UUID recursoId, String estadoAnterior,
                           String estadoNuevo, String origen, OffsetDateTime fechaRegistro)
        // asigna campos; nuevo = false (registro existente leído de BD)

    public static EventoAuditoria nueva(UUID eventId, OffsetDateTime fechaEvento, String tipoEvento,
                                        String tipoRecurso, UUID recursoId, String estadoAnterior,
                                        String estadoNuevo, String origen, OffsetDateTime fechaRegistro)
        // id = UUID.randomUUID(); nuevo = true

    @Override public UUID getId()
    @Override public boolean isNew()  // return nuevo
    // getters de todos los campos (getEventId(), getFechaEvento(), …). SIN setters. SIN campo datos.
}
```
Importes: `org.springframework.data.annotation.{Id, PersistenceCreator, Transient}`, `org.springframework.data.domain.Persistable`, `org.springframework.data.relational.core.mapping.{Table, Column}`. No implementar `equals/hashCode` (no hace falta).

#### `…auditoria.persistence.EventoAuditoriaRepository` (interfaz propia)
```java
/** Solo inserción y consultas. Deliberadamente NO extiende Repository/ReactiveCrudRepository (D-40). */
public interface EventoAuditoriaRepository {
    Mono<EventoAuditoria> insertar(EventoAuditoria evento);
    Mono<EventoAuditoria> buscarPorId(UUID id);
    Flux<EventoAuditoria> buscar(FiltroAuditoria filtro, Pageable pagina);   // orden fecha_evento DESC, id ASC
    Mono<Long> contar(FiltroAuditoria filtro);
    Flux<EventoAuditoria> historial(String tipoRecurso, UUID recursoId);     // orden fecha_evento ASC, fecha_registro ASC, id ASC
}
```

#### `…auditoria.persistence.EventoAuditoriaRepositoryR2dbc`
```java
@Repository
public class EventoAuditoriaRepositoryR2dbc implements EventoAuditoriaRepository {
    static final Sort ORDEN_LISTADO = Sort.by(Sort.Order.desc("fechaEvento"), Sort.Order.asc("id"));
    static final Sort ORDEN_HISTORIAL = Sort.by(Sort.Order.asc("fechaEvento"),
                                                Sort.Order.asc("fechaRegistro"), Sort.Order.asc("id"));
    private final R2dbcEntityTemplate template;

    public EventoAuditoriaRepositoryR2dbc(R2dbcEntityTemplate template)

    insertar  -> template.insert(evento)
    buscarPorId -> template.selectOne(Query.query(Criteria.where("id").is(id)), EventoAuditoria.class)
    buscar    -> template.select(Query.query(criterio(filtro)).with(pagina), EventoAuditoria.class)
                 // el Pageable lo arma el servicio con ParametrosPagina.aPageable(ORDEN_LISTADO)
    contar    -> template.count(Query.query(criterio(filtro)), EventoAuditoria.class)
    historial -> template.select(Query.query(Criteria.where("tipoRecurso").is(tipoRecurso)
                         .and("recursoId").is(recursoId)).sort(ORDEN_HISTORIAL), EventoAuditoria.class)

    static Criteria criterio(FiltroAuditoria f) {
        Criteria c = Criteria.empty();
        if (f.tipoRecurso() != null) c = c.and("tipoRecurso").is(f.tipoRecurso().name());
        if (f.recursoId() != null)   c = c.and("recursoId").is(f.recursoId());
        if (f.tipoEvento() != null)  c = c.and("tipoEvento").is(f.tipoEvento());
        if (f.origen() != null)      c = c.and("origen").is(f.origen());
        if (f.desde() != null)       c = c.and("fechaEvento").greaterThanOrEquals(f.desde());
        if (f.hasta() != null)       c = c.and("fechaEvento").lessThanOrEquals(f.hasta());
        return c;
    }
}
```
`ORDEN_LISTADO` es `public static final` (lo usa el servicio). Se usan **nombres de propiedad** (`fechaEvento`) en `Criteria`/`Sort`: `QueryMapper` los traduce a columnas (`fecha_evento`) usando los metadatos de la entidad. Firmas verificadas en `spring-data-r2dbc 3.5.13` / `spring-data-relational 3.5.13`: `R2dbcEntityTemplate.insert(T)`, `select(Query, Class)`, `selectOne(Query, Class)`, `count(Query, Class)`, `Query.with(Pageable)`, `Query.sort(Sort)`.

**Por qué interfaz propia + `R2dbcEntityTemplate`** (y no `ReactiveCrudRepository`): (a) `ReactiveCrudRepository` expone `save`/`deleteById`/`deleteAll`, que contradicen D-40 "el repositorio no expone update/delete"; (b) las consultas dinámicas con filtros opcionales necesitan `Criteria` + `R2dbcEntityTemplate` de todos modos (spec 00 §4.2); (c) `template.insert` siempre emite `INSERT` (no depende de `isNew()`); `Persistable` se mantiene por convención del proyecto (spec 00 §6). Como no extiende `org.springframework.data.repository.Repository`, Spring Data no la escanea ni genera nada: es un bean `@Repository` normal.

Traducción de errores verificada (`spring-r2dbc 6.2.19`, `ConnectionFactoryUtils.convertR2dbcException`): `R2dbcDataIntegrityViolationException` con SQLSTATE `23505` → `org.springframework.dao.DuplicateKeyException`. Es lo que reconoce `ConsumidorEventos`.

#### `…auditoria.messaging.EventoAuditoriaMapper`
```java
public final class EventoAuditoriaMapper {
    private EventoAuditoriaMapper() {}
    /** Valida y convierte. Lanza SobreInvalidoException. NO copia datos. */
    public static EventoAuditoria aEntidad(EventoEnvelope sobre, OffsetDateTime fechaRegistro)
}
```
Reglas:
* Faltantes (en este orden, acumulando nombres en una lista): `ocurrioEn == null`, `tipoRecurso` null/blank, `recursoId == null`, `estadoNuevo` null/blank, `origen` null/blank → si la lista no está vacía: `throw new SobreInvalidoException("sobre de evento incompleto: faltan " + lista)`.
* Longitudes (columnas del §3.2): `tipo` ≤ 50, `tipoRecurso` ≤ 30, `estadoAnterior` ≤ 20 (si no es null), `estadoNuevo` ≤ 20, `origen` ≤ 40 → si excede: `SobreInvalidoException("campo <nombre> excede <n> caracteres")`.
* `eventId` y `tipo` ya los garantiza `ConsumidorEventos` (no nulos); igual se usan directo.
* Mapeo: `eventId←eventId`, `fechaEvento←ocurrioEn`, `tipoEvento←tipo`, `tipoRecurso←tipoRecurso`, `recursoId←recursoId`, `estadoAnterior←estadoAnterior` (puede ser null), `estadoNuevo←estadoNuevo`, `origen←origen`, `fechaRegistro←parámetro`. Llama a `EventoAuditoria.nueva(...)`. **`datos` se ignora.**

#### `…auditoria.service.AuditoriaService`
```java
@Service
public class AuditoriaService {
    private static final Logger log = LoggerFactory.getLogger(AuditoriaService.class);
    private final EventoAuditoriaRepository repositorio;
    private final Clock reloj;

    public AuditoriaService(EventoAuditoriaRepository repositorio, Clock reloj)

    /** Manejador del consumidor. */
    public Mono<Void> registrar(EventoEnvelope sobre) {
        return Mono.fromCallable(() -> EventoAuditoriaMapper.aEntidad(sobre, OffsetDateTime.now(reloj)))
                .flatMap(repositorio::insertar)
                .doOnNext(e -> log.info("auditoria registrada eventId={} tipo={} recurso={}/{} {}->{}",
                        e.getEventId(), e.getTipoEvento(), e.getTipoRecurso(), e.getRecursoId(),
                        e.getEstadoAnterior(), e.getEstadoNuevo()))
                .onErrorResume(DuplicateKeyException.class, ex -> {
                    log.info("evento de auditoria repetido ignorado eventId={} tipo={}", sobre.eventId(), sobre.tipo());
                    return Mono.empty();
                })
                .then();
    }

    public Flux<EventoAuditoriaResponse> buscar(FiltroAuditoria filtro, ParametrosPagina pagina)
        // repositorio.buscar(filtro, pagina.aPageable(EventoAuditoriaRepositoryR2dbc.ORDEN_LISTADO)).map(EventoAuditoriaResponse::desde)
    public Mono<Long> contar(FiltroAuditoria filtro)
    public Mono<EventoAuditoriaResponse> obtener(UUID id)
        // switchIfEmpty(Mono.error(new ErrorNegocio(HttpStatus.NOT_FOUND, "EVENTO_AUDITORIA_NO_ENCONTRADO",
        //      "No existe el evento de auditoria " + id + ".")))
    public Flux<EventoAuditoriaResponse> historial(TipoRecurso tipoRecurso, UUID recursoId)
        // repositorio.historial(tipoRecurso.name(), recursoId).map(EventoAuditoriaResponse::desde)
}
```
`SobreInvalidoException` **no** se captura: llega a `ConsumidorEventos` → reintentos → DLQ (D-M1). No registrar valores de `datos` ni del sobre más allá de ids, tipo y estados.

Nota: `ORDEN_LISTADO` vive en el repositorio; si prefieres, muévelo a una constante en el servicio. No uses nombres de columna en el `Sort`.

#### `…auditoria.api.EventoAuditoriaResponse` (record)
```java
public record EventoAuditoriaResponse(UUID id, UUID eventId, OffsetDateTime fechaEvento, String tipoEvento,
        String tipoRecurso, UUID recursoId, String estadoAnterior, String estadoNuevo, String origen,
        OffsetDateTime fechaRegistro) {
    public static EventoAuditoriaResponse desde(EventoAuditoria e)
    // fechas: x == null ? null : x.atZoneSameInstant(RelojAutoConfiguration.ZONA_HOSPITAL).toOffsetDateTime()  (D-M6)
}
```

#### `…auditoria.api.AuditoriaController`
```java
@RestController
@RequestMapping("/api/auditoria")
public class AuditoriaController {
    private final AuditoriaService servicio;
    public AuditoriaController(AuditoriaService servicio)

    @Operation(summary = "Lista eventos de auditoria con filtros y paginacion")
    @GetMapping
    public Mono<ResponseEntity<Flux<EventoAuditoriaResponse>>> listar(
            @RequestParam(required = false) String tipoRecurso,
            @RequestParam(required = false) UUID recursoId,
            @RequestParam(required = false) String tipoEvento,
            @RequestParam(required = false) String origen,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) OffsetDateTime desde,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) OffsetDateTime hasta,
            @RequestParam(required = false) Integer pagina,
            @RequestParam(required = false) Integer tamano) {
        ParametrosPagina parametros = ParametrosPagina.de(pagina, tamano);       // 400 si fuera de rango
        FiltroAuditoria filtro = FiltroAuditoria.de(tipoRecurso, recursoId, tipoEvento, origen, desde, hasta); // 400
        return RespuestaPaginada.de(servicio.buscar(filtro, parametros), servicio.contar(filtro));
    }

    @GetMapping("/{id}")
    public Mono<EventoAuditoriaResponse> obtener(@PathVariable UUID id)

    @Operation(summary = "Historial cronologico de estados de un recurso")
    @GetMapping("/recursos/{tipoRecurso}/{recursoId}/historial")
    public Flux<EventoAuditoriaResponse> historial(@PathVariable String tipoRecurso, @PathVariable UUID recursoId) {
        TipoRecurso tipo = TipoRecurso.desde(tipoRecurso);                      // 400 si inválido
        return servicio.historial(tipo, recursoId);
    }
}
```
Sin endpoints `POST/PUT/PATCH/DELETE`. UUID mal formado o fecha ilegible → `ServerWebInputException` → 400 `VALIDACION_FALLIDA` (lo hace `ManejadorErrores`).

#### `…auditoria.config.ConsumoAuditoriaConfig`
```java
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(prefix = "hospital.rabbit", name = "enabled", havingValue = "true", matchIfMissing = true)
public class ConsumoAuditoriaConfig {
    public static final String BINDING_TODOS = "#";
    private static final Logger log = LoggerFactory.getLogger(ConsumoAuditoriaConfig.class);

    @Bean
    public ApplicationRunner consumoAuditoria(ConsumidorEventos consumidor, AuditoriaService servicio) {
        return args -> {
            consumidor.iniciar(TopologiaHospital.COLA_AUDITORIA, List.of(BINDING_TODOS), servicio::registrar);
            log.info("consumo iniciado cola={} binding={}", TopologiaHospital.COLA_AUDITORIA, BINDING_TODOS);
        };
    }
}
```
* Misma condición que `RabbitAutoConfiguration` (`hospital.rabbit.enabled`, por defecto `true`). **No** usar `@ConditionalOnBean(ConsumidorEventos.class)`: las configuraciones de usuario se evalúan antes que las auto-configuraciones y la condición fallaría siempre.
* `iniciar` declara exchange, DLX, `auditoria.dlq` (+ binding a la DLX), `auditoria.cola` con `x-dead-letter-*` y el binding `#`; luego consume con ack manual, `qos(10)`, concurrencia 4, y se reconecta con backoff si RabbitMQ no está. La `Disposable` la libera `ConsumidorEventos.destroy()`.
* El `schema.sql` corre durante el refresh del contexto (bean inicializador), **antes** de los `ApplicationRunner`; por eso la tabla existe cuando llega el primer mensaje.

### 2.3 Nuevos — test

| Archivo | Paquete |
|---|---|
| `src/test/resources/application-test.yml` | — |
| `TipoRecursoTest`, `FiltroAuditoriaTest` | `…auditoria.domain` |
| `EventoAuditoriaMapperTest` | `…auditoria.messaging` |
| `AuditoriaServiceTest` | `…auditoria.service` |
| `AuditoriaControllerTest` | `…auditoria.api` |
| `AuditoriaRepositoryIT` | `…auditoria.persistence` |
| `AuditoriaConsumerIT` | `…auditoria.messaging` |

`application-test.yml` (perfil `test`, spec 00 §7; valores dummy, los reales los inyectan `@ServiceConnection`/`@DynamicPropertySource`):

```yaml
spring:
  r2dbc:
    password: test
hospital:
  rabbit:
    password: test
eureka:
  client:
    enabled: false
```

Todas las pruebas con contexto Spring llevan `@ActiveProfiles("test")`.

---

## 3. Decisiones técnicas verificadas

### 3.1 Inicializador SQL de Boot 3.5 con R2DBC y separador `@@` — **funciona, no hace falta inicializador propio**

Verificado en las fuentes de las versiones reales:

* `spring-boot-autoconfigure 3.5.16`, `R2dbcInitializationConfiguration`: activo con `@ConditionalOnClass({ConnectionFactory, DatabasePopulator})` y `@ConditionalOnSingleCandidate(ConnectionFactory)`; crea `SqlR2dbcScriptDatabaseInitializer` desde `spring.sql.init.*`.
* `spring-boot 3.5.16`, `AbstractScriptDatabaseInitializer` pasa `settings.getSeparator()` a `Scripts`; `R2dbcScriptDatabaseInitializer.runScripts` hace `populator.setSeparator(scripts.getSeparator())` sobre `org.springframework.r2dbc.connection.init.ResourceDatabasePopulator`.
* `spring-r2dbc 6.2.19`, `ScriptUtils.splitSqlScript`: corta **solo** por el separador indicado fuera de comillas simples/dobles; no conoce `$$`, pero con `@@` el `;` interno del cuerpo PL/pgSQL no corta nada. Detalles que imponen reglas al `schema.sql`:
  1. Colapsa todo espacio/salto de línea consecutivo en un espacio (el cuerpo PL/pgSQL queda en una línea: válido).
  2. Elimina comentarios `--` hasta fin de línea y `/* */` fuera de comillas → **no poner comentarios `--` dentro del cuerpo `$$`** (se borrarían) y **no escribir `@@` en comentarios**. Recomendación: el archivo sin comentarios.
  3. Trata `\` como escape estilo MySQL → no usar `\` en el script.
  4. Si el script **no** contiene el separador, cae a `"\n"` como separador → cada sentencia debe terminar en `@@`.
  5. Las comillas simples deben estar balanceadas (lo están: `'evento_auditoria es inmutable: % no permitido'`, `'P0001'`).
* `@DataR2dbcTest` de Boot 3.5.16 incluye `SqlInitializationAutoConfiguration` (verificado en `AutoConfigureDataR2dbc.imports`), así que el `schema.sql` corre también en la IT del repositorio con el `application.yml` principal (incluido el separador).
* r2dbc-postgresql 1.0.9 ejecuta cada sentencia sin parámetros por el protocolo simple: `CREATE FUNCTION ... $$ ... $$` funciona.

### 3.2 `schema.sql` completo (copiar tal cual, sin comentarios)

```sql
CREATE TABLE IF NOT EXISTS evento_auditoria (
  id               UUID PRIMARY KEY,
  event_id         UUID         NOT NULL UNIQUE,
  fecha_evento     TIMESTAMPTZ  NOT NULL,
  tipo_evento      VARCHAR(50)  NOT NULL,
  tipo_recurso     VARCHAR(30)  NOT NULL,
  recurso_id       UUID         NOT NULL,
  estado_anterior  VARCHAR(20),
  estado_nuevo     VARCHAR(20),
  origen           VARCHAR(40)  NOT NULL,
  fecha_registro   TIMESTAMPTZ  NOT NULL DEFAULT now()
)@@

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

* Repetible: `IF NOT EXISTS`, `CREATE OR REPLACE FUNCTION` (misma firma; permitido aunque los triggers la usen) y `CREATE OR REPLACE TRIGGER` (PostgreSQL ≥ 14; se usa 16).
* El trigger de fila (`FOR EACH ROW`) solo se dispara si el `UPDATE`/`DELETE` **afecta filas**; el de `TRUNCATE` (`FOR EACH STATEMENT`) siempre.
* Límite conocido (spec 07 §4.1): el dueño de la tabla puede hacer `DROP TRIGGER`. No se resuelve aquí.

### 3.3 Mapeo sobre → entidad

| Sobre (`EventoEnvelope`) | Entidad | Regla |
|---|---|---|
| `eventId` | `eventId` | único (idempotencia) |
| `ocurrioEn` | `fechaEvento` | obligatorio; momento del hecho, **no** de recepción |
| `tipo` | `tipoEvento` | ≤ 50 |
| `tipoRecurso` | `tipoRecurso` | obligatorio, ≤ 30, sin restringir valores (D-M3) |
| `recursoId` | `recursoId` | obligatorio |
| `estadoAnterior` | `estadoAnterior` | opcional (null en creaciones), ≤ 20 |
| `estadoNuevo` | `estadoNuevo` | obligatorio, ≤ 20 |
| `origen` | `origen` | obligatorio, ≤ 40 |
| `datos` | — | **no se guarda** (D-39) |
| — | `id` | `UUID.randomUUID()` |
| — | `fechaRegistro` | `OffsetDateTime.now(clock)` |

Sobres reales de los productores (specs 02–05): `origen` ∈ {`pacientes-service`, `citas-service`, `atenciones-service`, `pagos-service`}; `tipoRecurso` ∈ {`PACIENTE`, `CITA`, `ATENCION`, `PAGO`}; estados ≤ 10 caracteres (`CONFIRMADA`, `REGISTRADO`…); routing keys ≤ 24 caracteres (`paciente.estado-cambiado`). Todos caben.

### 3.4 Comportamiento real de `ConsumidorEventos` (commons) — resumen para el ejecutor

| Situación | Resultado |
|---|---|
| JSON ilegible, o sin `eventId`/`tipo` | `nack(false)` inmediato → `auditoria.dlq`; el manejador **no** se invoca |
| Manejador completa (vacío o no) | `ack` |
| Manejador emite `DuplicateKeyException` | `ack` (nuestro servicio ya la absorbe antes) |
| Manejador emite otro error (incluido `SobreInvalidoException`) | `Mono.defer(manejador)` se reintenta 2 veces con `Retry.backoff(2, 500 ms)` (3 invocaciones en total), luego `nack(false)` → `auditoria.dlq` |
| Jackson | Usa el `ObjectMapper` de Boot (con `JavaTimeModule`); ignora campos desconocidos del sobre |

### 3.5 Consultas y API

* Listado: `Criteria` con condiciones solo para parámetros presentes; `Query.query(c).with(PageRequest(pagina, tamano, fechaEvento DESC, id ASC))`; total con `template.count(Query.query(c), EventoAuditoria.class)`; `RespuestaPaginada.de(flux, total)` agrega `X-Total-Count`.
* `desde`/`hasta` inclusivos (`>=`, `<=`); `desde > hasta` → 400; `desde == hasta` permitido.
* Detalle: 404 `EVENTO_AUDITORIA_NO_ENCONTRADO`.
* Historial: sin paginar (todos los eventos del recurso), orden `fecha_evento ASC, fecha_registro ASC, id ASC`; vacío → `200 []`; `tipoRecurso` fuera de `PACIENTE|CITA|ATENCION|PAGO` → 400 `VALIDACION_FALLIDA`.
* Desactivación: con `hospital.rabbit.enabled=false` no se crean `Sender`/`Receiver`/`ConsumidorEventos` (commons) ni el `ApplicationRunner` (este servicio). La API sigue funcionando.

---

## 4. Pruebas

Convención: `*Test` (Surefire, sin Docker) y `*IT` (Failsafe, Docker). Datos ficticios; nada de datos personales. Usa `OffsetDateTime` fijos con offset `-05:00` y **sin nanosegundos** (PostgreSQL guarda microsegundos).

### 4.1 Unitarias (sin Spring)

**`TipoRecursoTest`**
* `desde("CITA")` → `CITA`; `desde("cita")`, `desde("REPORTE")`, `desde(null)`, `desde(" ")` → `ErrorNegocio` 400 `VALIDACION_FALLIDA` con campo `tipoRecurso`.
* `desdeOpcional(null)` y `desdeOpcional("")` → `null`; `desdeOpcional("PAGO")` → `PAGO`; `desdeOpcional("X")` → `ErrorNegocio`.

**`FiltroAuditoriaTest`**
* `desde` posterior a `hasta` → `ErrorNegocio` (status 400, código `VALIDACION_FALLIDA`, error de campo `desde`).
* `desde == hasta` válido; solo uno de los dos válido; `tipoEvento = "  "` → `null`.

**`EventoAuditoriaMapperTest`** (casos de la spec 07 §7 + extras)
* Sobre completo (`cita.confirmada`, `PENDIENTE→CONFIRMADA`, `citas-service`): todos los campos copiados; `fechaEvento` **igual** a `ocurrioEn`; `fechaRegistro` igual al parámetro; `id` no nulo; `isNew()` true.
* Creación (`cita.creada`, `estadoAnterior = null`) → entidad con `estadoAnterior` null, sin error.
* `datos` **no** se copia: verificar por reflexión que `EventoAuditoria` no tiene ningún campo llamado `datos` (`Arrays.stream(EventoAuditoria.class.getDeclaredFields()).map(Field::getName)` no contiene `"datos"`), y que un sobre con `datos` con contenido produce la misma entidad (campos) que uno con `datos = null`.
* Sin `recursoId` → `SobreInvalidoException` cuyo mensaje contiene `recursoId`.
* Sin `tipoRecurso`, sin `estadoNuevo`, sin `origen`, sin `ocurrioEn` (parametrizada) → `SobreInvalidoException`.
* `origen` de 41 caracteres → `SobreInvalidoException`.

**`AuditoriaServiceTest`** (`StepVerifier` + Mockito; `Clock.fixed(Instant.parse("2026-10-12T14:30:00Z"), ZONA_HOSPITAL)`; repositorio `mock(EventoAuditoriaRepository.class)`)
* `registrar` inserta: `insertar` devuelve `Mono.just(arg)` (`thenAnswer`); `StepVerifier.create(servicio.registrar(sobre)).verifyComplete()`; `ArgumentCaptor` verifica `eventId`, estados, origen, `fechaEvento = ocurrioEn`, `fechaRegistro = OffsetDateTime.now(clockFijo)`.
* `insertar` devuelve `Mono.error(new DuplicateKeyException("dup"))` → `verifyComplete()` (sin error).
* Sobre sin `recursoId` → `expectError(SobreInvalidoException.class)` y `verify(repositorio, never()).insertar(any())`.
* Otro error de BD (`Mono.error(new DataAccessResourceFailureException("x"))`) → se propaga (`expectError`), para que el consumidor reintente.
* `historial(CITA, id)`: el repositorio devuelve 3 entidades en orden ascendente → el servicio emite 3 `EventoAuditoriaResponse` en el **mismo** orden (`expectNextMatches` ×3) y se llamó con `"CITA"`.
* `obtener` inexistente (`Mono.empty()`) → `ErrorNegocio` con status 404 y código `EVENTO_AUDITORIA_NO_ENCONTRADO`.
* `buscar` pasa un `Pageable` con `pageNumber`, `pageSize` y orden `fechaEvento DESC, id ASC` (captor).

### 4.2 Controlador

**`AuditoriaControllerTest`**
```java
@WebFluxTest(controllers = AuditoriaController.class,
             properties = {"hospital.rabbit.enabled=false", "eureka.client.enabled=false"})
@Import(ManejadorErrores.class)
@ActiveProfiles("test")
class AuditoriaControllerTest {
    @Autowired WebTestClient cliente;
    @MockitoBean AuditoriaService servicio;   // org.springframework.test.context.bean.override.mockito.MockitoBean
}
```
Casos:
* `GET /api/auditoria?tipoRecurso=CITA&recursoId=<uuid>&tipoEvento=cita.confirmada&origen=citas-service&desde=2026-10-01T00:00:00-05:00&hasta=2026-10-31T23:59:59-05:00&pagina=0&tamano=5` → 200, cabecera `X-Total-Count: 7`, arreglo con los DTO del mock; con `ArgumentCaptor<FiltroAuditoria>` verificar que llegaron los 6 filtros (comparar fechas con `isEqual`).
* Sin parámetros → 200; el filtro capturado es todo `null`; paginación por defecto 0/20.
* `desde=2026-10-31T00:00:00-05:00&hasta=2026-10-01T00:00:00-05:00` → 400 `application/problem+json`, `$.codigo == VALIDACION_FALLIDA`; el servicio no se invoca.
* `tamano=500` → 400 `VALIDACION_FALLIDA`.
* `desde=no-es-fecha` → 400 `VALIDACION_FALLIDA`.
* `tipoRecurso=REPORTE` en el listado → 400.
* `GET /api/auditoria/{id}` con servicio `Mono.error(new ErrorNegocio(NOT_FOUND, "EVENTO_AUDITORIA_NO_ENCONTRADO", "..."))` → 404 con `$.codigo`.
* `GET /api/auditoria/no-es-uuid` → 400.
* `GET /api/auditoria/{id}` existente → 200 con `$.estadoNuevo`, `$.origen`.
* `GET /api/auditoria/recursos/FACTURA/<uuid>/historial` → 400 `VALIDACION_FALLIDA`; servicio no invocado.
* `GET /api/auditoria/recursos/CITA/<uuid>/historial` → 200, arreglo en el orden del mock (`$[0].estadoNuevo == PENDIENTE`, `$[1] == CONFIRMADA`, `$[2] == ATENDIDA`).
* No existe `DELETE /api/auditoria/{id}` ni `PUT`: `cliente.delete()...` → 405 (o 404; aceptar `is4xxClientError`).

Notas: `@WebFluxTest` **no** carga auto-configuraciones de commons ni `@Configuration` escaneadas (por eso `@Import(ManejadorErrores.class)`). En las URIs de prueba usa offsets negativos (`-05:00`); un `+` sin codificar en la query se interpretaría mal.

### 4.3 Integración con PostgreSQL

**`AuditoriaRepositoryIT`**
```java
@DataR2dbcTest
@Import(EventoAuditoriaRepositoryR2dbc.class)   // @DataR2dbcTest NO escanea @Repository propios
@Testcontainers
@ActiveProfiles("test")
class AuditoriaRepositoryIT {
    @Container @ServiceConnection
    static PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16");
    @Autowired EventoAuditoriaRepository repositorio;
    @Autowired DatabaseClient db;
    @Autowired ConnectionFactory connectionFactory;
}
```
Como **no se pueden borrar filas** (triggers), cada prueba usa `recursoId`/`eventId` nuevos (`UUID.randomUUID()`) y filtra por ellos; nunca afirmar conteos globales de la tabla.

Casos:
1. **Esquema creado y repetible**: tras el arranque existen la tabla, `fn_auditoria_inmutable` y los dos triggers (`SELECT tgname FROM pg_trigger WHERE tgrelid = 'evento_auditoria'::regclass AND NOT tgisinternal` → `tg_auditoria_sin_truncate`, `tg_auditoria_sin_update_delete`) y los índices (`SELECT indexname FROM pg_indexes WHERE tablename = 'evento_auditoria'` contiene `ix_auditoria_recurso`, `ix_auditoria_fecha`). Luego se **re-ejecuta** el script: `ResourceDatabasePopulator p = new ResourceDatabasePopulator(new ClassPathResource("schema.sql")); p.setSeparator("@@"); StepVerifier.create(p.populate(connectionFactory)).verifyComplete();` y los triggers siguen siendo exactamente 2. (Que el contexto haya arrancado ya prueba que el separador `@@` del `application.yml` se aplicó: con `;` fallaría la función.)
2. **Insertar y leer**: `insertar` → `buscarPorId` devuelve los mismos valores (fechas con `isEqual`, no `equals`: el driver puede devolver otro offset).
3. **Unicidad de `event_id`**: dos entidades con el mismo `eventId` (ids distintos) → la segunda `expectError(DuplicateKeyException.class)`.
4. **Filtros y paginación**: 3 eventos del mismo `recursoId` con `fechaEvento` t1<t2<t3 y distintos `tipoEvento`; `buscar(filtro recursoId, PageRequest.of(0, 2, ORDEN_LISTADO))` → 2 filas t3, t2; página 1 → t1; `contar` → 3; filtro `tipoEvento` → 1; `desde=t2` → 2; `hasta=t1` → 1.
5. **Historial ascendente**: insertar t3, t1, t2 (en ese orden) → `historial("CITA", recursoId)` devuelve t1, t2, t3.
6. **UPDATE directo falla**: insertar una fila; `db.sql("UPDATE evento_auditoria SET estado_nuevo = 'X' WHERE id = :id").bind("id", id).fetch().rowsUpdated()` → `expectErrorSatisfies(e -> assertThat(e).hasStackTraceContaining("evento_auditoria es inmutable: UPDATE no permitido"))`; luego la fila conserva su `estadoNuevo`.
7. **DELETE directo falla**: igual con `DELETE FROM evento_auditoria WHERE id = :id` → contiene `"evento_auditoria es inmutable: DELETE no permitido"`; la fila sigue existiendo. (Debe apuntar a una fila **existente**: el trigger es `FOR EACH ROW`.)
8. **TRUNCATE falla**: `db.sql("TRUNCATE evento_auditoria").fetch().rowsUpdated()` → contiene `"evento_auditoria es inmutable: TRUNCATE no permitido"`; las filas previas siguen.

`.block()` está permitido en pruebas, pero preferir `StepVerifier`.

### 4.4 Integración con PostgreSQL + RabbitMQ

**`AuditoriaConsumerIT`**
```java
@SpringBootTest(properties = "eureka.client.enabled=false")
@AutoConfigureWebTestClient
@Testcontainers
@ActiveProfiles("test")
class AuditoriaConsumerIT {
    @Container @ServiceConnection
    static PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16");
    @Container
    static RabbitMQContainer RABBIT = new RabbitMQContainer("rabbitmq:3.13-management");

    @DynamicPropertySource
    static void rabbit(DynamicPropertyRegistry r) {
        r.add("hospital.rabbit.host", RABBIT::getHost);
        r.add("hospital.rabbit.port", RABBIT::getAmqpPort);
        r.add("hospital.rabbit.username", RABBIT::getAdminUsername);
        r.add("hospital.rabbit.password", RABBIT::getAdminPassword);
    }

    @Autowired PublicadorEventos publicador;        // PublicadorRabbit REAL (auto-config de commons)
    @Autowired EventoAuditoriaRepository repositorio;
    @Autowired WebTestClient cliente;
    @Autowired ObjectMapper mapper;
    static Connection conexionDirecta;              // com.rabbitmq.client, para inspeccionar colas
}
```
Preparación:
* `@BeforeAll`: abrir `conexionDirecta` con `ConnectionFactory` (`host`, `amqpPort`, `adminUsername`, `adminPassword`). `@AfterAll`: cerrarla.
* `@BeforeEach`: esperar con Awaitility (incluida en `spring-boot-starter-test`) a que `auditoria.cola` tenga **≥ 1 consumidor** (`canal.queueDeclarePassive("auditoria.cola").getConsumerCount() >= 1`, `ignoreExceptions()`, `atMost(30 s)`): el consumidor arranca en un `ApplicationRunner` asíncrono y declara los bindings **antes** de consumir; si se publica antes, el mensaje se pierde (el exchange no tiene colas). Luego `canal.queuePurge("auditoria.dlq")`.
* Helper `mensajesEn(String cola)` con `queueDeclarePassive(cola).getMessageCount()`.
* Sobres construidos con `new EventoEnvelope(UUID.randomUUID(), tipo, OffsetDateTime.parse("2026-10-12T09:30:00-05:00"), origen, tipoRecurso, recursoId, anterior, nuevo, mapper.valueToTree(Map.of("codigo", "CIT-20261012-000001")))`. Publicar con `publicador.publicar(routingKey, sobre).block(Duration.ofSeconds(10))`.

Casos:
1. **`cita.confirmada` → fila `PENDIENTE → CONFIRMADA`, origen `citas-service`** (enunciado §14/§20): publicar con `TopologiaHospital.CITA_CONFIRMADA`; `await().atMost(15 s).untilAsserted(...)` sobre `repositorio.historial("CITA", citaId).collectList().block()`: 1 fila con `tipoEvento = cita.confirmada`, `tipoRecurso = CITA`, `recursoId`, `estadoAnterior = PENDIENTE`, `estadoNuevo = CONFIRMADA`, `origen = citas-service`, `eventId` igual, `fechaEvento.isEqual(ocurrioEn)`, `fechaRegistro` no nula. `auditoria.dlq` vacía.
2. **Evento repetido → una sola fila**: publicar **el mismo** sobre dos veces; `await().during(Duration.ofSeconds(2)).atMost(15 s).until(() -> filasDe(recursoId) == 1)` y `mensajesEn("auditoria.cola") == 0`; DLQ vacía.
3. **Cualquier routing key llega por `#`**: publicar `pago.anulado` (`PAGO`, `REGISTRADO→ANULADO`, `pagos-service`) y un evento inventado `reporte.generado` (`tipoRecurso = REPORTE`, `null→GENERADO`, `reportes-service`) → ambos quedan como fila (consultar con `repositorio.historial("PAGO", id)` y `repositorio.historial("REPORTE", id)`).
4. **Tres eventos en desorden → historial cronológico**: misma cita; publicar en este orden `cita.atendida` (t3 = 11:00, `CONFIRMADA→ATENDIDA`), `cita.creada` (t1 = 09:00, `null→PENDIENTE`), `cita.confirmada` (t2 = 10:00, `PENDIENTE→CONFIRMADA`); esperar 3 filas; `cliente.get().uri("/api/auditoria/recursos/CITA/{id}/historial", id)` → 200 y `$[0].tipoEvento == cita.creada`, `$[0].estadoAnterior` nulo (`jsonPath("$[0].estadoAnterior").isEmpty()`), `$[1].estadoNuevo == CONFIRMADA`, `$[2].estadoNuevo == ATENDIDA`.
5. **JSON inválido → `auditoria.dlq`**: con la conexión directa, `canal.basicPublish(TopologiaHospital.EXCHANGE, "cita.cancelada", MessageProperties.PERSISTENT_TEXT_PLAIN, "{esto no es json".getBytes(UTF_8))`; `await().atMost(15 s).until(() -> mensajesEn("auditoria.dlq") == 1)`; `basicGet("auditoria.dlq", true)` devuelve ese cuerpo.
6. **Sobre sin `recursoId` → `auditoria.dlq` tras reintentos** (D-M1): publicar con `recursoId = null`; `await().atMost(20 s).until(() -> mensajesEn("auditoria.dlq") == 1)`; ninguna fila con ese `eventId` (consulta con `DatabaseClient` `SELECT count(*) FROM evento_auditoria WHERE event_id = :e` o filtro equivalente).

---

## 5. Cómo ejecutar y verificar

Siempre desde la raíz del monorepo. El shell del agente no tiene activo el grupo `docker`: todo lo que use Testcontainers va con `sg docker -c "..."`. **No usar `-am`** (commons ya está instalado). El cwd no persiste entre llamadas: incluye el `cd`.

```bash
# Paso 0 (una vez; idempotente): POM padre en ~/.m2
cd /home/cazabache/hospital-vida-salud && ./mvnw -q -N install

# Compilar / unitarias y controlador (sin Docker)
cd /home/cazabache/hospital-vida-salud && ./mvnw -q -pl auditoria-service compile
cd /home/cazabache/hospital-vida-salud && ./mvnw -q -pl auditoria-service test

# Una sola IT
cd /home/cazabache/hospital-vida-salud && sg docker -c "./mvnw -q -pl auditoria-service verify -Dit.test=AuditoriaRepositoryIT"
cd /home/cazabache/hospital-vida-salud && sg docker -c "./mvnw -q -pl auditoria-service verify -Dit.test=AuditoriaConsumerIT"

# Verificación final (Surefire *Test + Failsafe *IT)
cd /home/cazabache/hospital-vida-salud && sg docker -c "./mvnw -q -pl auditoria-service verify"
```

Si falla, revisar `auditoria-service/target/surefire-reports/` y `target/failsafe-reports/` (`*.txt` y `*-output.txt`).

Comprobaciones estáticas al final:

```bash
cd /home/cazabache/hospital-vida-salud/auditoria-service
grep -rnE "\.block\(|\.subscribe\(|Thread\.sleep" src/main/java        # debe salir vacío
grep -rnE "lombok|jdbc|jakarta\.persistence|@Autowired" src/main/java  # vacío
grep -rnE "ReactiveCrudRepository|CrudRepository|R2dbcRepository" src/main/java  # vacío
grep -rnE "@(Post|Put|Patch|Delete)Mapping" src/main/java              # vacío
grep -rn "datos" src/main/java                                          # sin campo/columna datos
```

Verificación manual opcional (no requerida para terminar): con `infra/` levantado y `scripts/entorno-dev.sh`, arrancar el servicio, publicar desde citas y consultar `GET http://localhost:8084/api/auditoria/recursos/CITA/{id}/historial`; en el panel de RabbitMQ, `auditoria.cola` con binding `#` y `auditoria.dlq`; `psql` como `auditoria_user`: `UPDATE evento_auditoria SET origen='x';` → `evento_auditoria es inmutable: UPDATE no permitido` (si hay filas).

---

## 6. Trampas conocidas

1. **POM padre no instalado**: `-pl auditoria-service` falla resolviendo `hospital-commons` si `hospital-vida-salud-parent:1.0.0-SNAPSHOT` no está en `~/.m2` → `./mvnw -q -N install` (paso 0).
2. **`@ServiceConnection` con R2DBC necesita `org.testcontainers:r2dbc`** (D-M11). Síntoma sin ella: `NoClassDefFoundError: org/testcontainers/r2dbc/R2DBCDatabaseContainer`.
3. **`@DataR2dbcTest` no ve `EventoAuditoriaRepositoryR2dbc`** (no es un repositorio Spring Data): `@Import` explícito.
4. **`schema.sql`**: cada sentencia termina en `@@`; sin comentarios dentro de `$$`; sin `\`; sin `@@` en comentarios; si falta el separador en el archivo, Spring corta por línea y todo falla. El separador se configura en `application.yml` (no en el perfil de prueba), así aplica también a `@DataR2dbcTest`.
5. **No se puede limpiar la tabla en pruebas** (los triggers bloquean `DELETE`/`TRUNCATE`): usar ids únicos por prueba y filtrar por ellos; nunca `@Sql` de limpieza.
6. **Trigger de fila**: `UPDATE`/`DELETE` que no afectan filas **no** fallan. En la IT, apuntar a una fila insertada antes.
7. **Mensaje del error del trigger**: llega envuelto (`DataAccessResourceFailureException`/`UncategorizedR2dbcException` según el mapeo del driver); usar `hasStackTraceContaining("evento_auditoria es inmutable")`, no el tipo exacto.
8. **Fechas**: PostgreSQL guarda microsegundos y el driver puede devolver otro offset; comparar con `isEqual` y usar fechas fijas sin nanos. Jackson de Boot ajusta `OffsetDateTime` a UTC al deserializar (`ADJUST_DATES_TO_CONTEXT_TIME_ZONE`): mismo instante, distinto offset.
9. **Carrera de arranque del consumidor**: publicar antes de que exista el binding `#` pierde el mensaje. Esperar `consumerCount >= 1` en `auditoria.cola` (paso 4.4).
10. **DLQ compartida entre casos** de `AuditoriaConsumerIT`: purgar `auditoria.dlq` en `@BeforeEach`; JUnit ejecuta los métodos en serie.
11. **Sobre incompleto tarda ~2 s en llegar a la DLQ** (reintentos de commons, D-M1): `atMost(20 s)`.
12. **`@ConditionalOnBean(ConsumidorEventos.class)` no sirve** en configuración de usuario (orden de evaluación); usar `@ConditionalOnProperty` como en §2.2.
13. **`@Transient` correcto**: `org.springframework.data.annotation.Transient` (no `java.beans`, no `jakarta.persistence`).
14. **`Criteria`/`Sort` con nombres de propiedad** (`fechaEvento`), no de columna, para que `QueryMapper` aplique conversiones; no mezclar.
15. **`@MockBean` está deprecado** en Boot 3.4+: usar `@MockitoBean`.
16. **`+` en query strings** de pruebas: usar offsets `-05:00`.
17. **Logs**: solo `eventId`, `tipo`, `tipoRecurso`, `recursoId`, estados. Nunca `datos` ni el cuerpo del mensaje.
18. **Varias instancias** arrancando a la vez podrían chocar en `CREATE OR REPLACE FUNCTION` ("tuple concurrently updated"); a esta escala hay una sola instancia. Solo documentar si aparece.
19. Si RabbitMQ local ya tiene `auditoria.cola` declarada con otros argumentos, la declaración falla con `PRECONDITION_FAILED` (no afecta a Testcontainers).

---

## 7. Criterios de terminado

- [ ] `cd /home/cazabache/hospital-vida-salud && sg docker -c "./mvnw -q -pl auditoria-service verify"` en verde (todas las `*Test` y las dos `*IT`).
- [ ] Archivos de §2 creados con los paquetes y firmas indicados; `pom.xml` solo con la dependencia `org.testcontainers:r2dbc` agregada; `application.yml` con `spring.sql.init.separator: "@@"`.
- [ ] `schema.sql` idéntico a §3.2 y re-ejecutable (probado en `AuditoriaRepositoryIT`).
- [ ] `UPDATE`, `DELETE` y `TRUNCATE` directos fallan con "evento_auditoria es inmutable" (probado).
- [ ] No hay ningún endpoint ni método de repositorio para modificar o borrar; `EventoAuditoriaRepository` no extiende interfaces de Spring Data.
- [ ] La entidad no tiene campo `datos`; `fechaEvento = ocurrioEn`.
- [ ] Consumo con `ConsumidorEventos.iniciar("auditoria.cola", List.of("#"), servicio::registrar)` en un `ApplicationRunner` condicionado a `hospital.rabbit.enabled` (por defecto `true`).
- [ ] `AuditoriaConsumerIT` demuestra: `cita.confirmada` → fila `PENDIENTE→CONFIRMADA` con origen `citas-service`; duplicado → 1 fila; `pago.anulado` y una routing key inventada llegan; 3 eventos en desorden → historial cronológico; JSON inválido y sobre incompleto → `auditoria.dlq`.
- [ ] API: listado con filtros + `X-Total-Count`, orden `fecha_evento DESC, id`; detalle con 404 `EVENTO_AUDITORIA_NO_ENCONTRADO`; historial con 400 para `tipoRecurso` inválido; `desde > hasta` → 400.
- [ ] Comprobaciones estáticas de §5 sin resultados (sin `.block()`/`.subscribe()`/Lombok/JDBC/JPA/`@Autowired` en `src/main`).
- [ ] No se modificó nada fuera de `auditoria-service/` (en particular, **no** `hospital-commons`). No hacer commits.
