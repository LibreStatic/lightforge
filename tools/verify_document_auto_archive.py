#!/usr/bin/env python3
"""Static integration contract; behavior is verified by Room/Compose/device tests."""
from pathlib import Path
import re, sys, xml.etree.ElementTree as ET
root=Path(sys.argv[1]).resolve() if len(sys.argv)>1 else Path(__file__).resolve().parents[1]
def read(path): return (root/path).read_text()
db=read("core/database/src/main/kotlin/com/ugallery/core/database/GalleryDatabase.kt")
if "Migration19To20" not in db:
    sys.exit("FAIL: persistent document auto-archive rule and migration are missing")
assert "version = 26," in db
assert 'dependsOn("kspDebugKotlin", "copyRoomSchemas")' in read("core/database/build.gradle.kts")
for token in ("DocumentArchiveRuleEntity::class", "DocumentArchiveHistoryEntity::class", "documentArchiveDao", "Migration20To21"):
    assert token in db,token
repo=read("core/data/src/main/kotlin/com/ugallery/core/data/DocumentAutoArchiveRepository.kt")
for token in ("database.withTransaction", "DocumentArchivePreviewChanged", "current.revision != preview.revision", "sourceAvailable", "undoLastRun", "writtenAtMillis", "BatchSize = 100", "current != expectedRule", "afterMediaStoreId = cursor?.mediaStoreId"):
    assert token in repo,token
sql=read("core/database/src/main/kotlin/com/ugallery/core/database/DocumentArchiveEntities.kt")
for token in ("m.isFavorite=0", "m.timelineSortMillis>0", "h.generationModified=m.generationModified", "ForeignKey.CASCADE", "LIMIT 101"):
    assert token in sql,token
for name in ("GalleryDocumentRepository", "GalleryArchiveRepository"):
    assert ".relinquish(" in read(f"core/data/src/main/kotlin/com/ugallery/core/data/{name}.kt")
ui=read("feature/collections/src/main/kotlin/com/ugallery/feature/collections/DocumentAutoArchiveContent.kt")
for token in ("repository.preview", "repository.enable", "repository.pause", "repository.undoLastRun", "secondaryContainer", "onSecondaryContainer", "document-auto-confirm"):
    assert token in ui,token
assert "DocumentAutoArchiveContent" in read("feature/collections/src/main/kotlin/com/ugallery/feature/collections/DocumentsContent.kt")
worker=read("app/src/main/kotlin/com/ugallery/app/DocumentAutoArchiveWorker.kt")
for token in ("ExistingPeriodicWorkPolicy.KEEP", "15, TimeUnit.MINUTES", "GENERATION_MODIFIED", "IS_FAVORITE", "IS_TRASHED", "Result.retry()"):
    assert token in worker,token
assert "DocumentAutoArchiveWorker.install(this)" in read("app/src/main/kotlin/com/ugallery/app/UGalleryApplication.kt")
assert not any(t in worker+repo+ui for t in ("HttpURLConnection", "WebView", "Color(0x"))
expected=None
for locale in ("values","values-es","values-fr","values-pt","values-it","values-de"):
    strings={n.attrib["name"]:"".join(n.itertext()) for n in ET.parse(root/"feature/collections/src/main/res"/locale/"document_auto_archive.xml").getroot()}
    assert len(strings)==22 and all(v.strip() for v in strings.values()),locale
    signature={k:sorted(re.findall(r"%[0-9]+\$[dsf]",v)) for k,v in strings.items()}
    if expected is None:expected=signature
    else:assert signature==expected,locale
print("PASS: Room v26, reviewed local auto-archive, durable rules/undo, worker and 6 locales")
