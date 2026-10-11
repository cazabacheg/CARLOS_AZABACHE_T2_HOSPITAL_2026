# Plan de implementación 06 — `notificaciones-service`

> Ejecutor: agente que **no** vio la conversación de diseño. Este plan es autosuficiente.
> Fuentes (ya contrastadas por el planificador): `docs/specs/00-convenciones-comunes.md`, `docs/specs/06-notificaciones-service.md`, `docs/specs/08-observabilidad.md`, specs 02 §6, 03 §7, 04 §7, 05 §7, `docs/documento-tecnico.md` §5.7, §6.4, §8.3, §9, §10, y el código real de `hospital-commons` (versión instalada en `~/.m2`).
> Raíz del repositorio: `/home/cazabache/hospital-vida-salud`. Paquete base: `pe.edu.cibertec.vidasalud.notificaciones`.

## DUDAS CRUCIALES

Ninguna. Todo lo que cambia el diseño se pudo resolver con las specs y el código real. Las discrepancias menores están en "Decisiones menores tomadas".

## Decisiones menores tomadas

| # | Duda | Decisión |
|---|---|---|
| m1 | La spec 00 §5.2 dice que el `ApplicationRunner` llama a `consumir(...).subscribe()`. El código real de `ConsumidorEventos` tiene `Disposable iniciar(cola, bindings, manejador)`, que ya hace el `subscribe` y libera la suscripción en `destroy()` | Usar **`consumidorEventos.iniciar(...)`**. El servicio **no** escribe ningún `.subscribe()` |
| m2 | La spec 00 §4.2 llama `respuestaPaginada(...)` a la utilidad | El nombre real es **`RespuestaPaginada.de(Flux<T>, Mono<Long>)`** |
| m3 | ¿De dónde sale `pacienteId`? | De `datos.pacienteId` en los 8 eventos (lo confirman las specs 02 §6, 03 §7.1, 04 §7 y 05 §7; `paciente.registrado` trae `{pacienteId, fechaRegistro}`). **Respaldo:** si falta o no es UUID y el tipo es `paciente.registrado`, se usa `recursoId` del sobre (spec 02 §6: `recursoId = id del paciente`). En los demás tipos queda `null` (la columna `paciente_id` admite `NULL`, documento §8.3) |
| m4 | `recursoId` | Siempre el `recursoId` del **sobre** (cita, atención, pago o paciente). Si viene `null`, el servicio devuelve `Mono.error(IllegalArgumentException)`: `ConsumidorEventos` hará 3 intentos y lo mandará a `notificaciones.dlq` (no se puede cambiar la librería; es aceptable) |
| m5 | Campo de `datos` ausente o con formato inválido | El DTO es **tolerante**: el valor queda `null` y la plantilla escribe `N/D`. No se rechaza el evento (es mejor un aviso incompleto que perderlo). `especialidadNombre` ausente → se usa `especialidad`; `areaNombre` ausente → se usa `area` |
| m6 | Formato de fecha | `dd/MM/yyyy HH:mm` en `America/Lima`, `DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm", Locale.ROOT)`. Se aceptan `OffsetDateTime` ISO (con offset o `Z`) y, como respaldo, `LocalDateTime` ISO sin offset (se interpreta en hora de Lima) |
| m7 | Formato de monto | `monto.setScale(2, RoundingMode.HALF_UP).toPlainString()` → `80.00`. `toPlainString` no depende del `Locale` (nunca usa coma decimal ni separador de miles), que cumple el requisito de "Locale fijo". El monto llega como número JSON (`DoubleNode` tras deserializar) o como texto; ambos se aceptan |
| m8 | Largo del mensaje (`VARCHAR(300)`) | La plantilla recorta a 300 caracteres como defensa (con los datos reales no pasa de ~120) |
| m9 | `fechaEnvio` | `OffsetDateTime.now(clock).truncatedTo(ChronoUnit.MICROS)` (PostgreSQL guarda microsegundos; así lo guardado y lo devuelto coinciden). En la respuesta se expresa en hora de Lima (`atZoneSameInstant(Lima).toOffsetDateTime()`), coherente con la convención de offset `-05:00` |
| m10 | Filtro `fecha` | Día calendario en Lima sobre `fecha_envio`: `[fecha 00:00 Lima, fecha+1 00:00 Lima)` |
| m11 | Filtro `tipoEvento` vacío o en blanco | Se trata como ausente (no filtra). No se valida contra la lista de bindings (un tipo desconocido simplemente devuelve 0 resultados) |
| m12 | Fases A y B | Se implementan **ambas desde el inicio** con una lista de bindings centralizada (`BindingsNotificaciones`). La fase B solo agrega 3 claves y 3 plantillas; separarla no aporta |
| m13 | Dependencia de prueba faltante | Se agrega `org.testcontainers:r2dbc` (scope `test`, versión del BOM 1.21.4). **Verificado**: `PostgreSQLR2DBCDatabaseContainer` (que usa `@ServiceConnection` para R2DBC) implementa `org.testcontainers.r2dbc.R2DBCDatabaseContainer`, que **no** está en el classpath sin ese artefacto |
| m14 | Prueba de repositorio | La spec 06 §7 no la pide, pero las consultas dinámicas son la parte más propensa a errores: se agrega `NotificacionRepositoryIT` (`@DataR2dbcTest`), con el mismo nombre que usa la spec 02 (`PacienteRepositoryIT`) |
| m15 | Criterio de aceptación de la spec 06 §8 usa `-am` | Por instrucción del proyecto se usa **sin `-am`** (ver §5): `hospital-commons` ya está instalado en `~/.m2` |
| m16 | Dónde viven las clases | Entidad, `DatosEvento`, `FiltroNotificaciones` y `PlantillasNotificacion` en `domain/` (sin Spring salvo las anotaciones de mapeo de la entidad); consulta dinámica en `persistence/`; DTO de respuesta en `api/`; el servicio devuelve DTOs (`NotificacionResponse`) |
| m17 | `hospital.rabbit.password` y `spring.r2dbc.password` no tienen defecto | Las pruebas usan `src/test/resources/application-test.yml` (perfil `test`) con valores ficticios; los contenedores los sobrescriben |

---

## 1. Resumen y orden de implementación

`notificaciones-service` es un **consumidor puro** de RabbitMQ (cola `notificaciones.cola`, DLQ `notificaciones.dlq`, exchange `hospital.eventos`) más una **API de solo lectura**. Por cada evento enlazado genera un texto en español con una plantilla, lo guarda en `notificacion` (canal `SIMULADO`) y registra un `INFO` **sin el texto**. La idempotencia la da `event_id UNIQUE`: el segundo `INSERT` lanza `DuplicateKeyException` y se confirma (`ack`). **No** usa `WebClient`, `@LoadBalanced` ni publica eventos.

Cada paso deja el módulo compilando. Comandos (desde la raíz del repo; ver §5 para detalles):

* Compilar: `./mvnw -q -pl notificaciones-service compile`
* Pruebas unitarias (sin Docker): `./mvnw -q -pl notificaciones-service test`
* Todo (con Docker): `sg docker -c "./mvnw -q -pl notificaciones-service verify"`

### Paso 0 — Preparar el entorno de construcción
1. Comprobar que `./mvnw -q -pl notificaciones-service validate` funciona. Si falla con *"Child module … does not exist"*, otro agente aún no creó ese módulo: esperar o avisar (no editar el `pom.xml` raíz). Si falla con *"Could not find artifact …:hospital-vida-salud-parent:pom"*, ejecutar `./mvnw -q -N install` (instala solo el POM padre; el planificador ya lo hizo una vez).
2. En `notificaciones-service/pom.xml`, agregar en las dependencias de prueba (después de `org.testcontainers:postgresql`):
   ```xml
   <dependency>
     <groupId>org.testcontainers</groupId>
     <artifactId>r2dbc</artifactId>
     <scope>test</scope>
   </dependency>
   ```
   No tocar el resto del POM (puede quedar `mockwebserver`; no se usa).
3. Verificar: `./mvnw -q -pl notificaciones-service compile`.

### Paso 1 — Lista centralizada de bindings
Crear `messaging/BindingsNotificaciones` (§2.6) con `FASE_A`, `FASE_B` y `TODOS`. Compilar.

### Paso 2 — DTO tolerante de `datos` y plantillas (dominio puro)
1. `domain/DatosEvento` (§2.1) con `desde(String tipo, UUID recursoId, JsonNode datos)`.
2. `domain/PlantillasNotificacion` (§2.2): textos exactos, fechas en Lima, monto con 2 decimales.
3. Pruebas `DatosEventoTest`, `PlantillasNotificacionTest`, `BindingsNotificacionesTest` (§4).
4. Verificar: `./mvnw -q -pl notificaciones-service test`.

### Paso 3 — Esquema, entidad y persistencia
1. `src/main/resources/schema.sql` (§3.1).
2. `domain/Notificacion` (§2.3) implementando `Persistable<UUID>`.
3. `domain/FiltroNotificaciones` (§2.4).
4. `persistence/NotificacionRepository` y `persistence/ConsultaNotificaciones` (§2.5).
5. `src/test/resources/application-test.yml` (§3.6).
6. Prueba `NotificacionRepositoryIT` (§4).
7. Verificar: `sg docker -c "./mvnw -q -pl notificaciones-service verify"`.

### Paso 4 — Servicio
1. `api/NotificacionResponse` (§2.7).
2. `service/NotificacionService` (§2.8).
3. Prueba `NotificacionServiceTest`. Verificar con `test`.

### Paso 5 — Consumidor y arranque
1. `messaging/NotificacionesConsumer` (§2.9).
2. `config/ConsumoNotificacionesConfig` (§2.10) con `@ConditionalOnProperty(hospital.rabbit.enabled)`.
3. Compilar.

### Paso 6 — API
1. `api/NotificacionController` (§2.11).
2. Prueba `NotificacionControllerTest`. Verificar con `test`.

### Paso 7 — Integración extremo a extremo
1. Prueba `NotificacionesConsumerIT` (§4) con PostgreSQL y RabbitMQ reales.
2. Verificar: `sg docker -c "./mvnw -q -pl notificaciones-service verify"`.

### Paso 8 — Repaso final
Revisar la lista de §6 (trampas) y §7 (criterios de terminado): sin `.block()`/`.subscribe()` en `src/main`, sin texto del mensaje ni datos personales en logs, `application.yml` sin cambios no pedidos.

---

## 2. Archivos exactos

Rutas relativas a `notificaciones-service/src/main/java/pe/edu/cibertec/vidasalud/notificaciones/` salvo indicación. **Sin Lombok**, DTOs como `record`, inyección por constructor.

Archivo existente que **no** se modifica: `NotificacionesServiceApplication.java` (ya tiene `@SpringBootApplication`). `src/main/resources/application.yml` **ya está completo** (puerto 8083, R2DBC, `spring.sql.init.mode: always`, `hospital.rabbit.*`, Eureka, actuator con `health,info,metrics`, patrón de log de la spec 08); no requiere cambios.

### 2.1 `domain/DatosEvento.java`

```java
package pe.edu.cibertec.vidasalud.notificaciones.domain;

/** Campos de `datos` que usan las plantillas. Tolerante: lo que falta o no se puede leer queda null. */
public record DatosEvento(
        UUID pacienteId,
        String codigo,               // CIT-…, ATE-…
        String especialidadNombre,   // si falta: "especialidad"
        OffsetDateTime fechaHora,
        OffsetDateTime fechaHoraAnterior,
        String areaNombre,           // si falta: "area"
        OffsetDateTime fechaAtencion,
        UUID citaId,
        BigDecimal monto,
        String numeroOperacion) {

    public static DatosEvento desde(String tipo, UUID recursoId, JsonNode datos) { … }
}
```

Reglas de `desde` (sin Spring; solo Jackson `JsonNode` y `java.time`):

* `datos == null`, `NullNode` o no objeto → tratar como `MissingNode` (todo `null`, salvo el respaldo de `pacienteId`).
* Helper privado `texto(JsonNode n, String campo)`: `n.path(campo)`; si `isMissingNode()` o `isNull()` → `null`; si es texto → `textValue()` con `strip()`, y `null` si queda en blanco; si no → `asText()`.
* `uuid(...)`: `UUID.fromString(texto)` dentro de `try/catch (IllegalArgumentException)` → `null`.
* `fecha(...)`: `OffsetDateTime.parse(texto)`; si lanza `DateTimeParseException`, intentar `LocalDateTime.parse(texto).atZone(ZONA_LIMA).toOffsetDateTime()`; si también falla → `null`.
* `monto(...)`: si el nodo es numérico → `nodo.decimalValue()`; si es texto → `new BigDecimal(texto)` con `try/catch (NumberFormatException)`; otro → `null`.
* `pacienteId = uuid(datos, "pacienteId")`; si es `null` **y** `"paciente.registrado".equals(tipo)` → `recursoId` (decisión m3).
* `especialidadNombre = texto("especialidadNombre")`, si `null` → `texto("especialidad")`.
* `areaNombre = texto("areaNombre")`, si `null` → `texto("area")`.
* Resto: `codigo`, `fechaHora`, `fechaHoraAnterior`, `fechaAtencion`, `citaId`, `monto`, `numeroOperacion` con el helper correspondiente.
* Constante `static final ZoneId ZONA_LIMA = ZoneId.of("America/Lima")` (no importar `RelojAutoConfiguration` en `domain/`: es una clase de Spring).

Campos reales de `datos` por evento (para referencia):

| Evento | `datos` (productor) | Campos que usa la plantilla |
|---|---|---|
| `paciente.registrado` | `{pacienteId, fechaRegistro}` | `pacienteId` |
| `cita.creada` / `cita.confirmada` / `cita.cancelada` | `{codigo, pacienteId, especialidad, especialidadNombre, fechaHora, profesionalCodigo}` (+`motivo`, +`atencionId` en cancelada por anulación) | `codigo`, `especialidadNombre`, `fechaHora` |
| `cita.reprogramada` | común + `fechaHoraAnterior`, `profesionalAnterior` | + `fechaHoraAnterior` |
| `atencion.registrada` | `{atencionId, citaId, pacienteId, codigo, area, areaNombre, fechaAtencion}` | `codigo`, `areaNombre`, `fechaAtencion`, `citaId` |
| `pago.registrado` / `pago.anulado` | `{pagoId, pacienteId, citaId, numeroOperacion, concepto, monto, moneda, medioPago, fechaPago}` (+`motivo` en anulado) | `monto`, `numeroOperacion` |

### 2.2 `domain/PlantillasNotificacion.java`

```java
public final class PlantillasNotificacion {
    public static final int LARGO_MAXIMO = 300;
    public static final String SIN_DATO = "N/D";
    private static final ZoneId ZONA_LIMA = ZoneId.of("America/Lima");
    private static final DateTimeFormatter FORMATO_FECHA = DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm", Locale.ROOT);

    private PlantillasNotificacion() { }

    /** Texto del aviso; Optional.empty() si el tipo no tiene plantilla (o es null). */
    public static Optional<String> generar(String tipo, DatosEvento datos) { … }

    /** Tipos con plantilla (para la prueba de consistencia con los bindings). */
    public static Set<String> tiposSoportados() { … }

    static String fecha(OffsetDateTime f)  // null → SIN_DATO; si no: f.atZoneSameInstant(ZONA_LIMA).format(FORMATO_FECHA)
    static String monto(BigDecimal m)      // null → SIN_DATO; si no: m.setScale(2, RoundingMode.HALF_UP).toPlainString()
    static String valor(Object o)          // null → SIN_DATO; si no: String.valueOf(o)
}
```

`generar` usa un `switch` sobre `tipo` con las constantes de `TopologiaHospital` (son `static final String` literales, válidas en `case`; `TopologiaHospital` no depende de Spring). Textos **exactos** (UTF-8, con tildes; `%s` = valor ya formateado):

| Tipo | Texto |
|---|---|
| `paciente.registrado` | `Bienvenido/a al Hospital Vida Salud. Su registro fue exitoso (paciente {valor(pacienteId)}).` |
| `cita.creada` | `Su cita {codigo} de {especialidadNombre} fue registrada para el {fecha(fechaHora)}. Está pendiente de confirmación.` |
| `cita.confirmada` | `Su cita {codigo} de {especialidadNombre} del {fecha(fechaHora)} fue confirmada.` |
| `cita.reprogramada` | `Su cita {codigo} de {especialidadNombre} fue reprogramada del {fecha(fechaHoraAnterior)} al {fecha(fechaHora)}.` |
| `cita.cancelada` | `Su cita {codigo} de {especialidadNombre} del {fecha(fechaHora)} fue cancelada.` |
| `atencion.registrada` | `Su atención {codigo} en {areaNombre} fue registrada el {fecha(fechaAtencion)} (cita {valor(citaId)}).` |
| `pago.registrado` | `Se registró su pago de S/ {monto(monto)} (operación {numeroOperacion}).` |
| `pago.anulado` | `Su pago con operación {numeroOperacion} fue anulado.` |

Los `String` (`codigo`, `especialidadNombre`, `areaNombre`, `numeroOperacion`) pasan por `valor(...)` (→ `N/D` si son `null`). `default -> null` y se devuelve `Optional.ofNullable(texto).map(PlantillasNotificacion::recortar)` (recorta a `LARGO_MAXIMO`). Armar con concatenación o `String.format(Locale.ROOT, …)`.

### 2.3 `domain/Notificacion.java` (entidad R2DBC)

```java
@Table("notificacion")
public class Notificacion implements Persistable<UUID> {
    public static final String CANAL_SIMULADO = "SIMULADO";

    @Id private final UUID id;
    @Column("event_id") private final UUID eventId;
    @Column("paciente_id") private final UUID pacienteId;      // puede ser null
    @Column("tipo_evento") private final String tipoEvento;
    @Column("recurso_id") private final UUID recursoId;
    @Column("canal") private final String canal;
    @Column("mensaje") private final String mensaje;
    @Column("fecha_envio") private final OffsetDateTime fechaEnvio;
    @Transient private boolean nuevo;

    @PersistenceCreator
    public Notificacion(UUID id, UUID eventId, UUID pacienteId, String tipoEvento, UUID recursoId,
                        String canal, String mensaje, OffsetDateTime fechaEnvio) { …; this.nuevo = false; }

    /** Nueva notificación: id UUID v4 generado aquí, canal SIMULADO, marcada como nueva (INSERT). */
    public static Notificacion nueva(UUID eventId, UUID pacienteId, String tipoEvento, UUID recursoId,
                                     String mensaje, OffsetDateTime fechaEnvio) { … n.nuevo = true; return n; }

    @Override public UUID getId() { return id; }
    @Override public boolean isNew() { return nuevo; }
    // getters: getEventId(), getPacienteId(), getTipoEvento(), getRecursoId(), getCanal(), getMensaje(), getFechaEnvio()
}
```

Imports exactos: `org.springframework.data.annotation.Id`, `org.springframework.data.annotation.Transient`, `org.springframework.data.annotation.PersistenceCreator`, `org.springframework.data.domain.Persistable`, `org.springframework.data.relational.core.mapping.Table`, `org.springframework.data.relational.core.mapping.Column`. Sin setters. Sin `equals/hashCode` necesarios.

### 2.4 `domain/FiltroNotificaciones.java`

```java
/** Filtros opcionales del listado; null = no filtra. */
public record FiltroNotificaciones(UUID pacienteId, String tipoEvento, LocalDate fecha) {
    public FiltroNotificaciones {               // normaliza: tipoEvento en blanco → null, strip()
        tipoEvento = (tipoEvento == null || tipoEvento.isBlank()) ? null : tipoEvento.strip();
    }
}
```

### 2.5 Persistencia

`persistence/NotificacionRepository.java`:
```java
public interface NotificacionRepository extends ReactiveCrudRepository<Notificacion, UUID> { }
```

`persistence/ConsultaNotificaciones.java` (`@Component`):
```java
public class ConsultaNotificaciones {
    public static final Sort ORDEN = Sort.by(Sort.Order.desc("fechaEnvio"), Sort.Order.asc("id"));
    private static final ZoneId ZONA_LIMA = ZoneId.of("America/Lima");
    private final R2dbcEntityTemplate template;

    public ConsultaNotificaciones(R2dbcEntityTemplate template) { … }

    public Flux<Notificacion> buscar(FiltroNotificaciones filtro, ParametrosPagina pagina) {
        return template.select(Query.query(criterio(filtro)).with(pagina.aPageable(ORDEN)), Notificacion.class);
    }

    public Mono<Long> contar(FiltroNotificaciones filtro) {
        return template.count(Query.query(criterio(filtro)), Notificacion.class);
    }

    static Criteria criterio(FiltroNotificaciones f) {
        Criteria c = Criteria.empty();
        if (f.pacienteId() != null) c = c.and("pacienteId").is(f.pacienteId());
        if (f.tipoEvento() != null) c = c.and("tipoEvento").is(f.tipoEvento());
        if (f.fecha() != null) {
            OffsetDateTime inicio = f.fecha().atStartOfDay(ZONA_LIMA).toOffsetDateTime();
            OffsetDateTime fin = f.fecha().plusDays(1).atStartOfDay(ZONA_LIMA).toOffsetDateTime();
            c = c.and("fechaEnvio").greaterThanOrEquals(inicio).and("fechaEnvio").lessThan(fin);
        }
        return c;
    }
}
```
Imports: `org.springframework.data.r2dbc.core.R2dbcEntityTemplate`, `org.springframework.data.relational.core.query.Criteria`, `org.springframework.data.relational.core.query.Query`, `org.springframework.data.domain.Sort`, `pe.edu.cibertec.vidasalud.commons.paginacion.ParametrosPagina`. En `Criteria`, `Sort` y `Query` se usan **nombres de propiedad Java** (`fechaEnvio`), el template los traduce a columnas. `R2dbcEntityTemplate` lo crea la autoconfiguración de Boot (`R2dbcDataAutoConfiguration`).

### 2.6 `messaging/BindingsNotificaciones.java`

```java
public final class BindingsNotificaciones {
    /** Fase A: prioridad normal. */
    public static final List<String> FASE_A = List.of(
            TopologiaHospital.PACIENTE_REGISTRADO, TopologiaHospital.CITA_CREADA, TopologiaHospital.CITA_CONFIRMADA,
            TopologiaHospital.CITA_REPROGRAMADA, TopologiaHospital.CITA_CANCELADA);
    /** Fase B: prioridad baja (solo agrega bindings y plantillas). */
    public static final List<String> FASE_B = List.of(
            TopologiaHospital.ATENCION_REGISTRADA, TopologiaHospital.PAGO_REGISTRADO, TopologiaHospital.PAGO_ANULADO);
    /** Lo que se enlaza a notificaciones.cola. Bindings explícitos (D-34): nunca comodines. */
    public static final List<String> TODOS = Stream.concat(FASE_A.stream(), FASE_B.stream()).toList();
    private BindingsNotificaciones() { }
}
```
No enlazar `cita.atendida`, `cita.ausente`, `paciente.estado-cambiado` ni `atencion.*` distintos de `atencion.registrada`.

### 2.7 `api/NotificacionResponse.java`

```java
public record NotificacionResponse(UUID id, UUID eventId, UUID pacienteId, String tipoEvento, UUID recursoId,
                                   String canal, String mensaje, OffsetDateTime fechaEnvio) {
    public static NotificacionResponse desde(Notificacion n) {
        // fechaEnvio expresada en hora de Lima: n.getFechaEnvio().atZoneSameInstant(ZoneId.of("America/Lima")).toOffsetDateTime()
    }
}
```

### 2.8 `service/NotificacionService.java` (`@Service`)

```java
public class NotificacionService {
    private static final Logger log = LoggerFactory.getLogger(NotificacionService.class);
    private final NotificacionRepository repositorio;
    private final ConsultaNotificaciones consulta;
    private final Clock reloj;

    public NotificacionService(NotificacionRepository repositorio, ConsultaNotificaciones consulta, Clock reloj) { … }

    /** Manejador de cada evento consumido. */
    public Mono<Void> procesar(EventoEnvelope evento) { … }

    public Flux<NotificacionResponse> listar(FiltroNotificaciones filtro, ParametrosPagina pagina) {
        return consulta.buscar(filtro, pagina).map(NotificacionResponse::desde);
    }

    public Mono<Long> contar(FiltroNotificaciones filtro) { return consulta.contar(filtro); }

    public Mono<NotificacionResponse> obtener(UUID id) {
        return repositorio.findById(id).map(NotificacionResponse::desde)
                .switchIfEmpty(Mono.error(() -> new ErrorNegocio(HttpStatus.NOT_FOUND, "NOTIFICACION_NO_ENCONTRADA",
                        "No existe la notificación " + id + ".")));
    }
}
```

`procesar` (todo dentro de `Mono.defer`, sin `.block()`):
1. `DatosEvento datos = DatosEvento.desde(evento.tipo(), evento.recursoId(), evento.datos())`.
2. `Optional<String> texto = PlantillasNotificacion.generar(evento.tipo(), datos)`. Si está vacío → `log.warn("evento sin plantilla ignorado eventId={} tipo={}", …)` y `Mono.empty()` (se confirma sin guardar).
3. Si `evento.recursoId() == null` → `Mono.error(new IllegalArgumentException("evento sin recursoId"))` (decisión m4).
4. `Notificacion.nueva(evento.eventId(), datos.pacienteId(), evento.tipo(), evento.recursoId(), texto.get(), OffsetDateTime.now(reloj).truncatedTo(ChronoUnit.MICROS))`.
5. `repositorio.save(n)` → `doOnSuccess`: `log.info("notificacion simulada enviada eventId={} tipo={} recursoId={} pacienteId={}", …)` (**sin** el texto) → `.then()`.
6. Al final del pipeline: `.onErrorResume(DuplicateKeyException.class, e -> { log.info("notificacion repetida ignorada eventId={} tipo={}", …); return Mono.empty(); })`. Es redundante con `ConsumidorEventos` (que también hace `ack` ante `DuplicateKeyException`), pero la spec 06 §7 exige que el servicio complete sin error.

Otros errores (BD caída, etc.) se propagan: `ConsumidorEventos` reintenta 3 veces con espera exponencial (500 ms) y luego manda a `notificaciones.dlq`.

`Clock` lo aporta `RelojAutoConfiguration` de `hospital-commons` (`Clock.system(America/Lima)`). `ErrorNegocio` es `pe.edu.cibertec.vidasalud.commons.error.ErrorNegocio`; `DuplicateKeyException` es `org.springframework.dao.DuplicateKeyException` (Spring R2DBC la produce desde `SQLSTATE 23505`; verificado en `ConnectionFactoryUtils` de `spring-r2dbc` 6.2.19).

### 2.9 `messaging/NotificacionesConsumer.java` (`@Component`)

```java
public class NotificacionesConsumer {
    private final NotificacionService servicio;
    public NotificacionesConsumer(NotificacionService servicio) { … }
    /** Manejador que se registra en ConsumidorEventos. */
    public Mono<Void> manejar(EventoEnvelope evento) { return servicio.procesar(evento); }
}
```
No depende de beans de RabbitMQ (por eso existe aunque `hospital.rabbit.enabled=false`).

### 2.10 `config/ConsumoNotificacionesConfig.java`

```java
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(prefix = "hospital.rabbit", name = "enabled", havingValue = "true", matchIfMissing = true)
public class ConsumoNotificacionesConfig {
    @Bean
    public ApplicationRunner iniciarConsumoNotificaciones(ConsumidorEventos consumidor, NotificacionesConsumer consumer) {
        return args -> consumidor.iniciar(TopologiaHospital.COLA_NOTIFICACIONES, BindingsNotificaciones.TODOS,
                consumer::manejar);
    }
}
```
* Misma condición que `RabbitAutoConfiguration` de commons: con `hospital.rabbit.enabled=false` no existe `ConsumidorEventos` y este runner tampoco. **No** usar `@ConditionalOnBean(ConsumidorEventos.class)` (en clases de usuario se evalúa antes que las autoconfiguraciones y falla).
* `iniciar` vuelve de inmediato; declara (idempotente) exchange `hospital.eventos` (topic), DLX `hospital.eventos.dlx` (direct), `notificaciones.dlq`, `notificaciones.cola` (con `x-dead-letter-exchange` y `x-dead-letter-routing-key=notificaciones.dlq`) y los 8 bindings; si RabbitMQ no está, reintenta la conexión con backoff hasta 30 s sin impedir el arranque.

### 2.11 `api/NotificacionController.java`

```java
@RestController
@RequestMapping("/api/notificaciones")
public class NotificacionController {
    private final NotificacionService servicio;

    @Operation(summary = "Lista notificaciones simuladas (paginado, X-Total-Count)")
    @GetMapping
    public Mono<ResponseEntity<Flux<NotificacionResponse>>> listar(
            @RequestParam(required = false) UUID pacienteId,
            @RequestParam(required = false) String tipoEvento,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate fecha,
            @RequestParam(required = false) Integer pagina,
            @RequestParam(required = false) Integer tamano) {
        ParametrosPagina pag = ParametrosPagina.de(pagina, tamano);   // fuera de rango → ErrorNegocio 400 VALIDACION_FALLIDA
        FiltroNotificaciones filtro = new FiltroNotificaciones(pacienteId, tipoEvento, fecha);
        return RespuestaPaginada.de(servicio.listar(filtro, pag), servicio.contar(filtro));
    }

    @Operation(summary = "Obtiene una notificación por id")
    @GetMapping("/{id}")
    public Mono<NotificacionResponse> obtener(@PathVariable UUID id) { return servicio.obtener(id); }
}
```
* Sin endpoints de escritura.
* `pacienteId`/`id` no UUID o `fecha` mal formada → `ServerWebInputException` → `ManejadorErrores` (commons, autoconfigurado) → `400 VALIDACION_FALLIDA`.
* `@Operation` es `io.swagger.v3.oas.annotations.Operation` (viene con springdoc).

### 2.12 Recursos

* `src/main/resources/schema.sql` (§3.1).
* `src/test/resources/application-test.yml` (§3.6).

### 2.13 Pruebas (en `src/test/java/pe/edu/cibertec/vidasalud/notificaciones/…`)

| Archivo | Paquete |
|---|---|
| `domain/DatosEventoTest.java` | `…notificaciones.domain` |
| `domain/PlantillasNotificacionTest.java` | `…notificaciones.domain` |
| `messaging/BindingsNotificacionesTest.java` | `…notificaciones.messaging` |
| `service/NotificacionServiceTest.java` | `…notificaciones.service` |
| `api/NotificacionControllerTest.java` | `…notificaciones.api` |
| `persistence/NotificacionRepositoryIT.java` | `…notificaciones.persistence` |
| `messaging/NotificacionesConsumerIT.java` | `…notificaciones.messaging` |

---

## 3. Decisiones técnicas (verificadas contra las versiones reales)

Versiones resueltas en el módulo: Spring Boot 3.5.16, Spring Data R2DBC 3.5.13, spring-r2dbc 6.2.19, r2dbc-postgresql 1.0.9, Spring Cloud 2025.0.3 (Eureka client 4.3.3), Testcontainers 1.21.4, Mockito 5.17, Awaitility 4.2.2 (incluida vía `spring-boot-starter-test`), Jackson 2.21.

### 3.1 `schema.sql` (DDL del documento §8.3, con `IF NOT EXISTS`)

```sql
CREATE TABLE IF NOT EXISTS notificacion (
  id           UUID PRIMARY KEY,
  event_id     UUID         NOT NULL UNIQUE,
  paciente_id  UUID,
  tipo_evento  VARCHAR(50)  NOT NULL,
  recurso_id   UUID         NOT NULL,
  canal        VARCHAR(15)  NOT NULL DEFAULT 'SIMULADO',
  mensaje      VARCHAR(300) NOT NULL,
  fecha_envio  TIMESTAMPTZ  NOT NULL DEFAULT now()
);
CREATE INDEX IF NOT EXISTS ix_notificacion_paciente ON notificacion (paciente_id);
CREATE INDEX IF NOT EXISTS ix_notificacion_fecha ON notificacion (fecha_envio);
```
Se ejecuta en cada arranque por `spring.sql.init.mode: always` (ya en `application.yml`). Separador `;` normal (no hay PL/pgSQL). Sin comentarios con `;` dentro.

### 3.2 Entidad con UUID asignado
Como el id lo genera la aplicación, `save()` haría `UPDATE` (0 filas → `TransientDataAccessResourceException`) si la entidad no implementa `Persistable`. Por eso `Notificacion.isNew()` devuelve `true` solo para las creadas con `nueva(...)`; las leídas de BD pasan por el constructor `@PersistenceCreator` con `nuevo=false`. Los nombres de parámetro del constructor deben coincidir con los campos (el POM padre compila con `-parameters`).

### 3.3 DTO tolerante a partir de `JsonNode`
`EventoEnvelope.datos` es `JsonNode`. Se lee campo a campo con `path(...)` (§2.1) en vez de `treeToValue` a un record, porque un solo campo con formato raro (fecha sin offset, monto como texto) haría fallar toda la conversión y el mensaje terminaría en la DLQ tras 3 reintentos inútiles. Ojo: al deserializar el sobre, Jackson convierte `80.00` en `DoubleNode(80.0)`; `decimalValue()` devuelve `BigDecimal.valueOf(80.0)` y `setScale(2)` da `80.00`.

### 3.4 Bindings explícitos e idempotencia
* Bindings de `BindingsNotificaciones.TODOS` (fases A y B), nunca comodines.
* Idempotencia: `event_id UNIQUE`. Segundo `INSERT` del mismo `eventId` → `DuplicateKeyException` → el servicio completa vacío → `ConsumidorEventos` hace `ack`. **No** hacer consulta previa por `event_id` (la restricción es la garantía).
* Mensaje no deserializable (JSON inválido, sin `eventId` o `tipo`) → `ConsumidorEventos` hace `nack(requeue=false)` inmediato → `notificaciones.dlq`, sin invocar al manejador (D-36). No hay que programar nada.
* Otros errores → 3 intentos (backoff 500 ms) → DLQ. Ya implementado en commons.

### 3.5 Consultas dinámicas y paginación
`R2dbcEntityTemplate` + `Criteria` agregando cada condición solo si el parámetro llegó; `Query.with(PageRequest)` vía `ParametrosPagina.aPageable(ORDEN)`; total con `template.count(Query.query(criterio), Notificacion.class)` usando **el mismo** `Criteria`. Orden estable `fecha_envio DESC, id ASC`. Respuesta con `RespuestaPaginada.de(flux, total)` → `Mono<ResponseEntity<Flux<NotificacionResponse>>>` con cabecera `X-Total-Count`.

### 3.6 Configuración de pruebas
`src/test/resources/application-test.yml`:
```yaml
eureka:
  client:
    enabled: false
spring:
  r2dbc:
    password: test          # lo reemplaza @ServiceConnection en las IT
hospital:
  rabbit:
    username: guest
    password: guest         # lo reemplaza @DynamicPropertySource en la IT de mensajería
```
Todas las pruebas con contexto Spring usan `@ActiveProfiles("test")`.

* **PostgreSQL**: `@Container @ServiceConnection static PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16");` (requiere `org.testcontainers:r2dbc`, paso 0).
* **RabbitMQ**: `@Container static RabbitMQContainer RABBIT = new RabbitMQContainer("rabbitmq:3.13-management");` y
  ```java
  @DynamicPropertySource
  static void rabbit(DynamicPropertyRegistry r) {
      r.add("hospital.rabbit.enabled", () -> "true");
      r.add("hospital.rabbit.host", RABBIT::getHost);
      r.add("hospital.rabbit.port", RABBIT::getAmqpPort);
      r.add("hospital.rabbit.username", RABBIT::getAdminUsername);
      r.add("hospital.rabbit.password", RABBIT::getAdminPassword);
  }
  ```
  (No hay `@ServiceConnection` para RabbitMQ: commons usa sus propias propiedades `hospital.rabbit.*`, no `spring.rabbitmq.*`.)
* **Controlador**: `@WebFluxTest(controllers = NotificacionController.class, properties = {"hospital.rabbit.enabled=false", "eureka.client.enabled=false"})`, `@Import(ManejadorErrores.class)` (el slice no carga las autoconfiguraciones de commons), `@MockitoBean NotificacionService` (`org.springframework.test.context.bean.override.mockito.MockitoBean`; `@MockBean` está deprecado en Boot 3.4+).

### 3.7 Desactivación del consumo
`hospital.rabbit.enabled=false` → no se crean `ConexionRabbit`/`Sender`/`Receiver`/`ConsumidorEventos`/`PublicadorEventos` (commons) ni `ConsumoNotificacionesConfig` (este servicio). El resto (API, BD) funciona igual.

### 3.8 Logs
* `INFO` por notificación: `eventId`, `tipo`, `recursoId`, `pacienteId`. **Nunca** el texto del mensaje ni `datos`.
* `ConsumidorEventos` ya registra `evento procesado eventId=… tipo=…` y los envíos a la DLQ.

---

## 4. Pruebas

Convención: `*Test` → Surefire (sin Docker); `*IT` → Failsafe en `verify` (Docker). Datos de prueba ficticios. Fechas de ejemplo: `fechaHora = "2026-10-15T15:00:00Z"` → `15/10/2026 10:00`; `fechaHoraAnterior = "2026-10-14T14:30:00Z"` → `14/10/2026 09:30`. UUIDs fijos de ejemplo: paciente `11111111-1111-4111-8111-111111111111`, cita `22222222-2222-4222-8222-222222222222`.

### 4.1 `DatosEventoTest` (JUnit + AssertJ, `ObjectMapper` local para crear `JsonNode`)
1. Lee todos los campos de una cita completa (incluye `fechaHora` con offset `-05:00` y con `Z`).
2. `especialidadNombre` ausente → usa `especialidad`; `areaNombre` ausente → usa `area`.
3. `pacienteId` ausente en `paciente.registrado` → `recursoId`; ausente en `cita.creada` → `null`.
4. `pacienteId` no UUID → `null` (no lanza).
5. Fecha inválida → `null`; fecha sin offset (`2026-10-15T10:00:00`) → interpretada en Lima.
6. `monto` numérico `80`, `80.0`, `80.00` y texto `"80.00"` → `BigDecimal` comparable a `80.00`; texto no numérico → `null`.
7. `datos` `null` o `NullNode` → todos los campos `null` sin excepción.

### 4.2 `PlantillasNotificacionTest`
Un caso por tipo (fases A y B), comparando el **texto exacto** de §2.2:
1. `paciente.registrado` → `Bienvenido/a al Hospital Vida Salud. Su registro fue exitoso (paciente 11111111-1111-4111-8111-111111111111).`
2. `cita.creada` (`codigo` `CIT-20261012-000001`, `especialidadNombre` `Medicina General`) → `Su cita CIT-20261012-000001 de Medicina General fue registrada para el 15/10/2026 10:00. Está pendiente de confirmación.`
3. `cita.confirmada` → `Su cita CIT-20261012-000001 de Medicina General del 15/10/2026 10:00 fue confirmada.` (evento con offset UTC → hora de Lima)
4. `cita.reprogramada` → `Su cita CIT-20261012-000001 de Medicina General fue reprogramada del 14/10/2026 09:30 al 15/10/2026 10:00.`
5. `cita.cancelada` → `Su cita CIT-20261012-000001 de Medicina General del 15/10/2026 10:00 fue cancelada.`
6. `atencion.registrada` (`ATE-20261015-000001`, `Consulta Externa`, `fechaAtencion` `2026-10-15T15:05:00Z`, `citaId` fijo) → `Su atención ATE-20261015-000001 en Consulta Externa fue registrada el 15/10/2026 10:05 (cita 22222222-2222-4222-8222-222222222222).`
7. `pago.registrado` con `monto` `80` → `Se registró su pago de S/ 80.00 (operación OP-20261012-000001).`
8. `pago.anulado` → `Su pago con operación OP-20261012-000001 fue anulado.`
9. Tipo desconocido (`cita.atendida`) y tipo `null` → `Optional.empty()`.
10. Campo ausente (`codigo`) → el texto contiene `N/D`.
11. Texto resultante nunca supera 300 caracteres (con `especialidadNombre` de 400 caracteres).

### 4.3 `BindingsNotificacionesTest`
1. `TODOS` = `FASE_A` + `FASE_B`, 8 elementos, sin repetidos, sin comodines (`*`/`#`).
2. `Set.copyOf(TODOS)` es igual a `PlantillasNotificacion.tiposSoportados()` (cada binding tiene plantilla y viceversa).
3. No contiene `cita.atendida`, `cita.ausente`, `paciente.estado-cambiado`.

### 4.4 `NotificacionServiceTest` (`StepVerifier` + Mockito, `Clock.fixed(Instant.parse("2026-10-12T14:30:00Z"), America/Lima)`)
Mocks: `NotificacionRepository`, `ConsultaNotificaciones`. `when(repositorio.save(any())).thenAnswer(inv -> Mono.just(inv.getArgument(0)))`; capturar con `ArgumentCaptor<Notificacion>`.
1. `cita.confirmada` → guarda con `eventId` del sobre, `pacienteId` de `datos.pacienteId`, `recursoId` del sobre, `tipoEvento`, canal `SIMULADO`, `fechaEnvio` igual al instante del reloj, `isNew()==true`, mensaje esperado; completa.
2. `paciente.registrado` sin `pacienteId` en `datos` → `pacienteId == recursoId`.
3. `save` devuelve `Mono.error(new DuplicateKeyException("dup"))` → `StepVerifier…verifyComplete()`.
4. Tipo sin plantilla (`cita.atendida`) → completa y `verify(repositorio, never()).save(any())`.
5. `recursoId == null` → `verifyError(IllegalArgumentException.class)`.
6. Otro error de BD (`Mono.error(new TransientDataAccessResourceException("x"))`) → se propaga (no se traga).
7. `obtener` inexistente (`findById` → `Mono.empty()`) → `ErrorNegocio` con `getStatus()==NOT_FOUND` y `getCodigo()=="NOTIFICACION_NO_ENCONTRADA"`.
8. `listar` mapea entidades a `NotificacionResponse` (fecha expresada con offset `-05:00`).

### 4.5 `NotificacionControllerTest` (`@WebFluxTest`, ver §3.6)
1. `GET /api/notificaciones?pacienteId={uuid}&tipoEvento=cita.confirmada&fecha=2026-10-12&pagina=1&tamano=5` → `200`, cabecera `X-Total-Count: 7`, cuerpo arreglo con 1 elemento; `verify(servicio).listar(new FiltroNotificaciones(uuid, "cita.confirmada", LocalDate.of(2026,10,12)), new ParametrosPagina(1, 5))` (los records comparan por valor).
2. Sin parámetros → `listar(new FiltroNotificaciones(null, null, null), new ParametrosPagina(0, 20))`.
3. `GET /api/notificaciones/{id}` existente → `200` con `mensaje` y `eventId`.
4. `GET /api/notificaciones/{id}` inexistente (servicio lanza `ErrorNegocio` 404) → `404`, `application/problem+json`, `$.codigo == "NOTIFICACION_NO_ENCONTRADA"`.
5. `GET /api/notificaciones/no-es-uuid` → `400 VALIDACION_FALLIDA`.
6. `tamano=0` y `tamano=500` → `400 VALIDACION_FALLIDA`; `pacienteId=abc` → `400`; `fecha=12/10/2026` → `400`.
7. No hay `POST`/`PUT`/`DELETE`: `POST /api/notificaciones` → `405` (o `4xx`; basta afirmar que no es `2xx`).

Stubs: `when(servicio.listar(any(), any())).thenReturn(Flux.just(resp))`, `when(servicio.contar(any())).thenReturn(Mono.just(7L))` (un mock sin stub devuelve `null` y rompe con NPE).

### 4.6 `NotificacionRepositoryIT` (`@DataR2dbcTest` + `@Import(ConsultaNotificaciones.class)` + `@Testcontainers` + `@ActiveProfiles("test")`, PostgreSQL con `@ServiceConnection`)
`@DataR2dbcTest` incluye `SqlInitializationAutoConfiguration`, así que corre `schema.sql`. En `@BeforeEach`: `template.delete(Notificacion.class).all().block()`.
1. `schema.sql` crea la tabla; `save(Notificacion.nueva(...))` y `findById` devuelven los mismos datos (`fechaEnvio` comparar con `isEqual`).
2. Dos notificaciones con el mismo `eventId` → la segunda `save` termina en `DuplicateKeyException` (`StepVerifier.expectError(DuplicateKeyException.class)`).
3. Filtros: por `pacienteId`, por `tipoEvento`, por `fecha` (una fila a las 23:30 Lima del día D y otra a las 00:30 Lima del día D+1 → filtrar D devuelve solo la primera), combinados.
4. Orden `fechaEnvio DESC, id ASC` y paginación (`pagina=1, tamano=2` sobre 5 filas devuelve las filas 3.ª y 4.ª); `contar` devuelve el total sin paginar con el mismo filtro.

### 4.7 `NotificacionesConsumerIT` — prueba clave (`@SpringBootTest(webEnvironment = RANDOM_PORT)` + `@AutoConfigureWebTestClient` + `@Testcontainers` + `@ActiveProfiles("test")`)
Contenedores y propiedades: §3.6. Beans inyectados: `PublicadorEventos` (el **real** de commons), `reactor.rabbitmq.Sender`, `R2dbcEntityTemplate`, `ObjectMapper`, `WebTestClient`. `@MockitoSpyBean NotificacionService servicio` (para el caso 4). Conexión AMQP directa para inspeccionar colas (como en `MensajeriaRabbitIT` de commons): `com.rabbitmq.client.ConnectionFactory` → `RABBIT.getHost()`, `getAmqpPort()`, admin user/pass.

`@BeforeEach` (o `@BeforeAll` estático tras arrancar el contexto): **esperar a que el consumidor esté listo** antes de publicar:
`await().atMost(30 s).ignoreExceptions().until(() -> canal.queueDeclarePassive("notificaciones.cola").getConsumerCount() == 1)`. El consumidor se registra **después** de declarar cola y bindings, así que esto garantiza que el mensaje no se pierda por no estar enlazado.

Helpers: `long filasDe(UUID eventId)` = `template.select(Notificacion.class).matching(Query.query(Criteria.where("eventId").is(eventId))).count().block()`; `int mensajesEn(String cola)` = `queueDeclarePassive(cola).getMessageCount()`. Cada caso usa `eventId`, `pacienteId` y `recursoId` nuevos (`UUID.randomUUID()`), y compara la DLQ contra el conteo **previo** (la DLQ se comparte entre casos).

Sobre de ejemplo: `EventoEnvelope.nuevo(Clock.systemUTC(), "cita.confirmada", "citas-service", "CITA", citaId, "PENDIENTE", "CONFIRMADA", datos)` con `datos = mapper.createObjectNode()` + `codigo`, `pacienteId`, `especialidad=MEDICINA_GENERAL`, `especialidadNombre=Medicina General`, `fechaHora=2026-10-15T15:00:00Z`, `profesionalCodigo=MED-001`, `motivo`.

1. **Publicar `cita.confirmada` → aparece la fila** (enunciado §20): `publicador.publicar("cita.confirmada", evento).block(10 s)`; `await().atMost(15 s).until(() -> filasDe(eventId) == 1)`; la fila tiene `mensaje == "Su cita CIT-20261012-000001 de Medicina General del 15/10/2026 10:00 fue confirmada."`, `pacienteId`, `recursoId == citaId`, `tipoEvento == "cita.confirmada"`, `canal == "SIMULADO"`. Además `GET /api/notificaciones?pacienteId={pacienteId}` → `200`, `X-Total-Count: 1`, 1 elemento con ese mensaje.
2. **Duplicado**: publicar el **mismo** sobre dos veces → `await().during(2 s).atMost(8 s).until(() -> filasDe(eventId) == 1)`; `mensajesEn("notificaciones.dlq")` sin cambios; `mensajesEn("notificaciones.cola") == 0`.
3. **JSON inválido → DLQ**: `sender.send(Mono.just(new OutboundMessage(TopologiaHospital.EXCHANGE, "cita.confirmada", "{esto no es json".getBytes(UTF_8)))).block(10 s)` → `await().atMost(15 s).until(() -> mensajesEn("notificaciones.dlq") == previo + 1)`; con `basicGet("notificaciones.dlq", true)` el cuerpo es `{esto no es json`; la tabla no ganó filas.
4. **Evento no enlazado (`cita.atendida`) no llega a la cola**:
   a. Topología: `GET {RABBIT.getHttpUrl()}/api/queues/%2F/notificaciones.cola/bindings` (API de administración, `java.net.http.HttpClient` con Basic auth admin) → las `routing_key` con `source == "hospital.eventos"` son exactamente `BindingsNotificaciones.TODOS` y no incluyen `cita.atendida`.
   b. Comportamiento: publicar `cita.atendida` (eventId A) y luego un `cita.confirmada` centinela (eventId B); esperar la fila de B; `verify(servicio, never()).procesar(argThat(e -> e.eventId().equals(A)))`; `filasDe(A) == 0`.
5. **Fase B**: publicar `pago.registrado` (`tipoRecurso=PAGO`, `datos.monto = new BigDecimal("80.00")`, `numeroOperacion = OP-20261012-000001`) → fila con `Se registró su pago de S/ 80.00 (operación OP-20261012-000001).`

`.block()`, `Thread.sleep` y `await` están permitidos **solo en pruebas**.

---

## 5. Cómo ejecutar y verificar

Desde `/home/cazabache/hospital-vida-salud`:

```bash
./mvnw -q -pl notificaciones-service compile             # compilar
./mvnw -q -pl notificaciones-service test                # *Test (sin Docker)
sg docker -c "./mvnw -q -pl notificaciones-service verify"   # *Test + *IT (Testcontainers)
```

* **No usar `-am`**: `hospital-commons` ya está instalado en `~/.m2`. Si se cambió commons, reinstalarlo antes: `./mvnw -q -pl hospital-commons install -DskipTests`.
* `sg docker -c "…"`: el grupo `docker` no está activo en la shell; sin esto Testcontainers no encuentra Docker.
* Si falta el POM padre en `~/.m2`: `./mvnw -q -N install`. Si falta algún módulo listado en el `pom.xml` raíz: esperar a que exista (no editar el POM raíz).
* `-q` oculta el detalle; ante fallos, revisar `notificaciones-service/target/surefire-reports/` y `target/failsafe-reports/`, o repetir sin `-q`.
* Verificación manual opcional (con `infra/` levantado y `source scripts/entorno-dev.sh`): `./mvnw -q -pl notificaciones-service spring-boot:run`; en `http://localhost:15672` ver `notificaciones.cola` con los 8 bindings y `notificaciones.dlq`; `GET http://localhost:8083/api/notificaciones`; Swagger en `http://localhost:8083/swagger-ui.html`.

---

## 6. Trampas conocidas

1. **`@ServiceConnection` + R2DBC necesita `org.testcontainers:r2dbc`** (paso 0). Sin él: `NoClassDefFoundError: org/testcontainers/r2dbc/R2DBCDatabaseContainer` o contexto sin `ConnectionFactory`.
2. **Publicar antes de que el consumidor esté listo** = mensaje perdido (el exchange existe, pero la cola aún no está enlazada). Esperar `getConsumerCount() == 1` en `notificaciones.cola` antes de publicar.
3. **`Persistable`**: sin `isNew()==true`, `save()` hace `UPDATE` y falla. `@Transient` debe ser `org.springframework.data.annotation.Transient`.
4. **`LocalDate` en `@RequestParam`** necesita `@DateTimeFormat(iso = ISO.DATE)`; sin él se usa el formato corto dependiente del `Locale`.
5. **No usar `@ConditionalOnBean`** en `ConsumoNotificacionesConfig`: usar `@ConditionalOnProperty(prefix="hospital.rabbit", name="enabled", havingValue="true", matchIfMissing=true)`.
6. **Nada de `.subscribe()`/`.block()` en `src/main`**: el único `subscribe` es el de `ConsumidorEventos.iniciar` (en commons). El `ApplicationRunner` solo llama a `iniciar`.
7. **`ManejadorErrores` no se carga en `@WebFluxTest`**: importarlo con `@Import(ManejadorErrores.class)`.
8. **Mocks sin stub** devuelven `null` para `Flux`/`Mono` → NPE. Stub de `listar` **y** `contar`.
9. **DLQ compartida entre casos de la IT**: comparar contra el conteo previo; usar UUIDs nuevos en cada caso y consultar por `eventId`, no `count(*)` global.
10. **Jackson y montos**: un `80.00` llega como `DoubleNode(80.0)`; formatear siempre con `setScale(2, HALF_UP).toPlainString()`, nunca `String.valueOf(double)`.
11. **Zona horaria**: formatear con `atZoneSameInstant(America/Lima)`; no usar `toLocalDateTime()` del `OffsetDateTime` recibido (conservaría el offset de origen, p. ej. UTC).
12. **Privacidad en logs**: no registrar `mensaje` ni `datos`. Solo `eventId`, `tipo`, `recursoId`, `pacienteId`.
13. **Nombres de propiedad vs columna**: en `Criteria`/`Sort` usar `fechaEnvio`, `pacienteId`, `tipoEvento`, `eventId` (el template los mapea). En SQL crudo (no se usa) serían snake_case.
14. **`OffsetDateTime` de BD** puede volver con offset `Z`: en pruebas comparar con `isEqual(...)`/`isCloseTo(...)`, no con `equals`.
15. **Comando de la spec 06 §8 usa `-am`**: en este proyecto **no** se usa (ver §5).
16. **`TopologiaHospital.colaMuertos("notificaciones.cola")`** devuelve `notificaciones.dlq`: no hardcodear otros nombres.
17. **No crear un segundo `Clock`** ni `ObjectMapper`: vienen de commons (`RelojAutoConfiguration`) y de Boot.
18. **El texto lleva tildes** (`Está`, `atención`, `registró`, `operación`): los fuentes son UTF-8 (POM padre); copiar los textos exactamente.

---

## 7. Criterios de terminado

- [ ] `sg docker -c "./mvnw -q -pl notificaciones-service verify"` en verde: `DatosEventoTest`, `PlantillasNotificacionTest`, `BindingsNotificacionesTest`, `NotificacionServiceTest`, `NotificacionControllerTest`, `NotificacionRepositoryIT`, `NotificacionesConsumerIT`.
- [ ] Archivos de §2 creados con esos paquetes y firmas; `pom.xml` del módulo solo con el agregado de `org.testcontainers:r2dbc`; `application.yml` sin cambios.
- [ ] `schema.sql` = DDL del documento §8.3 con `recurso_id` e índices `ix_notificacion_paciente` y `ix_notificacion_fecha`, con `IF NOT EXISTS`.
- [ ] `notificaciones.cola` enlazada exactamente a las 8 routing keys de `BindingsNotificaciones.TODOS` (fases A y B); `notificaciones.dlq` existe.
- [ ] Publicar `cita.confirmada` produce una fila con el mensaje esperado; el duplicado no crea otra fila; JSON inválido termina en `notificaciones.dlq`; `cita.atendida` no llega.
- [ ] `GET /api/notificaciones` (filtros `pacienteId`, `tipoEvento`, `fecha`, paginación `pagina`/`tamano`, `X-Total-Count`, orden `fecha_envio DESC, id`) y `GET /api/notificaciones/{id}` (`404 NOTIFICACION_NO_ENCONTRADA`, `400` si no es UUID) funcionan; no hay endpoints de escritura.
- [ ] Con `hospital.rabbit.enabled=false` el contexto arranca sin RabbitMQ y sin consumidor.
- [ ] `grep -rnE "\.block\(|\.subscribe\(" notificaciones-service/src/main` sin resultados; sin JDBC/JPA; sin Lombok; sin `@Autowired` en campos.
- [ ] Ningún log contiene el texto del mensaje ni campos de `datos` distintos de ids.
- [ ] No se modificaron archivos fuera de `notificaciones-service/`.
