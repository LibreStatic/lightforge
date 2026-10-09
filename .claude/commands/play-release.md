---
description: Bump, gate, build and publish a signed Lightforge Studio AAB to Google Play with per-locale release notes
argument-hint: "[patch|minor|major|replace] [track, default: Closed testing - Alpha]"
---

# Play Store release: Lightforge Studio: Gallery

Arguments: `$ARGUMENTS`. The first word is the bump size; if it is missing, choose it from the commits since the
last release using the rule in `AGENTS.md`. `replace` keeps `versionName` and only raises `versionCode`. Use it to
swap the build of a release that Google has not approved yet. Any remaining words name the track.

## Repository facts

| Item | Value |
|---|---|
| Package | `com.librestatic.lightforge` (the store build is the `offline` flavor) |
| Play Console app | "Lightforge Studio: Gallery" in the app list of `https://play.google.com/console/u/0/developers/7999945149961326518/app-list` |
| Version | `versionName` and the local `?: <code>` fallback in `app/build.gradle.kts`. The fallback is stale, so pass `-Plightforge.versionCode=<code>` instead of relying on it |
| CI version code | `GITHUB_RUN_NUMBER + 100` (`.github/workflows/release.yml`) |
| Release notes | `store/play/release-notes/<versionName>.txt`, one `<locale>…</locale>` block per folder in `store/play/listings/` |
| Signing | Gradle signs `bundleOfflineRelease` with the upload key from `~/.android/lightforge/keystore.properties` |
| Upload certificate SHA-256 | `1dbd3d04b427de3469e946f3dd87d317fabf6b2ff32ab6925883ed97a9e8ab9c` |
| CI gate | `./gradlew :app:assembleOfflineDebug :app:assembleOfflineRelease test lint :app:bundleOfflineRelease` then `python tools/verify_offline_release.py` |
| Outputs | `app/build/outputs/bundle/offlineRelease/*.aab` and `app/build/outputs/mapping/offlineRelease/mapping.txt` |

## Steps

1. **Preflight.**
   - You are on `main` and in sync with `origin/main`.
   - The working tree usually holds many uncommitted user files. Stage explicit paths only; never `git add -A`
     or `commit -a`.
   - Run `free -h`, and check swap as well. The gate needs about 5 GB with the flags below. Stop if less is free,
     or if another Gradle build is running.

2. **Version.**
   - Find the last `versionCode` uploaded to Play with `tools/play-publish.py status` ("highest uploaded versionCode").
   - Find the latest CI code with `gh release list --limit 3`.
   - New code: above both, and equal to the next CI run number + 100 when the Release workflow is enabled
     (`gh workflow list --all`).
   - Set `versionName` (unless `replace`), and set the `?: <code>` fallback to the new code too.
   - Commit the bump alone: `chore(release): bump version to X.Y.Z-beta`.

3. **Release notes.**
   - Write `store/play/release-notes/<versionName>.txt` from `git log --no-merges <last release>..HEAD`.
   - Cover user-facing changes only, in every listing locale (en-US, es-419 with voseo, es-ES, pt-BR, pt-PT,
     fr-FR, de-DE, it-IT).
   - End each block with `Report problems at github.com/LibreStatic/lightforge/issues`, translated.
   - Use in-app names for features: check `app/src/main/res/values*/strings.xml`.
   - Each block must stay at most 500 characters; check that with a script.
   - Commit: `docs(release): add X.Y.Z-beta release notes`.

4. **Local CI gate, before pushing.**
   - Create a clean worktree:
     `git worktree add --detach ~/.cache/claude-tmp/ugallery/release-wt main`. Never use /tmp or /dev/shm.
   - The worktree has no `local.properties`, so export `ANDROID_HOME=ANDROID_SDK_ROOT=$HOME/Android/Sdk`.
   - Run the CI gate there with `--continue -Plightforge.versionCode=<code> --max-workers=2`,
     `-Dorg.gradle.jvmargs="-Xmx3g -XX:MaxMetaspaceSize=768m"` and `-Pkotlin.compiler.execution.strategy=in-process`.
   - Run it from a script file in the background; the context-mode hook blocks raw `./gradlew` in Bash.
   - Redirect output to a log and print `exit=$?`. Never pipe Gradle through `rtk err`, which hides failures.
     Grep the log for `^e:|FAILED`.
   - Fix every failure in its own commit, then rerun.

5. **Push** `main`. If any workflow is `disabled_manually`, say so and do not enable it without asking.

6. **Verify and stage the bundle.**
   - Check that `jarsigner -verify` passes on the AAB.
   - Check that `keytool -printcert -jarfile <aab>` shows the upload certificate SHA-256 above.
   - Copy it to `~/Downloads/lightforge-<versionName>-<code>.aab` and write a `.sha256` next to it.
   - Copy `mapping.txt` too, renamed to `lightforge-<versionName>-<code>-mapping.txt`.
   - Remove the older bundles there.

7. **Publish through the API.** See "Publishing to Google Play" in `AGENTS.md`.
   - `tools/play-publish.py publish --aab <aab> --mapping app/build/outputs/mapping/offlineRelease/mapping.txt --dry-run`: it must print the new versionCode, notes for
     8 languages and "edit validated".
   - Rerun it without `--dry-run`. If the commit refuses to send changes automatically, rerun with `--no-review`
     and finish from Publishing overview (Submit, then Send changes for review).
   - `tools/play-publish.py status` must show the track with the new release.
   - In the Console's release review page, *devices no longer supported* must stay 0. Check it with Claude in Chrome
     when the manifest or dependencies changed.
   - Fall back to the Console flow below only if the API is unavailable (for example a 403: the service account
     lacks access, so ask the user).

7b. **Console fallback.** Use Claude in Chrome (`mcp__claude-in-chrome__*`) and work on the DOM first.
   - Open the app, then Test and release > Testing > Closed testing > the track > **Create new release**.
   - **The upload is the user's step.** `file_upload` caps at 10 MB, and injecting the file with JS or a localhost
     server needs the user's explicit authorization. Otherwise ask the user to click Upload and pick the AAB in `~/Downloads`.
     Wait until `find` reports the bundle row with the new code.
   - Release name: keep the suggested `<code> (<versionName>)`.
   - Release notes:
     - fill the textarea with `form_input`, using the file contents without the trailing newline;
     - click it, then press `ctrl+End`, type a space and press Backspace, so Angular registers the change;
     - check that the counter reads "Release notes provided for 8 of 8 languages".
   - Click **Next**.
     - Expand the warnings. A native-debug-symbols warning is expected.
     - In the supported-devices table, *devices no longer supported* must be 0.
     - Stop on any error, or on any other warning or device drop, and report it.
   - Click **Save**, then **Go to overview**. The list must show only the intended track change, "Start full rollout".
   - Click **Submit N changes for review**, then **Send changes for review**. Check that the heading becomes
     "Changes in review".
   - Afterwards, the mapping file can go into App bundle explorer > the new version > Downloads >
     ReTrace mapping file, if it is under 10 MB (`file_upload`). Otherwise ask the user.
   - Clicking by `ref` often does nothing in the Console. Click coordinates from a fresh scaled screenshot instead.
     The viewport height changes, so re-read the bottom bar before clicking it.

8. **Replace a build already in review** (`replace`).
   - Publishing overview > **Remove changes** > confirm.
   - On the track, click **Create new release**, which supersedes the unsent draft.
   - Repeat step 7. "Edit release details" only changes the name and the notes.

9. **Close out.**
   - Remove the worktree only if nothing else needs it.
   - Report: the commits pushed, the gate result, the AAB path and versionCode, and the Console state.
   - Update `store/play/closed-testing.md` only if the user asks; it is their uncommitted file.
