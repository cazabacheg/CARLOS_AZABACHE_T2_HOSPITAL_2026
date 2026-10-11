# Guía de sustentación — Hospital Vida Salud (T2)

Preguntas probables con respuestas cortas y dónde mostrarlo. Referencias: documento técnico (`DT`), specs (`docs/specs/`).

## 1. Guion de demostración (≈ 8 minutos)

1. **Infraestructura y registro** — `docker ps` (Postgres, RabbitMQ, Jaeger, Seq) y panel de **Eureka** `:8761`: 7 instancias UP.
2. **Entrada única** — `GET :8080/actuator/gateway/routes`: 6 rutas `lb://`. Explicar que nadie usa los puertos internos.
3. **Flujo** (Postman, carpeta por carpeta, o `docs/ejemplos-curl.md`):
   registrar paciente → **duplicado 409** → cita (llamada síncrona a Pacientes) → **choque de agenda 409** → confirmar (evento) → **transición inválida 409**.
4. **RabbitMQ** — panel `:15672`: exchange `hospital.eventos`, colas `notificaciones.cola`, `auditoria.cola`; el mismo `cita.confirmada` llegó a **dos** consumidores. Mostrar `GET /api/notificaciones` y `GET /api/auditoria/recursos/CITA/{id}/historial`.
5. **Atención** — cita próxima (≤ 30 min) → registrar atención → la cita pasa a `ATENDIDA` **por evento** (consistencia eventual).
6. **Pagos** — pago válido, **monto 0 → 400**, **mismo `Idempotency-Key` → mismo pago** (`Idempotent-Replayed: true`).
7. **Resiliencia** — detener Pacientes: crear cita → **503** claro; `GET /api/citas` sigue funcionando.
8. **Observabilidad** — copiar el `X-Trace-Id` de una respuesta: **Jaeger** (árbol Gateway → Citas → Pacientes → SQL → RabbitMQ → consumidores) y **Seq** (`@TraceId = '...'`).
9. **Pruebas y CI** — `./mvnw verify` (449 pruebas) y la pestaña **Actions** de GitHub: ejecución en verde y la **fallida controlada** con su corrección.

## 2. Arquitectura y diseño

**¿Por qué microservicios aquí?** El monolito acopla módulos, impide escalar solo las citas en horas pico y obliga a desplegar todo ante un cambio (DT §1). Pero no dividí por tabla ni por capa: cada servicio es un contexto de negocio con dueño de sus datos.

**¿Cómo definiste los límites?** Pacientes = identidad; Citas = ciclo de vida de la cita y agenda; Atenciones y Pagos = procesos con reglas propias; Notificaciones y Auditoría = consumidores puros que no deben frenar ni tumbar el flujo principal (DT §3, spec por servicio).

**¿Por qué no hay claves foráneas entre servicios?** Cada servicio es dueño de su base; `cita.paciente_id` es solo un identificador validado por contrato (llamada a Pacientes). Una FK entre bases independientes sería un monolito disfrazado. Hay una instancia PostgreSQL compartida por economía académica, con **una base y un usuario por servicio** y `CONNECT` revocado a los demás (limitación declarada, DT §3.4).

**¿Para qué sirve Eureka y el Gateway?** Eureka registra instancias por nombre y el Gateway/`WebClient` resuelven `lb://pacientes-service` con balanceo del lado del cliente. Los clientes no dependen de puertos que cambian al escalar o reiniciar; además el Gateway centraliza trazas, errores y (a futuro) autenticación (DT §4.2–4.3).

**¿Por qué Eureka no es reactivo?** El servidor Netflix corre sobre Servlet; es infraestructura de registro, no procesa peticiones de negocio (DT §7.4/§7.3).

## 3. Comunicación

**¿Cuándo síncrona y cuándo asíncrona?** Síncrona si el solicitante necesita la respuesta para continuar (Citas → Pacientes: ¿existe y está activo?; Atenciones → Citas; Pagos → Pacientes). Asíncrona para efectos secundarios que no deben bloquear ni poder hacer fallar la operación (notificar, auditar) (DT §6.1).

**¿Qué pasa si Pacientes está caído?** Timeout de 2 s, un reintento solo ante error de red, y la cita **no se crea**: `503 SERVICIO_PACIENTES_NO_DISPONIBLE` (o `504` por timeout). Nunca se asume que el paciente existe. Las consultas no afectadas siguen operando. Mejora: *circuit breaker*.

**Explica el flujo de eventos.** Productor publica en el exchange `hospital.eventos` (tipo *topic*) con *routing key* `cita.confirmada`; cada consumidor tiene su cola con *bindings* (`notificaciones.cola` explícitos, `auditoria.cola` con `#`). El productor no conoce a los consumidores: agregar uno nuevo es crear una cola, sin tocar el emisor (DT §6.4).

**¿Y si un consumidor falla o el mensaje llega dos veces?** *Ack* manual tras persistir; 3 intentos con espera exponencial y luego **DLQ**; JSON corrupto va directo a la DLQ. El `event_id` es `UNIQUE`, así que un evento repetido se confirma sin duplicar (idempotencia).

**¿Se puede perder un evento?** Sí, en un caso: si el proceso muere entre el `commit` y la publicación. La solución es *Transactional Outbox*; está documentada como mejora (DT §14). Si RabbitMQ no confirma, el error se registra pero la operación de negocio no falla (decisión D-3).

**¿Cómo se actualiza la cita al registrar la atención?** Atenciones publica `atencion.registrada` y **Citas** cambia su propio estado (coreografía). Ningún servicio modifica datos ajenos.

**¿Qué carrera evitaste con las reglas de 24 h y 30 min?** "Cancelar la cita mientras se registra su atención". Se cancela solo hasta 24 h antes y se atiende desde 30 min antes: las ventanas no se cruzan, así que no hace falta una saga de compensación (DT §2.3 RN-15/16).

## 4. Reactivo y datos

**¿Qué aporta WebFlux/R2DBC?** Las esperas de E/S (BD, llamadas entre servicios, RabbitMQ) no retienen un hilo por petición: más concurrencia en horas pico con pocos hilos. Es coherente de punta a punta: controlador → servicio (`Mono`/`Flux`) → repositorio R2DBC → PostgreSQL, sin `.block()` (verificado con `grep` en todos los servicios).

**¿`Mono` vs `Flux`?** `Mono` para 0 o 1 resultado (crear, obtener, cambiar estado); `Flux` para colecciones (listados paginados).

**¿Por qué no JDBC?** Bloquearía el *event loop* de Netty; usar JDBC dentro de WebFlux y llamarlo reactivo sería incorrecto. Por la misma razón no hay Flyway: el esquema se crea con `schema.sql` al arrancar.

**¿Cómo evitas citas duplicadas aunque lleguen dos peticiones a la vez?** Consulta previa para dar un mensaje claro, pero la garantía es el **índice único parcial** (profesional + hora, y paciente + hora, solo para citas activas). La violación se traduce a `409 CITA_CHOQUE_AGENDA`. Igual con el documento del paciente.

**¿Cómo evitas pagos duplicados por un reintento?** `Idempotency-Key` obligatoria: se guarda con el pago (`UNIQUE`) junto a la huella SHA-256 del cuerpo. Mismo reintento → mismo pago; misma clave con otros datos → `422`; carrera → el `UNIQUE` frena al segundo y se devuelve el existente.

**¿Qué pasa si dos usuarios cambian la misma cita a la vez?** Bloqueo optimista (`@Version`): el segundo recibe `409 CONFLICTO_CONCURRENTE`.

**¿Cómo se garantiza que la auditoría no se altere?** Solo hay inserción (sin endpoints de modificación) y *triggers* de PostgreSQL que rechazan `UPDATE`, `DELETE` y `TRUNCATE`. Límite: el dueño de la tabla podría quitar el trigger (mejora: otro rol dueño).

## 5. Calidad, CI y seguridad

**¿Qué pruebas hay?** 449: unitarias de reglas (matriz completa de transiciones, agenda con reloj fijo, monto), de flujos reactivos (`StepVerifier`), de controlador (`WebTestClient`, errores `problem+json`) y de integración con **PostgreSQL y RabbitMQ reales** (Testcontainers). Cubren todos los casos del enunciado §24.

**¿Qué hace el CI y qué pasa si falla?** En cada `push` y `pull request` a `main`: checkout, JDK 21, `./mvnw verify` de todos los módulos. Si compila o una prueba falla, el job queda rojo, y con protección de rama el PR no se puede fusionar. Se demostró con un fallo controlado en una rama y su corrección (DT §11.4).

**¿Seguridad y datos sensibles?** Datos ficticios; contraseñas solo en `infra/.env` (ignorado por git, generado al azar); los eventos no llevan datos personales (solo ids y estados); los logs no imprimen documento, nombres ni correo; Auditoría no guarda el contenido de los eventos. No hay autenticación: mejora documentada (JWT en el Gateway).

**¿Observabilidad?** Agente de OpenTelemetry: trazas en Jaeger, logs en Seq, `X-Trace-Id` en cada respuesta, y la traza continúa a través de RabbitMQ.

## 6. Preguntas de cierre

**¿Qué limitaciones reconoces?** Sin autenticación; sin *outbox* ni *circuit breaker*; una instancia PostgreSQL compartida; Laboratorio, Hospitalización y Farmacia solo diseñados; trazas y claves de idempotencia sin retención; confirmar una cita no exige pago (DT §14).

**¿Qué harías distinto en producción?** Un clúster de base por servicio, *outbox*, *circuit breaker*, JWT en el Gateway, Eureka con valores por defecto, muestreo parcial de trazas y despliegue con imágenes Docker en un registro.

**¿Qué fue lo más difícil?** El plazo de cancelación y la ventana de atención (resolver una carrera con reglas de negocio en vez de infraestructura) y mantener todo el flujo reactivo sin bloquear (incluidos los consumidores de RabbitMQ).
