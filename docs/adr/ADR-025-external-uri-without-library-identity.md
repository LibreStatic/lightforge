# ADR-025: Keep external grants outside library identity

## Status
Accepted for M2.

## Decision
`ACTION_VIEW` and `ACTION_EDIT` route their temporary content URI to a dedicated external viewer.
The URI is never converted into a fake `(volumeName, mediaStoreId)` key and does not require broad
library permission. Access is revalidated by opening the descriptor on foreground; an expired grant
becomes an explicit unavailable state.

External edit mode offers a real, verified `IS_PENDING` save-copy operation and returns the new URI
to the caller. It never overwrites the granted original. The output name derives from the resolved
MIME type so MediaStore does not repair conflicting extensions.

## Consequences
- One-shot provider URIs work even when all READ_MEDIA permissions are denied.
- Relaunch without a grant fails closed with user-visible copy instead of crashing or entering the
  indexed library.
- Further editor recipes can transform the copy later without changing grant or publication rules.
