#!/usr/bin/env python3
"""Native UI wiring/localization contract. Device tests exercise the actual workflow and rendering."""
from pathlib import Path
import sys,re,json,xml.etree.ElementTree as ET
root=Path(sys.argv[1]).resolve() if len(sys.argv)>1 else Path(__file__).resolve().parents[1]
def read(p):return (root/p).read_text()
ui=root/'feature/collections/src/main/kotlin/com/ugallery/feature/collections/SmartAlbumsContent.kt'
if not ui.exists():sys.exit('BASELINE: native smart-album screen absent; Room v23 rule engine retained')
text=ui.read_text()
for word in ['SmartPage.Editor','SmartPage.Preview','SmartPage.Pending','SmartPage.Exclusions','SmartPage.People','SmartPage.Topics','repository.create','repository.update','repository.undoExclude','repository.includeAgain','SmartAlbumDraft.Saver','GridCells.Adaptive','smart-year','smart-favorites-row','smart-reload','smart-image-loaded-','secondaryContainer','onSecondaryContainer','LocalContentColor.current','change(draft)','current.copy(name','current.copy(year','current.copy(favorites']:
 assert word in text,word
app=read('app/src/main/kotlin/com/ugallery/app/ProductionGalleryApp.kt')
assert 'SurfaceRoute.SmartAlbums -> smartAlbumRepository' in app and 'onSmartAlbumsClick' in app
rail=read('app/src/main/kotlin/com/ugallery/app/AdaptiveGalleryNavigation.kt')
assert 'rail-smart-albums' in rail and 'maxLines = 3' in rail
assert 'collections-smart-albums' in read('feature/collections/src/main/kotlin/com/ugallery/feature/collections/CollectionsContent.kt')
assert 'val smartAlbumRepository = runtime.map' in read('app/src/main/kotlin/com/ugallery/app/GalleryViewModel.kt')
db=read('core/database/src/main/kotlin/com/ugallery/core/database/GalleryDatabase.kt');assert 'version = 26,' in db
expected=None
for loc in ['values','values-es','values-fr','values-pt','values-it','values-de']:
 strings={x.attrib['name']:''.join(x.itertext()) for x in ET.parse(root/f'feature/collections/src/main/res/{loc}/smart_albums.xml').getroot()}
 assert len(strings)==61 and all(v.strip() for v in strings.values()),loc
 sig={k:re.findall(r'%[0-9]+\$[ds]',v) for k,v in strings.items()}
 if expected is None:expected=sig
 else:assert sig==expected,loc
print('PASS: native smart albums; Collections/sidebar, saveable editor, live preview, exclusions/undo and 61 strings in 6 locales')
