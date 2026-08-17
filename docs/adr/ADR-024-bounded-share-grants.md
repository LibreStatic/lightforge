# ADR-024: Share bounded content URIs with temporary grants

## Status
Accepted for M2.

## Decision
Original sharing builds native `ACTION_SEND`/`ACTION_SEND_MULTIPLE` intents from canonical typed
MediaStore URIs, places every URI in `ClipData`, and grants read access without persistable flags.
Private candidates are removed before intent construction. A share contains at most 500 items;
larger selections remain streamed/chunked rather than materialized into one intent.

Metadata sanitization is an explicit `ShareCopySanitizer` boundary that must return a new local
asset; M2 does not ship a misleading no-op sanitizer. Temporary share files live only under the
app's share cache and expire through the owned-file registry.

## Consequences
- Receivers can read originals only through temporary URI grants.
- Private-vault content cannot enter an original-share plan.
- M5 can implement real metadata removal without changing selection or intent contracts.
