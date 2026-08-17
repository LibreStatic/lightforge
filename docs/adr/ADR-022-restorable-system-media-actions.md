# ADR-022: Persist descriptors, not IntentSenders, for system media actions

## Status
Accepted for M2.

## Decision
Every write/favorite/trash/delete action is a versioned state machine. It persists the action,
aggregate counts, a monotonically increasing request ID, and at most one 500-item typed chunk.
`IntentSender` instances are ephemeral and regenerated from the persisted descriptor after process
recreation. Targets remain `(volumeName, mediaStoreId)` plus media kind solely to construct the
canonical Images/Video URI required by MediaStore.

Cancellation retains the current chunk for retry or explicit skip. An approved result transitions
to verification: favorite/trash/delete query MediaStore and record partial successes; write approval
is recorded only as authorization, never as a completed mutation.

## Consequences
- Select-all remains bounded while system requests execute in chunks.
- Stale ActivityResult callbacks cannot complete a newer request.
- Process recreation can resume without serializing binder-backed `IntentSender` objects.
- UI must distinguish authorized, verified-complete, failed, skipped, and cancelled counts.
