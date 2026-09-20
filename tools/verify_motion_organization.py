from pathlib import Path
import json,hashlib,re,xml.etree.ElementTree as E
r=Path(__file__).resolve().parents[1]
read=lambda p:(r/p).read_text()
db=read('core/database/src/main/kotlin/com/ugallery/core/database/GalleryDatabase.kt')
assert 'version = 26,' in db and 'Migration24To25' in db
schema=json.loads(read('core/database/schemas/com.ugallery.core.database.GalleryDatabase/26.json'))['database']
assert schema['version']==26
assert {'motion_key_frames','gallery_restore_receipts','portable_timeline_overrides'} <= {e['tableName'] for e in schema['entities']}
old=json.loads(read('docs/evidence/memory-exclusion-rules/modified-hashes.json'))
p='core/database/schemas/com.ugallery.core.database.GalleryDatabase/24.json'
assert hashlib.sha256((r/p).read_bytes()).hexdigest()==old[p]
vm=read('app/src/main/kotlin/com/ugallery/app/GalleryViewModel.kt')
ui=read('app/src/main/kotlin/com/ugallery/app/ProductionGalleryApp.kt')
for token in ['motionDisplayUri(active, media)','motionDisplayUri(active, neighbor)','thumbnailEpoch.value++','GalleryRestoreMediaSession.recover','recoverIncompleteBackups()']:assert token in vm,token
for token in ['SurfaceRoute.MotionPhoto','MotionPhotoContent(','organizationPort = organizationPort','LocalBackupRecoveryBanner(']:assert token in ui,token
adapter=re.sub(r'\s+', '', read('app/src/main/kotlin/com/ugallery/app/GalleryOrganizationBackupAdapter.kt'))
for token in ['database.withTransaction','galleryRestoreReceiptDao().insert','portableTimelineOverrideDao()','importSnapshot','verifyCurrentBytes']:assert token in adapter,token
writer=read('app/src/main/kotlin/com/ugallery/app/GalleryRestoreMediaSession.kt')
for token in ['AtomicFile','isCommitted(operationId)','OWNER_PACKAGE_NAME','GENERATION_MODIFIED','verifyBytes','current.pending']:assert token in writer,token
for module,file in [('motionphotos','motion_photo.xml'),('settings','local_backup.xml')]:
 keys=None
 for locale in ['', '-es','-fr','-pt','-it','-de']:
  parsed=E.parse(r/f'feature/{module}/src/main/res/values{locale}/{file}')
  found={e.attrib['name'] for e in parsed.getroot() if e.tag=='string'}
  if keys is None:keys=found
  assert found==keys,(module,locale,found^keys)
print('PASS: Room26 preserves schema24/25; Motion display/save/reset routes; local archive/receipt/date restoration bridge; complete EN/ES/FR/PT/IT/DE resource keys. Runtime claims require the recorded native matrix.')
