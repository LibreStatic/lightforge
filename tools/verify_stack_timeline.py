#!/usr/bin/env python3
"""Static wiring and locale gate; actual SQL/selection/rendering are native-tested."""
from pathlib import Path
import sys,re,xml.etree.ElementTree as ET
root=Path(sys.argv[1]).resolve() if len(sys.argv)>1 else Path(__file__).resolve().parents[1]
def read(path):return (root/path).read_text()
repo=read('core/data/src/main/kotlin/com/ugallery/core/data/GalleryTimelineRepository.kt')
if 'collapseStacks' not in repo:sys.exit('FAIL: Photos timeline does not collapse saved stacks')
for word in ['StackTimelinePagingSource','rawStackTimelinePagingSource','selectStack','PhotoStackChanged']:
    assert word in repo,word
sql=read('core/data/src/main/kotlin/com/ugallery/core/data/GalleryTimelineQuery.kt')
for word in ['representatives','keepRank=1','stackSelection','s.revision=?','LIMIT 501','archived_media','FolderSelectionSql']:
    assert word in sql,word
vm=read('app/src/main/kotlin/com/ugallery/app/GalleryViewModel.kt')
for word in ['collapseStacks = !selecting','timelineStackSelectionJob?.cancel()','active.timeline.selectStack','mutableSelection.value != before']:
    assert word in vm,word
ui=read('feature/photos/src/main/kotlin/com/ugallery/feature/photos/PagedPhotosTimeline.kt')
for word in ['timeline_stack_description','timeline_stack_select','key(request, loader)','secondaryContainer','onSecondaryContainer','timeline-image-loaded-']:
    assert word in ui,word
app=read('app/src/main/kotlin/com/ugallery/app/ProductionGalleryApp.kt')
assert 'initialStackId = requireNotNull(media.stack).id' in app
expected=None
for loc in ['values','values-es','values-fr','values-pt','values-it','values-de']:
    strings={x.attrib['name']:''.join(x.itertext()) for x in ET.parse(root/f'feature/photos/src/main/res/{loc}/stack_timeline.xml').getroot()}
    assert len(strings)==4 and all(strings.values())
    sig={k:re.findall(r'%[0-9]+\$[ds]',v) for k,v in strings.items()}
    if expected is None:expected=sig
    else:assert sig==expected
db=read('core/database/src/main/kotlin/com/ugallery/core/database/GalleryDatabase.kt')
assert 'version = 26,' in db and '.enableMultiInstanceInvalidation()' in db
assert 'renderedRoute == SurfaceRoute.Root && !showDetails && selectionCount > 0' in app
print('PASS: Room v26; existing timeline behavior retained; filtered stack representatives, keyset paging, explicit selection and 6 locales')
