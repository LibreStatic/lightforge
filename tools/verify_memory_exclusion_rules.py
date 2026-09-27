#!/usr/bin/env python3
from pathlib import Path
import sys,json
root=Path(sys.argv[1]).resolve() if len(sys.argv)>1 else Path(__file__).resolve().parents[1]
def read(p):return (root/p).read_text()
db=read('core/database/src/main/kotlin/com/librestatic/lightforge/core/database/GalleryDatabase.kt')
if 'version = 26,' not in db:
 print('BASELINE: Room v23 memory playback has no persistent date/person exclusions')
 raise SystemExit(1)
assert 'Migration23To24' in db and '.enableMultiInstanceInvalidation()' in db
schema=json.loads(read('core/database/schemas/com.librestatic.lightforge.core.database.GalleryDatabase/26.json'))['database']
assert schema['version']==26
tables={t['tableName']:t for t in schema['entities']}
for name in ['memory_date_exclusions','memory_person_exclusions','memory_person_sources','smart_albums','moments']:assert name in tables
assert not tables['memory_person_exclusions'].get('foreignKeys', [])
assert len(tables['memory_person_sources']['foreignKeys'])==2
query=read('core/database/src/main/kotlin/com/librestatic/lightforge/core/database/MemoryEligibility.kt')
for token in ['m.isAccessible=1','m.isTrashed=0','archived_media','m.timelineSortMillis>=d.fromMillis','m.timelineSortMillis<d.untilMillis','r.generationModified=m.generationModified','e.detectionModelVersion=r.modelVersion','memory_person_sources']:
 assert token in query,token
repo=read('core/data/src/main/kotlin/com/librestatic/lightforge/core/data/MemoryExclusionRepository.kt')
for token in ['db.withTransaction','MemoryRuleCreation(it, false)','captureCurrentPersonMatches','MaximumRules = 200','MemoryPersonUnavailable']:
 assert token in repo,token
moments=read('core/data/src/main/kotlin/com/librestatic/lightforge/core/data/MomentRepository.kt')
assert 'database.withTransaction' in moments and 'dao.summarySnapshot' in moments and 'captureCurrentPersonMatches' in moments
assert 'MemoryVisibleCte' in read('core/database/src/main/kotlin/com/librestatic/lightforge/core/database/MomentDao.kt')
print('PASS: Room v26; persistent date/person memory exclusions, stable bounds, known matches, live filtered covers and reversible references')
