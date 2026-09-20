# E2E harness (adb + uiautomator)

Dependency-free bash helpers for driving the debug build on an emulator.

```bash
source tools/e2e/harness.sh
launch
expect "Timeline"
scrollto "Private Album"
tapbtn "Private Album"
expect "Unlock"
tapbtn "Unlock"          # exact-match wins over the "Unlock with ..." subtitle
```

## Why it was hardened

Two E2E rounds reported working features as broken. Both were harness bugs:

* `tapon "Unlock"` substring-matched and tapped the **first** hit, which was the
  subtitle *"Unlock with biometrics or your device screen lock."* — reported as
  "Unlock does nothing". Matching is now tiered (exact text/content-desc beats
  substring, clickable beats non-clickable) and **fails loudly with the list of
  candidates and their bounds** when a tier still holds several distinct tap
  targets. Use `tapbtn` when you mean a real button.
* A single large swipe scrolled straight past a Collections card — reported as
  "the Private Album card is not rendered at all". `scrollto` now swipes in
  small increments (`E2E_SCROLL_STEP`, default 260px), re-dumps between every
  step, is bounded by `E2E_SCROLL_MAX` passes, and reports an explicit failure
  with the last screen's texts when the target never appears.

`expect` / `expectgone` catch a no-op tap at the point it happens instead of
three steps later.

## Functions

| Function | Purpose |
| --- | --- |
| `shot <name>` | screenshot into `$SHOTS` |
| `dump` / `redump` | raw uiautomator XML |
| `nodes [query]` | TSV: `tier text desc clickable bounds tapx tapy class id hasclick` |
| `texts` | unique `text=` / `content-desc=` values on screen |
| `tapon <q>` | tiered, ambiguity-safe tap (back-compatible name) |
| `tapexact <q>` | exact text or content-desc only |
| `tapbtn <q>` | only matches whose tap target is a clickable node |
| `tapxy <x> <y>` | raw coordinate tap |
| `scrollto <q> [down\|up] [max]` | small-step scroll until `<q>` is on screen |
| `scrolltotap <q> [dir]` | `scrollto` then `tapbtn` |
| `expect <q> [tries]` | assert `<q>` is on screen; prints screen texts on failure |
| `expectgone <q> [tries]` | assert `<q>` is gone |
| `back` `swipe` `launch` `errs` `alive` `relaunch_if_dead` | device basics |

Legacy `tap1`, `tap_by_text`, `tap_by_desc` still exist and now route through the
hardened matchers.

Every helper returns non-zero on failure and prints a line starting with
`E2E FAIL:`. Drive scripts with `set -e` or check `$?`.

## Environment

| Var | Default | Meaning |
| --- | --- | --- |
| `PKG` | `com.ugallery.app.debug` | package under test |
| `ACTIVITY` | `com.ugallery.app.MainActivity` | launch activity |
| `SHOTS` | `docs/e2e-screenshots` | screenshot output directory |
| `E2E_TAP_SETTLE` | `1.5` | seconds to wait after a tap |
| `E2E_SCROLL_STEP` | `260` | px per scroll increment |
| `E2E_SCROLL_MAX` | `25` | bounded scroll passes |

## Emulator requirements

* **Start the AVD with `-gpu host`.** SwiftShader crashes on the app's ncnn
  Vulkan shaders and takes the whole emulator down mid-run.
* **The debug APK is ~548 MB**; a build takes **10–25 minutes**. Budget for it,
  and do not assume a fast rebuild loop.
* **After an emulator restart the device comes back locked**, and the package is
  often disabled at user level. Before a run:

  ```bash
  adb shell pm enable com.ugallery.app.debug
  adb shell input keyevent KEYCODE_WAKEUP
  adb shell input keyevent KEYCODE_MENU     # dismiss the keyguard
  ```

  `uiautomator dump` returning nothing is the usual symptom of a locked or
  asleep screen; `redump` reports that explicitly.
* One emulator is shared between agents — take turns, and avoid taps when
  another agent owns the device.
