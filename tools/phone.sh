#!/usr/bin/env bash
# phone.sh - drive the Play Store on ONE connected phone using plain adb (no root needed).
# Usage: ./phone.sh <command> [args]     (run "./phone.sh help" for the list)
set -u

PKG=com.android.vending
OUT="${OUT:-$HOME/hidegames/run}"
APK="${APK:-$HOME/hidegames/app/build/outputs/apk/debug/app-debug.apk}"
mkdir -p "$OUT"

die() { echo "$*" >&2; exit 1; }

need_device() {
  local n
  n=$(adb devices | awk 'NR>1 && $2=="device" {c++} END {print c+0}')
  [ "$n" = "1" ] || die "Expected exactly 1 authorized device, found $n. Run: adb devices"
}

screen_size() {  # prints "W H"
  adb shell wm size | tr -d '\r' | awk -F'[ x:]+' '/size/ {w=$(NF-1); h=$NF} END {print w, h}'
}

dump_ui() {  # dump the current UI hierarchy to $OUT/ui.xml
  adb shell uiautomator dump /sdcard/hg_ui.xml >/dev/null 2>&1 \
    || die "uiautomator dump failed (is the screen on and unlocked?)"
  adb pull /sdcard/hg_ui.xml "$OUT/ui.xml" >/dev/null 2>&1
}

texts() {  # list visible texts / content descriptions
  dump_ui
  python3 - "$OUT/ui.xml" <<'PY'
import sys
import xml.etree.ElementTree as ET

seen = set()
for n in ET.parse(sys.argv[1]).iter("node"):
    if n.get("bounds") == "[0,0][0,0]":
        continue
    for key in ("text", "content-desc"):
        v = n.get(key, "").strip()
        if v and v not in seen:
            seen.add(v)
            print(v)
PY
}

tap_text() {  # tap the centre of the first node whose text or content-desc equals $1
  dump_ui
  local xy
  xy=$(python3 - "$OUT/ui.xml" "$1" <<'PY'
import re
import sys
import xml.etree.ElementTree as ET

want = sys.argv[2]
for n in ET.parse(sys.argv[1]).iter("node"):
    if want in (n.get("text"), n.get("content-desc")):
        m = re.match(r"\[(\d+),(\d+)\]\[(\d+),(\d+)\]", n.get("bounds", ""))
        if m:
            x1, y1, x2, y2 = map(int, m.groups())
            if x2 > x1 and y2 > y1:
                print((x1 + x2) // 2, (y1 + y2) // 2)
                break
PY
)
  [ -n "$xy" ] || die "No visible node with text/content-desc '$1' (Compose labels can have empty bounds; use: tap X Y)"
  # shellcheck disable=SC2086
  adb shell input tap $xy
}

scroll() {  # scroll the page down N times (default 1)
  local n="${1:-1}" w h
  read -r w h < <(screen_size)
  for _ in $(seq "$n"); do
    adb shell input swipe $((w / 2)) $((h * 70 / 100)) $((w / 2)) $((h * 30 / 100)) 350
    sleep 1.2
  done
}

urlenc() { python3 -c 'import sys,urllib.parse; print(urllib.parse.quote(sys.argv[1]))' "$1"; }

search() {  # open Play Store search results for a query through the market:// deep link
  adb shell "am start -a android.intent.action.VIEW -d 'market://search?q=$(urlenc "$1")' $PKG" >/dev/null
}

restart() {  # force-stop and relaunch the Play Store so the module hooks are re-applied
  adb shell am force-stop "$PKG" || adb shell su -c "am force-stop $PKG"
  sleep 1
  adb shell monkey -p "$PKG" -c android.intent.category.LAUNCHER 1 >/dev/null 2>&1
  sleep 4
}

logs_clear() { adb logcat -c; }

logs_dump() {  # module logs, e-mail addresses redacted
  adb logcat -d -v time -s VectorLegacyBridge:I \
    | grep -E 'HG-|HideGames' \
    | sed -E 's/[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+/EMAIL/g'
}

errors() {  # crashes and Play Store errors
  echo "--- crash buffer"
  adb logcat -d -b crash -v time | tail -n 30
  echo "--- FATAL / AndroidRuntime"
  adb logcat -d -v time | grep -E 'FATAL EXCEPTION|AndroidRuntime' | tail -n 20
}

shot() {
  local f="$OUT/${1:-shot}.png"
  adb exec-out screencap -p > "$f" && echo "$f"
}

check() {
  need_device
  echo "device: $(adb shell getprop ro.product.model | tr -d '\r') / Android $(adb shell getprop ro.build.version.release | tr -d '\r')"
  echo "screen: $(screen_size)"
  echo "play store: $(adb shell dumpsys package "$PKG" | tr -d '\r' | grep -m1 versionName)"
  echo "wakefulness: $(adb shell dumpsys power | tr -d '\r' | grep -m1 mWakefulness)"
}

scenario() {  # restart, search a query, scroll through results, save texts/logs/errors/screenshot
  local q="$1" tag
  tag=$(echo "$q" | tr -c 'A-Za-z0-9\n' '_')
  logs_clear
  restart
  search "$q"
  sleep 6
  { echo "== $q (page 0)"; texts; } > "$OUT/texts_$tag.txt"
  for i in 1 2 3; do
    scroll 1
    { echo "== $q (scroll $i)"; texts; } >> "$OUT/texts_$tag.txt"
  done
  shot "scenario_$tag" >/dev/null
  logs_dump > "$OUT/logs_$tag.txt"
  errors > "$OUT/errors_$tag.txt" 2>&1
  echo "saved in $OUT: texts_$tag.txt logs_$tag.txt errors_$tag.txt scenario_$tag.png"
}

help() {
  cat <<'EOF'
Commands:
  check               device, screen size, Play Store version, screen state
  install             adb install -r the module APK ($APK)
  restart             force-stop + relaunch Play Store
  search <query>      open search results via market://search deep link
  scroll [n]          swipe down n times
  tap <x> <y>         tap coordinates
  tap-text <text>     tap node whose text/content-desc equals <text>
  back | home         key events
  texts               list visible texts (uiautomator dump)
  shot [name]         screenshot to $OUT/name.png
  logs-clear          adb logcat -c
  logs                module logs (HG-*, HideGames), e-mails redacted
  errors              crash buffer + FATAL EXCEPTION lines
  scenario <query>    restart, search, scroll x3, save texts/logs/errors/screenshot
EOF
}

cmd="${1:-help}"
shift || true
case "$cmd" in
  check) check ;;
  install) need_device; adb install -r "$APK" ;;
  restart) need_device; restart ;;
  search) need_device; search "${*:-}" ;;
  scroll) need_device; scroll "${1:-1}" ;;
  tap) need_device; adb shell input tap "${1:?x}" "${2:?y}" ;;
  tap-text) need_device; tap_text "${1:?text}" ;;
  back) need_device; adb shell input keyevent KEYCODE_BACK ;;
  home) need_device; adb shell input keyevent KEYCODE_HOME ;;
  texts) need_device; texts ;;
  shot) need_device; shot "${1:-shot}" ;;
  logs-clear) need_device; logs_clear ;;
  logs) need_device; logs_dump ;;
  errors) need_device; errors ;;
  scenario) need_device; scenario "${1:?query}" ;;
  *) help ;;
esac
