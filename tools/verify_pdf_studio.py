#!/usr/bin/env python3
"""Static integration contracts supplement the PDF engine's device tests."""
from pathlib import Path
import re
import xml.etree.ElementTree as ET
ROOT = Path(__file__).resolve().parents[1]
module = ROOT / "feature/pdfstudio"
resources = module / "src/main/res"
expected = None
for locale in ["values", "values-es", "values-fr", "values-pt", "values-it", "values-de"]:
    strings = {n.attrib["name"]: "".join(n.itertext()) for n in ET.parse(resources / locale / "strings.xml").getroot()}
    assert all(v.strip() for v in strings.values()), locale
    # aapt2 rejects bare apostrophes/quotes; they must be escaped as \' and \".
    bare = [k for k, v in strings.items() if re.search(r"(?<!\\)['\"]", v)]
    assert not bare, f"{locale}: unescaped apostrophe/quote in {bare}"
    signatures = {k: sorted(re.findall(r"%[0-9]+\$[dsf]", v)) for k,v in strings.items()}
    if expected is None: expected = signatures
    else: assert signatures == expected, locale
app = (ROOT / "app/src/main/kotlin/com/ugallery/app/ProductionGalleryApp.kt").read_text()
rail = (ROOT / "app/src/main/kotlin/com/ugallery/app/AdaptiveGalleryNavigation.kt").read_text()
assert "SurfaceRoute.PdfStudio ->" in app
assert "route = SurfaceRoute.PdfStudio" in app
assert "onPdfStudioClick" in app and "onPdfStudio =" in app
# PDF Studio is opened from Documents and from the multi-select action bar, not from the
# navigation rail/dock (there is no SurfaceRoute.PdfStudio destination there to route to); confirm
# that remains true instead of asserting a rail entry point that was never implemented.
assert "SurfaceRoute.PdfStudio" not in rail, (
    "A PdfStudio rail entry point was added; wire it through onRoute and update this check instead"
    " of re-adding the stale onRoute(SurfaceRoute.PdfStudio) assertion."
)
android = "{http://schemas.android.com/apk/res/android}"
service = ET.parse(module / "src/main/AndroidManifest.xml").find("application/service")
assert service.attrib[android+"exported"] == "false"
assert service.attrib[android+"isolatedProcess"] == "true"
for path in (module / "src/main").rglob("*.kt"):
    source = path.read_text()
    assert not any(x in source for x in ["WebView", "HttpURLConnection", "okhttp3.", "java.net.URL"]), path
print("PDF integration checks passed: 4 entry points, 6 locales, isolated service, no cloud clients")

processor = (module / "src/main/aidl/com/ugallery/feature/pdfstudio/IPdfProcessor.aidl").read_text()
assert "in ParcelFileDescriptor manifest, boolean compact" in processor
assert "String manifest" not in processor
engine = (module / "src/main/kotlin/com/ugallery/feature/pdfstudio/PdfEngine.kt").read_text()
queue = (module / "src/main/kotlin/com/ugallery/feature/pdfstudio/PdfExportQueue.kt").read_text()
renderer = (module / "src/main/kotlin/com/ugallery/feature/pdfstudio/PdfProcessingService.kt").read_text()
assert "384 * 1024" not in engine + queue
assert "PdfExportManifest.encode(snapshot)" in engine and "PdfExportManifest.encode(snapshot)" in queue
assert "budgetedOutput(output, manifest).use(document::save)" in renderer
print("PDF export contracts passed: bounded descriptor manifest, matching queue limit, budgeted final save")

source_pages = (module / "src/main/kotlin/com/ugallery/feature/pdfstudio/PdfSourcePages.kt").read_text()
assert "PdfSourcePages(document)" in renderer and "loaded.getOrPut" not in renderer
assert "copy.resources = original.resources" not in renderer
assert "clone.cloneForNewDocument(value)" in source_pages
assert "imported.close()" in renderer
print("PDF source lifetime checks passed: one parsed source, deep-cloned resources, release before image decoding/save")

screen = (module / "src/main/kotlin/com/ugallery/feature/pdfstudio/PdfStudioScreen.kt").read_text()
assert "role = Role.Switch" in screen and "Switch(checked, onCheckedChange = null)" in screen
print("PDF toggle contract passed: one labeled switch row, no competing child action")

# Material You guardrail: PdfPaperTokens.kt is the only file allowed to hold color literals
# (paper white, the selection/guide double stroke, print-space ink). Everything else must take its
# colors from MaterialTheme.colorScheme so light/dark/dynamic themes are always respected.
color_literal = re.compile(r"\bColor\.(White|Black|Transparent)\b|\bColor\(")
offenders = []
for path in (module / "src/main").rglob("*.kt"):
    if path.name == "PdfPaperTokens.kt":
        continue
    for lineno, line in enumerate(path.read_text().splitlines(), start=1):
        if color_literal.search(line):
            offenders.append(f"{path.relative_to(ROOT)}:{lineno}: {line.strip()}")
assert not offenders, "Color literals outside PdfPaperTokens.kt:\n" + "\n".join(offenders)
print("PDF Material You check passed: no color literals outside PdfPaperTokens.kt")
