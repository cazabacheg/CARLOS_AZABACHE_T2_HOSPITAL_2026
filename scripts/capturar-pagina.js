// Uso: node scripts/capturar-pagina.js <url> <salida.png> [esperaMs] [ancho]
// Captura una pagina publica (p. ej. GitHub Actions o un pull request) con Chrome headless.
const puppeteer = require('/home/cazabache/hospital-vida-salud/tools/mermaid/node_modules/puppeteer');
(async () => {
  const [url, salida, espera = '3000', ancho = '1400'] = process.argv.slice(2);
  const browser = await puppeteer.launch({ headless: 'new', executablePath: '/usr/bin/google-chrome', args: ['--no-sandbox'] });
  const page = await browser.newPage();
  await page.setViewport({ width: Number(ancho), height: 900 });
  await page.goto(url, { waitUntil: 'networkidle2', timeout: 60000 });
  await new Promise((r) => setTimeout(r, Number(espera)));
  await page.screenshot({ path: salida, fullPage: true });
  await browser.close();
})().catch((e) => { console.error(e.message); process.exit(1); });
