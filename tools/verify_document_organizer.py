#!/usr/bin/env python3
"""Static wiring/locale contract, supplemented by native data and UI tests."""
from pathlib import Path
import re
import sys
import xml.etree.ElementTree as ET

root = Path(sys.argv[1]).resolve() if len(sys.argv) > 1 else Path(__file__).resolve().parents[1]
db = (root / "core/database/src/main/kotlin/com/ugallery/core/database/GalleryDatabase.kt").read_text()
if "DocumentAnnotationEntity::class" not in db or "Migration18To19" not in db:
    sys.exit("FAIL: native document annotations and migration are missing")
assert "version = 26," in db
screen = (root / "feature/collections/src/main/kotlin/com/ugallery/feature/collections/DocumentsContent.kt").read_text()
for token in ("repository.classify", "repository.archive", "repository.undo", "repository.pdfKeys", "repository.clearClassification", "ClipboardManager", "testTagsAsResourceId = true"):
    assert token in screen, token
app = (root / "app/src/main/kotlin/com/ugallery/app/ProductionGalleryApp.kt").read_text()
for token in ("SurfaceRoute.Documents ->", "onDocumentsClick", "organizeSelectedDocuments()", "SearchConcept.Document", "if (pdfReturnToDocuments) SurfaceRoute.Documents"):
    assert token in app, token
rail = (root / "app/src/main/kotlin/com/ugallery/app/AdaptiveGalleryNavigation.kt").read_text()
assert "onRoute(SurfaceRoute.Documents)" in rail
expected = None
for locale in ("values", "values-es", "values-fr", "values-pt", "values-it", "values-de"):
    resources = ET.parse(root / "feature/collections/src/main/res" / locale / "documents.xml").getroot()
    strings = {n.attrib["name"]: "".join(n.itertext()) for n in resources}
    assert all(value.strip() for value in strings.values())
    signatures = {k: sorted(re.findall(r"%[0-9]+\$[dsf]", v)) for k, v in strings.items()}
    if expected is None: expected = signatures
    else: assert signatures == expected, locale
assert "surfaceVariant" in screen and "onSurfaceVariant" in screen
assert not any(token in screen for token in ("Color(0x", "HttpURLConnection", "WebView"))
print("PASS: Documents routes, Room v26, manual categories, local text, archive/undo, PDF handoff and 6 locales")
