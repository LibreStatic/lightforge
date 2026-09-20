# ADR-025: Keep external grants outside library identity

## Status
Accepted for M2.

## Decision
`ACTION_VIEW` routes its temporary content URI through the same viewer surface used by the library,
while `ACTION_EDIT` opens the corresponding full editor directly. The URI is never converted into a
fake `(volumeName, mediaStoreId)` key and does not require broad
library permission. Access is revalidated by opening the descriptor on foreground; an expired grant
becomes an explicit unavailable state.

External editing uses ephemeral recipes and the regular image/video export pipelines. It offers a
real, verified `IS_PENDING` save-copy operation and returns the new URI to the caller. It never
overwrites the granted original. The output name derives from the resolved MIME type so MediaStore
does not repair conflicting extensions. Viewer actions that require an indexed MediaStore identity
are hidden; URI-safe share, copy, print, set-as, open-with, playback, gesture, and edit actions remain.

## Consequences
- One-shot provider URIs work even when all READ_MEDIA permissions are denied.
- Relaunch without a grant fails closed with user-visible copy instead of crashing or entering the
  indexed library.
- Further editor recipes can transform the copy later without changing grant or publication rules.
