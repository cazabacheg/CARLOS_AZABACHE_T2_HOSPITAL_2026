#!/usr/bin/env python3
"""Genera docs/postman/*.json (coleccion y entorno) con el flujo funcional del enunciado."""
import json, os

RAIZ = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))

PRE_LIMA = r"""
// Utilidades de hora de Lima (UTC-5, sin horario de verano)
function lima(ms) {
  const d = new Date(ms - 5 * 3600 * 1000);
  const p = (n) => String(n).padStart(2, '0');
  return `${d.getUTCFullYear()}-${p(d.getUTCMonth()+1)}-${p(d.getUTCDate())}T${p(d.getUTCHours())}:${p(d.getUTCMinutes())}:00-05:00`;
}
function diaSemanaLima(ms) { return new Date(ms - 5 * 3600 * 1000).getUTCDay(); } // 0 = domingo
function citaLejana(diasMin, hora, minuto) {
  // primer dia habil (lunes a sabado) a >= diasMin dias, a la hora indicada, hora de Lima
  let ms = Date.now() + diasMin * 24 * 3600 * 1000;
  while (diaSemanaLima(ms) === 0) { ms += 24 * 3600 * 1000; }
  const d = new Date(ms - 5 * 3600 * 1000);
  const base = Date.UTC(d.getUTCFullYear(), d.getUTCMonth(), d.getUTCDate(), hora, minuto) + 5 * 3600 * 1000;
  return base;
}
"""

def evento(codigo, estado, extra=""):
    return {"listen": "test", "script": {"type": "text/javascript", "exec": (
        f"pm.test('Estado {estado}', () => pm.response.to.have.status({estado}));\n" + extra).split("\n")}}

def pre(codigo):
    return {"listen": "prerequest", "script": {"type": "text/javascript", "exec": codigo.split("\n")}}

def peticion(nombre, metodo, ruta, cuerpo=None, estado=200, test="", prerequest=None, cabeceras=None, descripcion=""):
    hs = [{"key": "Content-Type", "value": "application/json"}] if cuerpo is not None else []
    for k, v in (cabeceras or {}).items():
        hs.append({"key": k, "value": v})
    req = {"method": metodo, "header": hs,
           "url": {"raw": "{{baseUrl}}" + ruta, "host": ["{{baseUrl}}"], "path": [p for p in ruta.split("?")[0].split("/") if p]}}
    if "?" in ruta:
        req["url"]["query"] = [dict(zip(("key", "value"), kv.split("=", 1))) for kv in ruta.split("?")[1].split("&")]
    if cuerpo is not None:
        req["body"] = {"mode": "raw", "raw": cuerpo, "options": {"raw": {"language": "json"}}}
    if descripcion:
        req["description"] = descripcion
    item = {"name": nombre, "request": req, "event": [evento(nombre, estado, test)]}
    if prerequest:
        item["event"].insert(0, pre(prerequest))
    return item

def guardar(var, expr):
    return f"pm.collectionVariables.set('{var}', {expr});"

def codigo_es(c):
    return f"pm.test('codigo = {c}', () => pm.expect(pm.response.json().codigo).to.eql('{c}'));"

PRE_PACIENTE = r"""
const dni = String(Math.floor(10000000 + Math.random() * 89999999)).slice(0, 8);
pm.collectionVariables.set('dni', dni);
"""

carpetas = []

# ---------------- 01 Pacientes ----------------
carpetas.append({"name": "01 Pacientes", "item": [
    peticion("Registrar paciente (valido)", "POST", "/api/pacientes",
             '{\n  "tipoDocumento": "DNI",\n  "numeroDocumento": "{{dni}}",\n  "nombres": "Ana Maria",\n  "apellidos": "Prueba Ficticia",\n  "fechaNacimiento": "1990-05-20",\n  "telefono": "999000111",\n  "correo": "ana.prueba@example.com"\n}',
             201, guardar("pacienteId", "pm.response.json().id") + "\npm.test('Location presente', () => pm.response.headers.has('Location'));",
             PRE_PACIENTE),
    peticion("Consultar paciente por id", "GET", "/api/pacientes/{{pacienteId}}", None, 200,
             "pm.test('mismo id', () => pm.expect(pm.response.json().id).to.eql(pm.collectionVariables.get('pacienteId')));"),
    peticion("Registrar paciente DUPLICADO (rechazo 409)", "POST", "/api/pacientes",
             '{\n  "tipoDocumento": "DNI",\n  "numeroDocumento": "{{dni}}",\n  "nombres": "Otra",\n  "apellidos": "Persona",\n  "fechaNacimiento": "1985-01-01"\n}',
             409, codigo_es("PACIENTE_DUPLICADO")),
    peticion("Registrar paciente con datos invalidos (400)", "POST", "/api/pacientes",
             '{\n  "tipoDocumento": "DNI",\n  "numeroDocumento": "123",\n  "nombres": "",\n  "apellidos": "X",\n  "fechaNacimiento": "2999-01-01",\n  "correo": "no-es-correo"\n}',
             400, codigo_es("VALIDACION_FALLIDA") + "\npm.test('lista de errores', () => pm.expect(pm.response.json().errores.length).to.be.above(0));"),
    peticion("Consultar paciente inexistente (404)", "GET", "/api/pacientes/00000000-0000-4000-8000-000000000000", None, 404,
             codigo_es("PACIENTE_NO_ENCONTRADO")),
    peticion("Listar pacientes (paginado)", "GET", "/api/pacientes?pagina=0&tamano=5", None, 200,
             "pm.test('X-Total-Count presente', () => pm.expect(pm.response.headers.has('X-Total-Count')).to.be.true);"),
    peticion("Actualizar datos de contacto", "PUT", "/api/pacientes/{{pacienteId}}",
             '{\n  "nombres": "Ana Maria",\n  "apellidos": "Prueba Ficticia",\n  "fechaNacimiento": "1990-05-20",\n  "telefono": "999000222",\n  "correo": "ana.nueva@example.com"\n}', 200),
    peticion("Registrar segundo paciente (para el caso inactivo)", "POST", "/api/pacientes",
             '{\n  "tipoDocumento": "DNI",\n  "numeroDocumento": "{{dni2}}",\n  "nombres": "Luis",\n  "apellidos": "Segundo Ficticio",\n  "fechaNacimiento": "1980-03-10"\n}',
             201, guardar("pacienteInactivoId", "pm.response.json().id"),
             "const d2 = String(Math.floor(10000000 + Math.random() * 89999999)).slice(0, 8);\npm.collectionVariables.set('dni2', d2);"),
    peticion("Inactivar segundo paciente", "PATCH", "/api/pacientes/{{pacienteInactivoId}}/estado",
             '{ "estado": "INACTIVO", "motivo": "Prueba" }', 200),
    peticion("Inactivar de nuevo (409 transicion no permitida)", "PATCH", "/api/pacientes/{{pacienteInactivoId}}/estado",
             '{ "estado": "INACTIVO" }', 409, codigo_es("TRANSICION_NO_PERMITIDA")),
]})

# ---------------- 02 Citas ----------------
PRE_CITA = PRE_LIMA + r"""
pm.collectionVariables.set('fechaCita', lima(citaLejana(3, 10, 0)));
pm.collectionVariables.set('fechaCitaNueva', lima(citaLejana(4, 11, 30)));
pm.collectionVariables.set('profesional', 'MED-' + String(Math.floor(100 + Math.random() * 899)));
"""
carpetas.append({"name": "02 Citas", "item": [
    peticion("Registrar cita (paciente valido) -> PENDIENTE", "POST", "/api/citas",
             '{\n  "pacienteId": "{{pacienteId}}",\n  "especialidad": "MEDICINA_GENERAL",\n  "fechaHora": "{{fechaCita}}",\n  "profesionalCodigo": "{{profesional}}",\n  "motivo": "Control administrativo"\n}',
             201, guardar("citaId", "pm.response.json().id") + "\npm.test('estado PENDIENTE', () => pm.expect(pm.response.json().estado).to.eql('PENDIENTE'));\npm.test('codigo CIT-', () => pm.expect(pm.response.json().codigo).to.match(/^CIT-\\d{8}-\\d{6}$/));\npm.test('X-Trace-Id (con agente OTel)', () => pm.expect(true).to.be.true);",
             PRE_CITA,
             descripcion="Llamada sincrona Citas -> Pacientes (Eureka). El campo motivo es administrativo: no ingresar datos clinicos."),
    peticion("Consultar cita", "GET", "/api/citas/{{citaId}}", None, 200),
    peticion("Choque de agenda: mismo profesional y hora (409)", "POST", "/api/citas",
             '{\n  "pacienteId": "{{pacienteId}}",\n  "especialidad": "MEDICINA_GENERAL",\n  "fechaHora": "{{fechaCita}}",\n  "profesionalCodigo": "{{profesional}}"\n}',
             409, codigo_es("CITA_CHOQUE_AGENDA")),
    peticion("Cita para paciente inexistente (404)", "POST", "/api/citas",
             '{\n  "pacienteId": "00000000-0000-4000-8000-000000000000",\n  "especialidad": "PEDIATRIA",\n  "fechaHora": "{{fechaCita}}",\n  "profesionalCodigo": "PED-001"\n}',
             404, codigo_es("PACIENTE_NO_ENCONTRADO")),
    peticion("Cita para paciente INACTIVO (422)", "POST", "/api/citas",
             '{\n  "pacienteId": "{{pacienteInactivoId}}",\n  "especialidad": "PEDIATRIA",\n  "fechaHora": "{{fechaCita}}",\n  "profesionalCodigo": "PED-002"\n}',
             422, codigo_es("PACIENTE_INACTIVO")),
    peticion("Cita en horario invalido (400)", "POST", "/api/citas",
             '{\n  "pacienteId": "{{pacienteId}}",\n  "especialidad": "MEDICINA_GENERAL",\n  "fechaHora": "2020-01-06T10:00:00-05:00",\n  "profesionalCodigo": "MED-001"\n}',
             400, codigo_es("HORARIO_FUERA_DE_AGENDA")),
    peticion("Listar citas del paciente", "GET", "/api/citas?pacienteId={{pacienteId}}", None, 200),
    peticion("Confirmar cita -> evento cita.confirmada", "PATCH", "/api/citas/{{citaId}}/estado",
             '{ "estado": "CONFIRMADA", "motivo": "Confirmada por telefono" }', 200,
             "pm.test('CONFIRMADA', () => pm.expect(pm.response.json().estado).to.eql('CONFIRMADA'));",
             descripcion="Publica cita.confirmada: llega a Notificaciones y a Auditoria (RabbitMQ)."),
    peticion("Transicion no permitida: confirmar de nuevo (409)", "PATCH", "/api/citas/{{citaId}}/estado",
             '{ "estado": "CONFIRMADA" }', 409, codigo_es("TRANSICION_NO_PERMITIDA")),
    peticion("ATENDIDA no se pone por PATCH (409)", "PATCH", "/api/citas/{{citaId}}/estado",
             '{ "estado": "ATENDIDA" }', 409, codigo_es("TRANSICION_NO_PERMITIDA")),
    peticion("Reprogramar cita -> vuelve a PENDIENTE", "PUT", "/api/citas/{{citaId}}/reprogramar",
             '{ "fechaHora": "{{fechaCitaNueva}}" }', 200,
             "pm.test('PENDIENTE', () => pm.expect(pm.response.json().estado).to.eql('PENDIENTE'));"),
    peticion("Cancelar cita (mas de 24 h de anticipacion)", "PATCH", "/api/citas/{{citaId}}/estado",
             '{ "estado": "CANCELADA", "motivo": "Paciente no puede asistir" }', 200),
    peticion("Confirmar una cita CANCELADA (409, RN-05)", "PATCH", "/api/citas/{{citaId}}/estado",
             '{ "estado": "CONFIRMADA" }', 409, codigo_es("TRANSICION_NO_PERMITIDA")),
    peticion("Cita inexistente (404)", "GET", "/api/citas/00000000-0000-4000-8000-000000000000", None, 404,
             codigo_es("CITA_NO_ENCONTRADA")),
]})

# ---------------- 03 Atenciones ----------------
PRE_PROXIMA = PRE_LIMA + r"""
// Siguiente bloque de 30 min (a menos de 30 min de ahora): permite registrar la atencion de inmediato.
let t = Date.now() + 2 * 60 * 1000;
const bloque = 30 * 60 * 1000;
const lim = t - 5 * 3600 * 1000;
t = Math.ceil(lim / bloque) * bloque + 5 * 3600 * 1000;
pm.collectionVariables.set('fechaCitaProxima', lima(t));
pm.collectionVariables.set('profesionalProximo', 'MED-' + String(Math.floor(100 + Math.random() * 899)));
"""
carpetas.append({"name": "03 Atenciones", "item": [
    peticion("Crear cita proxima (empieza en <= 30 min)", "POST", "/api/citas",
             '{\n  "pacienteId": "{{pacienteId}}",\n  "especialidad": "CARDIOLOGIA",\n  "fechaHora": "{{fechaCitaProxima}}",\n  "profesionalCodigo": "{{profesionalProximo}}"\n}',
             201, guardar("citaProximaId", "pm.response.json().id"), PRE_PROXIMA,
             descripcion="Requiere que el horario actual caiga dentro de la agenda (lunes a sabado 08:00-17:30). Para demostrar fuera de horario, arrancar citas-service con AGENDA_DIAS_ATENCION / AGENDA_HORA_INICIO / AGENDA_HORA_ULTIMO_INICIO."),
    peticion("Atencion sobre cita PENDIENTE (409 CITA_NO_CONFIRMADA)", "POST", "/api/atenciones",
             '{ "citaId": "{{citaProximaId}}", "area": "CONSULTA_EXTERNA" }', 409, codigo_es("CITA_NO_CONFIRMADA")),
    peticion("Confirmar la cita proxima", "PATCH", "/api/citas/{{citaProximaId}}/estado",
             '{ "estado": "CONFIRMADA" }', 200),
    peticion("Atencion fuera de ventana: cita lejana (409)", "POST", "/api/atenciones",
             '{ "citaId": "{{citaId}}", "area": "CONSULTA_EXTERNA" }', 409,
             "pm.test('codigo valido', () => pm.expect(['FUERA_DE_VENTANA_ATENCION','CITA_NO_CONFIRMADA']).to.include(pm.response.json().codigo));"),
    peticion("Registrar atencion -> evento atencion.registrada", "POST", "/api/atenciones",
             '{ "citaId": "{{citaProximaId}}", "area": "CONSULTA_EXTERNA" }', 201,
             guardar("atencionId", "pm.response.json().id") + "\npm.test('REGISTRADA', () => pm.expect(pm.response.json().estado).to.eql('REGISTRADA'));"),
    peticion("Atencion duplicada (409)", "POST", "/api/atenciones",
             '{ "citaId": "{{citaProximaId}}", "area": "CONSULTA_EXTERNA" }', 409,
             "pm.test('codigo valido', () => pm.expect(['ATENCION_DUPLICADA','CITA_NO_CONFIRMADA']).to.include(pm.response.json().codigo));"),
    peticion("La cita paso a ATENDIDA (consistencia eventual)", "GET", "/api/citas/{{citaProximaId}}", None, 200,
             "pm.test('ATENDIDA', () => pm.expect(pm.response.json().estado).to.eql('ATENDIDA'));",
             prerequest="setTimeout(() => {}, 1500);",
             descripcion="Citas reacciona al evento atencion.registrada. Si aparece CONFIRMADA, repetir la peticion en un par de segundos."),
    peticion("Consultar atencion", "GET", "/api/atenciones/{{atencionId}}", None, 200),
    peticion("Listar atenciones del paciente", "GET", "/api/atenciones?pacienteId={{pacienteId}}", None, 200),
    peticion("Anular atencion (cita aun no ocurrida -> cita CANCELADA)", "PATCH", "/api/atenciones/{{atencionId}}/estado",
             '{ "estado": "ANULADA", "motivo": "Registrada por error" }', 200),
]})

# ---------------- 04 Pagos ----------------
PRE_PAGO = r"""
function uuid() { return 'xxxxxxxx-xxxx-4xxx-yxxx-xxxxxxxxxxxx'.replace(/[xy]/g, c => { const r = Math.random()*16|0; return (c === 'x' ? r : (r&0x3|0x8)).toString(16); }); }
pm.collectionVariables.set('idempotencyKey', uuid());
"""
CUERPO_PAGO = '{\n  "pacienteId": "{{pacienteId}}",\n  "citaId": "{{citaId}}",\n  "concepto": "Reserva de cita - Medicina General",\n  "monto": 80.00,\n  "medioPago": "TARJETA"\n}'
carpetas.append({"name": "04 Pagos", "item": [
    peticion("Registrar pago (monto valido)", "POST", "/api/pagos", CUERPO_PAGO, 201,
             guardar("pagoId", "pm.response.json().id") + "\n" + guardar("numeroOperacion", "pm.response.json().numeroOperacion") +
             "\npm.test('numero OP-', () => pm.expect(pm.response.json().numeroOperacion).to.match(/^OP-\\d{8}-\\d{6}$/));",
             PRE_PAGO, {"Idempotency-Key": "{{idempotencyKey}}"}),
    peticion("REINTENTO con la misma Idempotency-Key (mismo pago, sin duplicar)", "POST", "/api/pagos", CUERPO_PAGO, 201,
             "pm.test('mismo pago', () => pm.expect(pm.response.json().id).to.eql(pm.collectionVariables.get('pagoId')));\npm.test('Idempotent-Replayed', () => pm.expect(pm.response.headers.get('Idempotent-Replayed')).to.eql('true'));",
             None, {"Idempotency-Key": "{{idempotencyKey}}"}),
    peticion("Misma clave con OTRO monto (422)", "POST", "/api/pagos", CUERPO_PAGO.replace("80.00", "95.00"), 422,
             codigo_es("CLAVE_IDEMPOTENCIA_REUTILIZADA"), None, {"Idempotency-Key": "{{idempotencyKey}}"}),
    peticion("Sin cabecera Idempotency-Key (400)", "POST", "/api/pagos", CUERPO_PAGO, 400,
             codigo_es("CLAVE_IDEMPOTENCIA_REQUERIDA")),
    peticion("Monto 0 (400 MONTO_INVALIDO)", "POST", "/api/pagos", CUERPO_PAGO.replace("80.00", "0"), 400,
             codigo_es("MONTO_INVALIDO"), "pm.collectionVariables.set('idempotencyKey', 'k-' + Date.now() + '-0');",
             {"Idempotency-Key": "k-{{$timestamp}}-0000"}),
    peticion("Monto negativo (400 MONTO_INVALIDO)", "POST", "/api/pagos", CUERPO_PAGO.replace("80.00", "-10"), 400,
             codigo_es("MONTO_INVALIDO"), None, {"Idempotency-Key": "k-{{$timestamp}}-0001"}),
    peticion("Pago para paciente inexistente (404)", "POST", "/api/pagos",
             CUERPO_PAGO.replace("{{pacienteId}}", "00000000-0000-4000-8000-000000000000"), 404,
             codigo_es("PACIENTE_NO_ENCONTRADO"), None, {"Idempotency-Key": "k-{{$timestamp}}-0002"}),
    peticion("Consultar pago", "GET", "/api/pagos/{{pagoId}}", None, 200),
    peticion("Listar pagos del paciente", "GET", "/api/pagos?pacienteId={{pacienteId}}", None, 200),
    peticion("Anular pago", "PATCH", "/api/pagos/{{pagoId}}/anular", '{ "motivo": "Pago registrado por error" }', 200),
    peticion("Anular de nuevo (409 PAGO_YA_ANULADO)", "PATCH", "/api/pagos/{{pagoId}}/anular",
             '{ "motivo": "Otra vez" }', 409, codigo_es("PAGO_YA_ANULADO")),
]})

# ---------------- 05 Trazabilidad ----------------
carpetas.append({"name": "05 Notificaciones y Auditoria", "item": [
    peticion("Notificaciones del paciente (RabbitMQ -> Notificaciones)", "GET", "/api/notificaciones?pacienteId={{pacienteId}}", None, 200,
             "pm.test('hay notificaciones', () => pm.expect(pm.response.json().length).to.be.above(0));"),
    peticion("Historial de auditoria de la cita (estados y origen)", "GET", "/api/auditoria/recursos/CITA/{{citaId}}/historial", None, 200,
             "pm.test('hay eventos', () => pm.expect(pm.response.json().length).to.be.above(0));"),
    peticion("Historial de auditoria del paciente", "GET", "/api/auditoria/recursos/PACIENTE/{{pacienteId}}/historial", None, 200),
    peticion("Auditoria filtrada por tipo de evento", "GET", "/api/auditoria?tipoEvento=cita.confirmada&pagina=0&tamano=5", None, 200),
]})

coleccion = {
    "info": {"name": "Hospital Vida Salud - flujo funcional (T2)",
             "description": "Flujo del enunciado (seccion 28) via API Gateway: pacientes, citas, atenciones, pagos, notificaciones y auditoria, con casos negativos. Ejecutar las carpetas en orden con el Collection Runner.",
             "schema": "https://schema.getpostman.com/json/collection/v2.1.0/collection.json"},
    "variable": [{"key": k, "value": ""} for k in
                 ["dni", "dni2", "pacienteId", "pacienteInactivoId", "citaId", "citaProximaId", "atencionId", "pagoId",
                  "numeroOperacion", "idempotencyKey", "fechaCita", "fechaCitaNueva", "fechaCitaProxima", "profesional", "profesionalProximo"]],
    "item": carpetas,
}
entorno = {"id": "hvs-local", "name": "Hospital Vida Salud - local", "_postman_variable_scope": "environment",
           "values": [{"key": "baseUrl", "value": "http://localhost:8080", "enabled": True}]}

destino = os.path.join(RAIZ, "docs", "postman")
os.makedirs(destino, exist_ok=True)
with open(os.path.join(destino, "hospital-vida-salud.postman_collection.json"), "w", encoding="utf-8") as f:
    json.dump(coleccion, f, ensure_ascii=False, indent=2)
with open(os.path.join(destino, "hospital-vida-salud.postman_environment.json"), "w", encoding="utf-8") as f:
    json.dump(entorno, f, ensure_ascii=False, indent=2)
print("peticiones:", sum(len(c["item"]) for c in carpetas))
