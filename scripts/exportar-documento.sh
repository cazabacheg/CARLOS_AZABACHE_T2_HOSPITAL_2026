#!/bin/bash
# Exporta docs/documento-tecnico.md a PDF: renderiza los diagramas Mermaid a PNG,
# convierte con pandoc a HTML y usa Chrome headless para generar el PDF.
# Requiere: pandoc, google-chrome, y tools/mermaid (npm install @mermaid-js/mermaid-cli).
set -euo pipefail
raiz="$(cd "$(dirname "$0")/.." && pwd)"
salida="${raiz}/docs/build"
mkdir -p "${salida}/diagramas"
fuente="${1:-${raiz}/docs/documento-tecnico.md}"
base="$(basename "${fuente%.md}")"
mmdc="${raiz}/tools/mermaid/node_modules/.bin/mmdc"

cat > "${salida}/puppeteer.json" <<JSON
{"executablePath":"/usr/bin/google-chrome","args":["--no-sandbox"]}
JSON

python3 - "${fuente}" "${salida}" "${base}" <<'PY'
import re, sys, subprocess, os
fuente, salida, base = sys.argv[1:4]
texto = open(fuente, encoding="utf-8").read()
n = 0
def render(m):
    global n
    n += 1
    mmd = f"{salida}/diagramas/{base}-{n:02d}.mmd"
    png = f"{salida}/diagramas/{base}-{n:02d}.png"
    open(mmd, "w", encoding="utf-8").write(m.group(1))
    raiz = os.path.dirname(os.path.dirname(salida))
    subprocess.run([f"{raiz}/tools/mermaid/node_modules/.bin/mmdc", "-i", mmd, "-o", png, "-s", "2",
                    "-b", "white", "-p", f"{salida}/puppeteer.json"], check=True,
                   stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
    return f"![Diagrama {n}]({png})"
convertido = re.sub(r"```mermaid\n(.*?)```", render, texto, flags=re.S)
open(f"{salida}/{base}.render.md", "w", encoding="utf-8").write(convertido)
print(f"{n} diagramas renderizados")
PY

python3 - "${raiz}" "${salida}" <<'PY'
import base64, html, json, os, sys
raiz, salida = sys.argv[1:3]
datos = json.load(open(f"{raiz}/docs/portada.json", encoding="utf-8"))
for campo in ("subtitulo", "descripcion"):
    datos[campo] = os.environ.get("PORTADA_" + campo.upper(), datos[campo])
if "PENDIENTE" in datos["alumno"]:
    print("AVISO: docs/portada.json tiene el nombre del alumno pendiente", file=sys.stderr)
logo = base64.b64encode(open(f"{raiz}/docs/assets/cibertec-logo.svg", "rb").read()).decode()
e = lambda k: html.escape(datos[k])
filas = [("Alumno", "alumno"), ("Código", "codigo"), ("Curso", "curso"),
         ("Profesor", "profesor"), ("Semestre", "semestre"), ("Fecha", "fecha")]
tabla = "\n".join(f"<tr><th>{n}</th><td>{e(k)}</td></tr>" for n, k in filas)
open(f"{salida}/portada.html", "w", encoding="utf-8").write(f"""
<section class="portada">
  <img class="logo" src="data:image/svg+xml;base64,{logo}" alt="Cibertec">
  <p class="institucion">INSTITUTO SUPERIOR TECNOLÓGICO PRIVADO CIBERTEC</p>
  <h1 class="portada-titulo">{e("titulo")}</h1>
  <p class="portada-sub">{e("subtitulo")}</p>
  <p class="portada-desc">{e("descripcion")}</p>
  <table class="portada-datos">{tabla}</table>
</section>
""")
PY

cat > "${salida}/estilo.css" <<'CSS'
@page { size: A4; margin: 18mm 16mm; }
body { font-family: "DejaVu Sans", Arial, sans-serif; font-size: 10pt; line-height: 1.45; color: #111; }
h1 { font-size: 17pt; border-bottom: 2px solid #1d4ed8; padding-bottom: 4px; margin-top: 26px; page-break-after: avoid; }
h2 { font-size: 13pt; margin-top: 18px; page-break-after: avoid; }
h3 { font-size: 11pt; page-break-after: avoid; }
table { border-collapse: collapse; width: 100%; margin: 8px 0; font-size: 8.8pt; page-break-inside: auto; }
tr { page-break-inside: avoid; }
th, td { border: 1px solid #bbb; padding: 3px 6px; vertical-align: top; }
th { background: #eef2ff; }
code { font-family: "DejaVu Sans Mono", monospace; font-size: 8.6pt; background: #f3f4f6; padding: 0 2px; }
pre { background: #f3f4f6; padding: 8px; font-size: 8pt; white-space: pre-wrap; word-break: break-word; page-break-inside: avoid; }
pre code { background: none; }
img { max-width: 100%; display: block; margin: 8px auto; page-break-inside: avoid; }
blockquote { border-left: 3px solid #1d4ed8; margin: 8px 0; padding: 2px 10px; color: #333; background: #f8fafc; }
header#title-block-header { display: none; }
.portada { height: 255mm; display: flex; flex-direction: column; align-items: center; justify-content: center; text-align: center; page-break-after: always; }
.portada .logo { width: 78mm; margin-bottom: 14mm; }
.portada .institucion { letter-spacing: 1.5px; font-size: 9.5pt; color: #444; margin: 0 0 26mm; }
.portada h1.portada-titulo { font-size: 26pt; border: none; margin: 0 0 6mm; color: #0b3d57; }
.portada .portada-sub { font-size: 14pt; margin: 0 0 3mm; }
.portada .portada-desc { font-size: 11pt; color: #444; margin: 0 0 24mm; }
table.portada-datos { width: 118mm; font-size: 11pt; border-collapse: collapse; }
table.portada-datos th { text-align: left; width: 32mm; background: none; border: none; border-bottom: 1px solid #ccc; padding: 4px 6px; color: #0b3d57; }
table.portada-datos td { text-align: left; border: none; border-bottom: 1px solid #ccc; padding: 4px 6px; }
CSS

pandoc "${salida}/${base}.render.md" -f markdown+pipe_tables -t html5 --standalone --toc --toc-depth=2 --resource-path="${raiz}/docs" \
  --include-before-body="${salida}/portada.html" --metadata lang=es --css "${salida}/estilo.css" --embed-resources \
  -o "${salida}/${base}.html" 2>/dev/null || \
pandoc "${salida}/${base}.render.md" -f markdown+pipe_tables -t html5 --standalone --toc --toc-depth=2 --resource-path="${raiz}/docs" \
  --include-before-body="${salida}/portada.html" --metadata lang=es --css "${salida}/estilo.css" --self-contained -o "${salida}/${base}.html"

google-chrome --headless=new --no-sandbox --disable-gpu --no-pdf-header-footer \
  --print-to-pdf="${salida}/${base}.pdf" "file://${salida}/${base}.html" >/dev/null 2>&1
echo "PDF: ${salida}/${base}.pdf"
