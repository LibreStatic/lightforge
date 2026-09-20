#!/usr/bin/env python3
"""Static stack integration contract, supplemented by native Room/Compose/app tests."""
from pathlib import Path
import sys,re,xml.etree.ElementTree as ET
root=Path(sys.argv[1]).resolve() if len(sys.argv)>1 else Path(__file__).resolve().parents[1]
def read(p):return (root/p).read_text()
db=read("core/database/src/main/kotlin/com/ugallery/core/database/GalleryDatabase.kt")
if "PhotoStackEntity::class" not in db:sys.exit("FAIL: persistent photo stacks and migration are missing")
for token in ("version = 26,","Migration21To22","photoStackDao"):
    assert token in db,token
repo=read("core/data/src/main/kotlin/com/ugallery/core/data/GalleryPhotoStackRepository.kt")
for token in ("database.withTransaction","PhotoStackChanged","signature(current)","normalizeTitle","MaxMembers=500","suspend fun separate","suspend fun dissolve"):
    assert token in repo or token.replace("="," = ") in repo,token
sql=read("core/database/src/main/kotlin/com/ugallery/core/database/PhotoStackEntities.kt")
for token in ("f.generationModified=m.generationModified","photo_stack_exclusions","m.isAccessible=1","m.isTrashed=0","m.mediaType=1","ForeignKey.CASCADE"):
    assert token in sql,token
ui=read("feature/collections/src/main/kotlin/com/ugallery/feature/collections/PhotoStacksContent.kt")
for token in ("repository.save","repository.setCover","repository.separate","repository.dissolve","stack-compare-zoom","ThumbnailRequest","secondaryContainer","onSecondaryContainer"):
    assert token in ui,token
assert not any(t in ui for t in (".blur(","RenderEffect","ColorFilter","HttpURLConnection","Color(0x"))
app=read("app/src/main/kotlin/com/ugallery/app/ProductionGalleryApp.kt")
for token in ("SurfaceRoute.Stacks ->","onStacksClick","createSelectedPhotoStack()","stacks_create"):
    assert token in app,token
assert "onRoute(SurfaceRoute.Stacks)" in read("app/src/main/kotlin/com/ugallery/app/AdaptiveGalleryNavigation.kt")
expected=None
for locale in ("values","values-es","values-fr","values-pt","values-it","values-de"):
    strings={n.attrib["name"]:"".join(n.itertext()) for n in ET.parse(root/"feature/collections/src/main/res"/locale/"photo_stacks.xml").getroot()}
    assert len(strings)==39 and all(v.strip() for v in strings.values())
    signature={k:sorted(re.findall(r"%[0-9]+\$(?:\.\d+)?[dsf]",v)) for k,v in strings.items()}
    if expected is None:expected=signature
    else:assert signature==expected,locale
print("PASS: Room v26, persistent photo stacks, local suggestions, cover/compare/separation and 6 locales")
