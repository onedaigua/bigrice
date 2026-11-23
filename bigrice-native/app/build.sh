#!/usr/bin/env bash
# Builds bigrice-native (proot-free): aapt2 -> javac -> d8 -> assemble -> zipalign -> apksigner.
#
#   bash build.sh            # debug-signed
#   bash build.sh release    # release-signed with keystore/release.keystore
#   bash build.sh debug keep # keep the intermediate work dir
#
# Environment:
#   ANDROID_HOME / ANDROID_SDK_ROOT  SDK root (default: the machine's usual SDK path)
#   BUILD_TOOLS / ANDROID_JAR        explicit tool + platform jar
#   VERSION_CODE / VERSION_NAME      defaults 203 / a2.3
#   KEYSTORE / KS_PASS / KEY_PASS / KS_ALIAS
set -euo pipefail

unset MSYS_NO_PATHCONV MSYS2_ARG_CONV_EXCL 2>/dev/null || true

APP="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
MODE="${1:-debug}"
KEEP="${2:-}"
SDK="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-C:/Users/daigua/AppData/Local/Android/Sdk}}"
CODE="${VERSION_CODE:-203}"
NAME="${VERSION_NAME:-a2.3}"

latest_dir() {
  ls -1 "$1" 2>/dev/null | sort -V | tail -1
}

to_win() {
  if command -v cygpath >/dev/null 2>&1; then
    cygpath -m "$1"
  else
    printf '%s' "$1" | sed -e 's|^/\([a-zA-Z]\)/|\1:/|'
  fi
}

BT="${BUILD_TOOLS:-$SDK/build-tools/$(latest_dir "$SDK/build-tools")}"
AJAR="${ANDROID_JAR:-$SDK/platforms/$(latest_dir "$SDK/platforms")/android.jar}"
OUT="$APP/build"
WORK="$OUT/work"

[ -x "$BT/aapt2.exe" ] || [ -x "$BT/aapt2" ] || { echo "aapt2 not found under $BT" >&2; exit 1; }
[ -f "$AJAR" ] || { echo "android.jar not found: $AJAR" >&2; exit 1; }

if [ -f "$APP/keystore/release.keystore" ]; then
  KEYSTORE="${KEYSTORE:-$APP/keystore/release.keystore}"
else
  KEYSTORE="${KEYSTORE:-$APP/../bigrice-app/app/keystore/release.keystore}"
fi
KS_PASS="${KS_PASS:-bigrice-release}"
KEY_PASS="${KEY_PASS:-bigrice-release}"
KS_ALIAS="${KS_ALIAS:-bigrice}"

rm -rf "$WORK" "$OUT/res.zip"
mkdir -p "$WORK/res" "$WORK/gen" "$WORK/classes" "$WORK/dex" "$WORK/apk/assets" "$OUT"

echo "==> aapt2 compile"
"$BT/aapt2.exe" compile --dir "$(to_win "$APP/res")" -o "$(to_win "$WORK/res.zip")" --no-crunch >/dev/null

echo "==> aapt2 link"
"$BT/aapt2.exe" link \
  -o "$(to_win "$WORK/apk/base.apk")" \
  -I "$(to_win "$AJAR")" \
  --manifest "$(to_win "$APP/AndroidManifest.xml")" \
  "$(to_win "$WORK/res.zip")" \
  --java "$(to_win "$WORK/gen")" \
  --min-sdk-version 26 \
  --target-sdk-version 28 \
  --version-code "$CODE" \
  --version-name "$NAME" >/dev/null

echo "==> javac"
find "$APP/src" -name '*.java' | while read -r f; do to_win "$f"; echo; done > "$WORK/sources.txt"
find "$WORK/gen" -name '*.java' | while read -r f; do to_win "$f"; echo; done >> "$WORK/sources.txt"
javac -J-Duser.language=en -J-Duser.country=US -source 8 -target 8 -nowarn \
  -bootclasspath "$(to_win "$AJAR")" \
  -d "$(to_win "$WORK/classes")" \
  "@$(to_win "$WORK/sources.txt")" 2>&1 | grep -v '^Note:' || true
[ -f "$WORK/classes/com/bigrice/MainActivity.class" ] || { echo "javac failed" >&2; exit 1; }

echo "==> d8"
find "$WORK/classes" -name '*.class' | while read -r f; do to_win "$f"; echo; done > "$WORK/classes.txt"
"$BT/d8.bat" --release --min-api 26 --lib "$(to_win "$AJAR")" --output "$(to_win "$WORK/dex")" "@$(to_win "$WORK/classes.txt")"
[ -f "$WORK/dex/classes.dex" ] || { echo "d8 failed" >&2; exit 1; }

echo "==> package"
mkdir -p "$WORK/apk/assets"
cp "$APP"/assets/* "$WORK/apk/assets/"
PY="${PYTHON:-}"
if [ -z "$PY" ]; then
  if python3 -c "import sys" >/dev/null 2>&1; then PY=python3; else PY=python; fi
fi
"$PY" "$APP/assemble.py" "$WORK" "$WORK/apk/unsigned.apk"

echo "==> align + sign ($MODE)"
"$BT/zipalign.exe" -f -p 4 "$(to_win "$WORK/apk/unsigned.apk")" "$(to_win "$OUT/bigrice-native-aligned.apk")"
"$BT/apksigner.bat" sign \
  --ks "$(to_win "$KEYSTORE")" --ks-pass "pass:$KS_PASS" --key-pass "pass:$KEY_PASS" --ks-key-alias "$KS_ALIAS" \
  --v1-signing-enabled true --v2-signing-enabled true \
  --out "$(to_win "$OUT/bigrice-native-$MODE.apk")" "$(to_win "$OUT/bigrice-native-aligned.apk")"

"$BT/aapt2.exe" dump badging "$(to_win "$OUT/bigrice-native-$MODE.apk")" | head -2
ls -l "$OUT/bigrice-native-$MODE.apk"
[ -n "$KEEP" ] || rm -rf "$WORK" "$OUT/bigrice-native-aligned.apk"
