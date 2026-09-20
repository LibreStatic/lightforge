#!/usr/bin/env python3
from pathlib import Path
import sys, xml.etree.ElementTree as ET
root=Path(sys.argv[1]).resolve() if len(sys.argv)>1 else Path(__file__).resolve().parents[1]
def read(p): return (root/p).read_text()
dao=read('core/database/src/main/kotlin/com/ugallery/core/database/MomentDao.kt')
if 'fun observeMembers' not in dao:
 print('BASELINE: memory members are one-shot; playback and persistent order are coupled')
 raise SystemExit(1)
repo=read('core/data/src/main/kotlin/com/ugallery/core/data/MomentRepository.kt')
vm=read('app/src/main/kotlin/com/ugallery/app/GalleryViewModel.kt')
ui=read('feature/collections/src/main/kotlin/com/ugallery/feature/collections/MomentContent.kt')
app=read('app/src/main/kotlin/com/ugallery/app/ProductionGalleryApp.kt')
for value in ['fun observeMoment','fun observeMembers','suspend fun reorderVisible','orderedKeys.toSet()','member.ordinal']:
 assert value in dao,value
assert 'dao.observeMembers' in repo and 'moments.observeMembers(id)' in vm and 'moments.observeMoment(id)' in vm
assert 'mutableSelectedMomentId' in vm and '.onStart { emit(emptyList()) }' in vm
for value in ['rememberSaveable(moment.momentId)','moment-previous','moment-next','moment-move-earlier','moment-set-cover','moment-empty','moment-delete-confirm','member.generationModified','moment-image-loaded-']:
 assert value in ui,value
assert 'members[storyIndex]' not in ui and 'StorySlotCount' not in ui
assert 'MomentUnavailableContent(onBack' in app
base=None
for locale in ['values','values-es','values-fr','values-pt','values-it','values-de']:
 strings={x.attrib['name']:x.text for x in ET.parse(root/f'feature/collections/src/main/res/{locale}/memory_story.xml').getroot()}
 assert len(strings)==10 and all(strings.values())
 if base is None:base=set(strings)
 assert set(strings)==base
assert 'version = 26,' in read('core/database/src/main/kotlin/com/ugallery/core/database/GalleryDatabase.kt')
print('PASS: live Room memory details, independent playback, stable photo identity, hidden-slot reorder and 10 strings in 6 locales')
