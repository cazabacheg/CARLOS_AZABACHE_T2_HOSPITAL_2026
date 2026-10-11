# Spec 01 — Esqueleto del proyecto, infraestructura, Eureka Server y API Gateway

> Depende de: spec 00. Cubre rúbrica: Gateway (1.5), Eureka (1.5) y parte de CI (2).
> Orden de construcción: este bloque va **primero** (documento §15.1, pasos 1–2).

## 1. Alcance

1. Proyecto padre Maven y Maven Wrapper.
2. Infraestructura local (`infra/`): PostgreSQL y RabbitMQ en Docker Compose.
3. `eureka-server`.
4. `api-gateway`.
5. Workflow de CI (`.github/workflows/ci.yml`).
6. Scripts de apoyo (`scripts/`).
7. Observabilidad: contenedores Jaeger y Seq, y arranque con el agente OTel (detalle en la spec 08).

`hospital-commons` y los servicios de negocio se especifican en las specs 00 y 02–07.

## 2. Proyecto padre y Maven Wrapper

**`pom.xml` raíz** (`pe.edu.cibertec.vidasalud:hospital-vida-salud-parent:1.0.0-SNAPSHOT`, `packaging=pom`):

* `parent`: `spring-boot-starter-parent:3.5.16`.
* `properties`: `java.version=21`, `spring-cloud.version=2025.0.3`, `springdoc.version=2.8.17`, `reactor-rabbitmq.version=1.5.6`.
* `dependencyManagement`: BOM `spring-cloud-dependencies:${spring-cloud.version}` (`scope=import`, `type=pom`) y versiones de `springdoc-openapi-starter-webflux-ui`, `reactor-rabbitmq` y `hospital-commons` (`${project.version}`).
* `modules`, en este orden: `hospital-commons`, `eureka-server`, `api-gateway`, `pacientes-service`, `citas-service`, `atenciones-service`, `pagos-service`, `notificaciones-service`, `auditoria-service`. (Los módulos se agregan al padre **cuando se crean**, para que el CI no falle por módulos inexistentes.)
* `build/pluginManagement` y `build/plugins`:
  * `maven-surefire-plugin`: ejecuta `**/*Test.java`.
  * `maven-failsafe-plugin`: goals `integration-test` y `verify`, ejecuta `**/*IT.java`.
  * `maven-compiler-plugin`: `release=21`, `-parameters`.

**Maven Wrapper:** se genera con `mvn -N wrapper:wrapper -Dmaven=3.9.9` (el Maven 3.8.7 del sistema solo se usa para esto). Se versionan `mvnw`, `mvnw.cmd`, `.mvn/wrapper/maven-wrapper.properties`. El `mvnw` debe tener permiso de ejecución (`git update-index --chmod=+x mvnw`), necesario en el CI. Para que `.mvn/wrapper/maven-wrapper.jar` no quede ignorado, `.gitignore` ya incluye la excepción `!.mvn/wrapper/maven-wrapper.jar` (si el wrapper usa el modo "only-script", no hay jar).

## 3. Infraestructura local

### 3.1 `infra/docker-compose.yml`

```yaml
name: hospital-vida-salud
services:
  postgres:
    image: postgres:16
    container_name: hvs-postgres
    environment:
      POSTGRES_USER: ${POSTGRES_ADMIN_USER}
      POSTGRES_PASSWORD: ${POSTGRES_ADMIN_PASSWORD}
      PACIENTES_DB_PASSWORD: ${PACIENTES_DB_PASSWORD}
      CITAS_DB_PASSWORD: ${CITAS_DB_PASSWORD}
      ATENCIONES_DB_PASSWORD: ${ATENCIONES_DB_PASSWORD}
      PAGOS_DB_PASSWORD: ${PAGOS_DB_PASSWORD}
      NOTIFICACIONES_DB_PASSWORD: ${NOTIFICACIONES_DB_PASSWORD}
      AUDITORIA_DB_PASSWORD: ${AUDITORIA_DB_PASSWORD}
    ports:
      - "127.0.0.1:5433:5432"
    volumes:
      - hvs-pgdata:/var/lib/postgresql/data
      - ./postgres/init:/docker-entrypoint-initdb.d:ro
    healthcheck:
      test: ["CMD-SHELL", "pg_isready -U ${POSTGRES_ADMIN_USER}"]
      interval: 5s
      timeout: 3s
      retries: 10
  rabbitmq:
    image: rabbitmq:3.13-management
    container_name: hvs-rabbitmq
    environment:
      RABBITMQ_DEFAULT_USER: ${RABBIT_USER}
      RABBITMQ_DEFAULT_PASS: ${RABBIT_PASSWORD}
    ports:
      - "127.0.0.1:5672:5672"
      - "127.0.0.1:15672:15672"
    volumes:
      - hvs-rabbitdata:/var/lib/rabbitmq
    healthcheck:
      test: ["CMD", "rabbitmq-diagnostics", "-q", "ping"]
      interval: 10s
      timeout: 5s
      retries: 10
  # Observabilidad (spec 08)
  jaeger:
    image: jaegertracing/jaeger:2.21.0
    container_name: hvs-jaeger
    ports:
      - "127.0.0.1:16686:16686"
      - "127.0.0.1:4318:4318"
  seq:
    image: datalust/seq:2026.1.17114
    container_name: hvs-seq
    environment:
      ACCEPT_EULA: "Y"
      SEQ_FIRSTRUN_ADMINPASSWORD: ${SEQ_ADMIN_PASSWORD}
    ports:
      - "127.0.0.1:8090:80"
      - "127.0.0.1:5341:5341"
    volumes:
      - hvs-seqdata:/data
volumes:
  hvs-pgdata:
  hvs-rabbitdata:
  hvs-seqdata:
```

* Los puertos solo escuchan en `127.0.0.1` (no se exponen a la red local).
* El puerto host de PostgreSQL es **5433** porque el 5432 lo usa el PostgreSQL nativo de la máquina.

### 3.2 `infra/postgres/init/01-bases.sh`

Se ejecuta una sola vez (al inicializar el volumen). Para cada servicio en `pacientes citas atenciones pagos notificaciones auditoria`:

1. Lee `<SVC>_DB_PASSWORD` de las variables de entorno del contenedor.
2. `CREATE ROLE <svc>_user LOGIN PASSWORD '<pwd>'` y `CREATE DATABASE <svc>_db OWNER <svc>_user`.
3. `REVOKE CONNECT ON DATABASE <svc>_db FROM PUBLIC` y `GRANT CONNECT ... TO <svc>_user`: ningún usuario puede entrar a la base de otro servicio.

Se usa `psql -v ON_ERROR_STOP=1` y la contraseña se pasa con `-v pwd=...` y `format('%L')` (nunca interpolada directamente en SQL). El script usa `set -euo pipefail` y **no imprime contraseñas**.

> Nota: cambiar una contraseña en `infra/.env` después de la primera ejecución no la cambia en el volumen existente. Para reiniciar de cero: `docker compose down -v`.

### 3.3 `infra/.env.example`

```dotenv
# Copiar a infra/.env (ignorado por git) y cambiar los valores.
POSTGRES_ADMIN_USER=postgres_admin
POSTGRES_ADMIN_PASSWORD=cambiar-esto
PACIENTES_DB_PASSWORD=cambiar-esto
CITAS_DB_PASSWORD=cambiar-esto
ATENCIONES_DB_PASSWORD=cambiar-esto
PAGOS_DB_PASSWORD=cambiar-esto
NOTIFICACIONES_DB_PASSWORD=cambiar-esto
AUDITORIA_DB_PASSWORD=cambiar-esto
RABBIT_USER=hospital
RABBIT_PASSWORD=cambiar-esto
SEQ_ADMIN_PASSWORD=cambiar-esto
```

El `.env` real lo genera `scripts/preparar-entorno.sh` con contraseñas aleatorias (`openssl rand -hex 16`) si no existe; está en `.gitignore`.

## 4. `eureka-server`

| Aspecto | Valor |
|---|---|
| Puerto | 8761 |
| Dependencias | `spring-cloud-starter-netflix-eureka-server`, `spring-boot-starter-actuator`; test: `spring-boot-starter-test` |
| Clase principal | `pe.edu.cibertec.vidasalud.eureka.EurekaServerApplication` con `@SpringBootApplication` y `@EnableEurekaServer` |
| Pila web | Servlet (Tomcat). El servidor Eureka de Netflix **no es reactivo**; la regla de "todo reactivo" (documento §7) aplica a los servicios de negocio y al Gateway, no a esta pieza de infraestructura. Se declara en el documento técnico |

**`application.yml`**

```yaml
server:
  port: 8761
spring:
  application:
    name: eureka-server
eureka:
  instance:
    hostname: localhost
  client:
    register-with-eureka: false
    fetch-registry: false
    service-url:
      defaultZone: http://localhost:8761/eureka
  server:
    enable-self-preservation: false        # solo desarrollo/demostración
    eviction-interval-timer-in-ms: 5000
management:
  endpoints:
    web:
      exposure:
        include: health,info
```

* Eureka es el mecanismo **real** de descubrimiento de la solución (no un simulacro): el Gateway y todos los clientes resuelven instancias por nombre a través de él. Lo único "de demostración" son estos dos ajustes de tiempos.
* `enable-self-preservation: false` y la evicción cada 5 s hacen que, en la demostración, una instancia detenida desaparezca del registro rápido. Se documenta que en producción se deja activado.
* **Panel:** `http://localhost:8761` = evidencia de rúbrica.

**Pruebas (`EurekaServerApplicationTest`)**

1. `@SpringBootTest(webEnvironment = RANDOM_PORT)`: el contexto arranca.
2. `GET /eureka/apps` responde `200`.
3. `GET /actuator/health` responde `200` con `status=UP`.

## 5. `api-gateway`

| Aspecto | Valor |
|---|---|
| Puerto | 8080 |
| Dependencias | `spring-cloud-starter-gateway-server-webflux`, `spring-cloud-starter-netflix-eureka-client`, `spring-cloud-starter-loadbalancer`, `spring-boot-starter-actuator`; test: `spring-boot-starter-test`, `okhttp3:mockwebserver` |
| Clase principal | `pe.edu.cibertec.vidasalud.gateway.ApiGatewayApplication` |
| Depende de `hospital-commons` | **No** |

> El artefacto nuevo `spring-cloud-starter-gateway-server-webflux` (Gateway 4.3.x) reemplaza al antiguo `spring-cloud-starter-gateway`. Su prefijo de propiedades es `spring.cloud.gateway.server.webflux.*`. Se verifica al construir; si el prefijo no se reconoce, se revisa la documentación de la versión.

### 5.1 `application.yml`

```yaml
server:
  port: 8080
spring:
  application:
    name: api-gateway
  cloud:
    gateway:
      server:
        webflux:
          httpclient:
            connect-timeout: 2000       # ms
            response-timeout: 10s
          routes:
            - id: pacientes
              uri: lb://pacientes-service
              predicates: [ "Path=/api/pacientes/**" ]
            - id: citas
              uri: lb://citas-service
              predicates: [ "Path=/api/citas/**" ]
            - id: atenciones
              uri: lb://atenciones-service
              predicates: [ "Path=/api/atenciones/**" ]
            - id: pagos
              uri: lb://pagos-service
              predicates: [ "Path=/api/pagos/**" ]
            - id: notificaciones
              uri: lb://notificaciones-service
              predicates: [ "Path=/api/notificaciones/**" ]
            - id: auditoria
              uri: lb://auditoria-service
              predicates: [ "Path=/api/auditoria/**" ]
eureka:
  client:
    service-url:
      defaultZone: ${EUREKA_URL:http://localhost:8761/eureka}
    registry-fetch-interval-seconds: 5
  instance:
    lease-renewal-interval-in-seconds: 5
    lease-expiration-duration-in-seconds: 10
management:
  endpoints:
    web:
      exposure:
        include: health,info,metrics,gateway
  endpoint:
    health:
      show-details: always
logging:
  pattern:
    level: "%5p [${spring.application.name:},%X{trace_id:-},%X{span_id:-}]"
```

* **Sin `discovery.locator.enabled`**: las rutas son explícitas (documento §5.2); si un servicio se registra en Eureka, **no** queda expuesto automáticamente.
* Los servicios publican ya sus rutas bajo `/api/<recurso>`, por lo que no hace falta `StripPrefix` ni reescritura.
* `lease-renewal-interval` y `registry-fetch-interval` bajos son para demostración: la baja de una instancia se refleja en unos segundos. Se repiten en los clientes Eureka de cada servicio (spec 00 no los fija; se declara en cada `application.yml`).
* El endpoint de actuator `gateway` (`/actuator/gateway/routes`) lista las rutas configuradas: **evidencia** de enrutamiento. Solo desarrollo; se documenta que en producción no se expone.

### 5.2 Componentes propios (mínimos)

| Clase | Qué hace |
|---|---|
| `TraceIdRespuestaFilter` (`WebFilter`) | Copia de la de `hospital-commons` (spec 08 §5): agrega `X-Trace-Id` a la respuesta con el `traceId` de OpenTelemetry. La propagación entre servicios (`traceparent`) la hace el agente OTel; no hay correlación casera |
| `RegistroPeticionesFilter` (`GlobalFilter`) | Al terminar, registra `INFO`: método, ruta (sin parámetros de consulta), estado HTTP y milisegundos (el `trace_id` va en el patrón de log). **No** registra cabeceras ni cuerpos |
| `ManejadorErroresGateway` (`ErrorWebExceptionHandler`, `@Order(-2)`) | Convierte los errores del Gateway al formato `application/problem+json` de la spec 00 §4.1: sin instancia disponible (`503`) → `codigo=SERVICIO_NO_DISPONIBLE`; tiempo de espera agotado (`504`) → `SERVICIO_TIEMPO_AGOTADO`; ruta inexistente (`404`) → `RUTA_NO_ENCONTRADA`. Sin stack trace |

El Gateway no valida autenticación (mejora documentada §14 del documento técnico).

### 5.3 Pruebas del Gateway

Sin necesidad de Eureka ni de los servicios reales:

| Prueba | Verificación |
|---|---|
| `RutasConfiguradasTest` | Carga `RouteDefinitionLocator` y comprueba que existen **6 rutas**, cada una con URI `lb://<servicio>` y predicado `Path` correcto |
| `EnrutamientoTest` | `@SpringBootTest(RANDOM_PORT)` con `eureka.client.enabled=false` y las rutas sobreescritas (por `@DynamicPropertySource`) para apuntar a un `MockWebServer`: `GET /api/pacientes/123` llega a `/api/pacientes/123` del mock; el cuerpo y el estado vuelven intactos |
| `ErrorSinInstanciaTest` | Ruta a un servicio inexistente → `503` con `application/problem+json` y `codigo=SERVICIO_NO_DISPONIBLE` |
| `RutaInexistenteTest` | `GET /api/otra-cosa` → `404 RUTA_NO_ENCONTRADA` |

## 6. Workflow de CI (`.github/workflows/ci.yml`)

Exactamente el del documento §11.2:

```yaml
name: Integracion Continua - Hospital Vida Salud

on:
  push:
    branches: [ main ]
  pull_request:
    branches: [ main ]

jobs:
  build-test:
    runs-on: ubuntu-latest
    steps:
      - name: Descargar código
        uses: actions/checkout@v4
      - name: Configurar Java 21
        uses: actions/setup-java@v4
        with:
          distribution: temurin
          java-version: '21'
          cache: maven
      - name: Compilar y ejecutar pruebas (todos los módulos)
        run: ./mvnw --batch-mode verify
      - name: Publicar reportes de pruebas
        if: always()
        uses: actions/upload-artifact@v4
        with:
          name: reportes-pruebas
          path: |
            **/target/surefire-reports
            **/target/failsafe-reports
```

* Se crea cuando exista el padre y al menos un módulo con pruebas, para que el primer *push* ya pueda pasar en verde.
* Se verifica localmente con `./mvnw --batch-mode verify` antes de subir.
* Si el repositorio todavía no tiene remoto, el archivo se versiona igualmente; la ejecución real en GitHub se hace al final (documento §15.1, paso 10).

## 7. Scripts de apoyo (`scripts/`)

| Script | Función |
|---|---|
| `preparar-entorno.sh` | Si no existe `infra/.env`, lo crea desde `.env.example` con contraseñas aleatorias. Nunca sobrescribe uno existente. Imprime solo "creado"/"ya existe", **sin mostrar** contraseñas |
| `entorno-dev.sh` | Debe usarse con `source`. Carga `infra/.env` y exporta las variables que esperan los servicios (`PACIENTES_DB_PASSWORD`, …, `RABBIT_USER`, `RABBIT_PASSWORD`, `DB_HOST`, `DB_PORT`) |
| `descargar-otel-agent.sh` | Descarga el agente OpenTelemetry 2.31.1 a `tools/` (ignorado por git) y verifica su SHA-256 fijado (spec 08 §6) |
| `iniciar-todo.sh` | Con la infraestructura arriba y el proyecto compilado (`./mvnw -DskipTests package`), arranca en este orden, esperando salud de cada uno (`/actuator/health`): Eureka → Gateway → pacientes → citas → atenciones → pagos → notificaciones → auditoría. Cada uno en segundo plano con `java -javaagent:tools/opentelemetry-javaagent-2.31.1.jar -jar` y las variables `OTEL_*` de la spec 08 §4 (sin el agente, arranca igual y avisa); salida en `logs/<servicio>.log`, PID en `logs/<servicio>.pid` |
| `detener-todo.sh` | Detiene los procesos de los PID guardados (SIGTERM), en orden inverso |

`logs/` y `tools/` están en `.gitignore`. Estos scripts no son parte de la evaluación pero facilitan la demostración y la reproducción.

## 8. Criterios de aceptación

- [ ] `./mvnw --batch-mode verify` pasa en la raíz con los módulos existentes en ese momento.
- [ ] `scripts/preparar-entorno.sh && docker compose -f infra/docker-compose.yml up -d` deja `hvs-postgres` y `hvs-rabbitmq` en estado `healthy`, y `hvs-jaeger` y `hvs-seq` en ejecución.
- [ ] Desde el host: `psql -h 127.0.0.1 -p 5433 -U pacientes_user pacientes_db` conecta; con la misma credencial a `citas_db` **falla** por falta de `CONNECT`.
- [ ] Panel RabbitMQ en `http://localhost:15672` accesible con el usuario del `.env`.
- [ ] Panel Eureka en `http://localhost:8761` muestra `API-GATEWAY` al arrancar el Gateway.
- [ ] `GET http://localhost:8080/actuator/gateway/routes` lista las 6 rutas.
- [ ] Con Eureka y un servicio ficticio registrado, el Gateway lo alcanza por nombre (se comprueba cuando exista `pacientes-service`).
- [ ] Una ruta cuyo servicio no está registrado devuelve `503 SERVICIO_NO_DISPONIBLE` en formato `problem+json`.
- [ ] `docker compose down -v` y una nueva subida recrean las bases desde cero.
- [ ] Ningún archivo versionado contiene contraseñas (verificación: `git grep -i "password" -- ':!docs'` solo muestra nombres de variables).

## 9. Decisiones (todas aprobadas)

| # | Decisión tomada | Alternativa |
|---|---|---|
| D-6 | **Aprobado:** Eureka con autopreservación desactivada y renovación rápida, **solo para la demo** | Valores por defecto de Eureka: la baja de una instancia tarda ~90 s en verse |
| D-7 | **Aprobado:** Gateway con filtros propios mínimos (`X-Trace-Id`, registro, errores en formato único; la correlación la hace OTel, spec 08) | Solo rutas: menos código, pero errores en formato distinto al resto |
| D-8 | **Aprobado:** Actuator `gateway` expuesto como evidencia de rutas | No exponerlo y evidenciar solo con peticiones |
| D-9 | **Aprobado:** Scripts `iniciar-todo.sh`/`detener-todo.sh` para la demo | Arrancar cada servicio manualmente |
| D-10 | **Aprobado:** PostgreSQL en el puerto host 5433, solo en `127.0.0.1` | Puerto 5432 (colisiona con el PostgreSQL nativo) |
