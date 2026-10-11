#!/usr/bin/env node
// Genera las evidencias de docs/evidencias/ (capturas PNG y salidas de texto).
// Requisitos: sistema completo arriba (scripts/iniciar-todo.sh + docker compose), infra/.env,
// puppeteer en tools/mermaid/node_modules, Newman en tools/newman, Chrome en /usr/bin/google-chrome.
// Uso: node scripts/capturar-evidencias.js [--sin-caida] [--solo=02]   (--sin-caida omite detener/reiniciar pacientes-service)
// Las contrasenas se leen de infra/.env y nunca se imprimen ni se guardan.
'use strict';
const fs = require('fs');
const path = require('path');
const { execSync, execFileSync } = require('child_process');

const RAIZ = path.resolve(__dirname, '..');
const OUT = path.join(RAIZ, 'docs', 'evidencias');
const puppeteer = require(path.join(RAIZ, 'tools/mermaid/node_modules/puppeteer'));
const SIN_CAIDA = process.argv.includes('--sin-caida');
const dormir = (ms) => new Promise((r) => setTimeout(r, ms));
const resultados = [];

// ---------- entorno (infra/.env) ----------
const env = {};
for (const linea of fs.readFileSync(path.join(RAIZ, 'infra/.env'), 'utf8').split('\n')) {
  const m = linea.match(/^\s*([A-Z0-9_]+)=(.*)$/);
  if (m) env[m[1]] = m[2].replace(/^["']|["']$/g, '');
}

// ---------- utilidades ----------
const esc = (s) => s.replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;');
function htmlPre(titulo, texto) {
  return `<html><body style="margin:0;background:#0f172a;font-family:monospace">
<div style="background:#1e293b;color:#7dd3fc;padding:14px 24px;font:bold 20px sans-serif">${esc(titulo)}</div>
<pre style="margin:0;padding:20px 24px;color:#e2e8f0;font-size:15px;line-height:1.35;white-space:pre-wrap;word-break:break-all">${esc(texto)}</pre></body></html>`;
}
async function nuevaPagina(browser, alto = 900) {
  const p = await browser.newPage();
  await p.setViewport({ width: 1500, height: alto, deviceScaleFactor: 1 });
  return p;
}
async function capturarHtml(browser, archivo, titulo, texto) {
  const p = await nuevaPagina(browser);
  await p.setContent(htmlPre(titulo, texto));
  await p.screenshot({ path: path.join(OUT, archivo), fullPage: true });
  await p.close();
}
async function capturarUrl(browser, archivo, url, { espera, extraMs = 1500, alto = 900 } = {}) {
  const p = await nuevaPagina(browser, alto);
  await p.goto(url, { waitUntil: 'networkidle2', timeout: 60000 });
  if (espera) await p.waitForSelector(espera, { timeout: 30000 });
  await dormir(extraMs);
  await p.screenshot({ path: path.join(OUT, archivo), fullPage: true });
  await p.close();
}
const SOLO = (process.argv.find((a) => a.startsWith('--solo=')) || '').slice(7);
async function paso(nombre, fn) {
  if (SOLO && !nombre.startsWith(SOLO)) return;
  try { await fn(); resultados.push(`OK    ${nombre}`); console.log(`OK    ${nombre}`); }
  catch (e) { resultados.push(`FALLO ${nombre}: ${e.message}`); console.log(`FALLO ${nombre}: ${e.message}`); }
}
async function http(metodo, url, cuerpo) {
  const r = await fetch(url, {
    method: metodo,
    headers: cuerpo ? { 'Content-Type': 'application/json' } : {},
    body: cuerpo ? JSON.stringify(cuerpo) : undefined,
  });
  const texto = await r.text();
  return { status: r.status, traceId: r.headers.get('x-trace-id'), texto };
}
function pg(db, sql) {
  return execFileSync('sudo', ['docker', 'exec', '-i', '-e', 'PGPASSWORD=' + env.POSTGRES_ADMIN_PASSWORD,
    'hvs-postgres', 'psql', '-U', env.POSTGRES_ADMIN_USER, '-d', db, '-P', 'pager=off', '-c', sql],
  { encoding: 'utf8', stdio: ['ignore', 'pipe', 'pipe'] });
}
function pgError(db, sql) {
  try { return pg(db, sql); } catch (e) { return String(e.stderr || e.message); }
}
async function capturarTraza(browser, archivo, traceId) {
  const p = await nuevaPagina(browser, 1000);
  await p.goto(`http://localhost:16686/trace/${traceId}`, { waitUntil: 'networkidle2', timeout: 60000 });
  await p.waitForSelector('[data-test-id="span-view"], .span-row, [class*="SpanBar"]', { timeout: 30000 }).catch(() => {});
  await dormir(3000);
  await p.screenshot({ path: path.join(OUT, archivo), fullPage: true });
  await p.close();
}

(async () => {
  fs.mkdirSync(OUT, { recursive: true });
  const browser = await puppeteer.launch({
    headless: 'new', executablePath: '/usr/bin/google-chrome', args: ['--no-sandbox'],
  });
  const ctx = {};

  // 01 Eureka
  await paso('01 Eureka', async () => {
    await capturarUrl(browser, '01-eureka-instancias-registradas.png', 'http://localhost:8761', { espera: '#instances', extraMs: 1500 });
  });

  // 02 rutas Gateway
  await paso('02 Gateway rutas', async () => {
    const r = await http('GET', 'http://localhost:8080/actuator/gateway/routes');
    let rutas = null;
    try { rutas = JSON.parse(r.texto); } catch (e) { /* no es JSON */ }
    if (Array.isArray(rutas)) {
      const resumen = rutas.map((x) => x.route_id + ' -> ' + x.uri).join('\n');
      await capturarHtml(browser, '02-gateway-rutas.png', 'GET http://localhost:8080/actuator/gateway/routes (' + rutas.length + ' rutas)',
        resumen + '\n\n' + JSON.stringify(rutas, null, 2));
    } else {
      // Alternativa: el endpoint no esta publicado; se muestra su respuesta real y las rutas configuradas.
      const yml = fs.readFileSync(path.join(RAIZ, 'api-gateway/src/main/resources/application.yml'), 'utf8');
      const rutasYml = yml.split('\n').filter((l) => /- id:|uri: lb:|Path=/.test(l)).join('\n');
      let vivo = '';
      for (const ruta of ['pacientes', 'citas', 'atenciones', 'pagos', 'notificaciones', 'auditoria']) {
        const x = await http('GET', 'http://localhost:8080/api/' + ruta + '?tamano=1');
        vivo += `GET /api/${ruta} via Gateway -> HTTP ${x.status}\n`;
      }
      await capturarHtml(browser, '02-gateway-rutas.png', 'Rutas del Gateway (GET /actuator/gateway/routes -> HTTP ' + r.status + ')',
        'Respuesta del endpoint: ' + r.texto.slice(0, 200) + '\n\nRutas lb:// configuradas (api-gateway/src/main/resources/application.yml):\n' + rutasYml + '\n\nVerificacion en vivo:\n' + vivo);
    }
  });

  // 03 salud citas
  await paso('03 Salud citas-service', async () => {
    const r = await http('GET', 'http://localhost:8082/actuator/health');
    await capturarHtml(browser, '03-salud-citas-service.png', 'GET http://localhost:8082/actuator/health (HTTP ' + r.status + ')',
      JSON.stringify(JSON.parse(r.texto), null, 2));
  });

  // 04 Newman
  await paso('04 Newman', async () => {
    let salida;
    // La coleccion depende de la hora (cita en el siguiente bloque de 30 min): se reintenta si falla.
    for (let intento = 1; intento <= 3; intento++) {
      try {
        salida = execSync('cd tools/newman && ./node_modules/.bin/newman run ../../docs/postman/hospital-vida-salud.postman_collection.json -e ../../docs/postman/hospital-vida-salud.postman_environment.json --delay-request 150 --color off 2>&1',
          { cwd: RAIZ, encoding: 'utf8', maxBuffer: 64 * 1024 * 1024, shell: '/bin/bash', env: { ...process.env, NO_COLOR: '1', FORCE_COLOR: '0' } });
        break;
      } catch (e) { salida = (e.stdout || '') + (e.stderr || ''); await dormir(60000); }
    }
    salida = salida.replace(/\x1b\[[0-9;]*m/g, '');
    fs.writeFileSync(path.join(OUT, 'newman-salida.txt'), salida);
    const lineas = salida.split('\n');
    const i = lineas.findIndex((l) => /^┌─+┬/.test(l) || l.includes('executed'));
    const resumen = lineas.slice(Math.max(0, i - 1)).join('\n');
    await capturarHtml(browser, '04-newman-resumen.png', 'Newman: resumen de la coleccion hospital-vida-salud', resumen);
  });

  // 05 Swagger
  await paso('05 Swagger pacientes', () => capturarUrl(browser, '05a-swagger-pacientes.png', 'http://localhost:8081/swagger-ui.html', { espera: '.opblock', extraMs: 1500 }));
  await paso('05 Swagger citas', () => capturarUrl(browser, '05b-swagger-citas.png', 'http://localhost:8082/swagger-ui.html', { espera: '.opblock', extraMs: 1500 }));

  // 06 trazas Jaeger
  const pacientes = JSON.parse((await http('GET', 'http://localhost:8080/api/pacientes?tamano=30')).texto);
  let pac = pacientes[0];
  const sufijo = String(Date.now()).slice(-3);
  const fecha = new Date(Date.now() + 5 * 86400000).toLocaleDateString('en-CA', { timeZone: 'America/Lima' });
  await paso('06a crear cita + traza', async () => {
    let r;
    for (const cand of pacientes) {
      r = await http('POST', 'http://localhost:8080/api/citas', {
        pacienteId: cand.id, especialidad: 'PEDIATRIA', fechaHora: `${fecha}T15:00:00-05:00`,
        profesionalCodigo: 'PED-9' + sufijo.slice(0, 2), motivo: 'Evidencia de trazabilidad',
      });
      if (r.status !== 409) { pac = cand; break; }
    }
    if (r.status !== 201) throw new Error('POST /api/citas devolvio ' + r.status + ' ' + r.texto.slice(0, 200));
    ctx.cita = JSON.parse(r.texto);
    ctx.trace6a = r.traceId;
    await dormir(7000);
    await capturarTraza(browser, '06a-jaeger-traza-crear-cita.png', ctx.trace6a);
  });
  await paso('06b confirmar cita + traza', async () => {
    const r = await http('PATCH', `http://localhost:8080/api/citas/${ctx.cita.id}/estado`, { estado: 'CONFIRMADA' });
    if (r.status !== 200) throw new Error('PATCH devolvio ' + r.status + ' ' + r.texto.slice(0, 200));
    ctx.trace6b = r.traceId;
    await dormir(8000);
    await capturarTraza(browser, '06b-jaeger-traza-confirmar-cita-rabbitmq.png', ctx.trace6b);
  });
  if (!SIN_CAIDA) {
    await paso('06c caida de pacientes-service + traza', async () => {
      try {
        execSync('kill $(cat logs/pacientes-service.pid)', { cwd: RAIZ, shell: '/bin/bash' });
        await dormir(4000);
        const r = await http('POST', 'http://localhost:8080/api/citas', {
          pacienteId: pac.id, especialidad: 'PEDIATRIA', fechaHora: `${fecha}T16:00:00-05:00`,
          profesionalCodigo: 'PED-8' + sufijo.slice(0, 2), motivo: 'Evidencia de fallo',
        });
        ctx.status6c = r.status;
        ctx.trace6c = r.traceId;
        if (r.status !== 503) throw new Error('se esperaba 503 y fue ' + r.status);
        await dormir(7000);
        await capturarTraza(browser, '06c-jaeger-traza-fallo-pacientes-503.png', ctx.trace6c);
      } finally {
        execSync('rm -f logs/pacientes-service.pid; scripts/iniciar-todo.sh pacientes-service >/dev/null 2>&1', { cwd: RAIZ, shell: '/bin/bash', timeout: 240000 });
        const s = await http('GET', 'http://localhost:8081/actuator/health');
        ctx.saludPacientes = s.status + ' ' + (JSON.parse(s.texto).status);
        console.log('pacientes-service tras reinicio: ' + ctx.saludPacientes);
      }
    });
  }

  // 07 Seq
  await paso('07 Seq', async () => {
    const p = await nuevaPagina(browser, 1000);
    await p.goto('http://localhost:8090/#/login', { waitUntil: 'networkidle2', timeout: 60000 });
    await dormir(2000);
    const campoUsuario = await p.$('input[type="text"], input[name="username"], input[placeholder*="ser" i]');
    const campoClave = await p.$('input[type="password"]');
    if (campoUsuario && campoClave) {
      await campoUsuario.type('admin');
      await campoClave.type(env.SEQ_ADMIN_PASSWORD);
      await p.keyboard.press('Enter');
      await dormir(4000);
    }
    const filtro = encodeURIComponent(`@TraceId = '${ctx.trace6b}'`);
    await p.goto(`http://localhost:8090/#/events?filter=${filtro}&range=1h`, { waitUntil: 'networkidle2', timeout: 60000 });
    await dormir(3000);
    // Seq no ejecuta el filtro de la URL por si solo: se enfoca la caja y se confirma con Enter.
    await p.click('.expression-input, input[type="text"], textarea, [contenteditable="true"]').catch(() => {});
    await p.keyboard.press('End');
    await p.keyboard.press('Enter');
    await dormir(5000);
    await p.screenshot({ path: path.join(OUT, '07-seq-logs-por-traceid.png'), fullPage: true });
    await p.close();
  });

  // 08 RabbitMQ
  await paso('08 RabbitMQ', async () => {
    const p = await nuevaPagina(browser, 1100);
    await p.goto('http://localhost:15672/#/', { waitUntil: 'networkidle2', timeout: 60000 });
    await p.waitForSelector('input[name="username"]', { timeout: 15000 }).catch(() => {});
    const u = await p.$('input[name="username"]');
    if (u) {
      await u.type(env.RABBIT_USER);
      await (await p.$('input[name="password"]')).type(env.RABBIT_PASSWORD);
      await p.keyboard.press('Enter');
      await dormir(3000);
    }
    await p.goto('http://localhost:15672/#/exchanges', { waitUntil: 'networkidle2' });
    await p.waitForSelector('table.list', { timeout: 20000 });
    await dormir(2500);
    await p.evaluate(() => {
      document.querySelectorAll('table.list tr').forEach((tr) => {
        if (/^\s*\/?\s*hospital\.eventos\s*topic/.test(tr.textContent.replace(/\s+/g, ' '))) {
          tr.style.outline = '3px solid #e11d48'; tr.style.background = '#fef08a';
        }
      });
    });
    await p.screenshot({ path: path.join(OUT, '08a-rabbitmq-exchanges.png'), fullPage: true });
    await p.goto('http://localhost:15672/#/queues', { waitUntil: 'networkidle2' });
    await p.waitForSelector('table.list', { timeout: 20000 });
    await dormir(7000);
    // Totales acumulados por cola (API de gestion) para evidenciar los mensajes procesados.
    const auth = 'Basic ' + Buffer.from(env.RABBIT_USER + ':' + env.RABBIT_PASSWORD).toString('base64');
    const colas = await (await fetch('http://localhost:15672/api/queues', { headers: { Authorization: auth } })).json();
    const filas = colas.map((q) => {
      const st = q.message_stats || {};
      return [q.name, q.messages, st.publish || 0, st.deliver_get || 0, st.ack || 0];
    });
    await p.evaluate((f) => {
      const d = document.createElement('div');
      d.style.cssText = 'margin:20px 38px;font:14px sans-serif';
      d.innerHTML = '<b>Totales acumulados por cola (GET /api/queues)</b><table border="1" cellpadding="5" style="border-collapse:collapse;margin-top:6px">'
        + '<tr style="background:#eee"><th>Cola</th><th>En cola</th><th>Publicados</th><th>Entregados</th><th>Confirmados (ack)</th></tr>'
        + f.map((r) => '<tr><td>' + r.join('</td><td>') + '</td></tr>').join('') + '</table>';
      document.getElementById('main').appendChild(d);
    }, filas);
    await p.screenshot({ path: path.join(OUT, '08b-rabbitmq-colas.png'), fullPage: true });
    await p.close();
  });

  // 09 PostgreSQL
  await paso('09 PostgreSQL', async () => {
    const consultas = [
      ['pacientes_db', 'paciente', 'SELECT id, estado, fecha_nacimiento, fecha_registro FROM paciente ORDER BY fecha_registro DESC LIMIT 3'],
      ['citas_db', 'cita', 'SELECT id, codigo, estado, especialidad, profesional_codigo, fecha_hora FROM cita ORDER BY fecha_creacion DESC LIMIT 5'],
      ['atenciones_db', 'atencion', 'SELECT id, codigo, estado, area, fecha_atencion FROM atencion ORDER BY fecha_creacion DESC LIMIT 5'],
      ['pagos_db', 'pago', 'SELECT id, estado, moneda, monto, medio_pago, fecha_pago FROM pago ORDER BY fecha_pago DESC LIMIT 5'],
      ['notificaciones_db', 'notificacion', 'SELECT id, tipo_evento, canal, fecha_envio FROM notificacion ORDER BY fecha_envio DESC LIMIT 5'],
      ['auditoria_db', 'evento_auditoria', 'SELECT id, tipo_evento, tipo_recurso, estado_anterior, estado_nuevo, fecha_evento FROM evento_auditoria ORDER BY fecha_evento DESC LIMIT 5'],
    ];
    let salida = `Evidencia de persistencia (PostgreSQL ${new Date().toISOString()})\n`;
    for (const [db, tabla, sql] of consultas) {
      salida += `\n===== ${db}.${tabla} =====\n` + pg(db, `SELECT count(*) AS total_${tabla} FROM ${tabla}`) + pg(db, sql);
    }
    salida += '\n===== Trigger de inmutabilidad (auditoria_db.evento_auditoria) =====\n'
      + "$ UPDATE evento_auditoria SET estado_nuevo='X';\n"
      + pgError('auditoria_db', "UPDATE evento_auditoria SET estado_nuevo='X'")
      + '\n$ SELECT count(*) FROM evento_auditoria WHERE estado_nuevo = \'X\';  -- debe ser 0\n'
      + pg('auditoria_db', "SELECT count(*) FROM evento_auditoria WHERE estado_nuevo = 'X'");
    fs.writeFileSync(path.join(OUT, 'postgres-salida.txt'), salida);
    await capturarHtml(browser, '09-postgres-persistencia.png', 'PostgreSQL: conteos, filas y trigger de inmutabilidad', salida);
  });

  await browser.close();

  // 10 resumen de pruebas
  await paso('10 Resumen de pruebas', async () => {
    const modulos = {};
    for (const mod of fs.readdirSync(RAIZ)) {
      for (const [dir, tipo] of [['surefire-reports', 'unitarias'], ['failsafe-reports', 'integracion']]) {
        const d = path.join(RAIZ, mod, 'target', dir);
        if (!fs.existsSync(d)) continue;
        for (const f of fs.readdirSync(d).filter((x) => x.endsWith('.txt'))) {
          const m = fs.readFileSync(path.join(d, f), 'utf8').match(/Tests run: (\d+), Failures: (\d+), Errors: (\d+), Skipped: (\d+)/);
          if (!m) continue;
          const o = (modulos[mod] ||= { unitarias: [0, 0, 0, 0], integracion: [0, 0, 0, 0] });
          m.slice(1).forEach((v, i) => { o[tipo][i] += Number(v); });
        }
      }
    }
    const fila = (n, u, i) => `${n.padEnd(24)}${String(u[0] + i[0]).padStart(8)}${String(u[1] + i[1]).padStart(9)}${String(u[2] + i[2]).padStart(8)}${String(u[3] + i[3]).padStart(9)}   (unit ${u[0]}, IT ${i[0]})`;
    let txt = 'Resumen de pruebas (surefire + failsafe, ./mvnw verify)\n\n'
      + 'Modulo'.padEnd(24) + 'Pruebas'.padStart(8) + 'Fallos'.padStart(9) + 'Errores'.padStart(8) + 'Omitidas'.padStart(9) + '\n' + '-'.repeat(90) + '\n';
    const tot = { unitarias: [0, 0, 0, 0], integracion: [0, 0, 0, 0] };
    for (const mod of Object.keys(modulos).sort()) {
      txt += fila(mod, modulos[mod].unitarias, modulos[mod].integracion) + '\n';
      for (const t of ['unitarias', 'integracion']) modulos[mod][t].forEach((v, i) => { tot[t][i] += v; });
    }
    txt += '-'.repeat(90) + '\n' + fila('TOTAL', tot.unitarias, tot.integracion) + '\n';
    fs.writeFileSync(path.join(OUT, 'resumen-pruebas.txt'), txt);
    console.log(txt);
  });

  console.log('\nTrace IDs: 6a=' + ctx.trace6a + ' 6b=' + ctx.trace6b + ' 6c=' + ctx.trace6c + ' (HTTP ' + ctx.status6c + ')');
  console.log(resultados.join('\n'));
})();
