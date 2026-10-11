# Plan de implementación — `pacientes-service`

> Ejecutor: agente de implementación. Este plan es autosuficiente: no hace falta otra conversación.
> Fuentes: `docs/specs/00-convenciones-comunes.md`, `docs/specs/02-pacientes-service.md`, `docs/specs/08-observabilidad.md` §4–5 y §8, `docs/documento-tecnico.md` §5.3, §8.1, §9, §10, y el código real de `hospital-commons`.
> **Servicio de referencia:** paquetes, estilo de pruebas, Testcontainers y forma de usar R2DBC se copiarán en los otros 5 servicios. Sigue los patrones tal como están escritos.

## DUDAS CRUCIALES

Ninguna. No hay ninguna duda que bloquee el diseño. Las discrepancias menores entre specs se resolvieron abajo.

## Decisiones menores tomadas

| # | Decisión | Motivo |
|---|---|---|
| M-1 | `PacienteService.listar` **no** devuelve `ResponseEntity` (como sugiere spec 02 §4.3). El servicio expone `Flux<PacienteResponse> listar(FiltroPacientes, ParametrosPagina)` y `Mono<Long> contar(FiltroPacientes)`, y el controlador arma la respuesta con `RespuestaPaginada.de(...)` de commons | Mantiene `ResponseEntity` (tipo web) fuera de la capa de servicio; `RespuestaPaginada` ya está pensada para el controlador |
| M-2 | La consulta dinámica vive en `persistence/BusquedaPacientes` (`@Component` con `R2dbcEntityTemplate`), no en un *fragment* `...Impl` de Spring Data | Explícito, fácil de probar (`@Import` en `@DataR2dbcTest`) y sin convenciones mágicas de nombres |
| M-3 | `FiltroPacientes` (record con validación) va en `domain/` | Lo construye el controlador y lo consume la persistencia; así `api` no depende de `persistence` |
| M-4 | El dominio usa `ErrorNegocio` de commons (que solo arrastra el enum `HttpStatus`) | La spec 02 §3.2 lo pide explícitamente para `cambiarEstado`; no hay contexto Spring en el dominio |
| M-5 | La fábrica de la entidad se llama `Paciente.registrar(...)` (spec 02) en lugar de `nueva(...)` (spec 00 §6). Se mantiene `marcarComoExistente()` | Spec 02 es la específica del servicio |
| M-6 | `actualizarDatos` recibe el `Clock` y también aplica la regla de 120 años | Una fecha inválida no debe poder entrar por `PUT` |
| M-7 | Normalización de datos personales: `trim()` en todos los textos; los opcionales en blanco pasan a `null`. El correo no se pasa a minúsculas | Mínimo razonable; evita guardar `"  "` |
| M-8 | El filtro `apellidos` usa `Criteria.like("%valor%").ignoreCase(true)` (genera `UPPER(apellidos) LIKE UPPER(?)`, semántica equivalente a `ILIKE`) y escapa `\`, `%` y `_` del valor del usuario | `Criteria` no tiene `ilike`; verificado contra PostgreSQL 16 |
| M-9 | En `Criteria` se pasan los enums como texto (`estado.name()`) | Determinista con el driver; también funciona con el enum (verificado), pero el texto no depende de la conversión del `QueryMapper` |
| M-10 | `EventosPaciente` recibe `Optional<PublicadorEventos>`: si `hospital.rabbit.enabled=false` no hay publicador y simplemente no publica. Además envuelve la publicación con `onErrorResume` (registra `WARN` y sigue) | Permite contextos completos con RabbitMQ desactivado; refuerza D-3 incluso si el publicador fallara con error |
| M-11 | `PacienteResponse.desde` convierte `fechaRegistro` a la zona `America/Lima` (`RelojAutoConfiguration.ZONA_HOSPITAL`) | El driver devuelve `TIMESTAMPTZ` en la zona de la JVM (en CI será `Z`); la API debe responder siempre con `-05:00` |
| M-12 | `fechaRegistro` se trunca a microsegundos al crearla | PostgreSQL guarda microsegundos y **redondea** (verificado: `...553976515` → `...553977`); sin truncar, la entidad en memoria y la leída no coinciden |
| M-13 | Mensajes de validación en español con `message = "..."` explícito en cada anotación; textos con UTF-8 normal (se permiten tildes, p. ej. "formato inválido para DNI" de la spec). Las pruebas comparan **`codigo` y `campo`**, nunca textos con tildes | Mensajes deterministas independientemente del `Locale` de la JVM |
| M-14 | Comando de verificación: `sg docker -c "./mvnw -q -pl pacientes-service verify"`; si el reactor raíz falla porque falta algún módulo de `<modules>` (hoy falta `api-gateway`), usar `sg docker -c "./mvnw -q -f pacientes-service/pom.xml verify"` (equivalente; no usa `-am`) | Verificado: con un módulo inexistente Maven no puede leer el reactor |
| M-15 | La spec 02 §9 dice `-am`; **no** se usa (`hospital-commons` ya está instalado en `~/.m2`) | Instrucción del responsable |
| M-16 | Se agrega una cola de prueba propia en `PacienteFlujoIT` enlazada a `paciente.registrado` y `paciente.estado-cambiado` | La spec pide verificar el sobre recibido |

## Hechos verificados contra las versiones reales

Verificado el 2026-10-11 con un proyecto de prueba descartable (Boot 3.5.16, Spring Data R2DBC 3.5.13 / BOM 2025.0.13, r2dbc-postgresql 1.0.9.RELEASE, Testcontainers 1.21.4, `postgres:16`, Docker vía `sg docker`):

1. **`@ServiceConnection` con `PostgreSQLContainer` para R2DBC requiere la dependencia de prueba `org.testcontainers:r2dbc`.** Sin ella el contexto falla con `NoClassDefFoundError: org/testcontainers/r2dbc/R2DBCDatabaseContainer` (Boot llama a `PostgreSQLR2DBCDatabaseContainer.getOptions`, que vive en el jar `postgresql` pero implementa una interfaz del módulo `r2dbc`). **El `pom.xml` actual NO la tiene: hay que agregarla (paso 1).** Versión gestionada por el BOM.
2. `@DataR2dbcTest` incluye `SqlInitializationAutoConfiguration` y `ServiceConnectionAutoConfiguration`: `schema.sql` se ejecuta con `spring.sql.init.mode=always` del `application.yml` principal.
3. Un `${PACIENTES_DB_PASSWORD}` sin resolver en `application.yml` **no** rompe el arranque (queda literal y `@ServiceConnection` lo reemplaza). Aun así se define `application-test.yml` con valores dummy (spec 00 §7).
4. Enums en la entidad → se guardan como texto (`name()`) y se leen de vuelta sin configuración.
5. Consulta derivada `existsByTipoDocumentoAndNumeroDocumento(TipoDocumento, String)` con parámetro enum funciona.
6. Entidad con `Persistable<UUID>`, campos `final`, `@PersistenceCreator` y `@Transient boolean nuevo`: `save` hace `INSERT` si `nuevo=true` y `UPDATE` si se cargó de BD.
7. Violación de `uq_paciente_documento` → `org.springframework.dao.DuplicateKeyException` (traducción de SQLSTATE 23505 de Spring).
8. `R2dbcEntityTemplate` + `Criteria.empty().and(...)` encadenado, `like(...).ignoreCase(true)`, `Query.with(PageRequest.of(p, t, Sort.by("apellidos","nombres","id")))` y `template.count(Query, Paciente.class)` funcionan usando **nombres de propiedad Java** (`"tipoDocumento"`), que el `QueryMapper` traduce a columnas.
9. Escape de `LIKE` con barra invertida (`\%`) funciona en PostgreSQL 16 sin cláusula `ESCAPE`.
10. `OffsetDateTime` leído de `TIMESTAMPTZ` vuelve en la zona por defecto de la JVM y redondeado a microsegundos (ver M-11, M-12).
11. El Docker local funciona con `sg docker -c "..."` (Ryuk incluido).

---

## 1. Resumen y orden de implementación

Servicio WebFlux + R2DBC (PostgreSQL) que registra, consulta, lista con filtros y paginación, actualiza datos de contacto y cambia el estado de pacientes; publica `paciente.registrado` y `paciente.estado-cambiado` en RabbitMQ mediante `PublicadorEventos` de commons. Puerto 8081.

Paquete base: `pe.edu.cibertec.vidasalud.pacientes` (abreviado `…pacientes` abajo). Rutas de archivo relativas a `pacientes-service/`.

Cada paso termina en algo compilable o verificable. Comando corto usado abajo: `MVN_P="./mvnw -q -f pacientes-service/pom.xml"` (o `-pl pacientes-service` si el reactor raíz está completo; ver M-14). Ejecutar desde la raíz del repositorio.

| Paso | Qué | Verificación |
|---|---|---|
| 1 | `pom.xml`: agregar `org.testcontainers:r2dbc` (test). Crear `src/main/resources/schema.sql` y `src/test/resources/application-test.yml` | `$MVN_P compile` |
| 2 | Dominio: `TipoDocumento`, `EstadoPaciente`, `ErroresPaciente`, `ReglasDocumento` + `ReglasDocumentoTest` | `$MVN_P test` |
| 3 | Dominio: `DatosPersonales`, `Paciente` (entidad) + `PacienteTest` | `$MVN_P test` |
| 4 | Dominio: `FiltroPacientes` + `FiltroPacientesTest` | `$MVN_P test` |
| 5 | Persistencia: `PacienteRepository`, `BusquedaPacientes` + `PacienteRepositoryIT` | `sg docker -c "$MVN_P verify"` |
| 6 | API DTOs: `PacienteCrearRequest`, `PacienteActualizarRequest`, `CambioEstadoRequest`, `PacienteResponse` | `$MVN_P compile` |
| 7 | Mensajería: `EventosPaciente` | `$MVN_P compile` |
| 8 | Servicio: `PacienteService` + `PacienteServiceTest` | `$MVN_P test` |
| 9 | Controlador: `PacienteController` + `PacienteControllerTest` | `$MVN_P test` |
| 10 | `config/OpenApiConfig` (Swagger) | `$MVN_P compile` |
| 11 | `PacienteFlujoIT` (contexto completo + PostgreSQL + RabbitMQ) | `sg docker -c "$MVN_P verify"` |
| 12 | Revisión final: greps de prohibiciones, logs sin datos personales, verificación completa | §5 y §7 de este plan |
| 13 (opcional) | Prueba de humo con la infraestructura local (`infra/`) | §5.3 |

---

## 2. Archivos a crear o modificar

No se modifica nada fuera de `pacientes-service/`. **No** se toca `hospital-commons` (si se detecta un error en commons, detenerse y reportarlo). `PacientesServiceApplication` se deja **tal cual** (sin anotaciones extra: romperían los *test slices*). `application.yml` ya está correcto (incluye las métricas y el patrón de log de la spec 08) y **no** se modifica.

Estilo general: sin Lombok; inyección por constructor; `private static final Logger log = LoggerFactory.getLogger(X.class)`; comentarios solo si explican un porqué; nada de `.block()`/`.subscribe()` en `src/main`.

### 2.1 `pom.xml` (modificar)

Agregar en las dependencias de prueba, junto a `org.testcontainers:postgresql`:

```xml
<dependency>
  <groupId>org.testcontainers</groupId>
  <artifactId>r2dbc</artifactId>
  <scope>test</scope>
</dependency>
```

Nada más (el resto de dependencias ya coincide con la spec 02 §2; `mockwebserver` sobra pero no estorba: dejarla).

### 2.2 `src/main/resources/schema.sql` (crear)

DDL del documento §8.1 con `IF NOT EXISTS`:

```sql
CREATE TABLE IF NOT EXISTS paciente (
  id                UUID PRIMARY KEY,
  tipo_documento    VARCHAR(15)  NOT NULL CHECK (tipo_documento IN ('DNI','CE','PASAPORTE')),
  numero_documento  VARCHAR(20)  NOT NULL,
  nombres           VARCHAR(80)  NOT NULL,
  apellidos         VARCHAR(80)  NOT NULL,
  fecha_nacimiento  DATE         NOT NULL,
  telefono          VARCHAR(20),
  correo            VARCHAR(120),
  direccion         VARCHAR(200),
  fecha_registro    TIMESTAMPTZ  NOT NULL DEFAULT now(),
  estado            VARCHAR(10)  NOT NULL DEFAULT 'ACTIVO' CHECK (estado IN ('ACTIVO','INACTIVO')),
  CONSTRAINT uq_paciente_documento UNIQUE (tipo_documento, numero_documento)
);
CREATE INDEX IF NOT EXISTS ix_paciente_apellidos ON paciente (apellidos);
```

Sin comentarios con `;` dentro (el separador por defecto es `;`).

### 2.3 `src/test/resources/application-test.yml` (crear)

```yaml
spring:
  r2dbc:
    password: prueba
hospital:
  rabbit:
    enabled: false
    password: prueba
eureka:
  client:
    enabled: false
```

Todas las pruebas con contexto Spring llevan `@ActiveProfiles("test")`. La prueba que necesita RabbitMQ (`PacienteFlujoIT`) lo reactiva con `@DynamicPropertySource` (mayor precedencia que el archivo de perfil).

### 2.4 Dominio — paquete `…pacientes.domain`

**`TipoDocumento`** (enum): `DNI`, `CE`, `PASAPORTE`.

**`EstadoPaciente`** (enum): `ACTIVO`, `INACTIVO`.

**`ErroresPaciente`** (clase `final`, constructor privado): fábricas de `ErrorNegocio` y códigos.

```java
public static final String PACIENTE_DUPLICADO = "PACIENTE_DUPLICADO";
public static final String PACIENTE_NO_ENCONTRADO = "PACIENTE_NO_ENCONTRADO";
public static final String TRANSICION_NO_PERMITIDA = "TRANSICION_NO_PERMITIDA";

public static ErrorNegocio duplicado(TipoDocumento tipo, String numero)
    // 409, "Ya existe un paciente con el documento " + tipo + " " + numero + "."
public static ErrorNegocio noEncontrado(UUID id)
    // 404, "No existe el paciente " + id + "."
public static ErrorNegocio transicionNoPermitida(EstadoPaciente estado)
    // 409, "El paciente ya está " + estado + "."
```

**`ReglasDocumento`** (clase `final`, constructor privado, sin Spring):

```java
public static String normalizar(String numero)          // null -> null; trim() + toUpperCase(Locale.ROOT)
public static String validar(TipoDocumento tipo, String numero)
    // normaliza y comprueba el patrón; devuelve el número normalizado
    // si no cumple: throw ErrorNegocio.validacion("numeroDocumento", "formato inválido para " + tipo)
```

Patrones (`Pattern` precompilados en un `Map<TipoDocumento, Pattern>` o `switch`): `DNI` `^\d{8}$`; `CE` `^[A-Z0-9]{9,12}$`; `PASAPORTE` `^[A-Z0-9]{6,12}$`. `numero == null` → mismo error de formato.

**`DatosPersonales`** (record): `String nombres, String apellidos, LocalDate fechaNacimiento, String telefono, String correo, String direccion`. Sin validación propia (la hace `Paciente`).

**`FiltroPacientes`** (record): `EstadoPaciente estado, TipoDocumento tipoDocumento, String numeroDocumento, String apellidos`.

```java
public static FiltroPacientes de(EstadoPaciente estado, TipoDocumento tipo, String numero, String apellidos)
```

* `numero` y `apellidos` en blanco → `null`; `apellidos` con `trim()`; `numero` con `ReglasDocumento.normalizar`.
* Si llega `tipo` sin `numero` o `numero` sin `tipo` → `throw ErrorNegocio.validacion("numeroDocumento", "tipoDocumento y numeroDocumento deben enviarse juntos")`.
* No valida el formato del número (solo filtra).

**`Paciente`** (entidad R2DBC; único punto con anotaciones de Spring Data en el dominio):

```java
@Table("paciente")
public class Paciente implements Persistable<UUID> {

    @Id private final UUID id;
    @Column("tipo_documento") private final TipoDocumento tipoDocumento;
    @Column("numero_documento") private final String numeroDocumento;
    @Column("nombres") private String nombres;
    @Column("apellidos") private String apellidos;
    @Column("fecha_nacimiento") private LocalDate fechaNacimiento;
    @Column("telefono") private String telefono;
    @Column("correo") private String correo;
    @Column("direccion") private String direccion;
    @Column("fecha_registro") private final OffsetDateTime fechaRegistro;
    @Column("estado") private EstadoPaciente estado;

    @Transient private boolean nuevo;   // org.springframework.data.annotation.Transient

    @PersistenceCreator
    Paciente(UUID id, TipoDocumento tipoDocumento, String numeroDocumento, String nombres, String apellidos,
             LocalDate fechaNacimiento, String telefono, String correo, String direccion,
             OffsetDateTime fechaRegistro, EstadoPaciente estado) { ...; this.nuevo = false; }

    public static Paciente registrar(TipoDocumento tipo, String numeroDocumento, DatosPersonales datos, Clock reloj)
    public void actualizarDatos(DatosPersonales datos, Clock reloj)
    public EstadoPaciente cambiarEstado(EstadoPaciente nuevoEstado)
    public Paciente marcarComoExistente()      // nuevo = false; return this

    @Override public UUID getId()
    @Override public boolean isNew()          // return nuevo
    // getters de todos los campos (sin setters)
}
```

Comportamiento:

* `registrar`: `numero = ReglasDocumento.validar(tipo, numeroDocumento)`; valida fecha de nacimiento (abajo); `id = UUID.randomUUID()`; `fechaRegistro = OffsetDateTime.now(reloj).truncatedTo(ChronoUnit.MICROS)` (M-12); `estado = ACTIVO`; aplica normalización M-7; marca `nuevo = true`.
* Regla de fecha (método privado estático `validarFechaNacimiento(LocalDate, Clock)`): si `fecha.isBefore(LocalDate.now(reloj).minusYears(120))` → `ErrorNegocio.validacion("fechaNacimiento", "no puede ser de hace más de 120 años")`. Exactamente 120 años atrás es válido. (Fecha futura la rechaza `@Past` en el DTO.)
* `actualizarDatos`: valida la fecha y reemplaza nombres, apellidos, fecha de nacimiento, teléfono, correo y dirección (normalizados). **No** toca id, documento, estado ni fecha de registro.
* `cambiarEstado`: si `nuevoEstado == estado` → `throw ErroresPaciente.transicionNoPermitida(estado)`; si no, cambia y devuelve el estado anterior.
* **No** sobrescribir `toString()` con datos personales (si se sobrescribe, solo `id` y `estado`).

### 2.5 Persistencia — paquete `…pacientes.persistence`

**`PacienteRepository`**:

```java
public interface PacienteRepository extends ReactiveCrudRepository<Paciente, UUID> {
    Mono<Boolean> existsByTipoDocumentoAndNumeroDocumento(TipoDocumento tipoDocumento, String numeroDocumento);
}
```

**`BusquedaPacientes`** (`@Component`, constructor con `R2dbcEntityTemplate`):

```java
static final Sort ORDEN = Sort.by("apellidos", "nombres", "id");

public Flux<Paciente> buscar(FiltroPacientes filtro, ParametrosPagina pagina) {
    Query consulta = Query.query(criterio(filtro)).with(pagina.aPageable(ORDEN));
    return template.select(Paciente.class).matching(consulta).all();
}

public Mono<Long> contar(FiltroPacientes filtro) {
    return template.count(Query.query(criterio(filtro)), Paciente.class);   // sin orden ni límite
}

static Criteria criterio(FiltroPacientes filtro) {
    Criteria c = Criteria.empty();
    if (filtro.estado() != null) c = c.and("estado").is(filtro.estado().name());
    if (filtro.tipoDocumento() != null) c = c.and("tipoDocumento").is(filtro.tipoDocumento().name())
            .and("numeroDocumento").is(filtro.numeroDocumento());
    if (filtro.apellidos() != null) c = c.and("apellidos").like("%" + escaparLike(filtro.apellidos()) + "%").ignoreCase(true);
    return c;
}

static String escaparLike(String valor)   // antepone '\' a '\', '%' y '_' (en ese orden: primero '\')
```

Usar **nombres de propiedad Java** en `Criteria` y `Sort` (el `QueryMapper` los traduce). Las dos consultas (`buscar`/`contar`) deben usar el **mismo** `criterio(filtro)`.

### 2.6 API — DTOs en `…pacientes.api.dto` (todos `record`)

Mensajes explícitos (M-13). Anotaciones de `jakarta.validation.constraints`.

```java
public record PacienteCrearRequest(
    @NotNull(message = "es obligatorio") TipoDocumento tipoDocumento,
    @NotBlank(message = "es obligatorio") @Size(min = 6, max = 20, message = "debe tener entre 6 y 20 caracteres") String numeroDocumento,
    @NotBlank(message = "es obligatorio") @Size(max = 80, message = "debe tener como máximo 80 caracteres") String nombres,
    @NotBlank(message = "es obligatorio") @Size(max = 80, message = "debe tener como máximo 80 caracteres") String apellidos,
    @NotNull(message = "es obligatoria") @Past(message = "debe ser una fecha pasada") LocalDate fechaNacimiento,
    @Pattern(regexp = "^[0-9+ ]{7,15}$", message = "debe tener de 7 a 15 caracteres entre dígitos, espacios o +") String telefono,
    @Email(message = "debe ser un correo válido") @Size(max = 120, message = "debe tener como máximo 120 caracteres") String correo,
    @Size(max = 200, message = "debe tener como máximo 200 caracteres") String direccion) {

    public DatosPersonales datosPersonales() { ... }
}

public record PacienteActualizarRequest(nombres, apellidos, fechaNacimiento, telefono, correo, direccion)
    // mismas anotaciones que en PacienteCrearRequest para esos campos; método datosPersonales()

public record CambioEstadoRequest(
    @NotNull(message = "es obligatorio") EstadoPaciente estado,
    @Size(max = 200, message = "debe tener como máximo 200 caracteres") String motivo) { }

public record PacienteResponse(UUID id, TipoDocumento tipoDocumento, String numeroDocumento, String nombres,
                               String apellidos, LocalDate fechaNacimiento, String telefono, String correo,
                               String direccion, OffsetDateTime fechaRegistro, EstadoPaciente estado) {
    public static PacienteResponse desde(Paciente p)
        // fechaRegistro = p.getFechaRegistro().atZoneSameInstant(RelojAutoConfiguration.ZONA_HOSPITAL).toOffsetDateTime()
}
```

`PacienteActualizarRequest` **no** tiene campos de documento ni estado: si el cliente los envía, Jackson los ignora (Boot tiene `FAIL_ON_UNKNOWN_PROPERTIES=false`), lo que garantiza D-11.

### 2.7 Mensajería — `…pacientes.messaging.EventosPaciente` (`@Component`)

```java
public static final String ORIGEN = "pacientes-service";
public static final String TIPO_RECURSO = "PACIENTE";

public EventosPaciente(Optional<PublicadorEventos> publicador, Clock reloj)

public Mono<Void> publicarRegistrado(Paciente p)
    // EventoEnvelope.nuevo(reloj, TopologiaHospital.PACIENTE_REGISTRADO, ORIGEN, TIPO_RECURSO, p.getId(),
    //                      null, "ACTIVO" (p.getEstado().name()), datos)
    // datos = JsonNodeFactory.instance.objectNode()
    //           .put("pacienteId", p.getId().toString())
    //           .put("fechaRegistro", p.getFechaRegistro().toString())
public Mono<Void> publicarEstadoCambiado(Paciente p, EstadoPaciente anterior, String motivo)
    // tipo PACIENTE_ESTADO_CAMBIADO; estadoAnterior = anterior.name(); estadoNuevo = p.getEstado().name()
    // datos = { "pacienteId": ..., "motivo": motivo }   (motivo puede ser null -> JSON null)
```

* Routing key = `tipo` del sobre (constantes de `TopologiaHospital`).
* Implementación común privada `publicar(String routingKey, EventoEnvelope e)`: si `publicador` está vacío → `Mono.empty()`; si no, `publicador.get().publicar(routingKey, e).onErrorResume(ex -> { log.warn("no se publico {} eventId={}: {}", routingKey, e.eventId(), ex.toString()); return Mono.empty(); })`.
* **Nunca** poner documento, nombres, teléfono, correo ni dirección en `datos` (D-13).

### 2.8 Servicio — `…pacientes.service.PacienteService` (`@Service`)

Constructor: `(PacienteRepository repositorio, BusquedaPacientes busqueda, EventosPaciente eventos, Clock reloj)`.

```java
public Mono<PacienteResponse> registrar(PacienteCrearRequest req)
public Mono<PacienteResponse> obtener(UUID id)
public Flux<PacienteResponse> listar(FiltroPacientes filtro, ParametrosPagina pagina)
public Mono<Long> contar(FiltroPacientes filtro)
public Mono<PacienteResponse> actualizar(UUID id, PacienteActualizarRequest req)
public Mono<PacienteResponse> cambiarEstado(UUID id, CambioEstadoRequest req)
private Mono<Paciente> buscarExistente(UUID id)   // findById(id).switchIfEmpty(Mono.error(ErroresPaciente.noEncontrado(id)))
```

Forma de `registrar` (patrón de referencia para los demás servicios):

```java
return Mono.fromCallable(() -> Paciente.registrar(req.tipoDocumento(), req.numeroDocumento(), req.datosPersonales(), reloj))
        .flatMap(p -> repositorio.existsByTipoDocumentoAndNumeroDocumento(p.getTipoDocumento(), p.getNumeroDocumento())
                .flatMap(existe -> existe
                        ? Mono.<Paciente>error(ErroresPaciente.duplicado(p.getTipoDocumento(), p.getNumeroDocumento()))
                        : repositorio.save(p))
                .onErrorMap(DuplicateKeyException.class,
                        e -> ErroresPaciente.duplicado(p.getTipoDocumento(), p.getNumeroDocumento())))
        .map(Paciente::marcarComoExistente)
        .flatMap(p -> eventos.publicarRegistrado(p).thenReturn(p))
        .doOnNext(p -> log.info("paciente registrado id={}", p.getId()))
        .map(PacienteResponse::desde);
```

* `Mono.fromCallable` convierte el `ErrorNegocio` de validación del dominio en `Mono.error` (no se lanza fuera del flujo).
* `obtener`: `buscarExistente(id).map(PacienteResponse::desde)`.
* `listar`: `busqueda.buscar(filtro, pagina).map(PacienteResponse::desde)`; `contar`: `busqueda.contar(filtro)`.
* `actualizar`: `buscarExistente(id)` → `Mono.fromCallable`/`map` con `p.actualizarDatos(req.datosPersonales(), reloj)` → `repositorio.save(p)` → `log.info("paciente actualizado id={}", id)` → DTO. **No** publica evento (D-14).
* `cambiarEstado`: `buscarExistente(id)` → `flatMap(p -> { EstadoPaciente anterior = p.cambiarEstado(req.estado()); return repositorio.save(p).flatMap(g -> eventos.publicarEstadoCambiado(g, anterior, req.motivo()).thenReturn(g)); })` (la excepción lanzada dentro de `flatMap` se convierte en `Mono.error` automáticamente) → `log.info("paciente id={} cambio de estado {} -> {}", ...)` → DTO.
* No se necesita `@Transactional`: cada operación escribe una sola fila.
* Logs: solo `id` y estados. **Nunca** el request ni la entidad.

### 2.9 Controlador — `…pacientes.api.PacienteController`

```java
@RestController
@RequestMapping("/api/pacientes")
@Tag(name = "Pacientes")
public class PacienteController {

    public PacienteController(PacienteService servicio)

    @PostMapping
    @Operation(summary = "Registrar paciente")
    public Mono<ResponseEntity<PacienteResponse>> registrar(@Valid @RequestBody PacienteCrearRequest req)
        // servicio.registrar(req).map(r -> ResponseEntity.created(URI.create("/api/pacientes/" + r.id())).body(r))

    @GetMapping("/{id}")
    @Operation(summary = "Consultar paciente por id")
    public Mono<PacienteResponse> obtener(@PathVariable UUID id)

    @GetMapping
    @Operation(summary = "Listar pacientes con filtros y paginación")
    public Mono<ResponseEntity<Flux<PacienteResponse>>> listar(
            @RequestParam(required = false) EstadoPaciente estado,
            @RequestParam(required = false) TipoDocumento tipoDocumento,
            @RequestParam(required = false) String numeroDocumento,
            @RequestParam(required = false) String apellidos,
            @RequestParam(required = false) Integer pagina,
            @RequestParam(required = false) Integer tamano)
        // return Mono.defer(() -> {
        //     FiltroPacientes filtro = FiltroPacientes.de(estado, tipoDocumento, numeroDocumento, apellidos);
        //     ParametrosPagina p = ParametrosPagina.de(pagina, tamano);
        //     return RespuestaPaginada.de(servicio.listar(filtro, p), servicio.contar(filtro));
        // });

    @PutMapping("/{id}")
    public Mono<PacienteResponse> actualizar(@PathVariable UUID id, @Valid @RequestBody PacienteActualizarRequest req)

    @PatchMapping("/{id}/estado")
    @Operation(summary = "Cambiar estado (ACTIVO/INACTIVO)")
    public Mono<PacienteResponse> cambiarEstado(@PathVariable UUID id, @Valid @RequestBody CambioEstadoRequest req)
}
```

* **No** anotar la clase con `@Validated` ni poner `@Min/@Max` en `pagina`/`tamano` (ver trampas T-6): la validación de paginación la hace `ParametrosPagina.de`.
* Errores: los traduce el `ManejadorErrores` de commons (autoconfigurado). Mapa: `@Valid` del cuerpo → `WebExchangeBindException` → 400 con `errores`; JSON ilegible, enum inválido en cuerpo o parámetro, `id` no UUID, `pagina=abc` → `ServerWebInputException` → 400; `ErrorNegocio` → su estado/código.
* No hay `DELETE` (D-12).

### 2.10 Configuración — `…pacientes.config.OpenApiConfig` (`@Configuration`)

```java
@Bean
public OpenAPI pacientesOpenApi() {
    return new OpenAPI().info(new Info()
            .title("pacientes-service")
            .version("1.0.0")
            .description("Identidad de pacientes de Hospital Vida Salud: registro, consulta, datos de contacto y estado."));
}
```

Swagger UI en `http://localhost:8081/swagger-ui.html` (ya configurado en `application.yml`); JSON en `/v3/api-docs`.

### 2.11 Resumen de estructura final

```text
pacientes-service/
├── pom.xml                                   (modificado: + org.testcontainers:r2dbc)
└── src
    ├── main
    │   ├── java/pe/edu/cibertec/vidasalud/pacientes
    │   │   ├── PacientesServiceApplication.java        (sin cambios)
    │   │   ├── api/PacienteController.java
    │   │   ├── api/dto/{PacienteCrearRequest, PacienteActualizarRequest, CambioEstadoRequest, PacienteResponse}.java
    │   │   ├── config/OpenApiConfig.java
    │   │   ├── domain/{TipoDocumento, EstadoPaciente, ErroresPaciente, ReglasDocumento, DatosPersonales, FiltroPacientes, Paciente}.java
    │   │   ├── messaging/EventosPaciente.java
    │   │   ├── persistence/{PacienteRepository, BusquedaPacientes}.java
    │   │   └── service/PacienteService.java
    │   └── resources/{application.yml (sin cambios), schema.sql}
    └── test
        ├── java/pe/edu/cibertec/vidasalud/pacientes
        │   ├── domain/{ReglasDocumentoTest, PacienteTest, FiltroPacientesTest}.java
        │   ├── persistence/PacienteRepositoryIT.java
        │   ├── service/PacienteServiceTest.java
        │   ├── api/PacienteControllerTest.java
        │   └── PacienteFlujoIT.java
        └── resources/application-test.yml
```

No se crean paquetes `client/` (Pacientes no llama a otros servicios) ni consumidores.

---

## 3. Patrones técnicos (para copiar en los demás servicios)

1. **Entidad con UUID de la aplicación:** `Persistable<UUID>` + `@Transient boolean nuevo` (anotación de `org.springframework.data.annotation`) + constructor `@PersistenceCreator` que deja `nuevo=false` + fábrica estática que lo pone en `true` + `marcarComoExistente()` tras el primer `save`. `@Column` explícito en todas las columnas. Campos inmutables `final`.
2. **Enums:** se guardan como texto sin conversores; columna `VARCHAR` con `CHECK`. En `Criteria` pasar `enum.name()`.
3. **Esquema:** `schema.sql` con `CREATE TABLE IF NOT EXISTS` / `CREATE [UNIQUE] INDEX IF NOT EXISTS` + `spring.sql.init.mode=always`. Sin Flyway/Liquibase.
4. **Consultas dinámicas:** clase `Busqueda<X>` con `R2dbcEntityTemplate`, `Criteria.empty().and(...)` condicional, `Query.with(ParametrosPagina.aPageable(ORDEN))` con orden estable que termina en `id`, y `template.count` con el mismo criterio sin paginar.
5. **Unicidad:** consulta previa para el mensaje + `onErrorMap(DuplicateKeyException.class, ...)` sobre el `save` como garantía.
6. **Respuestas:** `201` con `ResponseEntity.created(URI.create("/api/<recurso>/" + id))`; listados con `RespuestaPaginada.de(flux, count)`.
7. **Eventos:** clase `Eventos<X>` en `messaging/` que arma el `EventoEnvelope` con `Clock` inyectado y `datos` mínimos con `JsonNodeFactory`; publica tras el `save`; nunca hace fallar la operación.
8. **Validación de dominio dentro del flujo:** `Mono.fromCallable(() -> Entidad.fabrica(...))` o lanzar dentro de `map`/`flatMap`; nunca lanzar antes de devolver el `Mono` en el servicio.

---

## 4. Pruebas

Convenciones: JUnit 5 + AssertJ; `StepVerifier` para flujos; nombres de método en español descriptivos (`registroValidoPublicaEvento`); `*Test` = unitarias/slice sin Docker (Surefire), `*IT` = con contenedores (Failsafe). Datos: documentos `0000xxxx`, correos `@example.com`, nombres genéricos. `Clock` fijo para pruebas: `Clock.fixed(Instant.parse("2026-10-12T14:30:00Z"), ZoneId.of("America/Lima"))` (= 09:30 `-05:00`).

Cuerpo base (spec 02 §8):

```json
{ "tipoDocumento": "DNI", "numeroDocumento": "00000001", "nombres": "Ana María",
  "apellidos": "Prueba Ficticia", "fechaNacimiento": "1990-05-20",
  "telefono": "999000111", "correo": "ana.prueba@example.com" }
```

### 4.1 `domain/ReglasDocumentoTest` (unitaria)

* DNI `00000001` válido; `0000000A` → error; `0000001` (7) → error; `000000012` (9) → error.
* CE: `ABC123456` (9) y `ABC123456789` (12) válidos; `ABC12345` (8) y `ABC1234567890` (13) → error.
* PASAPORTE: `AB1234` (6) y `AB1234567890` (12) válidos; `AB123` (5) y 13 caracteres → error; `AB-12345` → error.
* Normalización: `"  ab123456 "` como PASAPORTE → `"AB123456"`; `" 00000001 "` como DNI → `"00000001"`.
* El error es `ErrorNegocio` con `status 400`, `codigo VALIDACION_FALLIDA` y `getErrores()` con `campo = "numeroDocumento"` y mensaje que contiene el tipo.
* Número `null` → error de formato.

### 4.2 `domain/PacienteTest` (unitaria)

* `registrar` deja `estado ACTIVO`, `isNew() == true`, `fechaRegistro` igual a `OffsetDateTime.now(relojFijo)`, `id` no nulo, documento normalizado.
* `registrar` con DNI inválido → `ErrorNegocio` 400.
* Fecha de nacimiento hace 121 años → `ErrorNegocio` 400 con `campo fechaNacimiento`; exactamente 120 años → válido.
* `cambiarEstado(INACTIVO)` sobre ACTIVO → devuelve `ACTIVO` y el estado queda `INACTIVO`.
* `cambiarEstado(ACTIVO)` sobre ACTIVO → `ErrorNegocio` 409 `TRANSICION_NO_PERMITIDA`.
* `actualizarDatos` cambia nombres/correo y **no** cambia `id`, `tipoDocumento`, `numeroDocumento`, `estado` ni `fechaRegistro`.
* Opcionales en blanco (`"  "`) quedan `null`; nombres con espacios se recortan.
* `marcarComoExistente()` deja `isNew() == false`.

### 4.3 `domain/FiltroPacientesTest` (unitaria)

* Todo `null` → filtro vacío (sin excepción).
* `tipoDocumento` sin `numeroDocumento` y viceversa → `ErrorNegocio` 400 `campo numeroDocumento`.
* `numeroDocumento " ab123456 "` se normaliza; `apellidos "  "` → `null`.

### 4.4 `service/PacienteServiceTest` (Mockito + `StepVerifier`)

Configuración: `@ExtendWith(MockitoExtension.class)`; `@Mock PacienteRepository repositorio`, `@Mock BusquedaPacientes busqueda`, `@Mock PublicadorEventos publicador`; en `@BeforeEach`: `servicio = new PacienteService(repositorio, busqueda, new EventosPaciente(Optional.of(publicador), RELOJ), RELOJ)`. Stub típico: `when(repositorio.save(any(Paciente.class))).thenAnswer(inv -> Mono.just(inv.getArgument(0)))`; `when(publicador.publicar(anyString(), any())).thenReturn(Mono.empty())`. Usar `lenient()` solo donde haga falta.

| Caso | Verificación |
|---|---|
| **Registro válido** | Emite `PacienteResponse` con `estado ACTIVO`; `ArgumentCaptor<EventoEnvelope>`: routing key `paciente.registrado`, `tipo` igual, `origen pacientes-service`, `tipoRecurso PACIENTE`, `recursoId` = id respuesta, `estadoAnterior null`, `estadoNuevo ACTIVO`; los nombres de campo de `datos` son **exactamente** `{pacienteId, fechaRegistro}` y el JSON de `datos` no contiene `00000001`, `Ana`, ni `example.com` |
| **Duplicado por consulta previa** | `exists → true` ⇒ error `ErrorNegocio` 409 `PACIENTE_DUPLICADO`; `verify(repositorio, never()).save(any())`; `verifyNoInteractions(publicador)` |
| `DuplicateKeyException` al guardar | `exists → false`, `save → Mono.error(new DuplicateKeyException("uq"))` ⇒ 409 `PACIENTE_DUPLICADO`; sin publicar |
| Documento con formato inválido | 400 `VALIDACION_FALLIDA`; `verifyNoInteractions(repositorio, publicador)` |
| Falla del publicador | `publicador.publicar → Mono.error(new IllegalStateException())` ⇒ `registrar` igual emite la respuesta y completa |
| Sin publicador (`Optional.empty()`) | `registrar` completa (construir otro servicio en el caso) |
| `obtener` inexistente | `findById → Mono.empty()` ⇒ 404 `PACIENTE_NO_ENCONTRADO` |
| `obtener` existente | Emite el DTO con el mismo id |
| `actualizar` existente | Guarda con los nuevos datos y el mismo documento; `verifyNoInteractions(publicador)` |
| `actualizar` inexistente | 404; `save` nunca invocado |
| **Cambio de estado** | ACTIVO→INACTIVO con motivo `"Solicitud del paciente"`: respuesta `INACTIVO`; evento `paciente.estado-cambiado` con `estadoAnterior ACTIVO`, `estadoNuevo INACTIVO`, `datos.motivo` y `datos.pacienteId` |
| Cambio al mismo estado | 409 `TRANSICION_NO_PERMITIDA`; ni `save` ni publicación |
| `listar` / `contar` | Delegan en `BusquedaPacientes` y mapean a DTO |

Para pacientes "existentes" usar `Paciente.registrar(...).marcarComoExistente()`.

### 4.5 `api/PacienteControllerTest` (`@WebFluxTest`)

Configuración exacta:

```java
@WebFluxTest(controllers = PacienteController.class,
        properties = {"hospital.rabbit.enabled=false", "eureka.client.enabled=false"})
@Import(ManejadorErrores.class)
@ActiveProfiles("test")
class PacienteControllerTest {
    @Autowired WebTestClient cliente;
    @MockitoBean PacienteService servicio;   // org.springframework.test.context.bean.override.mockito.MockitoBean (no @MockBean, obsoleto en 3.4+)
}
```

`@Import(ManejadorErrores.class)` es obligatorio: el *slice* no carga las autoconfiguraciones de commons.

| Caso | Verificación |
|---|---|
| `POST` válido | `201`; cabecera `Location` = `/api/pacientes/{id}`; `$.id` y `$.estado == ACTIVO` |
| `POST` sin `nombres` | `400`; `$.codigo VALIDACION_FALLIDA`; `$.errores[*].campo` contiene `nombres`; `verifyNoInteractions(servicio)` |
| `POST` con `correo: "correo-sin-arroba"` | `400`; `errores` contiene `correo` |
| `POST` con `tipoDocumento: "XYZ"` | `400 VALIDACION_FALLIDA` |
| `POST` cuando el servicio devuelve duplicado | `409`; `Content-Type` compatible con `application/problem+json`; `$.codigo PACIENTE_DUPLICADO`; `$.instance /api/pacientes` |
| `GET /{uuid}` inexistente | `404`; `$.codigo PACIENTE_NO_ENCONTRADO` |
| `GET /no-es-uuid` | `400 VALIDACION_FALLIDA` |
| `GET /api/pacientes` | `200`; cabecera `X-Total-Count: 2`; `$.length() == 2`; verificar con `ArgumentCaptor` que se llamó con `ParametrosPagina(0, 20)` |
| `GET ?tamano=500` | `400 VALIDACION_FALLIDA`; `verifyNoInteractions(servicio)` |
| `GET ?tipoDocumento=DNI` (sin número) | `400` |
| `GET ?estado=OTRO` | `400` |
| `PUT` válido | `200` con el DTO |
| `PATCH /{id}/estado` sin `estado` | `400` |
| `PATCH` con servicio que lanza `TRANSICION_NO_PERMITIDA` | `409`; `$.codigo TRANSICION_NO_PERMITIDA` |

### 4.6 `persistence/PacienteRepositoryIT` (`@DataR2dbcTest` + PostgreSQL)

Configuración exacta (patrón de referencia):

```java
@DataR2dbcTest(properties = {"hospital.rabbit.enabled=false", "eureka.client.enabled=false"})
@Import(BusquedaPacientes.class)
@ActiveProfiles("test")
@Testcontainers
class PacienteRepositoryIT {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16");

    @Autowired PacienteRepository repositorio;
    @Autowired BusquedaPacientes busqueda;
    @Autowired DatabaseClient db;        // org.springframework.r2dbc.core.DatabaseClient

    @BeforeEach
    void limpiar() { db.sql("DELETE FROM paciente").then().block(); }   // .block() permitido solo en pruebas
}
```

Casos:

| Caso | Verificación |
|---|---|
| `schema.sql` crea tabla e índice | `SELECT count(*) FROM information_schema.tables WHERE table_name = 'paciente'` = 1; `SELECT count(*) FROM pg_indexes WHERE indexname = 'ix_paciente_apellidos'` = 1 |
| Ida y vuelta | `save(Paciente.registrar(...))` y `findById`: enums, `LocalDate`, `estado ACTIVO`, `fechaRegistro.isEqual(original)`, `isNew() == false` |
| **Duplicado tipo+número** | Segundo `save` con `DNI 00000001` → `StepVerifier...expectError(DuplicateKeyException.class)` |
| Mismo número, distinto tipo | `CE` con número `000000001` vs `PASAPORTE 000000001` → ambos se guardan (usar un número válido para los dos tipos) |
| `existsBy...` | `true` para el existente; `false` para otro tipo con el mismo número |
| Actualización hace `UPDATE` | `findById` → `cambiarEstado(INACTIVO)` → `save` → `findById` devuelve `INACTIVO` y `count()` sigue en 1 |
| Filtros y paginación | Sembrar 5 pacientes (apellidos `Alva`, `Benites`, `Benavides`, `Castro`, `Díaz`; uno INACTIVO). `estado=ACTIVO` → `contar` = 4; `apellidos="BEN"` → 2 (insensible a mayúsculas); `tipo+número` → 1; `ParametrosPagina(0,2)` → `[Alva, Benavides]`, `(1,2)` → `[Benites, Castro]`, `(2,2)` → `[Díaz]`; `contar` sin filtro = 5 independientemente de la página |
| Comodines escapados | `apellidos="%"` → `contar` = 0 |

### 4.7 `PacienteFlujoIT` (`@SpringBootTest` + PostgreSQL + RabbitMQ)

Configuración exacta:

```java
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "eureka.client.enabled=false")
@ActiveProfiles("test")
@Testcontainers
class PacienteFlujoIT {

    static final String COLA_PRUEBA = "prueba.pacientes.cola";

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16");

    @Container
    static final RabbitMQContainer RABBIT = new RabbitMQContainer("rabbitmq:3.13-management");

    @DynamicPropertySource
    static void rabbit(DynamicPropertyRegistry r) {
        r.add("hospital.rabbit.enabled", () -> "true");
        r.add("hospital.rabbit.host", RABBIT::getHost);
        r.add("hospital.rabbit.port", RABBIT::getAmqpPort);
        r.add("hospital.rabbit.username", RABBIT::getAdminUsername);
        r.add("hospital.rabbit.password", RABBIT::getAdminPassword);
    }

    static Connection conexion;   // com.rabbitmq.client, cliente directo solo para la prueba

    @BeforeAll
    static void declararColaDePrueba() throws Exception {
        // ConnectionFactory con host/puerto/credenciales del contenedor
        // canal.exchangeDeclare(TopologiaHospital.EXCHANGE, "topic", true)   (mismos argumentos que PublicadorRabbit)
        // canal.queueDeclare(COLA_PRUEBA, false, false, false, null)
        // canal.queueBind(COLA_PRUEBA, EXCHANGE, PACIENTE_REGISTRADO) y (…, PACIENTE_ESTADO_CAMBIADO)
    }

    @AfterAll static void cerrar() throws Exception { conexion.close(); }

    @BeforeEach void vaciarCola() throws Exception { /* canal.queuePurge(COLA_PRUEBA) */ }

    @Autowired WebTestClient cliente;
    @Autowired ObjectMapper mapper;
    @Autowired PacienteRepository repositorio;
}
```

Leer mensajes con `await().atMost(Duration.ofSeconds(10)).until(...)` (Awaitility viene en `spring-boot-starter-test`) haciendo `canal.basicGet(COLA_PRUEBA, true)` hasta que no sea `null`; deserializar el cuerpo como `JsonNode` o `EventoEnvelope` con el `ObjectMapper` del contexto. Cada prueba usa un número de documento distinto (`00000101`, `00000102`, …) porque la BD se comparte en la clase.

| Caso | Verificación |
|---|---|
| **Registro real** | `POST` → `201`; `repositorio.findById(id)` (con `StepVerifier`) encuentra la fila; mensaje en `COLA_PRUEBA` con `tipo paciente.registrado`, `origen pacientes-service`, `tipoRecurso PACIENTE`, `recursoId` = id, `estadoAnterior` null, `estadoNuevo ACTIVO`, `datos.pacienteId` = id; el cuerpo del mensaje no contiene el documento, nombres, teléfono ni correo |
| **Duplicado** extremo a extremo | Mismo cuerpo dos veces → segundo `409 PACIENTE_DUPLICADO` |
| Inexistente | `GET /api/pacientes/{UUID.randomUUID()}` → `404 PACIENTE_NO_ENCONTRADO` |
| Listado | `GET /api/pacientes?tipoDocumento=DNI&numeroDocumento=<doc>` → `200`, `X-Total-Count: 1` |
| Cambio de estado | `PATCH …/estado {"estado":"INACTIVO","motivo":"Prueba"}` → `200`, mensaje `paciente.estado-cambiado` con `ACTIVO → INACTIVO`; repetir → `409 TRANSICION_NO_PERMITIDA` |
| Swagger | `GET /v3/api-docs` → `200` y el cuerpo contiene `/api/pacientes` |

### 4.8 Cobertura de casos obligatorios (enunciado §24)

| Caso | Pruebas |
|---|---|
| Registro de paciente válido | `PacienteServiceTest`, `PacienteControllerTest`, `PacienteFlujoIT` |
| Rechazo de paciente duplicado | `PacienteServiceTest` (consulta y `DuplicateKeyException`), `PacienteControllerTest`, `PacienteRepositoryIT` (restricción), `PacienteFlujoIT` |
| Error ante recurso inexistente | `PacienteServiceTest`, `PacienteControllerTest`, `PacienteFlujoIT` |

---

## 5. Cómo ejecutar y verificar

Siempre desde `/home/cazabache/hospital-vida-salud`. `hospital-commons` ya está instalado en `~/.m2`: **no** usar `-am` ni reinstalarlo. Docker requiere `sg docker -c "..."` porque la shell aún no tiene el grupo `docker`.

### 5.1 Comandos

```bash
# Unitarias y slices (sin Docker)
./mvnw -q -pl pacientes-service test

# Todo (Surefire *Test + Failsafe *IT con Testcontainers)
sg docker -c "./mvnw -q -pl pacientes-service verify"

# Alternativa si el reactor raíz falla por un módulo inexistente (p. ej. "Child module .../api-gateway does not exist"):
./mvnw -q -f pacientes-service/pom.xml test
sg docker -c "./mvnw -q -f pacientes-service/pom.xml verify"

# Una sola prueba
sg docker -c "./mvnw -q -f pacientes-service/pom.xml verify -Dit.test=PacienteRepositoryIT -Dtest=NoExiste -Dsurefire.failIfNoSpecifiedTests=false"
./mvnw -q -f pacientes-service/pom.xml test -Dtest=PacienteControllerTest
```

Con `-q` solo se ve la salida si algo falla; los detalles quedan en `pacientes-service/target/surefire-reports/` y `target/failsafe-reports/`. `verify` falla si falla cualquier `*IT` (el padre ya configura los objetivos `integration-test` y `verify` de Failsafe).

### 5.2 Revisiones estáticas obligatorias (paso 12)

```bash
grep -rnE '\.block\(|\.subscribe\(|Thread\.sleep|java\.sql|jakarta\.persistence|lombok' pacientes-service/src/main   # debe salir vacío
grep -rnE 'log\.(info|warn|error|debug)' pacientes-service/src/main                                                   # revisar a mano: solo ids, estados, routingKey, eventId
grep -rn '@Autowired' pacientes-service/src/main                                                                       # debe salir vacío
```

### 5.3 Prueba de humo opcional (paso 13; requiere `infra/` levantada)

```bash
source scripts/entorno-dev.sh
./mvnw -q -f pacientes-service/pom.xml package -DskipTests
java -jar pacientes-service/target/pacientes-service-1.0.0-SNAPSHOT.jar &
curl -i -X POST localhost:8081/api/pacientes -H 'Content-Type: application/json' -d '<cuerpo base>'   # 201
# repetir → 409; GET /api/pacientes/<id> → 200; /actuator/health → r2dbc y rabbit UP; /swagger-ui.html carga
```

Sin Eureka el cliente registra advertencias de conexión: es esperado. Detener el proceso al terminar. Si no hay infraestructura, omitir este paso (no es criterio de terminado).

---

## 6. Trampas conocidas

| # | Trampa | Cómo evitarla |
|---|---|---|
| T-1 | Falta `org.testcontainers:r2dbc` → `NoClassDefFoundError: R2DBCDatabaseContainer` al crear el `ConnectionFactory` | Paso 1 |
| T-2 | El reactor raíz no se puede leer si falta un módulo listado en `<modules>` | Usar `-f pacientes-service/pom.xml` (M-14). No editar el `pom.xml` raíz |
| T-3 | Entidad sin `Persistable` o con `isNew()` mal → `save` hace `UPDATE` de 0 filas (`TransientDataAccessResourceException`) en lugar de `INSERT` | Patrón §2.4; `@Transient` de `org.springframework.data.annotation` |
| T-4 | Guardar dos veces la misma instancia recién insertada sin `marcarComoExistente()` → segundo `INSERT` y `DuplicateKeyException` | Llamar `marcarComoExistente()` tras el primer `save`, o recargar con `findById` |
| T-5 | `fechaRegistro` distinta tras leerla (zona de la JVM, redondeo a µs) | M-11 y M-12; en pruebas comparar con `isEqual`, no con `equals` |
| T-6 | `@Validated` en el controlador o `@Min/@Max` en `@RequestParam` activan la validación de métodos (`HandlerMethodValidationException`, campo "parametro") en vez de `ParametrosPagina` | No usarlos; la paginación la valida `ParametrosPagina.de` |
| T-7 | Lanzar `ErrorNegocio` antes de devolver el `Mono` en el servicio (fuera del flujo) | `Mono.fromCallable`/`Mono.defer` o lanzar dentro de `map`/`flatMap` |
| T-8 | `@WebFluxTest` sin `@Import(ManejadorErrores.class)` → errores con el formato por defecto de Spring | Importarlo siempre |
| T-9 | Anotar `PacientesServiceApplication` con `@EnableR2dbcRepositories`, `@ComponentScan`, etc. rompe los *slices* | Dejarla como está |
| T-10 | Declarar en el servicio un `@Bean @ConditionalOnMissingBean PublicadorEventos` de respaldo: las configuraciones de usuario se procesan antes que las autoconfiguraciones y anularía el publicador real | Usar `Optional<PublicadorEventos>` (M-10) |
| T-11 | En la prueba, declarar `hospital.eventos` con argumentos distintos a los de `PublicadorRabbit` → `PRECONDITION_FAILED` | `exchangeDeclare(EXCHANGE, "topic", true)` (durable, sin autoDelete, sin argumentos) |
| T-12 | Comodines del usuario (`%`, `_`) en el filtro `apellidos` | `escaparLike` |
| T-13 | Registrar datos personales en logs: `log.info("{}", request)` imprime el `toString()` del record con nombres y documento | Registrar solo ids, estados y códigos |
| T-14 | Contenedores `static` por clase + contextos Spring en caché: si dos clases `*IT` comparten exactamente la misma configuración de contexto, la segunda reutiliza un contexto apuntando a un contenedor ya detenido | Aquí las dos `*IT` tienen contextos distintos (`@DataR2dbcTest` vs `@SpringBootTest`). En otros servicios: no repetir la misma configuración en dos clases con contenedores propios, o unificar en una sola clase |
| T-15 | `@MockBean` está obsoleto en Boot 3.4+ | `@MockitoBean` |
| T-16 | Mockito estricto (`MockitoExtension`) falla con stubs no usados | Stubear solo lo necesario por caso o usar `lenient()` puntual |
| T-17 | El orden de la paginación sin `id` final mezcla páginas con apellidos y nombres iguales | `Sort.by("apellidos", "nombres", "id")` |
| T-18 | `contar` con la `Query` paginada (con `limit`) devolvería un total incorrecto | Construir una `Query` sin `with(pageable)` para el `count` |
| T-19 | Docker sin permisos en la shell → Testcontainers no encuentra Docker | Ejecutar `verify` dentro de `sg docker -c "..."` |
| T-20 | `PublicadorRabbit` tiene timeout de 3 s; esperar el mensaje con menos tiempo da falsos negativos | `await().atMost(10 s)` |

---

## 7. Criterios de terminado

- [ ] Todos los archivos de §2 creados; `pom.xml` con `org.testcontainers:r2dbc`; `application.yml` y `PacientesServiceApplication` sin cambios.
- [ ] `sg docker -c "./mvnw -q -pl pacientes-service verify"` (o la variante `-f pacientes-service/pom.xml`) termina con código 0, ejecutando las 7 clases de prueba: `ReglasDocumentoTest`, `PacienteTest`, `FiltroPacientesTest`, `PacienteServiceTest`, `PacienteControllerTest` (Surefire) y `PacienteRepositoryIT`, `PacienteFlujoIT` (Failsafe). Comprobar en `target/surefire-reports` y `target/failsafe-reports` que hay reportes de las 7 y 0 fallos/omitidas.
- [ ] Los greps de §5.2 salen vacíos (salvo los logs revisados a mano: sin documento, nombres, teléfono, correo ni dirección).
- [ ] Ningún `DELETE` en la API; el documento no cambia por `PUT`.
- [ ] Eventos con `datos` mínimos (`pacienteId` + `fechaRegistro` / `motivo`) verificados por pruebas.
- [ ] Errores en `application/problem+json` con `codigo` (`VALIDACION_FALLIDA`, `PACIENTE_DUPLICADO`, `PACIENTE_NO_ENCONTRADO`, `TRANSICION_NO_PERMITIDA`) verificados por pruebas.
- [ ] Nada modificado fuera de `pacientes-service/`; sin commits.
- [ ] Informe final del ejecutor: comando usado, número de pruebas por clase, desviaciones respecto a este plan (si las hubo) y por qué.
