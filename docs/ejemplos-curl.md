# Ejemplos con curl (flujo del enunciado, vía API Gateway)

Requisitos: sistema en ejecución (`scripts/iniciar-todo.sh`). Todas las peticiones pasan por el Gateway (`:8080`).
Datos ficticios. Equivalente a la colección de Postman en `docs/postman/`.

```bash
BASE=http://localhost:8080
JSON='Content-Type: application/json'

# 1. Registrar un paciente  -> 201 + Location
DNI=$(printf "%08d" $((RANDOM*RANDOM % 100000000)))
PACIENTE=$(curl -s -X POST $BASE/api/pacientes -H "$JSON" -d "{
  \"tipoDocumento\":\"DNI\",\"numeroDocumento\":\"$DNI\",\"nombres\":\"Ana Maria\",
  \"apellidos\":\"Prueba Ficticia\",\"fechaNacimiento\":\"1990-05-20\",\"correo\":\"ana@example.com\"}" \
  | python3 -c "import sys,json;print(json.load(sys.stdin)['id'])")

# 2. Consultar por id -> 200 ; repetir el registro -> 409 PACIENTE_DUPLICADO
curl -s $BASE/api/pacientes/$PACIENTE
curl -s -X POST $BASE/api/pacientes -H "$JSON" -d "{
  \"tipoDocumento\":\"DNI\",\"numeroDocumento\":\"$DNI\",\"nombres\":\"X\",\"apellidos\":\"Y\",\"fechaNacimiento\":\"1985-01-01\"}"

# 3. Registrar una cita (a mas de 24 h) -> 201 PENDIENTE
FECHA=$(python3 -c "
import datetime as d
t=d.datetime.now(d.timezone(d.timedelta(hours=-5)))+d.timedelta(days=3)
print(t.replace(hour=10,minute=0,second=0,microsecond=0).isoformat(timespec='seconds'))")
CITA=$(curl -s -X POST $BASE/api/citas -H "$JSON" -d "{
  \"pacienteId\":\"$PACIENTE\",\"especialidad\":\"MEDICINA_GENERAL\",\"fechaHora\":\"$FECHA\",
  \"profesionalCodigo\":\"MED-001\",\"motivo\":\"Control administrativo\"}" \
  | python3 -c "import sys,json;print(json.load(sys.stdin)['id'])")

# 4. Confirmar (publica cita.confirmada) ; confirmar de nuevo -> 409 TRANSICION_NO_PERMITIDA
curl -s -X PATCH $BASE/api/citas/$CITA/estado -H "$JSON" -d '{"estado":"CONFIRMADA"}'
curl -s -X PATCH $BASE/api/citas/$CITA/estado -H "$JSON" -d '{"estado":"CONFIRMADA"}'

# 5. Pago con Idempotency-Key: el reintento devuelve el mismo pago (Idempotent-Replayed: true)
CLAVE=$(python3 -c "import uuid;print(uuid.uuid4())")
PAGO='{"pacienteId":"'$PACIENTE'","citaId":"'$CITA'","concepto":"Reserva de cita","monto":80.00,"medioPago":"TARJETA"}'
curl -si -X POST $BASE/api/pagos -H "$JSON" -H "Idempotency-Key: $CLAVE" -d "$PAGO" | grep -iE "^HTTP|numeroOperacion"
curl -si -X POST $BASE/api/pagos -H "$JSON" -H "Idempotency-Key: $CLAVE" -d "$PAGO" | grep -iE "^HTTP|idempotent-replayed"
# monto invalido -> 400 MONTO_INVALIDO
curl -s -X POST $BASE/api/pagos -H "$JSON" -H "Idempotency-Key: $(python3 -c 'import uuid;print(uuid.uuid4())')" \
  -d '{"pacienteId":"'$PACIENTE'","concepto":"x","monto":0,"medioPago":"EFECTIVO"}'

# 6. Trazabilidad: notificaciones (RabbitMQ) y auditoria de la cita
curl -s "$BASE/api/notificaciones?pacienteId=$PACIENTE"
curl -s $BASE/api/auditoria/recursos/CITA/$CITA/historial
```
