# Lightforge Studio Privacy Policy

Last updated: 2026-09-30. Applies to the Android app Lightforge Studio ("Lightforge", package `com.librestatic.lightforge`), version 0.2.0-beta and later.

## Summary

Lightforge is a local-first photo and video gallery. **We do not collect, receive, store or sell your personal data.** Your photos, videos, face groups, recognized text, locations and search queries stay on your device. The app has no account, no ads, no analytics and no crash-reporting service.

## Who is responsible

The app is developed and published by FacuM and the Lightforge contributors (project: https://github.com/LibreStatic/lightforge). Contact: support@librestatic.com.

## Information Lightforge accesses on your device

- **Photos and videos.** With the permission you grant (all media or only selected items), Lightforge reads your media to display, search, organize and edit it. Edits are saved as new copies unless you choose to overwrite an original.
- **Photo metadata.** Dates, camera information and, if present, the location stored in a photo (EXIF) are read on the device to build the timeline and the offline Places map. Lightforge does not ask for device location permission. You can share photos without location data.
- **Optional on-device analysis.** If you turn it on, Lightforge analyzes your library on the device to detect and group faces, recognize pets, read text in photos (OCR), and compute search embeddings. Face analysis is opt-in, can be paused, and its results (face regions, face signatures, person groups) are stored only on your device. Face signatures are biometric-style data: they are never uploaded, and you can delete all analysis data at any time in Settings. Detection does not identify or name people unless you label a group yourself.
- **Private album and app lock.** Items in the private album are encrypted with keys held in the Android Keystore and unlocked with your biometrics or device credential. Lightforge never receives your fingerprint or face template; Android handles authentication.
- **Credentials you enter.** If you configure your own SFTP or SMB server for backup or mirroring, the connection details and credentials are stored encrypted on your device using the Android Keystore and are used only to connect to that server.

## When Lightforge uses the internet

The network is used only when you start an action:

1. **Downloading models and maps.** Optional semantic-search and pet-recognition models are downloaded from public hosting (GitHub Releases and Google storage) and verified with checksums/signatures before use. Optional offline map packages can be downloaded from a public map host (Protomaps). These requests contain no photos, metadata, search queries or identifiers from your library. The hosts can see your IP address and basic request details, as any website does, and are governed by their own privacy policies. Downloads on mobile data are off by default.
2. **Your own servers.** If you set up backup or mirroring to an SFTP or SMB server, your selected media is sent over an encrypted connection to that server, which you control. We have no access to it.
3. **Sending to another device.** Android-to-Android sharing sends originals directly to another device on your local network over an encrypted connection that you start and approve. There is no relay server.

Lightforge blocks unencrypted (cleartext) network traffic.

## What we do not do

- We do not collect or transmit your photos, videos, metadata, locations, face data, search history or device identifiers.
- We do not use advertising or analytics SDKs, and do not sell or share data with third parties.
- We do not back up app data to the cloud: Android cloud backup and device-transfer of app data are disabled to keep your library and analysis data private.

## Third-party components

The app bundles on-device machine-learning components (including Google ML Kit for face detection and text recognition, and open-source models). They run locally inside the app. Transmission of ML Kit usage logs is disabled in the app. The full list of open-source libraries and their licenses is in the app under Settings > About. The app is installed from Google Play, which has its own privacy policy and processes your purchase; Lightforge never receives your payment details. Google may also collect anonymous diagnostics (for example Android vitals) according to your device settings; we do not control or receive that data as app data.

## Data retention and deletion

All data stays on your device until you delete it. You can remove face and analysis data, downloaded models, trash and private-album items from within the app, or remove everything by uninstalling Lightforge. Because we do not hold your data, there is nothing for us to delete on a server.

## Children

Lightforge is not directed to children under 13 and we do not knowingly collect information from anyone, including children.

## Your rights

Since data is processed only on your device, you control it directly. If you are in the EEA, UK or another region with data protection rights (access, rectification, erasure, portability, objection), you can exercise them in the app or by uninstalling. You may contact us at support@librestatic.com with any question.

## Security

Private album items and their index are encrypted at rest, credentials are kept in the Android Keystore, network traffic is encrypted, and downloaded models are verified before use. No system is perfectly secure, so keep your device updated and protected with a screen lock.

## Changes

If this policy changes, we will update the date above and publish the new version at the same address. Material changes will be mentioned in the release notes.

## Contact

support@librestatic.com, or open an issue at https://github.com/LibreStatic/lightforge/issues.
