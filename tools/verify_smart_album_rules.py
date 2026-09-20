#!/usr/bin/env python3
"""Source/schema contract gate. SQL correctness and migration are exercised on actual Android SQLite."""
from pathlib import Path
import json,sys
root=Path(sys.argv[1]).resolve() if len(sys.argv)>1 else Path(__file__).resolve().parents[1]
def read(p):return (root/p).read_text()
repository=root/'core/data/src/main/kotlin/com/ugallery/core/data/GallerySmartAlbumRepository.kt'
if not repository.exists():sys.exit('BASELINE: persistent smart-album rules absent')
db=read('core/database/src/main/kotlin/com/ugallery/core/database/GalleryDatabase.kt')
assert 'version = 26,' in db and 'Migration22To23' in db and '.enableMultiInstanceInvalidation()' in db
schema=json.loads(read('core/database/schemas/com.ugallery.core.database.GalleryDatabase/23.json'))['database']
assert schema['version']==23
tables={e['tableName']:e for e in schema['entities']}
assert {'smart_albums','smart_album_exclusions','photo_stacks','document_archive_rule'}<=tables.keys()
assert not tables['smart_albums'].get('foreignKeys',[])
assert len(tables['smart_album_exclusions']['foreignKeys'])==2
assert all(f['onDelete']=='CASCADE' for f in tables['smart_album_exclusions']['foreignKeys'])
repo=repository.read_text()
for token in ['db.withTransaction','SmartAlbumChanged','validatePerson','flatMapLatest','current.bounds()','album.bounds()','undoExclude','maxSize = 360']:
    assert token in repo,token
sql=read('core/data/src/main/kotlin/com/ugallery/core/data/SmartAlbumQuery.kt')
for token in ['archived_media','r.generationModified=m.generationModified','label_suppressions','s.revision=?','e.detectionModelVersion=r.modelVersion','args.toTypedArray()','excluded.size <= 200']:
    assert token in sql,token
assert 'http' not in repo.lower() and 'http' not in sql.lower()
print('PASS: Room v26; local AND rules, saved year bounds, current analysis, live paging, CAS and durable exclusions')
