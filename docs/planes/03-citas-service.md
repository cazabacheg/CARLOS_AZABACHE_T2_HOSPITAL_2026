# Plan de implementación — `citas-service`

> Ejecutor: agente que **no** vio la conversación de diseño. Este plan es autosuficiente.
> Specs de referencia (leerlas si hay duda, pero **este plan manda** donde las precisa): `docs/specs/00-convenciones-comunes.md`, `docs/specs/03-citas-service.md`, `docs/specs/02-pacientes-service.md` (§2.1 plantilla yml, §5 contrato `GET /api/pacientes/{id}`), `docs/specs/08-observabilidad.md`, `docs/documento-tecnico.md` §2.3, §2.4, §5.4, §6.2–6.4, §8.2, §9, §10.
> Módulo: `/home/cazabache/hospital-vida-salud/citas-service`. Paquete base: `pe.edu.cibertec.vidasalud.citas`.
> Al momento de escribir este plan **no existía** `docs/planes/02-pacientes-service.md`; la estructura de paquetes sigue la spec 00 §3 (los DTO van en `api/dto`, igual que la spec 02 §5.1).

## DUDAS CRUCIALES

Ninguna. Todo lo que faltaba se resolvió con las specs, el código real de `hospital-commons` y la inspección de los JAR de las versiones fijadas (ver "Decisiones técnicas" y "Decisiones menores tomadas").

## Decisiones menores tomadas

| # | Decisión | Motivo |
|---|---|---|
| M-1 | `marcarAtendida(UUID citaId, UUID atencionId)` (la spec dice `eventId` como 2.º parámetro) | El evento `cita.atendida` necesita `datos.atencionId` (spec 03 §7.1). El `eventId` ya lo registra `ConsumidorEventos` en el log |
| M-2 | En `PATCH /estado`, antes de la regla de 24 h se valida que el estado actual permita ese destino **por API** (`permitidoPorApi`). Orden final: destino de API → permitido por API desde el estado actual → plazo (solo `CANCELADA`) → `cambiarEstado` | Sin esto, `ATENDIDA → CANCELADA` pasaría por API (la matriz lo permite como transición de sistema) y cancelar una `CANCELADA` respondería `FUERA_DE_PLAZO` en vez de `TRANSICION_NO_PERMITIDA` |
| M-3 | Pedir `PENDIENTE` por `PATCH` → `409 TRANSICION_NO_PERMITIDA` con detalle "Una cita vuelve a PENDIENTE solo al reprogramarla." (pedir `ATENDIDA` usa el texto de la spec) | La spec dice "mismo error"; mismo código, texto más útil |
| M-4 | `esFinal()` = `CANCELADA` o `AUSENTE` (sin salidas en la matriz). `ATENDIDA` no es final en el enum (tiene la salida de sistema), pero `permitidoPorApi` desde `ATENDIDA` siempre es `false` | Coherente con la spec 03 §3.1 ("ATENDIDA es final **para la API**") |
| M-5 | El `motivo` del `PATCH /estado` **no** se persiste (la columna `motivo` es el motivo administrativo de la cita); solo viaja en `datos.motivo` del evento | La tabla §8.2 no tiene columna para el motivo del cambio |
| M-6 | Los textos 503/504 de Pacientes son los que genera `PoliticaResiliencia.proteger` de commons ("El servicio de pacientes no esta disponible. Intente mas tarde." / "...no respondio a tiempo."), sin tildes | No se modifica commons; las pruebas validan **estado y `codigo`**, no el texto |
| M-7 | Choque detectado por índice (carrera): se distingue el índice violado por el nombre en el mensaje de la excepción; `ux_cita_profesional_horario`/`ux_cita_paciente_horario` → `409 CITA_CHOQUE_AGENDA` "El horario ya fue ocupado."; cualquier otra violación (PK, `codigo`) se deja pasar (→ 500) | La spec pide mensaje genérico; las otras violaciones no son de agenda y no deben disfrazarse |
| M-8 | "Hasta 90 días": se compara por **fecha en Lima**: error si `fecha(cita) > hoy + 90 días` | Regla de calendario, sin depender de la hora |
| M-9 | Orden de `ReglasAgenda.validar`: futura → día → hora → bloque → 90 días (el de la tabla de la spec) | El caso "UTC que en Lima cae en domingo" debe responder "No se atiende ese día." |
| M-10 | `validarPlazoCambio`: permitido solo si `ahora < fechaHora − plazo` (exactamente 24 h antes ya **no** se permite) | Literal de la spec ("`ahora < fechaHoraCita − 24 h`") |
| M-11 | `cancelarPorAtencionAnulada`: cancela si `ahora < fechaHora` (estrictamente); a la hora exacta la cita "ya ocurrió" | Literal de la spec |
| M-12 | Si `datos.atencionId` falta en el evento de Atenciones, se usa `recursoId` del sobre (es la atención) | Tolerancia; el `citaId` sí es obligatorio (si falta → error → DLQ tras reintentos) |
| M-13 | Un evento con `tipo` distinto de `atencion.registrada`/`atencion.anulada` se confirma (ack) sin hacer nada y se registra `WARN` | No hay binding para otros tipos; si llegara, no tiene sentido mandarlo a la DLQ |
| M-14 | Paciente con `estado` distinto de `"ACTIVO"` (incluido desconocido) → `422 PACIENTE_INACTIVO` | Postura conservadora |
| M-15 | El servicio expone `listar(filtro, pagina)` (`Flux`) y `contar(filtro)` (`Mono<Long>`); el **controlador** arma la respuesta con `RespuestaPaginada.de(...)` | Spec 00 §4.2 dice que el controlador arma la `ResponseEntity`; el servicio no conoce tipos web |
| M-16 | Se agrega la dependencia de prueba `org.testcontainers:r2dbc` al `pom.xml` | `@ServiceConnection` para R2DBC usa `PostgreSQLR2DBCDatabaseContainer`, que implementa una interfaz de ese módulo (verificado en `spring-boot-testcontainers-3.5.16.jar` y `postgresql-1.21.4.jar`); sin ella: `NoClassDefFoundError` |
| M-17 | Mensaje de `MISMA_FECHA_HORA`: "La nueva fecha y hora es igual a la actual." | La spec no lo fija |
| M-18 | Se agrega `CitaConsultasIT`-style casos dentro de `CitaRepositoryIT` (filtro `fecha` en hora de Lima, orden y conteo) | Es la parte de zonas horarias más propensa a errores |

---

## 1. Resumen y orden de implementación

Se construye de adentro hacia afuera; **cada paso compila y tiene su verificación**. Comando base (desde `/home/cazabache/hospital-vida-salud`):

```bash
# Unitarias de un paso (sin Docker):
./mvnw -q -pl citas-service test -Dtest='EstadoCitaTest' -Dsurefire.failIfNoSpecifiedTests=false
# Integración de un paso (Docker; el grupo docker no está activo en la shell):
sg docker -c "./mvnw -q -pl citas-service verify -Dtest=NINGUNA -Dsurefire.failIfNoSpecifiedTests=false -Dit.test=CitaRepositoryIT"
# Final:
sg docker -c "./mvnw -q -pl citas-service verify"
```

`hospital-commons` **ya está instalado** en `~/.m2` (`1.0.0-SNAPSHOT`): **no** usar `-am`, **no** modificar commons.

| Paso | Contenido | Verificación |
|---|---|---|
| 0 | `pom.xml` (+`org.testcontainers:r2dbc`), `application.yml` (+`hospital.agenda`), `src/test/resources/application-test.yml` | `./mvnw -q -pl citas-service compile` |
| 1 | `domain/ZonaHospital`, `domain/EstadoCita` + `EstadoCitaTest` | `-Dtest=EstadoCitaTest` |
| 2 | `domain/Especialidad` | compila |
| 3 | `domain/ParametrosAgenda`, `domain/ReglasAgenda` + `ReglasAgendaTest`, `PlazoCambioTest` | `-Dtest='ReglasAgendaTest,PlazoCambioTest'` |
| 4 | `domain/DatosNuevaCita`, `domain/FiltroCitas`, `domain/Cita` + `CitaTest` | `-Dtest=CitaTest` |
| 5 | `schema.sql`, `persistence/CitaRepository`, `persistence/SecuenciaCodigoCita`, `persistence/CitaConsultas` + `CitaRepositoryIT` | `sg docker ... -Dit.test=CitaRepositoryIT` |
| 6 | `client/PacienteResumen`, `client/PacientesClient` + `PacientesClientTest` | `-Dtest=PacientesClientTest` |
| 7 | `config/AgendaPropiedades`, `config/AgendaConfig`, `config/ClientesConfig` | compila |
| 8 | `api/dto/*` (records), `messaging/EventosCita`, `service/CitaService` + `CitaServiceTest` | `-Dtest=CitaServiceTest` |
| 9 | `api/CitaController` + `CitaControllerTest` | `-Dtest=CitaControllerTest` |
| 10 | `messaging/AtencionEventoDatos`, `messaging/AtencionEventosConsumer`, `config/MensajeriaConfig` + `AtencionRegistradaConsumerIT` | `sg docker ... -Dit.test=AtencionRegistradaConsumerIT` |
| 11 | `CitaFlujoIT` | `sg docker ... -Dit.test=CitaFlujoIT` |
| 12 | Verificación completa + revisiones de `grep` (§5) | `sg docker -c "./mvnw -q -pl citas-service verify"` |

---

## 2. Archivos (paquete, clase, campos, firmas)

Rutas relativas a `citas-service/`. `B` = `pe/edu/cibertec/vidasalud/citas`.

### 2.0 Configuración del módulo (paso 0)

**`pom.xml`**: agregar, junto a las demás dependencias de prueba (versión gestionada por el BOM de Testcontainers que importa Boot):

```xml
<dependency>
  <groupId>org.testcontainers</groupId>
  <artifactId>r2dbc</artifactId>
  <scope>test</scope>
</dependency>
```

No agregar nada más (Awaitility viene en `spring-boot-starter-test`; `junit-jupiter-params` también; `amqp-client` llega transitivamente por `reactor-rabbitmq` vía commons; `mockwebserver` ya está). **No** agregar driver JDBC.

**`src/main/resources/application.yml`**: conservar todo lo existente y agregar dentro de `hospital:` (valores **entre comillas**, ver trampa T-12):

```yaml
hospital:
  # ... rabbit y clientes ya existentes ...
  agenda:
    dias-atencion: "${AGENDA_DIAS_ATENCION:MONDAY,TUESDAY,WEDNESDAY,THURSDAY,FRIDAY,SATURDAY}"
    hora-inicio: "${AGENDA_HORA_INICIO:08:00}"
    hora-ultimo-inicio: "${AGENDA_HORA_ULTIMO_INICIO:17:30}"
    bloque-minutos: 30
    max-dias-anticipacion: 90
    plazo-cambio-horas: 24
```

**`src/test/resources/application-test.yml`** (perfil `test`, spec 00 §7):

```yaml
spring:
  r2dbc:
    password: test          # lo reemplaza @ServiceConnection
hospital:
  rabbit:
    password: test          # lo reemplaza @DynamicPropertySource en las IT con RabbitMQ
eureka:
  client:
    enabled: false
```

`CitasServiceApplication` **no se toca** (sin `@EnableConfigurationProperties` ni `@ConfigurationPropertiesScan` en la clase principal: ensuciarían los *slices*).

### 2.1 `domain/` (sin Spring, salvo anotaciones de mapeo en `Cita`)

**`B/domain/ZonaHospital.java`** — `public final class`
- `public static final ZoneId LIMA = ZoneId.of("America/Lima");`
- `public static OffsetDateTime aLima(OffsetDateTime valor)` → `valor == null ? null : valor.atZoneSameInstant(LIMA).toOffsetDateTime()`.

**`B/domain/EstadoCita.java`** — `public enum EstadoCita { PENDIENTE, CONFIRMADA, ATENDIDA, CANCELADA, AUSENTE; }`
- `public boolean puedeCambiarA(EstadoCita destino)` — `switch (this)`:
  - `PENDIENTE` → `CONFIRMADA`, `CANCELADA`
  - `CONFIRMADA` → `ATENDIDA`, `AUSENTE`, `CANCELADA`
  - `ATENDIDA` → `CANCELADA` (sistema)
  - `CANCELADA`, `AUSENTE` → nada
  (exactamente **6** transiciones; `X → X` siempre `false`; `null` → `false`).
- `public static boolean esDestinoDeApi(EstadoCita destino)` → `CONFIRMADA`, `CANCELADA`, `AUSENTE`.
- `public boolean permitidoPorApi(EstadoCita destino)` → `this != ATENDIDA && esDestinoDeApi(destino) && puedeCambiarA(destino)`.
- `public boolean esActivo()` → `PENDIENTE || CONFIRMADA`.
- `public boolean esFinal()` → `CANCELADA || AUSENTE`.

**`B/domain/Especialidad.java`** — enum con `private final String nombre` y `public String getNombre()`:
`MEDICINA_GENERAL("Medicina General")`, `PEDIATRIA("Pediatría")`, `CARDIOLOGIA("Cardiología")`, `GINECOLOGIA("Ginecología")`, `TRAUMATOLOGIA("Traumatología")`, `DERMATOLOGIA("Dermatología")`, `OFTALMOLOGIA("Oftalmología")`.

**`B/domain/ParametrosAgenda.java`** — `public record ParametrosAgenda(Set<DayOfWeek> diasAtencion, LocalTime horaInicio, LocalTime horaUltimoInicio, int bloqueMinutos, int maxDiasAnticipacion, int plazoCambioHoras)`
- Constructor compacto: `Objects.requireNonNull` de los objetos; `diasAtencion` no vacío → `Set.copyOf(...)` (o `EnumSet.copyOf`); `horaInicio` ≤ `horaUltimoInicio`; `bloqueMinutos > 0`, `maxDiasAnticipacion > 0`, `plazoCambioHoras >= 0`; si no → `IllegalArgumentException`.
- `public static ParametrosAgenda porDefecto()` → lunes–sábado, `08:00`, `17:30`, `30`, `90`, `24`.

**`B/domain/ReglasAgenda.java`** — `public final class ReglasAgenda`
- Campos: `private final Clock reloj; private final ParametrosAgenda parametros;`
- Constructor `ReglasAgenda(Clock reloj, ParametrosAgenda parametros)`.
- `public void validar(OffsetDateTime fechaHora)` — lanza `ErrorNegocio(HttpStatus.BAD_REQUEST, "HORARIO_FUERA_DE_AGENDA", <detalle>)`. Algoritmo:
  1. `OffsetDateTime lima = ZonaHospital.aLima(fechaHora); OffsetDateTime ahora = ZonaHospital.aLima(OffsetDateTime.now(reloj));`
  2. `!lima.isAfter(ahora)` → "La cita debe programarse en una fecha futura."
  3. `!diasAtencion.contains(lima.getDayOfWeek())` → "No se atiende ese día."
  4. `LocalTime h = lima.toLocalTime()`; `h.isBefore(horaInicio) || h.isAfter(horaUltimoInicio)` → `"Horario de atención: " + horaInicio + " a " + horaUltimoInicio + "."` (`LocalTime.toString()` da `08:00`).
  5. `h.getSecond() != 0 || h.getNano() != 0 || (h.getHour() * 60 + h.getMinute()) % bloqueMinutos != 0` → `"Las citas se programan en bloques de " + bloqueMinutos + " minutos."`
  6. `lima.toLocalDate().isAfter(ahora.toLocalDate().plusDays(maxDiasAnticipacion))` → `"Solo se programan citas con hasta " + maxDiasAnticipacion + " días de anticipación."`
- `public void validarPlazoCambio(OffsetDateTime fechaHoraCita)` — si `!ahora.isBefore(fechaHoraCita.minusHours(plazoCambioHoras))` → `ErrorNegocio(HttpStatus.CONFLICT, "FUERA_DE_PLAZO", "Solo se puede cancelar o reprogramar hasta " + plazoCambioHoras + " horas antes de la cita.")`.
- `public ParametrosAgenda parametros()` (getter, útil en pruebas).

**`B/domain/DatosNuevaCita.java`** — `public record DatosNuevaCita(UUID pacienteId, Especialidad especialidad, OffsetDateTime fechaHora, String profesionalCodigo, String motivo)`.

**`B/domain/FiltroCitas.java`** — `public record FiltroCitas(UUID pacienteId, EstadoCita estado, Especialidad especialidad, String profesionalCodigo, LocalDate fecha)`; `public static FiltroCitas vacio()`.

**`B/domain/Cita.java`** — entidad (ver §3.1 para el porqué):

```java
@Table("cita")
public class Cita implements Persistable<UUID> {
    @Id private UUID id;
    private String codigo;
    private UUID pacienteId;
    private Especialidad especialidad;
    private OffsetDateTime fechaHora;
    private String profesionalCodigo;
    private String motivo;
    private EstadoCita estado;
    private OffsetDateTime fechaCreacion;
    private OffsetDateTime fechaActualizacion;
    @Version private Long version;               // NO final, NO primitivo
    @Transient private boolean nuevo;            // org.springframework.data.annotation.Transient

    @PersistenceCreator
    Cita(UUID id, String codigo, UUID pacienteId, Especialidad especialidad, OffsetDateTime fechaHora,
         String profesionalCodigo, String motivo, EstadoCita estado, OffsetDateTime fechaCreacion,
         OffsetDateTime fechaActualizacion, Long version) {
        // asigna todo normalizando las 3 fechas con ZonaHospital.aLima(...)
        // nuevo = false  (equivale a marcarComoExistente())
    }

    public static Cita programar(DatosNuevaCita datos, String codigo, Clock reloj) { ... }
    public EstadoCita cambiarEstado(EstadoCita destino, Clock reloj) { ... }
    public void validarCambioPorApi(EstadoCita destino) { ... }
    public OffsetDateTime reprogramar(OffsetDateTime nueva, String nuevoProfesional, Clock reloj) { ... }
    public boolean yaOcurrio(Clock reloj) { ... }
    public Cita marcarComoExistente() { this.nuevo = false; return this; }

    @Override public UUID getId() { return id; }
    @Override public boolean isNew() { return nuevo; }
    // getters de todos los campos (getCodigo(), getPacienteId(), ..., getVersion()); SIN setters
}
```

Semántica:
- `programar`: `id = UUID.randomUUID()`, `codigo`, campos de `datos` (`fechaHora` normalizada a Lima), `estado = PENDIENTE`, `fechaCreacion = fechaActualizacion = OffsetDateTime.now(reloj)` normalizado a Lima, `version = null`, `nuevo = true`. Usa un constructor privado o el `@PersistenceCreator` y luego `nuevo = true`. **No** valida agenda (eso es `ReglasAgenda`; así las pruebas pueden fabricar citas en el pasado).
- `cambiarEstado(destino, reloj)`: si `!estado.puedeCambiarA(destino)` → `ErrorNegocio(CONFLICT, "TRANSICION_NO_PERMITIDA", "No se puede pasar una cita de " + estado + " a " + destino + ".")`; si no, guarda el anterior, asigna `destino`, `fechaActualizacion = now`, devuelve el anterior. (Lo usan la API **y** el sistema.)
- `validarCambioPorApi(destino)` (M-2, M-3):
  - `destino == ATENDIDA` → `409 TRANSICION_NO_PERMITIDA` "Una cita pasa a ATENDIDA solo al registrar su atención."
  - `destino == PENDIENTE` → `409 TRANSICION_NO_PERMITIDA` "Una cita vuelve a PENDIENTE solo al reprogramarla."
  - `!estado.permitidoPorApi(destino)` → `409 TRANSICION_NO_PERMITIDA` "No se puede pasar una cita de <ACTUAL> a <DESTINO>."
- `reprogramar(nueva, nuevoProfesional, reloj)`: `!estado.esActivo()` → `409 TRANSICION_NO_PERMITIDA` "Solo se reprograman citas pendientes o confirmadas."; `nueva.isEqual(fechaHora)` (mismo instante) → `409 MISMA_FECHA_HORA` "La nueva fecha y hora es igual a la actual."; si no: guarda anterior, `fechaHora = aLima(nueva)`, `profesionalCodigo = nuevoProfesional != null ? nuevoProfesional : profesionalCodigo`, `estado = PENDIENTE`, `fechaActualizacion = now`; devuelve la fecha anterior.
- `yaOcurrio(reloj)`: `!OffsetDateTime.now(reloj).isBefore(fechaHora)`.

### 2.2 `persistence/`

**`src/main/resources/schema.sql`** (DDL del documento §8.2 con `IF NOT EXISTS`; **sin `;` dentro de comentarios**):

```sql
CREATE TABLE IF NOT EXISTS cita (
  id                  UUID PRIMARY KEY,
  codigo              VARCHAR(30)  NOT NULL UNIQUE,
  paciente_id         UUID         NOT NULL,
  especialidad        VARCHAR(30)  NOT NULL,
  fecha_hora          TIMESTAMPTZ  NOT NULL,
  profesional_codigo  VARCHAR(20)  NOT NULL,
  motivo              VARCHAR(200),
  estado              VARCHAR(12)  NOT NULL DEFAULT 'PENDIENTE'
                      CHECK (estado IN ('PENDIENTE','CONFIRMADA','ATENDIDA','CANCELADA','AUSENTE')),
  fecha_creacion      TIMESTAMPTZ  NOT NULL DEFAULT now(),
  fecha_actualizacion TIMESTAMPTZ  NOT NULL DEFAULT now(),
  version             BIGINT       NOT NULL DEFAULT 0
);
CREATE SEQUENCE IF NOT EXISTS cita_codigo_seq;
CREATE UNIQUE INDEX IF NOT EXISTS ux_cita_profesional_horario
  ON cita (profesional_codigo, fecha_hora) WHERE estado IN ('PENDIENTE','CONFIRMADA');
CREATE UNIQUE INDEX IF NOT EXISTS ux_cita_paciente_horario
  ON cita (paciente_id, fecha_hora) WHERE estado IN ('PENDIENTE','CONFIRMADA');
CREATE INDEX IF NOT EXISTS ix_cita_paciente ON cita (paciente_id);
CREATE INDEX IF NOT EXISTS ix_cita_estado_fecha ON cita (estado, fecha_hora);
```

**`B/persistence/CitaRepository.java`** — `public interface CitaRepository extends ReactiveCrudRepository<Cita, UUID>`:

```java
@Query("SELECT EXISTS (SELECT 1 FROM cita WHERE profesional_codigo = :profesionalCodigo "
     + "AND fecha_hora = :fechaHora AND estado IN ('PENDIENTE','CONFIRMADA'))")
Mono<Boolean> existeActivaDeProfesional(String profesionalCodigo, OffsetDateTime fechaHora);

@Query("SELECT EXISTS (SELECT 1 FROM cita WHERE paciente_id = :pacienteId "
     + "AND fecha_hora = :fechaHora AND estado IN ('PENDIENTE','CONFIRMADA'))")
Mono<Boolean> existeActivaDePaciente(UUID pacienteId, OffsetDateTime fechaHora);
```

(Son los `existsActivaBy...` de la spec. Con SQL explícito para no depender de la conversión de colecciones de enum en consultas derivadas. Los nombres `:param` funcionan porque el padre compila con `-parameters`.)

**`B/persistence/SecuenciaCodigoCita.java`** — `@Component`, constructor `(DatabaseClient databaseClient)`:
- `public Mono<Long> siguiente()` → `databaseClient.sql("SELECT nextval('cita_codigo_seq')").map(fila -> fila.get(0, Long.class)).one()`.

**`B/persistence/CitaConsultas.java`** — `@Component`, constructor `(R2dbcEntityTemplate template)`:
- `public static final Sort ORDEN = Sort.by("fechaHora", "id");`
- `public Flux<Cita> listar(FiltroCitas filtro, ParametrosPagina pagina)` → `template.select(Query.query(criterio(filtro)).with(pagina.aPageable(ORDEN)), Cita.class)`.
- `public Mono<Long> contar(FiltroCitas filtro)` → `template.count(Query.query(criterio(filtro)), Cita.class)`.
- `static Criteria criterio(FiltroCitas f)` — parte de `Criteria.empty()` y agrega con `.and(...)` **solo** si el valor llegó; usar **nombres de propiedad Java** (el `QueryMapper` los traduce a columnas):
  - `pacienteId` → `.and("pacienteId").is(f.pacienteId())`
  - `estado` → `.and("estado").is(f.estado().name())`
  - `especialidad` → `.and("especialidad").is(f.especialidad().name())`
  - `profesionalCodigo` → `.and("profesionalCodigo").is(...)`
  - `fecha` → `OffsetDateTime desde = f.fecha().atStartOfDay(ZonaHospital.LIMA).toOffsetDateTime(); OffsetDateTime hasta = f.fecha().plusDays(1).atStartOfDay(ZonaHospital.LIMA).toOffsetDateTime();` → `.and("fechaHora").greaterThanOrEquals(desde).and("fechaHora").lessThan(hasta)` (intervalo semiabierto `[00:00, 24:00)` de Lima).

### 2.3 `client/`

**`B/client/PacienteResumen.java`** — `@JsonIgnoreProperties(ignoreUnknown = true) public record PacienteResumen(UUID id, String estado) { public boolean estaActivo() { return "ACTIVO".equals(estado); } }`

**`B/client/PacientesClient.java`** — clase simple (**sin** `@Component`; la crea `ClientesConfig`, así la prueba la construye sin Spring):

```java
public class PacientesClient {
    public static final String CODIGO_NO_DISPONIBLE = "SERVICIO_PACIENTES_NO_DISPONIBLE";
    private static final Logger log = LoggerFactory.getLogger(PacientesClient.class);
    private final WebClient webClient;

    public PacientesClient(WebClient webClient) { this.webClient = webClient; }

    public Mono<PacienteResumen> obtener(UUID pacienteId) {
        Mono<PacienteResumen> llamada = webClient.get()
                .uri("/api/pacientes/{id}", pacienteId)
                .retrieve()
                .onStatus(s -> s.value() == 404, r -> r.releaseBody().then(Mono.error(
                        new ErrorNegocio(HttpStatus.NOT_FOUND, "PACIENTE_NO_ENCONTRADO",
                                "No existe el paciente " + pacienteId + "."))))
                .bodyToMono(PacienteResumen.class)
                .doOnSubscribe(s -> log.info("consultando pacientes-service paciente id={}", pacienteId));
        return PoliticaResiliencia.proteger(llamada, CODIGO_NO_DISPONIBLE, "pacientes");
    }
}
```

- La cadena es **fría**: el reintento de `PoliticaResiliencia` vuelve a suscribir y repite la petición.
- `ErrorNegocio` (404) pasa sin cambios por `proteger`; 5xx/red → 503; `TimeoutException` (2 s) → 504; "sin instancias" → el filtro del *load balancer* responde **503** sintético → 503.
- Otros 4xx quedan como `WebClientResponseException` → 500 por `ManejadorErrores` (no esperado en el contrato).

### 2.4 `config/`

**`B/config/AgendaPropiedades.java`**:

```java
@ConfigurationProperties("hospital.agenda")
public record AgendaPropiedades(
        @DefaultValue({"MONDAY","TUESDAY","WEDNESDAY","THURSDAY","FRIDAY","SATURDAY"}) List<DayOfWeek> diasAtencion,
        @DefaultValue("08:00") String horaInicio,
        @DefaultValue("17:30") String horaUltimoInicio,
        @DefaultValue("30") int bloqueMinutos,
        @DefaultValue("90") int maxDiasAnticipacion,
        @DefaultValue("24") int plazoCambioHoras) {
    public ParametrosAgenda aParametros() {
        return new ParametrosAgenda(Set.copyOf(diasAtencion), LocalTime.parse(horaInicio.trim()),
                LocalTime.parse(horaUltimoInicio.trim()), bloqueMinutos, maxDiasAnticipacion, plazoCambioHoras);
    }
}
```

(Horas como `String` + `LocalTime.parse`: evita depender del formateador localizado de Spring para `LocalTime`.)

**`B/config/AgendaConfig.java`** — `@Configuration @EnableConfigurationProperties(AgendaPropiedades.class)`:
- `@Bean ReglasAgenda reglasAgenda(AgendaPropiedades props, Clock reloj)` → `new ReglasAgenda(reloj, props.aParametros())`. (El `Clock` lo aporta `RelojAutoConfiguration` de commons, `@ConditionalOnMissingBean`.)

**`B/config/ClientesConfig.java`** — `@Configuration`:

```java
@Bean
@LoadBalanced
WebClient.Builder webClientBalanceado(ObjectProvider<WebClientCustomizer> personalizadores) {
    WebClient.Builder builder = WebClient.builder();
    personalizadores.orderedStream().forEach(p -> p.customize(builder)); // códecs Jackson de Boot
    return builder;
}

@Bean
PacientesClient pacientesClient(@LoadBalanced WebClient.Builder balanceado,
                                @Value("${hospital.clientes.pacientes.url}") String url) {
    return new PacientesClient(ClienteHttpFactory.crear(balanceado, url));
}
```

(Definir un `WebClient.Builder` hace que el de Boot, `@ConditionalOnMissingBean`, se retire; por eso se aplican a mano los `WebClientCustomizer`. El `@LoadBalanced` es un `@Qualifier`; Spring Cloud le agrega el filtro de balanceo **al bean**, y `ClienteHttpFactory.crear` lo clona conservando ese filtro.)

**`B/config/MensajeriaConfig.java`** — paso 10:

```java
@Configuration
@ConditionalOnProperty(prefix = "hospital.rabbit", name = "enabled", havingValue = "true", matchIfMissing = true)
public class MensajeriaConfig {
    @Bean
    ApplicationRunner consumidorAtenciones(ConsumidorEventos consumidor, AtencionEventosConsumer manejador) {
        return args -> consumidor.iniciar(TopologiaHospital.COLA_CITAS_ATENCIONES,
                List.of(TopologiaHospital.ATENCION_REGISTRADA, TopologiaHospital.ATENCION_ANULADA),
                manejador::manejar);
    }
}
```

(`ConsumidorEventos.iniciar` contiene la **única** `subscribe()` permitida; la DLQ `citas.atenciones.dlq` la declara commons con `TopologiaHospital.colaMuertos`.)

### 2.5 `api/dto/` (records; paso 8)

```java
public record CitaCrearRequest(
        @NotNull UUID pacienteId,
        @NotNull Especialidad especialidad,
        @NotNull OffsetDateTime fechaHora,
        @NotBlank @Pattern(regexp = "^[A-Z]{3}-\\d{3}$", message = "debe tener el formato AAA-999") String profesionalCodigo,
        @Size(max = 200) @Schema(description = "Motivo administrativo (p. ej. Control). No ingresar datos clínicos.") String motivo) {
    public DatosNuevaCita aDatos() { ... }
}

public record ReprogramarCitaRequest(
        @NotNull OffsetDateTime fechaHora,
        @Pattern(regexp = "^[A-Z]{3}-\\d{3}$", message = "debe tener el formato AAA-999") String profesionalCodigo) { }

public record CambioEstadoCitaRequest(@NotNull EstadoCita estado, @Size(max = 200) String motivo) { }

public record CitaResponse(UUID id, String codigo, UUID pacienteId, Especialidad especialidad,
        String especialidadNombre, OffsetDateTime fechaHora, String profesionalCodigo, String motivo,
        EstadoCita estado, OffsetDateTime fechaCreacion, OffsetDateTime fechaActualizacion) {
    public static CitaResponse de(Cita c) { /* fechas con ZonaHospital.aLima(...) */ }
}
```

(`@Schema` de `io.swagger.v3.oas.annotations.media`, ya disponible por springdoc.)

### 2.6 `messaging/` (pasos 8 y 10)

**`B/messaging/EventosCita.java`** — `@Component`, constructor `(ObjectMapper mapper, Clock reloj)`. Construye sobres (`EventoEnvelope.nuevo(reloj, tipo, "citas-service", "CITA", cita.getId(), anterior, nuevo, datos)`; `tipo` = routing key):
- `public static final String ORIGEN = "citas-service", TIPO_RECURSO = "CITA";`
- `public EventoEnvelope creada(Cita c)` → `cita.creada`, `null → PENDIENTE`.
- `public EventoEnvelope cambioEstado(Cita c, EstadoCita anterior, String motivo, UUID atencionId)` → tipo según `c.getEstado()`: `CONFIRMADA→cita.confirmada`, `CANCELADA→cita.cancelada`, `AUSENTE→cita.ausente`, `ATENDIDA→cita.atendida` (constantes de `TopologiaHospital`). Agrega `motivo` si no es `null` y `atencionId` si no es `null`.
- `public EventoEnvelope reprogramada(Cita c, EstadoCita anterior, OffsetDateTime fechaHoraAnterior, String profesionalAnterior)` → `cita.reprogramada`, `anterior → PENDIENTE`, `datos.fechaHoraAnterior`, `datos.profesionalAnterior`.
- `public static String routingKeyPara(EstadoCita destino)`.
- `private ObjectNode datosComunes(Cita c)` → `ObjectNode` con `codigo`, `pacienteId` (texto), `especialidad` (`name()`), `especialidadNombre`, `fechaHora` (`DateTimeFormatter.ISO_OFFSET_DATE_TIME.format(aLima(...))` → `2026-10-15T10:00:00-05:00`, **con segundos**), `profesionalCodigo`.
- Los `datos` se arman con `mapper.createObjectNode().put(...)` y solo **strings** (no `valueToTree` de `OffsetDateTime`, para no depender de la configuración de Jackson). **Sin datos personales** (solo ids y códigos).

**`B/messaging/AtencionEventoDatos.java`** — `@JsonIgnoreProperties(ignoreUnknown = true) public record AtencionEventoDatos(UUID atencionId, UUID citaId)`.

**`B/messaging/AtencionEventosConsumer.java`** — `@Component`, constructor `(CitaService citaService, ObjectMapper mapper)`:
- `public Mono<Void> manejar(EventoEnvelope evento)`:
  1. `Mono.fromCallable(() -> mapper.treeToValue(evento.datos(), AtencionEventoDatos.class))`; si `datos` es `null` o `citaId` es `null` → `Mono.error(new IllegalArgumentException("evento sin citaId"))` (→ 3 intentos → DLQ).
  2. `atencionId = datos.atencionId() != null ? datos.atencionId() : evento.recursoId()` (M-12).
  3. `switch (evento.tipo())`: `ATENCION_REGISTRADA` → `citaService.marcarAtendida(citaId, atencionId)`; `ATENCION_ANULADA` → `citaService.cancelarPorAtencionAnulada(citaId, atencionId)`; otro → `log.warn("tipo de evento no esperado eventId={} tipo={}", ...)` y `Mono.empty()` (M-13).
- Todo diferido (dentro de `Mono.defer`/`flatMap`) para que el reintento de `ConsumidorEventos` (que re-suscribe) vuelva a leer la cita.

### 2.7 `service/` (paso 8)

**`B/service/CitaService.java`** — `@Service`, constructor:
`(CitaRepository repositorio, CitaConsultas consultas, SecuenciaCodigoCita secuencia, PacientesClient pacientes, PublicadorEventos publicador, EventosCita eventos, ReglasAgenda reglas, Clock reloj)`.

Firmas:

```java
public Mono<CitaResponse> programar(CitaCrearRequest solicitud)
public Mono<CitaResponse> obtener(UUID id)
public Flux<CitaResponse> listar(FiltroCitas filtro, ParametrosPagina pagina)
public Mono<Long> contar(FiltroCitas filtro)
public Mono<CitaResponse> cambiarEstado(UUID id, CambioEstadoCitaRequest solicitud)
public Mono<CitaResponse> reprogramar(UUID id, ReprogramarCitaRequest solicitud)
public Mono<Void> marcarAtendida(UUID citaId, UUID atencionId)
public Mono<Void> cancelarPorAtencionAnulada(UUID citaId, UUID atencionId)
```

Ayudantes privados: `Mono<Cita> buscar(UUID id)` (vacío → `ErrorNegocio(404, "CITA_NO_ENCONTRADA", "No existe la cita " + id + ".")`), `Mono<Void> verificarChoque(String profesional, UUID pacienteId, OffsetDateTime fechaHora)`, `Mono<Cita> guardar(Cita c)` (traducción de errores, §3.3), `String codigo(long numero)` → `String.format("CIT-%s-%06d", LocalDate.now(reloj.withZone(ZonaHospital.LIMA)).format(DateTimeFormatter.BASIC_ISO_DATE), numero)`.

Flujos (todas las llamadas a colaboradores **dentro de lambdas** o `Mono.defer`, ver T-9):

- **programar** (spec 03 §5.1):
  `Mono.fromRunnable(() -> reglas.validar(s.fechaHora()))` →
  `then(Mono.defer(() -> pacientes.obtener(s.pacienteId())))` → si `!estaActivo()` → `ErrorNegocio(422, "PACIENTE_INACTIVO", "El paciente está inactivo y no puede recibir citas.")` →
  `verificarChoque(profesional, pacienteId, aLima(fechaHora))` → `secuencia.siguiente()` → `Cita.programar(s.aDatos(), codigo(n), reloj)` → `guardar` → `.doOnNext(Cita::marcarComoExistente)` → `publicador.publicar(CITA_CREADA, eventos.creada(c)).thenReturn(c)` → `CitaResponse.de` + `log.info("cita programada id={} codigo={}", ...)`.
- **verificarChoque**: `existeActivaDeProfesional` → `true` → `409 CITA_CHOQUE_AGENDA` "El profesional <código> ya tiene una cita en ese horario."; luego `existeActivaDePaciente` → `true` → `409 CITA_CHOQUE_AGENDA` "El paciente ya tiene una cita en ese horario.".
- **obtener**: `buscar(id).map(CitaResponse::de)`.
- **listar/contar**: delegan en `CitaConsultas` (`listar(...).map(CitaResponse::de)`).
- **cambiarEstado** (spec 03 §5.4 + M-2): `buscar(id)` → `cita.validarCambioPorApi(destino)` → si `destino == CANCELADA` → `reglas.validarPlazoCambio(cita.getFechaHora())` → `anterior = cita.cambiarEstado(destino, reloj)` → `guardar` → publicar `eventos.cambioEstado(c, anterior, s.motivo(), null)` con `routingKeyPara(destino)` → DTO. Validaciones síncronas dentro de `flatMap(c -> Mono.fromCallable(...))` para que el `ErrorNegocio` viaje como señal de error.
- **reprogramar** (spec 03 §5.5): `buscar(id)` → `validarPlazoCambio(fechaHora actual)` → `reglas.validar(nueva)` → guardar `estadoAnterior`, `profesionalAnterior` → `fechaAnterior = cita.reprogramar(nueva, s.profesionalCodigo(), reloj)` → `verificarChoque(cita.getProfesionalCodigo(), cita.getPacienteId(), cita.getFechaHora())` → `guardar` → publicar `eventos.reprogramada(...)` → DTO. **No** consulta a Pacientes (D-20).
- **marcarAtendida** (spec 03 §5.6): `repositorio.findById` vacío → `ErrorNegocio(404, CITA_NO_ENCONTRADA, ...)`; si `ATENDIDA` → `Mono.empty()` (idempotente, **sin** publicar); si no → `cita.cambiarEstado(ATENDIDA, reloj)` (409 si no está `CONFIRMADA`) → `repositorio.save` (sin traducir: cualquier error debe llegar a `ConsumidorEventos` para reintentos/DLQ) → publicar `cita.atendida` con `atencionId` → `log.info("cita atendida id={} atencionId={}")` → `then()`.
- **cancelarPorAtencionAnulada** (spec 03 §5.7): no existe → error; `CANCELADA` → `Mono.empty()`; `ATENDIDA` y `!cita.yaOcurrio(reloj)` → `cambiarEstado(CANCELADA)` (transición de sistema, **sin** plazo de 24 h) → `save` → publicar `cita.cancelada` con `motivo = "Atención anulada"` y `atencionId`; `ATENDIDA` y ya ocurrió → `log.info("anulacion administrativa, la cita ya ocurrio id={}")` y `Mono.empty()`; otro estado → `ErrorNegocio(409, "TRANSICION_NO_PERMITIDA", "No se puede cancelar por anulación una cita en estado <ESTADO>.")`.

Logs: solo ids, códigos y estados (nunca datos personales; `pacienteId` es UUID y está permitido).

### 2.8 `api/` (paso 9)

**`B/api/CitaController.java`** — `@RestController @RequestMapping("/api/citas")`, constructor `(CitaService servicio)`:

```java
@PostMapping @Operation(summary = "Programar una cita")
Mono<ResponseEntity<CitaResponse>> programar(@Valid @RequestBody CitaCrearRequest solicitud)
    // -> ResponseEntity.created(URI.create("/api/citas/" + r.id())).body(r)

@GetMapping("/{id}")
Mono<CitaResponse> obtener(@PathVariable UUID id)

@GetMapping
Mono<ResponseEntity<Flux<CitaResponse>>> listar(
        @RequestParam(required = false) UUID pacienteId,
        @RequestParam(required = false) EstadoCita estado,
        @RequestParam(required = false) Especialidad especialidad,
        @RequestParam(required = false) String profesionalCodigo,
        @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate fecha,
        @RequestParam(required = false) Integer pagina,
        @RequestParam(required = false) Integer tamano)
    // Mono.defer(() -> { ParametrosPagina p = ParametrosPagina.de(pagina, tamano);
    //   FiltroCitas f = new FiltroCitas(...);
    //   return RespuestaPaginada.de(servicio.listar(f, p), servicio.contar(f)); })

@PutMapping("/{id}/reprogramar")
Mono<CitaResponse> reprogramar(@PathVariable UUID id, @Valid @RequestBody ReprogramarCitaRequest solicitud)

@PatchMapping("/{id}/estado") @Operation(summary = "Cambiar el estado de una cita (CONFIRMADA, CANCELADA, AUSENTE)")
Mono<CitaResponse> cambiarEstado(@PathVariable UUID id, @Valid @RequestBody CambioEstadoCitaRequest solicitud)
```

Errores 400 los produce `ManejadorErrores` (commons): cuerpo inválido → `WebExchangeBindException`; enum desconocido en JSON o UUID inválido en ruta/consulta → `ServerWebInputException`; `pagina`/`tamano` fuera de rango → `ErrorNegocio` de `ParametrosPagina`.

---

## 3. Decisiones técnicas (verificadas contra las versiones reales)

Versiones resueltas en `~/.m2`: Boot 3.5.16, Spring Data R2DBC 3.5.13, r2dbc-postgresql 1.0.9.RELEASE, Spring Cloud Commons 4.3.3, Testcontainers 1.21.4.

### 3.1 `Persistable<UUID>` + `@Version` (insert vs update)

- `SimpleR2dbcRepository.save` decide con `entityInformation.isNew(entity)`. Para tipos `Persistable`, Spring Data usa `PersistableIsNewStrategy` → **manda `isNew()`** (no mira `version` ni `id`). Con `nuevo = true` → `INSERT`; con `nuevo = false` → `UPDATE`.
- En el `INSERT`, `R2dbcEntityTemplate.setVersionIfNecessary` (verificado con `javap` en 3.5.13) **fija la versión inicial**: `0` para `Long` (sería `1` para primitivo). Coincide con el `DEFAULT 0` de la tabla. Por eso `version` es `Long` (no `long`) y empieza en `null` en `Cita.programar`.
- En el `UPDATE`, el template agrega `WHERE id = ? AND version = <leída>` y escribe `version + 1`; si actualiza 0 filas lanza **`OptimisticLockingFailureException`** (`OptimisticLockingUtils.updateFailed`). Sin `@Version` lanzaría `TransientDataAccessResourceException`, por eso `@Version` es obligatorio para RN-14.
- El acceso es por **campo**: `version` y el resto **no** deben ser `final` (si lo fueran, Spring Data intentaría crear instancias nuevas vía *wither*/constructor y el objeto devuelto ya no sería el mismo).
- Las entidades leídas (repositorio o `R2dbcEntityTemplate`) pasan por el `@PersistenceCreator` → `nuevo = false` → los `save` posteriores son `UPDATE` con control de versión.
- El objeto devuelto por un `INSERT` **conserva `nuevo = true`** (Spring no lo cambia). Por eso el servicio aplica `.doOnNext(Cita::marcarComoExistente)` tras insertar; nunca se re-guarda una instancia recién insertada sin esto (daría `DuplicateKeyException` por PK).

### 3.2 Secuencia `cita_codigo_seq`

`DatabaseClient.sql("SELECT nextval('cita_codigo_seq')").map(f -> f.get(0, Long.class)).one()`. Se pide **después** de las validaciones y de las consultas de choque (fallar barato primero). Los huecos en la numeración (si falla el `INSERT`) son aceptables. Formato: `CIT-<yyyyMMdd de hoy en Lima>-<6 dígitos>` (`%06d`).

### 3.3 Traducción de errores de escritura (`CitaService.guardar`)

```java
private Mono<Cita> guardar(Cita cita) {
    return repositorio.save(cita)
            .onErrorMap(DuplicateKeyException.class, CitaService::traducirDuplicado)
            .onErrorMap(OptimisticLockingFailureException.class, e -> new ErrorNegocio(HttpStatus.CONFLICT,
                    "CONFLICTO_CONCURRENTE", "La cita fue modificada por otra operación; vuelva a consultarla."));
}

static Throwable traducirDuplicado(DuplicateKeyException e) {
    String texto = e.getMessage() + " " + e.getMostSpecificCause().getMessage();
    if (texto.contains("ux_cita_profesional_horario") || texto.contains("ux_cita_paciente_horario")) {
        return new ErrorNegocio(HttpStatus.CONFLICT, "CITA_CHOQUE_AGENDA", "El horario ya fue ocupado.");
    }
    return e; // cita_pkey / cita_codigo_key: no es choque de agenda -> 500
}
```

- Spring (`ConnectionFactoryUtils.convertR2dbcException`) convierte el `R2dbcDataIntegrityViolationException` con SQLSTATE `23505` en `DuplicateKeyException`; su mensaje incluye el del driver: `duplicate key value violates unique constraint "ux_cita_profesional_horario"`. El nombre del índice es la forma de distinguir cuál se violó (la API `io.r2dbc.postgresql.api.ErrorDetails#getConstraintName` no se usa porque `r2dbc-postgresql` está en `scope runtime`). `CitaRepositoryIT` **verifica** que el mensaje contenga el nombre del índice.
- `guardar` se usa en `programar`, `cambiarEstado` y `reprogramar`. En `marcarAtendida`/`cancelarPorAtencionAnulada` se usa `repositorio.save` directo: los errores deben llegar crudos a `ConsumidorEventos` (reintenta; `OptimisticLockingFailureException` se resuelve sola al releer).

### 3.4 Zonas horarias

- Jackson de Boot tiene `ADJUST_DATES_TO_CONTEXT_TIME_ZONE` activo: un `OffsetDateTime` recibido como `2026-10-15T10:00:00-05:00` llega **convertido a UTC** (`15:00Z`). Por eso `ReglasAgenda` y `Cita` normalizan con `ZonaHospital.aLima` antes de evaluar y guardar.
- r2dbc-postgresql envía el `OffsetDateTime` como `timestamptz` (se preserva el **instante**). Al leer, devuelve un `OffsetDateTime` cuyo *offset* depende de la zona de la sesión (configurable en el driver; por defecto la de la JVM; en CI suele ser UTC). **No se confía en ese offset**: el `@PersistenceCreator` normaliza a Lima. En pruebas, comparar fechas con `isEqual(...)` / `isAtSameInstantAs`, no con `equals`.
- Los índices únicos comparan instantes (`timestamptz`), así que dos fechas con distinto offset pero el mismo instante chocan correctamente.
- Filtro `fecha`: `[fecha 00:00 Lima, fecha+1 00:00 Lima)` como `OffsetDateTime` con offset `-05:00` (Lima no tiene horario de verano).
- Respuestas y eventos salen con `-05:00` porque las fechas de la entidad ya están en Lima; Jackson serializa `OffsetDateTime` con su propio offset.

### 3.5 Consultas dinámicas

`R2dbcEntityTemplate.select(Query, Class)` y `count(Query, Class)` con `Criteria` armado condicionalmente (spec 00 §4.2), `Query.with(pageable)` con orden estable `fechaHora, id`. Los nombres de **propiedad** Java se traducen a columnas por el `QueryMapper` (también en `Sort`). Los enums se pasan como `name()` (la columna es `VARCHAR`).

### 3.6 Cliente de Pacientes con Eureka

- `@Bean @LoadBalanced WebClient.Builder` + `ClienteHttpFactory.crear(builder, "http://pacientes-service")` (`hospital.clientes.pacientes.url`, ya en el yml).
- `PoliticaResiliencia.proteger(llamada, "SERVICIO_PACIENTES_NO_DISPONIBLE", "pacientes")`: timeout 2 s, 1 reintento a los 200 ms solo en errores de red, traducción 503/504. Nunca reintenta 4xx/5xx ni timeouts.
- `traceparent` lo propaga el agente OTel; no hay código de trazas.
- Pruebas: `PacientesClientTest` usa `WebClient.builder()` **sin** `@LoadBalanced` contra `MockWebServer`. `CitaFlujoIT` usa el builder balanceado real, resolviendo `pacientes-service` con el `SimpleReactiveDiscoveryClient` de Spring Cloud (`spring.cloud.discovery.client.simple.instances.pacientes-service[0].uri`), con Eureka deshabilitado.

### 3.7 Consumidor de eventos

- `ApplicationRunner` en `MensajeriaConfig` (condicionado a `hospital.rabbit.enabled`, igual que `RabbitAutoConfiguration`) → `ConsumidorEventos.iniciar("citas.atenciones.cola", ["atencion.registrada","atencion.anulada"], manejador::manejar)`.
- Semántica de commons: éxito → ack; `DuplicateKeyException` → ack; JSON inválido → DLQ inmediata; otro error → 3 intentos (backoff desde 500 ms) → `citas.atenciones.dlq`.
- Idempotencia por **estado** (no hay tabla de eventos procesados): `ATENDIDA` repetida → no-op; `CANCELADA` repetida → no-op.
- Transición de sistema `ATENDIDA → CANCELADA` solo en `cancelarPorAtencionAnulada`, sin plazo de 24 h, solo si la cita aún no ocurrió.

### 3.8 Beans y *slices*

- `@WebFluxTest` solo escanea componentes web: **no** carga `CitaService`, `config/*`, `PublicadorEventos` ni autoconfiguraciones de commons → importar `ManejadorErrores` y mockear `CitaService` con `@MockitoBean` (`org.springframework.test.context.bean.override.mockito.MockitoBean`; `@MockBean` está deprecado en 3.4+).
- `@DataR2dbcTest` incluye `SqlInitializationAutoConfiguration` (verificado) → ejecuta `schema.sql` con `spring.sql.init.mode=always` del yml; importar `SecuenciaCodigoCita` y `CitaConsultas`.
- `CitaService` requiere `PublicadorEventos`, que solo existe con `hospital.rabbit.enabled=true`: un `@SpringBootTest` con Rabbit deshabilitado fallaría (no hay ninguno en este plan).

---

## 4. Pruebas

Datos comunes:
- `Clock RELOJ = Clock.fixed(Instant.parse("2026-10-14T14:00:00Z"), ZoneId.of("America/Lima"));` → **miércoles 2026-10-14 09:00 Lima** (verificado: 2026-10-14 es miércoles; 2026-10-18 domingo; 2027-01-12 martes; 2027-01-13 miércoles).
- Profesional `MED-001`/`MED-002`; paciente `UUID.randomUUID()`; especialidad `MEDICINA_GENERAL`; motivo "Control".
- Fecha válida base: `OffsetDateTime.parse("2026-10-15T10:00:00-05:00")` (jueves).
- `src/test/java/B/...` con el mismo paquete que la clase probada. Unitarias `*Test` (Surefire), integración `*IT` (Failsafe).

### 4.1 `domain/EstadoCitaTest` (unitaria)

- `@ParameterizedTest @MethodSource` con los **25 pares** (`EstadoCita.values()` × `values()`); esperado `true` solo para los 6 de la matriz; afirma `puedeCambiarA`.
- Conteo: exactamente 6 pares permitidos.
- `esActivo`: `true` solo `PENDIENTE`, `CONFIRMADA`. `esFinal`: `true` solo `CANCELADA`, `AUSENTE`.
- `permitidoPorApi`: `PENDIENTE→CONFIRMADA` true; `CONFIRMADA→AUSENTE` true; `CONFIRMADA→ATENDIDA` false; `ATENDIDA→CANCELADA` false; `X→PENDIENTE` false.

### 4.2 `domain/ReglasAgendaTest` (unitaria, `RELOJ`, `ParametrosAgenda.porDefecto()`)

| Caso | `fechaHora` | Esperado |
|---|---|---|
| válida jueves 10:00 | `2026-10-15T10:00:00-05:00` | sin error |
| pasada | `2026-10-14T08:30:00-05:00` | 400 "La cita debe programarse en una fecha futura." |
| exactamente ahora | `2026-10-14T09:00:00-05:00` | 400 futura |
| domingo | `2026-10-18T10:00:00-05:00` | 400 "No se atiende ese día." |
| 07:30 | `2026-10-15T07:30:00-05:00` | 400 "Horario de atención: 08:00 a 17:30." |
| 17:30 válida | `2026-10-15T17:30:00-05:00` | sin error |
| 18:00 | `2026-10-15T18:00:00-05:00` | 400 horario |
| 10:15 | `2026-10-15T10:15:00-05:00` | 400 "Las citas se programan en bloques de 30 minutos." |
| con segundos | `2026-10-15T10:00:30-05:00` | 400 bloques |
| +90 días válida | `2027-01-12T10:00:00-05:00` | sin error |
| +91 días | `2027-01-13T10:00:00-05:00` | 400 "Solo se programan citas con hasta 90 días de anticipación." |
| UTC que en Lima es domingo | `2026-10-19T02:00:00Z` (= dom 21:00 Lima) | 400 "No se atiende ese día." |
| UTC válida en Lima | `2026-10-15T22:00:00Z` (= jue 17:00 Lima) | sin error |

Afirmar `ErrorNegocio` con `getStatus() == BAD_REQUEST`, `getCodigo() == "HORARIO_FUERA_DE_AGENDA"`, `getDetalle()` igual al texto.

### 4.3 `domain/PlazoCambioTest` (unitaria, `RELOJ`)

| Caso | Entrada | Esperado |
|---|---|---|
| cancelar a 25 h | `validarPlazoCambio(2026-10-15T10:00-05:00)` | OK |
| a 23 h 59 min | `validarPlazoCambio(2026-10-15T08:59-05:00)` | 409 `FUERA_DE_PLAZO` |
| exactamente 24 h | `validarPlazoCambio(2026-10-15T09:00-05:00)` | 409 `FUERA_DE_PLAZO` (M-10) |
| reprogramar a 2 h | `validarPlazoCambio(2026-10-14T11:00-05:00)` | 409 `FUERA_DE_PLAZO` |
| domingo habilitado | `ParametrosAgenda` con los 7 días; `validar(2026-10-18T10:00-05:00)` | OK |

### 4.4 `domain/CitaTest` (unitaria, `RELOJ`)

- `programar` deja `PENDIENTE`, `isNew() == true`, `version == null`, `fechaCreacion == fechaActualizacion == 2026-10-14T09:00-05:00`, `id` no nulo, `fechaHora` enviada en UTC (`2026-10-15T15:00Z`) queda con offset `-05:00` y mismo instante.
- `cambiarEstado(CONFIRMADA)` devuelve `PENDIENTE` y actualiza `fechaActualizacion` (usar un segundo reloj fijo más tarde).
- **RN-05**: cita `CANCELADA` → `cambiarEstado(CONFIRMADA)` → 409 `TRANSICION_NO_PERMITIDA` "No se puede pasar una cita de CANCELADA a CONFIRMADA."
- `validarCambioPorApi(ATENDIDA)` → 409 con el texto de la spec; `validarCambioPorApi(PENDIENTE)` → 409; desde `ATENDIDA`, `validarCambioPorApi(CANCELADA)` → 409.
- `reprogramar` desde `ATENDIDA` → 409 `TRANSICION_NO_PERMITIDA`; misma fecha (mismo instante con otro offset, p. ej. `2026-10-15T15:00Z`) → 409 `MISMA_FECHA_HORA`; desde `CONFIRMADA` → queda `PENDIENTE`, devuelve fecha anterior, profesional nuevo si llega y se mantiene si es `null`.
- `yaOcurrio`: cita `2026-10-14T08:30-05:00` → true; `2026-10-14T09:00-05:00` → true; `2026-10-15T10:00-05:00` → false.

### 4.5 `client/PacientesClientTest` (`MockWebServer`, sin Spring, sin Eureka, sin `@LoadBalanced`)

Montaje: `servidor = new MockWebServer(); servidor.start();` `cliente = new PacientesClient(ClienteHttpFactory.crear(WebClient.builder(), servidor.url("/").toString()));` `@AfterEach servidor.shutdown()`.

| Caso | Respuesta encolada | Esperado |
|---|---|---|
| 200 ACTIVO | `200`, `Content-Type: application/json`, `{"id":"<uuid>","estado":"ACTIVO","nombres":"Ana","apellidos":"Prueba"}` | `PacienteResumen(id, "ACTIVO")`; `takeRequest().getPath()` = `/api/pacientes/<uuid>` |
| 404 | `404` | `ErrorNegocio` 404 `PACIENTE_NO_ENCONTRADO`; `getRequestCount() == 1` (no reintenta) |
| 500 | `500` (y un `200` encolado que no debe consumirse) | `ErrorNegocio` 503 `SERVICIO_PACIENTES_NO_DISPONIBLE`; `getRequestCount() == 1` |
| lento | `200` con `setHeadersDelay(3, TimeUnit.SECONDS)` | `ErrorNegocio` 504 `SERVICIO_PACIENTES_NO_DISPONIBLE` (tarda ~2 s) |
| error de conexión | guardar `url`, `servidor.shutdown()`, construir el cliente con `WebClient.builder().filter((req, next) -> { contador.incrementAndGet(); return next.exchange(req); })` apuntando a esa `url` | `ErrorNegocio` 503; `contador == 2` (intento + 1 reintento). Luego `servidor = new MockWebServer()` para que `@AfterEach` no falle (patrón de `PoliticaResilienciaTest` de commons) |

Usar `StepVerifier.create(...).expectErrorSatisfies(...).verify(Duration.ofSeconds(10))`.

### 4.6 `service/CitaServiceTest` (`StepVerifier` + Mockito)

Montaje: `@ExtendWith(MockitoExtension.class)`; `@Mock CitaRepository, CitaConsultas, SecuenciaCodigoCita, PacientesClient, PublicadorEventos`; reales: `ReglasAgenda(RELOJ, porDefecto())`, `EventosCita(new ObjectMapper(), RELOJ)`. `when(publicador.publicar(any(), any())).thenReturn(Mono.empty())` (con `lenient()` si algún caso no publica). `save` → `thenAnswer(inv -> Mono.just(inv.getArgument(0)))`. Ayudante `citaEn(EstadoCita estado, OffsetDateTime fechaHora)` que usa `Cita.programar` + `cambiarEstado` (p. ej. `ATENDIDA` = `CONFIRMADA` → `ATENDIDA`) y `marcarComoExistente()`. `ArgumentCaptor<EventoEnvelope>` + `ArgumentCaptor<String>` para la routing key.

Casos (spec 03 §8 + complementos):
1. Programar OK: paciente `ACTIVO`, sin choques, secuencia `7` → respuesta `PENDIENTE`, `codigo == "CIT-20261014-000007"`; publica `cita.creada` con `estadoAnterior null`, `estadoNuevo "PENDIENTE"`, `origen "citas-service"`, `tipoRecurso "CITA"`, `datos.fechaHora == "2026-10-15T10:00:00-05:00"`, `datos.especialidadNombre == "Medicina General"`, sin campos de datos personales.
2. Agenda inválida (domingo) → 400 `HORARIO_FUERA_DE_AGENDA`; `verify(pacientes, never()).obtener(any())`, `verifyNoInteractions(repositorio, secuencia, publicador)`.
3. Paciente `INACTIVO` → 422 `PACIENTE_INACTIVO`; no guarda.
4. Paciente 404 (`Mono.error(ErrorNegocio 404)`) → se propaga 404 `PACIENTE_NO_ENCONTRADO`.
5. Choque de profesional (`existeActivaDeProfesional → true`) → 409 `CITA_CHOQUE_AGENDA` con detalle que contiene `MED-001`; `verify(repositorio, never()).save(any())`; no pide secuencia.
6. Choque de paciente → 409 "El paciente ya tiene una cita en ese horario."
7. `save` lanza `DuplicateKeyException("... unique constraint \"ux_cita_profesional_horario\"")` → 409 `CITA_CHOQUE_AGENDA` "El horario ya fue ocupado."; no publica.
8. `save` lanza `DuplicateKeyException("... \"cita_pkey\"")` → se propaga `DuplicateKeyException` (no 409).
9. Confirmar `PENDIENTE` (cita 2026-10-15 10:00) → publica `cita.confirmada` con anterior `PENDIENTE`, nuevo `CONFIRMADA`, `datos.motivo`.
10. Pedir `ATENDIDA` por API → 409 `TRANSICION_NO_PERMITIDA` "Una cita pasa a ATENDIDA solo al registrar su atención."; no guarda.
11. Confirmar una `CANCELADA` → 409 `TRANSICION_NO_PERMITIDA` (RN-05).
12. `save` lanza `OptimisticLockingFailureException` al confirmar → 409 `CONFLICTO_CONCURRENTE`; no publica.
13. Cancelar por API a menos de 24 h (cita `2026-10-15T08:30-05:00`, `PENDIENTE`) → 409 `FUERA_DE_PLAZO`; no guarda.
14. `obtener` inexistente → 404 `CITA_NO_ENCONTRADA`.
15. Reprogramar `CONFIRMADA` (2026-10-16 10:00) a 2026-10-16 11:30 → `PENDIENTE`; publica `cita.reprogramada` con `datos.fechaHoraAnterior == "2026-10-16T10:00:00-05:00"`, `profesionalAnterior`; `verifyNoInteractions(pacientes)`.
16. `marcarAtendida` sobre `CONFIRMADA` → `ATENDIDA`, publica `cita.atendida` con `datos.atencionId`.
17. `marcarAtendida` sobre `ATENDIDA` → completa vacío, **no** guarda ni publica (idempotente).
18. `marcarAtendida` sobre `CANCELADA` → error `ErrorNegocio` 409; no publica.
19. `marcarAtendida` con cita inexistente → error.
20. `cancelarPorAtencionAnulada` sobre `ATENDIDA` futura (2026-10-15 10:00) → `CANCELADA`; publica `cita.cancelada` con `estadoAnterior "ATENDIDA"`, `datos.motivo == "Atención anulada"`, `datos.atencionId`.
21. `cancelarPorAtencionAnulada` sobre `ATENDIDA` pasada (2026-10-14 08:30) → completa sin guardar ni publicar.
22. `cancelarPorAtencionAnulada` sobre `CANCELADA` → completa sin guardar ni publicar.

### 4.7 `api/CitaControllerTest` (`@WebFluxTest`)

```java
@WebFluxTest(controllers = CitaController.class,
        properties = {"hospital.rabbit.enabled=false", "eureka.client.enabled=false"})
@Import(ManejadorErrores.class)
class CitaControllerTest {
    @Autowired WebTestClient cliente;
    @MockitoBean CitaService servicio;
```

| Caso | Petición | Esperado |
|---|---|---|
| POST OK | cuerpo del ejemplo de la spec §9 | `201`, `Location: /api/citas/<id>`, `$.estado == "PENDIENTE"`, `$.especialidadNombre` |
| `profesionalCodigo` inválido | `"profesionalCodigo":"med-1"` | `400`, `application/problem+json`, `$.codigo == "VALIDACION_FALLIDA"`, `$.errores[0].campo == "profesionalCodigo"`; `verifyNoInteractions(servicio)` |
| `especialidad` desconocida | `"especialidad":"ASTROLOGIA"` | `400 VALIDACION_FALLIDA` |
| GET inexistente | servicio → `Mono.error(ErrorNegocio 404 CITA_NO_ENCONTRADA)` | `404`, `$.codigo == "CITA_NO_ENCONTRADA"` |
| GET id no UUID | `/api/citas/abc` | `400 VALIDACION_FALLIDA` |
| PATCH prohibido | servicio → `ErrorNegocio 409 TRANSICION_NO_PERMITIDA` | `409`, `$.codigo == "TRANSICION_NO_PERMITIDA"` |
| PATCH sin `estado` | `{}` | `400`, `$.errores[0].campo == "estado"` |
| listado | `GET /api/citas?fecha=2026-10-15&pagina=0&tamano=10`, servicio `listar → Flux.just(r)`, `contar → Mono.just(1L)` | `200`, cabecera `X-Total-Count: 1`, arreglo de 1; verificar con `ArgumentCaptor<FiltroCitas>` que `fecha` llegó |
| `tamano=500` | `GET /api/citas?tamano=500` | `400 VALIDACION_FALLIDA` |

### 4.8 `persistence/CitaRepositoryIT` (PostgreSQL)

```java
@DataR2dbcTest
@Testcontainers
@ActiveProfiles("test")
@Import({SecuenciaCodigoCita.class, CitaConsultas.class})
class CitaRepositoryIT {
    @Container @ServiceConnection
    static PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16");
    @Autowired CitaRepository repositorio; @Autowired SecuenciaCodigoCita secuencia;
    @Autowired CitaConsultas consultas; @Autowired DatabaseClient db;
    @BeforeEach void limpiar() { db.sql("DELETE FROM cita").then().block(); }
```

(`.block()` está permitido en pruebas.) Casos:
1. `schema.sql` aplicado: insertar y leer una cita; `version == 0` tras el insert; `fechaHora` leída `isEqual` a la escrita y con offset `-05:00`.
2. Dos citas **activas** mismo profesional y misma hora (pacientes distintos) → `DuplicateKeyException` cuyo mensaje contiene `ux_cita_profesional_horario`.
3. Si la primera está `CANCELADA` (leerla, `cambiarEstado(CANCELADA)`, `save`) → la segunda se inserta.
4. Mismo paciente, misma hora, profesionales distintos → `DuplicateKeyException` con `ux_cita_paciente_horario`.
5. Mismo profesional y mismo instante expresado con otro offset (`2026-10-15T15:00Z`) → también choca.
6. Secuencia: dos `siguiente()` consecutivos → el segundo = primero + 1.
7. `@Version`: leer la misma cita dos veces (`findById` ×2), confirmar ambas instancias; el primer `save` OK (`version == 1`); el segundo → `OptimisticLockingFailureException`.
8. Existencias: `existeActivaDeProfesional` true para cita activa y false tras cancelarla; idem `existeActivaDePaciente`.
9. `CitaConsultas`: citas en `2026-10-15T08:00-05:00`, `2026-10-15T23:30-05:00` (= 16 oct UTC) y `2026-10-16T08:00-05:00` → filtro `fecha=2026-10-15` devuelve las **dos** primeras en orden, `contar == 2`; filtro por `estado` y por `profesionalCodigo`; paginación `tamano=1, pagina=1` devuelve la segunda.

### 4.9 `messaging/AtencionRegistradaConsumerIT` (PostgreSQL + RabbitMQ)

```java
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@Testcontainers
@ActiveProfiles("test")
@DirtiesContext
class AtencionRegistradaConsumerIT {
    @Container @ServiceConnection static PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16");
    @Container static RabbitMQContainer RABBIT = new RabbitMQContainer("rabbitmq:3.13-management");

    @DynamicPropertySource
    static void rabbit(DynamicPropertyRegistry r) {
        r.add("hospital.rabbit.host", RABBIT::getHost);
        r.add("hospital.rabbit.port", RABBIT::getAmqpPort);
        r.add("hospital.rabbit.username", RABBIT::getAdminUsername);
        r.add("hospital.rabbit.password", RABBIT::getAdminPassword);
    }

    @TestConfiguration
    static class RelojFijo { @Bean Clock reloj() { return RELOJ; } } // reemplaza al de commons (@ConditionalOnMissingBean)
```

- Conexión AMQP directa (`com.rabbitmq.client.ConnectionFactory`) en `@BeforeAll`: declarar exchange `hospital.eventos` (`topic`, durable), cola de prueba `prueba.citas.eventos` enlazada a `cita.atendida` y `cita.cancelada`.
- Antes de publicar: `await()` hasta que `queueDeclarePassive("citas.atenciones.cola").getConsumerCount() >= 1` (el consumidor ya declaró cola y *bindings*). `@BeforeEach`: `queuePurge` de la cola de prueba y de `citas.atenciones.dlq` (si existe).
- Publicar con el bean `PublicadorEventos`: `EventoEnvelope.nuevo(RELOJ, "atencion.registrada", "atenciones-service", "ATENCION", atencionId, null, "REGISTRADA", datos)` con `datos = {"atencionId": "...", "citaId": "...", "pacienteId": "...", "area": "CONSULTA_EXTERNA"}` (campo extra para probar tolerancia).
- Datos: insertar con `CitaRepository` una cita `CONFIRMADA` en `2026-10-15T10:00-05:00` (`Cita.programar` + `cambiarEstado(CONFIRMADA)` + `save`).

Casos:
1. `atencion.registrada` → en ≤ 10 s la cita queda `ATENDIDA` y llega **un** `cita.atendida` a la cola de prueba con `recursoId == citaId`, `estadoAnterior "CONFIRMADA"`, `datos.atencionId`.
2. Publicar **el mismo** sobre (mismo `eventId`) dos veces → tras esperar ~2 s, la cola de prueba tiene **un solo** `cita.atendida`.
3. Luego `atencion.anulada` para esa cita (futura respecto a `RELOJ`) → `CANCELADA` y un `cita.cancelada` con `datos.atencionId` y `datos.motivo == "Atención anulada"`.
4. Cita `CANCELADA` + `atencion.registrada` → en ≤ 20 s `citas.atenciones.dlq` tiene 1 mensaje; la cita sigue `CANCELADA`; no hay `cita.atendida`.

### 4.10 `CitaFlujoIT` (PostgreSQL + RabbitMQ + `MockWebServer` como Pacientes) — paquete `B`

```java
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
@ActiveProfiles("test")
@DirtiesContext
class CitaFlujoIT {
    // mismos contenedores, @DynamicPropertySource de Rabbit y Clock fijo que 4.9
    static MockWebServer PACIENTES = iniciar();   // arranca en inicializador estático
    // en @DynamicPropertySource además:
    //   r.add("spring.cloud.discovery.client.simple.instances.pacientes-service[0].uri",
    //         () -> "http://localhost:" + PACIENTES.getPort());
    @Autowired WebTestClient cliente;
    @AfterAll static void cerrar() throws IOException { PACIENTES.shutdown(); }
```

- Cola de prueba enlazada a `cita.confirmada` (conexión AMQP directa, como en 4.9).
- Encolar en `PACIENTES` `200 {"id":"<pacienteId>","estado":"ACTIVO"}` (`Content-Type: application/json`).
- `POST /api/citas` (fecha `2026-10-15T10:00:00-05:00`) → `201`, `Location`, `codigo` empieza con `CIT-20261014-`. `PACIENTES.takeRequest().getPath()` = `/api/pacientes/<pacienteId>` (prueba que el nombre `pacientes-service` se resolvió por el *load balancer*).
- `GET /api/citas/{id}` → `200`, `fechaHora == "2026-10-15T10:00:00-05:00"` (registro y consulta de cita).
- `PATCH /api/citas/{id}/estado` `{"estado":"CONFIRMADA","motivo":"Confirmada por teléfono"}` → `200`, `estado == "CONFIRMADA"`.
- En ≤ 10 s la cola de prueba recibe el sobre: `tipo "cita.confirmada"`, `origen "citas-service"`, `tipoRecurso "CITA"`, `recursoId == id`, `estadoAnterior "PENDIENTE"`, `estadoNuevo "CONFIRMADA"`, `datos.codigo`, `datos.pacienteId`, `datos.especialidad "MEDICINA_GENERAL"`, `datos.especialidadNombre "Medicina General"`, `datos.fechaHora "2026-10-15T10:00:00-05:00"`, `datos.profesionalCodigo "MED-001"`, `datos.motivo "Confirmada por teléfono"`.
- **Plan B** si el *simple discovery* no resolviera (síntoma: 503 `SERVICIO_PACIENTES_NO_DISPONIBLE` en el POST): agregar en el test un `@TestConfiguration` con `@Bean @Primary PacientesClient` construido con `ClienteHttpFactory.crear(WebClient.builder(), "http://localhost:" + PACIENTES.getPort())`, y documentarlo en el informe.

---

## 5. Cómo ejecutar y verificar

Desde `/home/cazabache/hospital-vida-salud`:

```bash
# Paso a paso (ver tabla §1). Unitarias sin Docker:
./mvnw -q -pl citas-service test -Dtest='EstadoCitaTest,ReglasAgendaTest,PlazoCambioTest,CitaTest' -Dsurefire.failIfNoSpecifiedTests=false

# Verificación completa (unitarias + IT con Testcontainers):
sg docker -c "./mvnw -q -pl citas-service verify"

# Para depurar: sin -q, y revisar
#   citas-service/target/surefire-reports/  y  citas-service/target/failsafe-reports/
```

- **No** usar `-am` (commons ya instalado). Si commons cambiara, no es tarea de este plan.
- El repositorio **no es git**: las revisiones de la spec con `git grep` se hacen con `grep`:

```bash
grep -rn "\.block()" citas-service/src/main/java          # debe salir vacío
grep -rn "\.subscribe(" citas-service/src/main/java       # debe salir vacío (la única está en commons)
grep -rn "@Autowired" citas-service/src/main/java         # vacío (constructor injection)
grep -rln "lombok\|java.sql\|jakarta.persistence" citas-service/src/main/java   # vacío
```

- Verificación manual opcional (si la infraestructura está arriba; **no** es requisito para terminar): arrancar con `CITAS_DB_PASSWORD`/`RABBIT_PASSWORD` del `infra/.env` y comprobar `http://localhost:8082/swagger-ui.html` y `/actuator/health`.

---

## 6. Trampas conocidas

| # | Trampa | Cómo evitarla |
|---|---|---|
| T-1 | `@Version` + `Persistable`: el `INSERT` deja `nuevo = true` en el objeto devuelto | `.doOnNext(Cita::marcarComoExistente)` tras insertar; nunca re-guardar sin releer |
| T-2 | Campos `final` o `version` primitivo en `Cita` | Campos no finales, `Long version`; el `@PersistenceCreator` con nombres de parámetro **idénticos** a los campos (compilación con `-parameters`, ya en el padre) |
| T-3 | `@Transient` equivocado | `org.springframework.data.annotation.Transient`, no `java.beans.Transient` ni `jakarta.persistence` |
| T-4 | Jackson convierte el `OffsetDateTime` de entrada a UTC; el driver devuelve el offset de la sesión | Normalizar con `ZonaHospital.aLima` al validar, al construir/leer la entidad y al responder; en pruebas comparar con `isEqual` |
| T-5 | `@ServiceConnection` con R2DBC sin `org.testcontainers:r2dbc` | Paso 0 lo agrega (M-16) |
| T-6 | `@WebFluxTest` no carga autoconfiguraciones de commons | `@Import(ManejadorErrores.class)` y `@MockitoBean CitaService`; propiedades `hospital.rabbit.enabled=false`, `eureka.client.enabled=false` |
| T-7 | Poner `@EnableConfigurationProperties`/`@ConfigurationPropertiesScan` en `CitasServiceApplication` | Solo en `config/AgendaConfig` |
| T-8 | Definir el `WebClient.Builder` balanceado retira el builder de Boot | Aplicar `WebClientCustomizer` en el bean (§2.4) |
| T-9 | Llamadas a colaboradores evaluadas al **ensamblar** la cadena (`.then(secuencia.siguiente())`) | Usar `Mono.defer(() -> ...)` o lambdas de `flatMap`; si no, `verify(never())` falla y hay efectos antes de validar |
| T-10 | Lanzar `ErrorNegocio` fuera de la cadena reactiva en el servicio | Envolver validaciones síncronas en `Mono.fromRunnable/fromCallable` o dentro de `flatMap` |
| T-11 | `DuplicateKeyException` del `codigo`/PK traducida como choque de agenda | Distinguir por nombre de índice (§3.3) |
| T-12 | YAML 1.1 interpreta `17:30` sin comillas como número sexagesimal (1050) | Valores de hora **entre comillas** en el yml |
| T-13 | Variables `AGENDA_*` no se enlazan solas a `hospital.agenda.*` | Placeholders explícitos en el yml (§2.0) |
| T-14 | Publicar en el IT antes de que el consumidor declare cola y *bindings* (mensaje no enrutable, se pierde) | Esperar `getConsumerCount() >= 1` en `citas.atenciones.cola` |
| T-15 | Contextos de prueba cacheados con consumidores reintentando contra contenedores ya detenidos (ruido, puertos) | `@DirtiesContext` en las IT con RabbitMQ |
| T-16 | `marcarAtendida` traduciendo errores o tragándolos | Dejar que el error llegue a `ConsumidorEventos` (reintentos + DLQ); idempotencia solo por estado |
| T-17 | `Criteria` con nombres de columna mezclados con propiedades | Usar siempre nombres de propiedad Java (`fechaHora`, `pacienteId`) y `Sort.by("fechaHora", "id")` |
| T-18 | Comentarios con `;` en `schema.sql` | El separador es `;`; no usarlo en comentarios |
| T-19 | Mockito estricto: *stubs* no usados fallan la prueba | `lenient()` en *stubs* compartidos (p. ej. `publicador.publicar`) |
| T-20 | Registrar datos personales | Logs solo con ids, códigos y estados; el `motivo` tampoco se registra |
| T-21 | `PoliticaResiliencia.proteger` sobre una cadena "caliente" | La llamada debe ser fría (`webClient...bodyToMono`) para que el reintento repita la petición |
| T-22 | MockWebServer lento retiene el `shutdown` | Normal (≤ 3 s); no bajar el retraso por debajo de 2 s o el caso 504 deja de probar el timeout |

---

## 7. Criterios de terminado

- [ ] Existen todos los archivos de §2 con los paquetes y firmas indicados; no se modificó nada fuera de `citas-service/` (salvo que el informe lo justifique).
- [ ] `sg docker -c "./mvnw -q -pl citas-service verify"` termina en verde: Surefire ejecuta `EstadoCitaTest`, `ReglasAgendaTest`, `PlazoCambioTest`, `CitaTest`, `PacientesClientTest`, `CitaServiceTest`, `CitaControllerTest`; Failsafe ejecuta `CitaRepositoryIT`, `AtencionRegistradaConsumerIT`, `CitaFlujoIT`.
- [ ] Todos los casos de §4 implementados (los de la spec 03 §8 como mínimo).
- [ ] Las revisiones de `grep` de §5 salen vacías (sin `.block()`, `.subscribe(`, `@Autowired`, Lombok, JDBC/JPA en `src/main`).
- [ ] Respuestas de error en `application/problem+json` con los códigos de la spec 03 §6.2.
- [ ] Eventos con el sobre de commons, `origen = "citas-service"`, `tipoRecurso = "CITA"`, `datos` comunes + adicionales de la spec 03 §7.1, fechas con `-05:00` y segundos.
- [ ] Consumidor: cola `citas.atenciones.cola` con *bindings* `atencion.registrada` y `atencion.anulada`, DLQ `citas.atenciones.dlq`, idempotente.
- [ ] `application.yml` con `hospital.agenda.*` configurables por `AGENDA_DIAS_ATENCION`, `AGENDA_HORA_INICIO`, `AGENDA_HORA_ULTIMO_INICIO`.
- [ ] Informe final del ejecutor: pasos completados, desviaciones (p. ej. si se usó el plan B de `CitaFlujoIT`) y salida del `verify`.
