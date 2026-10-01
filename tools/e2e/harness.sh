#!/usr/bin/env bash
# ---------------------------------------------------------------------------
# Lightforge E2E harness — dependency-free bash + adb + uiautomator.
#
#   source tools/e2e/harness.sh
#
# WHY THIS FILE EXISTS (failure modes it is built to prevent)
#
#   1. SUBSTRING SHADOWING -> false "button does nothing".
#      The old `tapon` substring-matched text/content-desc and tapped the FIRST
#      hit. Body copy that merely *starts with* a button's label shadows the
#      button: tapping "Unlock" hit the subtitle "Unlock with biometrics or your
#      device screen lock." and the run was reported as "Unlock does nothing".
#      Here, matching is tiered -- exact text/desc beats substring, and
#      clickable beats non-clickable -- and when a tier still holds several
#      DIFFERENT tap targets the call FAILS LOUDLY, printing every candidate
#      with its bounds. A wrong tap that looks like a pass is worse than an
#      error. Use `tapbtn` when you mean a real button.
#
#   2. COARSE SCROLLING -> false "content is not rendered".
#      One big swipe flew past a Collections card and the run reported "the
#      Private Album card is not rendered at all"; a finer scroll found it two
#      passes in. `scrollto` swipes in small increments, re-dumps between every
#      step, stops as soon as the target appears, and reports an explicit
#      failure (with the last screen's texts) when it never does.
#
#   3. NO-OP TAPS GOING UNNOTICED.
#      `expect` asserts a post-tap screen state and, on failure, dumps the
#      current screen's texts so the no-op is caught where it happened rather
#      than three steps later.
#
#   4. `adb shell` EATING THE DRIVING SCRIPT'S STDIN.
#      `adb shell`/`adb exec-out` forward their stdin to the device and drain
#      it. A script piped into bash (`bash <<'EOF' ... EOF`, or `cat s.sh |
#      bash`) therefore vanished at the FIRST helper that shelled out: the run
#      "produced no output at all and looked like a dead device". Every adb
#      invocation in this file redirects `</dev/null` so heredoc-driven scripts
#      survive. If you add one, redirect it too.
#
#   5. BEING SOURCED FROM A NON-BASH SHELL -> silent WRONG TAPS.
#      This file relies on bash word-splitting and process substitution. Under
#      zsh `set -- $wh` yields ONE word, so a screen size of "1080 2400" became
#      a single garbage token and the harness tapped the wrong place without
#      erroring. Loading under anything but bash is now refused up front.
#
# BACK-COMPAT: shot dump nodes tapxy tapon texts back swipe launch errs alive
# tap1 tap_by_text tap_by_desc all keep their old names and call signatures.
#
# QUICK REFERENCE
#   shot <name>              screenshot to $SHOTS/<name>.png
#   dump                     raw uiautomator XML (cached, see redump)
#   redump                   force a fresh dump
#   nodes [query]            TSV of nodes (tier text desc clickable bounds tapx
#                            tapy class id hasclick label); `label` is the best
#                            text/content-desc found in the node's own subtree,
#                            so clickable Compose containers whose own text= and
#                            desc= are empty still show what they say.
#   texts                    unique text= / content-desc= values on screen
#   tapon <q>                tiered match (exact>substring, clickable>not); ambiguity = failure
#   tapexact <q>             exact text or content-desc only
#   tapbtn <q>               clickable nodes only (a real button)
#   tapxy <x> <y>            raw coordinate tap
#   scrollto <q> [dir] [max] small-step scroll until <q> is on screen (dir: down|up)
#   expect <q> [tries]       assert <q> is on screen; on failure prints screen texts
#   expectgone <q> [tries]   assert <q> is NOT on screen
#   back / swipe / launch / errs / alive / relaunch_if_dead
#
# EXIT/RETURN CONVENTION: every helper returns non-zero on failure and prints a
# line starting with "E2E FAIL:". Drive scripts with `set -e` or check $?.
#
# REQUIREMENTS: bash (the login shell here is zsh -- run `bash` first), adb on
# PATH, a device/emulator in `adb get-state` = device. Heredoc-driven scripts
# are supported: `bash <<'EOF' ... EOF` works.
# ---------------------------------------------------------------------------

# --- shell guard: refuse to load anywhere but bash --------------------------
if [ -z "${BASH_VERSION:-}" ]; then
  echo "E2E FAIL: tools/e2e/harness.sh requires bash, but this shell is not bash." >&2
  echo "  (the login shell on this machine is zsh; zsh does not word-split unquoted" >&2
  echo "   expansions, so the harness would compute garbage coordinates and TAP THE" >&2
  echo "   WRONG PLACE instead of failing.)" >&2
  echo "  Run it under bash instead, e.g.:" >&2
  echo "      bash -c 'source tools/e2e/harness.sh; texts'" >&2
  echo "      bash <<'EOF'" >&2
  echo "      source tools/e2e/harness.sh" >&2
  echo "      texts" >&2
  echo "      EOF" >&2
  return 1 2>/dev/null || exit 1
fi

SHOTS="${SHOTS:-${XDG_CACHE_HOME:-$HOME/.cache}/lightforge/e2e-screenshots}"
PKG="${PKG:-com.librestatic.lightforge.debug}"
ACTIVITY="${ACTIVITY:-com.librestatic.lightforge.MainActivity}"
E2E_TAP_SETTLE="${E2E_TAP_SETTLE:-1.5}"   # seconds to wait after a tap
E2E_SCROLL_STEP="${E2E_SCROLL_STEP:-260}" # px per scroll increment (small on purpose)
E2E_SCROLL_MAX="${E2E_SCROLL_MAX:-25}"    # bounded passes, never an infinite loop
mkdir -p "$SHOTS" 2>/dev/null

_e2e_err() { echo "E2E FAIL: $*" >&2; }

# --- raw device helpers -----------------------------------------------------
# NOTE: every adb call below redirects </dev/null. `adb shell`/`adb exec-out`
# forward stdin to the device and will otherwise swallow the rest of a script
# that is being piped into bash (see failure mode 4 in the header).
shot()  { adb exec-out screencap -p </dev/null > "$SHOTS/$1.png"; echo "saved $SHOTS/$1.png"; }

_E2E_DUMP_CACHE=""
redump() {
  _E2E_DUMP_CACHE="$(adb shell uiautomator dump /sdcard/ui.xml </dev/null >/dev/null 2>&1; adb shell cat /sdcard/ui.xml </dev/null 2>/dev/null)"
  [ -n "$_E2E_DUMP_CACHE" ] || { _e2e_err "uiautomator dump returned nothing (device asleep/locked?)"; return 1; }
  printf '%s' "$_E2E_DUMP_CACHE"
}
# dump: cached within E2E_DUMP_TTL-less semantics -- callers that act on the UI
# must call redump (all tap/scroll helpers do so automatically).
dump() { redump; }

texts() { redump | tr '<' '\n' | grep -oE '(text|content-desc)="[^"]+"' | sort -u; }

back()  { adb shell input keyevent KEYCODE_BACK </dev/null; sleep 1; }
swipe() { adb shell input swipe "$@" </dev/null; sleep 1; }
launch(){ adb shell am force-stop "$PKG" </dev/null; adb shell am start -n "$PKG/$ACTIVITY" </dev/null >/dev/null; sleep 6; }
errs()  { adb logcat -d -b crash,main '*:E' </dev/null | tail -40; }
alive() { [ "$(adb get-state </dev/null 2>/dev/null)" = "device" ]; }
relaunch_if_dead() { alive || { _e2e_err "no device"; return 1; }; adb shell pidof "$PKG" </dev/null >/dev/null 2>&1 || launch; }
tapxy() { adb shell input tap "$1" "$2" </dev/null; sleep "$E2E_TAP_SETTLE"; }

# --- node parser ------------------------------------------------------------
# Emits TSV per node: tier text desc clickable bounds tapx tapy class resource-id hasclick
# `hasclick` is true when the tap target is a clickable node (the node itself or
# its nearest clickable ancestor) -- i.e. when tapping it can actually do
# something. `tapbtn` considers only these.
# `tier` is only meaningful when a query is given:
#   1 exact match + node itself clickable
#   2 exact match, tap target = nearest clickable ancestor (or the node itself)
#   3 substring match + node itself clickable
#   4 substring match, tap target = nearest clickable ancestor (or the node itself)
# With no query every node is emitted with tier 0.
_e2e_parse() {
  local xml
  xml="$(redump)" || return 1
  _e2e_parse_xml "$xml" "$1"
}

# _e2e_parse_xml <xml> <query> -- the parser proper, so one dump can be reused
# (nodes needs both the full node list and the filtered one).
_e2e_parse_xml() {
  local xml="$1" q="$2"
  printf '%s' "$xml" | sed 's/</\n</g' | awk -v q="$q" '
    function attr(line, name,   re, s) {
      re = name "=\""
      s = index(line, re); if (s == 0) return ""
      s += length(re)
      line = substr(line, s)
      s = index(line, "\""); if (s == 0) return ""
      return substr(line, 1, s - 1)
    }
    function unesc(s) {
      gsub(/&quot;/, "\"", s); gsub(/&apos;/, "'"'"'", s)
      gsub(/&lt;/, "<", s);    gsub(/&gt;/, ">", s)
      gsub(/&amp;/, "\\&", s); return s
    }
    function cx(b,   n) { split(b, n, /[^0-9]+/); return int((n[2]+n[4])/2) }
    function cy(b,   n) { split(b, n, /[^0-9]+/); return int((n[3]+n[5])/2) }
    function nearest_clickable(   i) {
      for (i = depth; i >= 1; i--) if (stack_click[i] == "true") return stack_bounds[i]
      return ""
    }
    /^<\/node/ { if (depth > 0) { delete stack_click[depth]; delete stack_bounds[depth]; depth-- } ; next }
    /^<node/ {
      t = unesc(attr($0, "text")); d = unesc(attr($0, "content-desc"))
      cl = attr($0, "clickable"); bn = attr($0, "bounds")
      kls = attr($0, "class"); rid = attr($0, "resource-id")
      selfclose = ($0 ~ /\/>[[:space:]]*$/)
      if (!selfclose) { depth++; stack_click[depth] = cl; stack_bounds[depth] = bn }
      tier = 0
      if (q != "") {
        exact = (t == q || d == q)
        sub_ = (index(t, q) > 0 || index(d, q) > 0)
        if (!sub_ && !exact) { if (selfclose) next; else next }
        if (exact) tier = (cl == "true") ? 1 : 2
        else       tier = (cl == "true") ? 3 : 4
      }
      tb = bn; hasclick = (cl == "true") ? "true" : "false"
      if (cl != "true") {
        anc = (selfclose) ? nearest_clickable() : ""
        if (!selfclose) {
          # node itself was just pushed; look at ancestors below it
          saveC = stack_click[depth]; saveB = stack_bounds[depth]; depth--
          anc = nearest_clickable()
          depth++; stack_click[depth] = saveC; stack_bounds[depth] = saveB
        }
        if (anc != "") { tb = anc; hasclick = "true" }
      }
      if (bn == "") next
      printf "%d\t%s\t%s\t%s\t%s\t%d\t%d\t%s\t%s\t%s\n", tier, t, d, cl, bn, cx(tb), cy(tb), kls, rid, hasclick
    }
  '
}

# _e2e_add_label <full-tsv>  -- reads rows on stdin, appends an 11th column:
# the best label for that node. A clickable Compose container almost never
# carries its own text; the label lives on a non-clickable descendant (this is
# the very relationship the matcher already walks to compute tap targets). We
# resolve it the same way, by geometric containment: the first node in document
# order whose bounds sit inside this node's bounds and which has a non-empty
# text/content-desc. Falls back to the node's own text, then desc, then "".
# Columns 1-10 are emitted byte-identically, so every existing caller is safe.
_e2e_add_label() {
  awk -F'\t' '
    function own(t, d) { return (t != "") ? t : d }
    NR==FNR {
      n++
      T[n]=$2; D[n]=$3; BN[n]=$5
      split($5, p, /[^0-9]+/)
      X1[n]=p[2]; Y1[n]=p[3]; X2[n]=p[4]; Y2[n]=p[5]
      next
    }
    {
      lab = own($2, $3)
      if (lab == "") {
        split($5, q, /[^0-9]+/)
        ax1=q[2]; ay1=q[3]; ax2=q[4]; ay2=q[5]
        for (j = 1; j <= n; j++) {
          l2 = own(T[j], D[j])
          # A candidate with a label is never this node: we only get here when
          # this node has neither text nor desc. So no self-check is needed --
          # and crucially we must NOT skip same-bounds rows, because in Compose
          # the labelled child usually fills its clickable parent exactly.
          if (l2 == "") continue
          if (X1[j] >= ax1 && Y1[j] >= ay1 && X2[j] <= ax2 && Y2[j] <= ay2) {
            lab = l2; break                   # document order == visual order
          }
        }
      }
      print $0 "\t" lab
    }
  ' "$1" -
}

nodes() {
  local q="${1:-}" xml full
  xml="$(redump)" || return 1
  full="$(_e2e_parse_xml "$xml" "")"
  [ -n "$full" ] || return 0
  if [ -n "$q" ]; then
    _e2e_parse_xml "$xml" "$q" | _e2e_add_label <(printf '%s\n' "$full")
  else
    printf '%s\n' "$full" | _e2e_add_label <(printf '%s\n' "$full")
  fi
}

# _e2e_resolve <query> <mode>   mode: tiered | exact | clickable
# Prints "x y" of the unambiguous tap target, or fails listing candidates.
_e2e_resolve() {
  local q="$1" mode="${2:-tiered}"
  local all tiers t cands targets n onlyclick=0
  all="$(_e2e_parse "$q")" || return 1
  case "$mode" in
    exact)     tiers="1 2" ;;
    clickable) tiers="1 2 3 4"; onlyclick=1 ;;
    *)         tiers="1 2 3 4" ;;
  esac
  for t in $tiers; do
    cands="$(printf '%s\n' "$all" | awk -F'\t' -v t="$t" -v oc="$onlyclick" '$1==t && (oc==0 || $10=="true")')"
    [ -n "$cands" ] || continue
    targets="$(printf '%s\n' "$cands" | cut -f6,7 | sort -u)"
    n="$(printf '%s\n' "$targets" | grep -c .)"
    if [ "$n" -eq 1 ]; then
      printf '%s\n' "$targets" | tr '\t' ' '
      return 0
    fi
    _e2e_err "ambiguous match for \"$q\" (tier $t): $n distinct tap targets. Refusing to guess."
    printf '%s\n' "$cands" | awk -F'\t' '{printf "  text=%-40s desc=%-30s clickable=%s bounds=%s tap=(%s,%s) class=%s\n", "\""$2"\"", "\""$3"\"", $4, $5, $6, $7, $8}' >&2
    echo "  -> disambiguate with tapexact/tapbtn, or tapxy with the bounds above." >&2
    return 2
  done
  _e2e_err "NOT FOUND: \"$q\" (mode=$mode)"
  return 1
}

_e2e_tap_resolved() {
  local q="$1" mode="$2" xy rc
  xy="$(_e2e_resolve "$q" "$mode")"; rc=$?
  [ $rc -eq 0 ] || return $rc
  local tx ty
  read -r tx ty <<<"$xy"      # explicit split: do not rely on unquoted $xy
  adb shell input tap "$tx" "$ty" </dev/null
  sleep "$E2E_TAP_SETTLE"
  echo "tapped [$mode] \"$q\" at $tx,$ty"
}

# Back-compatible name; now tiered + ambiguity-safe.
tapon()   { _e2e_tap_resolved "$1" tiered; }
tapexact(){ _e2e_tap_resolved "$1" exact; }
tapbtn()  { _e2e_tap_resolved "$1" clickable; }
# Legacy aliases kept so older scripts keep working.
tap1()        { tapon "$1"; }
tap_by_text() { tapexact "$1"; }
tap_by_desc() { tapexact "$1"; }

# --- assertions -------------------------------------------------------------
_e2e_present() { _e2e_parse "$1" | grep -q .; }

expect() {
  local q="$1" tries="${2:-5}" i
  for i in $(seq 1 "$tries"); do
    _e2e_present "$q" && { echo "expect OK: \"$q\""; return 0; }
    sleep 1
  done
  _e2e_err "expected \"$q\" on screen after ${tries}s, but it is not there."
  echo "  current screen texts:" >&2
  texts | sed 's/^/    /' >&2
  return 1
}

expectgone() {
  local q="$1" tries="${2:-5}" i
  for i in $(seq 1 "$tries"); do
    _e2e_present "$q" || { echo "expectgone OK: \"$q\""; return 0; }
    sleep 1
  done
  _e2e_err "expected \"$q\" to be gone, but it is still on screen."
  texts | sed 's/^/    /' >&2
  return 1
}

# --- scrolling that cannot skip content -------------------------------------
_e2e_screen_size() {
  adb shell wm size </dev/null 2>/dev/null | sed -n 's/.*: *\([0-9]*\)x\([0-9]*\).*/\1 \2/p' | tail -1
}

# scrollto <query> [down|up] [max_passes]
scrollto() {
  local q="$1" dir="${2:-down}" max="${3:-$E2E_SCROLL_MAX}"
  local wh w h cx y1 y2 i
  wh="$(_e2e_screen_size)"
  read -r w h <<<"$wh"        # explicit split: do not rely on unquoted $wh
  w="${w:-1080}"; h="${h:-2400}"
  cx=$(( w / 2 ))
  if [ "$dir" = "up" ]; then
    y1=$(( h / 3 )); y2=$(( y1 + E2E_SCROLL_STEP ))
  else
    y2=$(( h * 2 / 3 )); y1=$(( y2 + E2E_SCROLL_STEP ))
    [ "$y1" -ge "$h" ] && { y1=$(( h - 10 )); y2=$(( y1 - E2E_SCROLL_STEP )); }
  fi
  for i in $(seq 0 "$max"); do
    if _e2e_present "$q"; then
      echo "scrollto: found \"$q\" after $i step(s) ($dir)"
      return 0
    fi
    adb shell input swipe "$cx" "$y1" "$cx" "$y2" 350 </dev/null
    sleep 0.6
  done
  _e2e_err "scrollto: \"$q\" never appeared after $max ${dir} steps of ${E2E_SCROLL_STEP}px."
  echo "  last screen texts:" >&2
  texts | sed 's/^/    /' >&2
  return 1
}

# scrolltotap <query> [dir] -- scroll it into view, then tap it as a button.
scrolltotap() { scrollto "$1" "${2:-down}" && tapbtn "$1"; }

echo "e2e harness loaded (PKG=$PKG). Helpers: tapon tapexact tapbtn scrollto scrolltotap expect expectgone nodes texts shot" >&2
