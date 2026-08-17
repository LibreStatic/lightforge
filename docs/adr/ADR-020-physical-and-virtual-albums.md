# ADR-020: Keep physical albums as projections and virtual albums as references

## Status
Accepted for M2.

## Decision
Physical albums are derived from indexed MediaStore bucket metadata and retain the composite
`(volumeName, bucketId)` identity. Virtual albums are app-owned metadata whose membership table
stores only `(albumId, volumeName, mediaStoreId)` references. Adding media to a virtual album does
not move, rename, copy, or mutate its MediaStore row.

Album contents use bounded composite-keyset Paging for both newest-first and oldest-first order;
media-kind filters are applied in SQL. The membership relation intentionally has no foreign key to
the reconstructible MediaStore index, so a temporarily unavailable removable volume does not erase
the user's virtual album organization. A foreign key to the virtual album itself cascades on album
deletion.

## Consequences
- The same item may belong to multiple virtual albums without file duplication.
- Unmounting an SD card yields an explicit unavailable state and empty readable content; remounting
  can restore the references after reconciliation.
- Album mutations are limited to 500-reference chunks and can consume `SelectionChunker` output.
- Missing physical album names remain nullable domain data; localized fallback copy belongs to UI.
