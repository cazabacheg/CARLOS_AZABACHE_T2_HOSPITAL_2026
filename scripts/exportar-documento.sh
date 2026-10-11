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
header#title-block-header { text-align: center; margin-bottom: 24px; }
header .title { font-size: 22pt; font-weight: bold; }
header .subtitle { font-size: 12pt; color: #333; }
CSS

pandoc "${salida}/${base}.render.md" -f markdown+pipe_tables -t html5 --standalone --toc --toc-depth=2 \
  --metadata lang=es --css "${salida}/estilo.css" --embed-resources \
  -o "${salida}/${base}.html" 2>/dev/null || \
pandoc "${salida}/${base}.render.md" -f markdown+pipe_tables -t html5 --standalone --toc --toc-depth=2 \
  --metadata lang=es --css "${salida}/estilo.css" --self-contained -o "${salida}/${base}.html"

google-chrome --headless=new --no-sandbox --disable-gpu --no-pdf-header-footer \
  --print-to-pdf="${salida}/${base}.pdf" "file://${salida}/${base}.html" >/dev/null 2>&1
echo "PDF: ${salida}/${base}.pdf"
