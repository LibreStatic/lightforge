#!/usr/bin/env python3
"""Source integration gate; execution/contrast/recovery evidence is in the native matrix."""
from pathlib import Path
import hashlib,json,xml.etree.ElementTree as E
root=Path(__file__).resolve().parents[1]
read=lambda p:(root/p).read_text()
schema=json.loads(read('core/database/schemas/com.librestatic.lightforge.core.database.GalleryDatabase/26.json'))['database']
assert schema['version']==26
assert {'moment_discovery_revision','moment_discovery_seen'} <= {e['tableName'] for e in schema['entities']}
old='core/database/schemas/com.librestatic.lightforge.core.database.GalleryDatabase/25.json'
assert hashlib.sha256((root/old).read_bytes()).hexdigest()=='e3edf262df56aacf0f5239d38c18ec263466799a3a927dc043330cdaf8ce0377'
db=read('core/database/src/main/kotlin/com/librestatic/lightforge/core/database/GalleryDatabase.kt')
assert 'Migration25To26' in db and 'MomentDiscoverySchema' in db
vm=read('app/src/main/kotlin/com/librestatic/lightforge/GalleryViewModel.kt')
for token in ['observeRevision()', 'retainedGalleryOperations()', 'backupTaskController.reconcile()', 'CreationGifPreparedSession', 'memory_discovery_failed']:
 assert token in vm,token
ui=read('app/src/main/kotlin/com/librestatic/lightforge/ProductionGalleryApp.kt')
for token in ['MemoriesBrowserContent(', 'CreationGifContent(', 'LocalBackupTasksContent(', 'creationGifSessionId', 'selection-create-gif', 'create-gif']:
 assert token in ui,token
adapter=read('app/src/main/kotlin/com/librestatic/lightforge/GalleryOrganizationBackupAdapter.kt')
assert 'LocalBackupDurableOrganizationPort' in adapter and 'resumeRestore' in adapter
worker=read('app/src/main/kotlin/com/librestatic/lightforge/GalleryBackupTaskWorker.kt')
assert 'setForeground(' in worker and 'FOREGROUND_SERVICE_TYPE_DATA_SYNC' in worker
assert 'GalleryBackupTaskProbeProvider' not in read('app/src/main/AndroidManifest.xml')
for module,name in [('feature/collections','memories_browser'),('feature/collage','creation_gif'),('feature/settings','local_backup_tasks'),('app','memory_discovery')]:
 base=None
 for loc in ['', '-es','-fr','-pt','-it','-de']:
  elements=E.parse(root/f'{module}/src/main/res/values{loc}/{name}.xml').getroot()
  values={e.attrib['name']:e.text for e in elements if e.tag=='string'}
  assert values and all(values.values()),(module,loc)
  if base is None:base=set(values)
  assert set(values)==base,(module,loc)
print('PASS: Room26 with immutable schema25; full memory browser/live discovery, isolated GIF drafts, durable backup tasks/foreground worker and EN/ES/FR/PT/IT/DE resources. Runtime behavior requires recorded native tests.')
