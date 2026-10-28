# ADR-013: No Internet release

- Status: Accepted, amended 2026-10-28
- Date: 2026-08-16

## Context

Lightforge must remain native, offline, scalable to 100k–250k local items, and correct under public Android permissions and storage APIs.

## Decision

Offline release manifests must not contain INTERNET or related network permissions. CI inspects the merged manifest and an explicit runtime dependency group allowlist.

## Consequences

The decision is enforced by module boundaries, tests, build guards, and device evidence. Any incompatible change must update this ADR, affected backlog tickets, and regression coverage.

## Amendment (2026-10-28)

`app/src/main/AndroidManifest.xml` declares `INTERNET`, `ACCESS_NETWORK_STATE` and `ACCESS_LOCAL_NETWORK` for every flavor, offline included. The network is used only for actions the user starts:

- model package downloads: signed semantic packages; pet and inpainting models pinned by SHA-256.
- backup and OwnSync to the user's own SFTP/SMB servers.
- encrypted transfer over the local network.

Cleartext traffic is disabled (`app/src/main/res/xml/network_security_config.xml`). `tools/verify_offline_release.py` checks the merged manifest against a list of forbidden permissions (location, Wi-Fi/network state changes). It also checks the dependency allowlist, and it limits network client code to a reviewed list of source files. Gallery data is never sent to third parties.

"Offline release manifests must not contain INTERNET or related network permissions" no longer applies.
