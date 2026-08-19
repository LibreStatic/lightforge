# ADR-037: Offline places/gazetteer with local reverse geocoding

Date: 2026-08-19

## Status

Proposed

## Context

M6-T09 requires an offline places/gazetteer for reverse geocoding of photo GPS coordinates.
The feature must not use any online geocoder, must respect location privacy,
and must stay within an accepted storage budget.

## Decision

Use a bundled gazetteer dataset sourced from public domain GeoNames (CC-BY 4.0):
- ~70 major world cities covering all continents
- Stored as a Kotlin object literal (~15KB compiled)
- Equirectangular distance approximation for nearest-neighbor search
- Haversine distance available for longer-distance accuracy

The OfflineGazetteer class:
- reverseGeocode(lat, lon): finds nearest city
- findNearby(lat, lon, radiusKm): finds all cities within radius
- No network, no cloud, no online geocoder
- GPS coordinates never leave the device

## Storage Budget

- 70 cities x ~80 bytes each = ~5.6KB raw data
- Compiled Kotlin object: ~15KB
- Well within a reasonable storage budget for a bundled dataset
- For production, a larger dataset (GeoNames cities15000.zip ~2MB compressed)
  could be bundled with a geohash or R-tree index

## Consequences

- Only major cities are recognized (~70); small towns and rural areas
  will show the nearest major city, which may be far away
- This is an explicit limitation, not a silent degradation
- Location privacy is preserved: all lookups are local
- No online geocoder dependency
- Future enhancement: bundle a larger dataset with spatial indexing
