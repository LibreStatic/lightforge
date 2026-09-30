# Play Console "App content" and store settings: draft answers

Draft for Lightforge Studio (`com.librestatic.lightforge`), version 0.2.0-beta. Every answer below is derived from the repository (manifest, source allowlist in `tools/verify_offline_release.py`, ADRs). Items tagged **[CONFIRM]** need an owner decision before submitting.

## Store listing basics

| Field | Value |
|---|---|
| App name (Play title) | `Lightforge Studio: Gallery` (localized per `listings/<locale>/title.txt`). Launcher label is "Lightforge"; the in-app name is "Lightforge Studio". |
| Default language | English (United States), en-US |
| App or game | App |
| Free or paid | Paid (USD 5; ARS 2500 local price). Cannot be switched to free later. |
| Category | Photography (alternative: Tools) |
| Tags (pick up to 5 that Play offers) | Photo gallery, Photo editor, Video editor, Privacy, Productivity tools (map to the closest available tags in the Console) |
| Release track | Early access / open or closed testing for the beta (see "Beta and early access" below) |
| Contact email | support@librestatic.com |
| Contact website | https://github.com/LibreStatic/lightforge |
| Contact phone | Optional, leave empty |
| Developer name and address | **[CONFIRM]** required for paid apps and shown publicly (EU trader status under the DSA applies if selling in the EU) |

## Privacy policy

- Required. Host `store/play/privacy-policy.md` publicly (for example GitHub Pages) and paste the URL here and in the store listing.
- URL: **[CONFIRM: to be created]**, suggested `https://librestatic.github.io/lightforge/privacy/`
- The same URL should be reachable from inside the app (About screen); the app currently links only to the GitHub repository. **[CONFIRM]**

## Ads

Does the app contain ads? **No.** No ad SDK is in the dependency allowlist (`config/offline-dependency-allowlist.txt`) and the onboarding copy states "no ads".

## App access

All functionality is available without an account, login or special credentials. No reviewer credentials are needed.

Notes for the review team (paste into the instructions field, optional but helpful):

- Grant photo and video access (all or selected) on first launch. The app needs a device with some photos and videos; an empty library only shows empty states.
- Optional features that need the network are off by default: semantic-search and pet-recognition model downloads (Settings > Smart features), and offline map packages.
- Private album and app lock use the reviewer device's biometric or screen-lock credential. A device with a screen lock set is needed to exercise them; no separate password exists.
- Backup to "your own servers" (SFTP/SMB) and Android-to-Android sharing need a user-provided server or second device and are not required to review the rest of the app.

## Content rating (IARC questionnaire)

Category to choose: **Utility, Productivity, Communication, or Other** (not Social, not Game). Rationale: a gallery/editor tool; it does not generate or host content and has no social features.

| Question | Answer | Reasoning |
|---|---|---|
| Violence, blood, gore | No | Not in the app itself |
| Sexual content or nudity | No | Not in the app itself |
| Profanity or crude humor | No | |
| Controlled substances (drugs, alcohol, tobacco) | No | |
| Gambling or simulated gambling | No | |
| Horror / fear themes | No | |
| Discrimination or hate | No | |
| Users can interact or exchange content with each other | No | No accounts, no feed, no chat. Android-to-Android transfer is a direct, user-initiated file send between two devices on the same network, not a platform. **[CONFIRM]** whether the IARC wording for "share content" should be answered Yes because of this transfer; if Yes, choose "shares only with people the user selects". |
| Shares user's physical location with other users | No | Photo EXIF locations are read locally for the Places map and are not sent anywhere. "Share without location by default" is a setting. |
| Allows purchases of digital goods | No | Paid upfront; no in-app purchases (no Play Billing in the project) |
| Unrestricted internet access / web browsing | No | No browser; network is used only for model/map downloads and user-configured servers |
| Displays user-generated content from other users | No | Shows only the user's own media |
| Uses or simulates real-world dangerous activity / Gambling | No | |

Expected result: lowest age band for all regions (ESRB Everyone, PEGI 3, USK 0, IARC 3+). Because the app can display any media stored on the device, Play may ask about "user-generated content"; the answer is that it is the user's own local media and nothing is distributed.

## Target audience and content

- **Decided (owner): 13 and over** — select age groups 13–15, 16–17 and 18+. Do not opt into Designed for Families.
- Justification:
  1. The app offers optional face grouping ("People"), which processes biometric-like face signatures. Excluding under-13 users avoids Families Policy and COPPA/GDPR-K obligations (parental consent, stricter data rules) that the project has not designed for.
  2. It is a paid app with no child-directed content (13+ keeps teens but no children), characters or themes.
  3. The privacy policy states the app is not directed to children.
- Owner chose 13+ over the 18+ recommendation for wider reach.
- Does the app appeal to children unintentionally? No. No cartoon characters, games or child-oriented marketing.

## Data safety form

Summary answer: **no user data is collected or shared with the developer or any third party.** All photos, videos, face signatures, embeddings, OCR text, locations and search queries are processed and stored on the device only. The project has no analytics, crash-reporting, advertising or account SDK (ML Kit's Firelog transport is explicitly removed in the manifest: `TransportBackendDiscovery` with `tools:node="remove"`). Backups of app data are disabled (`allowBackup=false`, data-extraction rules exclude everything).

### Questions

| Console question | Answer |
|---|---|
| Does your app collect or share any of the required user data types? | **No** |
| Is all of the user data collected by your app encrypted in transit? | Not applicable (no data collected). If forced to answer: Yes. Cleartext traffic is disabled (`cleartextTrafficPermitted=false`); model downloads use HTTPS with signature/SHA-256 verification; SFTP/SMB/local-network transfers are encrypted. |
| Do you provide a way for users to request that their data be deleted? | Not applicable (no data collected). In-app, users can delete all analysis data, downloaded models, and trash/private items themselves. Uninstalling removes all app data. |
| Committed to the Play Families Policy? | No (audience is 13+, no under-13 groups) |
| Independent security review (MASA)? | No |

### Per data type (all "Not collected", "Not shared")

| Category / type | Collected | Shared | Notes |
|---|---|---|---|
| Location (approximate / precise) | No | No | The app declares no location permission (`ACCESS_FINE/COARSE_LOCATION` are removed). Photo EXIF coordinates are read on device (`ACCESS_MEDIA_LOCATION`) for the Places map and never transmitted. |
| Personal info (name, email, address, IDs, etc.) | No | No | No accounts |
| Financial info | No | No | Purchase is handled by Google Play; the app never sees payment data |
| Health and fitness | No | No | |
| Messages | No | No | |
| Photos and videos | No | No | Processed locally only. Sending originals to another device or to a user-configured SFTP/SMB server is an explicit user action to a destination the user controls; the developer never receives them. |
| Audio files | No | No | Optional local music track in the video editor stays on device |
| Files and docs | No | No | PDF Studio works on local files |
| Calendar / Contacts | No | No | No permission requested |
| App activity (interactions, search history, installed apps, in-app content) | No | No | Search queries stay on device |
| Web browsing | No | No | |
| App info and performance (crash logs, diagnostics) | No | No | No crash reporter. Model-download or sync errors are kept locally. |
| Device or other IDs (advertising ID, device ID) | No | No | No advertising ID permission; no device identifiers collected |
| Biometric data | No | No | Face analysis is opt-in, computed and stored on device only, deletable in Settings. Biometric authentication for the private album and app lock uses Android BiometricPrompt; the app never receives fingerprint or face templates. |

### Reasoning notes and judgement calls **[CONFIRM]**

1. **Network requests that reveal an IP address.** When the user starts a download, the device contacts: GitHub Releases (`github.com/LibreStatic/lightforge-models`) for semantic-search and pet-recognition models, `storage.googleapis.com` (object-detector model), and `build.protomaps.com` (optional map package). Those hosts see the device IP like any web server. Play's definition counts data as "collected" only when transmitted to the developer or a third party that the app sends user data to; fetching a public static file with no user payload is treated as not collecting user data. The developer operates no server that receives requests. Conservative alternative: declare nothing, and explain in the privacy policy (done).
2. **Own-server backup and mirror (SFTP/SMB) and Android-to-Android transfer.** Photos leave the device, but only to a server or device the user configures and controls. Play exempts transfers initiated by the user to a destination the user chooses, but reviewers may ask. Keep the in-app disclosure explicit and the wording in the privacy policy as written.
3. **Google Play services / ML Kit.** Face detection and text recognition use ML Kit bundled in the APK (no model download). The Firelog uploader is removed. Google Play services itself may collect its own diagnostics independent of this app; that is not attributed to the developer under Play's rules (SDKs bundled in the app are). **[CONFIRM]** by inspecting the merged release manifest and network traffic of the release build once.
4. **Crash data from Play Console (Android vitals)** is collected by Google for opted-in users, not by the app. No declaration needed.

## Sensitive permissions and declarations

| Item | Answer |
|---|---|
| Photo and video permissions policy (`READ_MEDIA_IMAGES`, `READ_MEDIA_VIDEO`, `READ_MEDIA_VISUAL_USER_SELECTED`) | Use the Play Console declaration form. Core use case is a gallery that browses, searches, organizes and edits the user's full library, which qualifies as core functionality (not a one-off pick). The photo picker is not sufficient for persistent, indexed, 100k+ item timelines. Mention that partial (selected items) access is supported. |
| `ACCESS_MEDIA_LOCATION` | Needed to read photo GPS EXIF for the on-device Places map and "share without location" controls. |
| `MANAGE_EXTERNAL_STORAGE` | Not declared. |
| `ACCESS_FINE_LOCATION` / `ACCESS_COARSE_LOCATION` | Not declared (explicitly removed from merged manifest). |
| `INTERNET`, `ACCESS_NETWORK_STATE` | Used for user-initiated model/map downloads, user-configured servers, local-network sharing. |
| `ACCESS_LOCAL_NETWORK` | Android-to-Android transfer on the local network. |
| `POST_NOTIFICATIONS` | Progress for long exports, backups and syncs only. |
| `FOREGROUND_SERVICE` + `FOREGROUND_SERVICE_DATA_SYNC` | Foreground-service declaration: long-running downloads, sync/backup and local sharing receive. Provide a short video showing the user-started task and its notification. |
| `FOREGROUND_SERVICE_MEDIA_PROCESSING` | Foreground-service declaration: video and PDF export, memory-video rendering. Provide a short video. |
| Exact alarms, VPN, accessibility, SMS/call log, background location | Not used. |
| Health apps, financial features, government apps, news | See below. |

## Other declarations

| Declaration | Answer |
|---|---|
| Government app | No |
| Financial features (banking, loans, crypto, trading, payments) | No |
| Health features / health apps declaration | No |
| News app | No |
| COVID-19 contact tracing / status | No |
| Data safety: advertising ID | Not used (declare "No" in the advertising ID form) |
| Content guidelines: user-generated content | Not applicable (no UGC shared between users) |
| Encryption / export compliance | Uses standard TLS, AES-GCM with Android Keystore and an SQLCipher index for the private album, SSH: qualifies as standard cryptography for US export purposes. **[CONFIRM]** with counsel if selling in regulated regions. |
| Play App Signing | Enabled; upload key is used for release signing (see `build(release)` commit) |
| Target API level | 36 (meets the current Play requirement) |

## Beta and early access

- The listing states "Beta / early access" in every short description. Use a Play testing track (open testing) or Early access (paid apps can use early access with a price) rather than Production while the label stays in the listing. **[CONFIRM]** which option; Early access has extra requirements (community guidelines, feedback channel).
- Provide an in-product or listing feedback channel: GitHub issues at https://github.com/LibreStatic/lightforge/issues (**[CONFIRM]** issues are enabled).
- Experimental tools (object eraser, subject cut-out) are labeled "Experimental" in-app and in the description.

## Price and distribution

- Price USD 5 and ARS 2500 set as local price in Pricing; let Play convert for other countries or set manually. Do not repeat prices in listing text.
- Countries: all supported by Play paid apps. Review EU (DSA trader) and India/Brazil tax settings.
- Device catalog: phones, tablets and foldables (adaptive layouts). Android 11 (API 30) minimum.
