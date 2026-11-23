import pathlib

p = pathlib.Path('app/build.sh')
s = p.read_text(encoding='utf-8')

# Windows tools need Windows paths: /c/Users/x -> C:/Users/x
helper = '''latest_dir() {
  ls -1 "$1" 2>/dev/null | sort -V | tail -1
}

to_win() {
  if command -v cygpath >/dev/null 2>&1; then
    cygpath -m "$1"
  else
    printf '%s' "$1" | sed -e 's|^/\\([a-zA-Z]\\)/|\\1:/|'
  fi
}
'''
s = s.replace('''latest_dir() {
  ls -1 "$1" 2>/dev/null | sort -V | tail -1
}
''', helper, 1)

s = s.replace('''for tool in aapt2 javac d8; do :; done
''', '')

# compile step
s = s.replace('''"$BT/aapt2" compile --dir "$APP/res" -o "$WORK/res.zip" --no-crunch >/dev/null''',
              '''"$BT/aapt2.exe" compile --dir "$(to_win "$APP/res")" -o "$(to_win "$WORK/res.zip")" --no-crunch >/dev/null''')

# link step
s = s.replace('''"$BT/aapt2" link \\
  -o "$WORK/apk/base.apk" \\
  -I "$AJAR" \\
  --manifest "$APP/AndroidManifest.xml" \\
  "$WORK/res.zip" \\
  --java "$WORK/gen" \\''',
              '''"$BT/aapt2.exe" link \\
  -o "$(to_win "$WORK/apk/base.apk")" \\
  -I "$(to_win "$AJAR")" \\
  --manifest "$(to_win "$APP/AndroidManifest.xml")" \\
  "$(to_win "$WORK/res.zip")" \\
  --java "$(to_win "$WORK/gen")" \\''')

# javac step: Windows paths in the @file and the jar
s = s.replace('''find "$APP/src" -name '*.java' > "$WORK/sources.txt"
find "$WORK/gen" -name '*.java' >> "$WORK/sources.txt"
"$(command -v javac || echo javac)" -source 8 -target 8 -nowarn \\
  -bootclasspath "$AJAR" \\
  -d "$WORK/classes" \\
  @"$WORK/sources.txt" 2>&1 | grep -v '^Note:' || true''',
              '''find "$APP/src" -name '*.java' | while read -r f; do to_win "$f"; echo; done > "$WORK/sources.txt"
find "$WORK/gen" -name '*.java' | while read -r f; do to_win "$f"; echo; done >> "$WORK/sources.txt"
javac -source 8 -target 8 -nowarn \\
  -bootclasspath "$(to_win "$AJAR")" \\
  -d "$(to_win "$WORK/classes")" \\
  "@$(to_win "$WORK/sources.txt")" 2>&1 | grep -v '^Note:' || true
[ -f "$WORK/classes/com/bigrice/MainActivity.class" ] || { echo "javac failed" >&2; exit 1; }''')

# d8 step
s = s.replace('''find "$WORK/classes" -name '*.class' > "$WORK/classes.txt"
"$BT/d8" --release --min-api 26 --lib "$AJAR" --output "$WORK/dex" @"$WORK/classes.txt"''',
              '''find "$WORK/classes" -name '*.class' | while read -r f; do to_win "$f"; echo; done > "$WORK/classes.txt"
"$BT/d8.bat" --release --min-api 26 --lib "$(to_win "$AJAR")" --output "$(to_win "$WORK/dex")" "@$(to_win "$WORK/classes.txt")"
[ -f "$WORK/dex/classes.dex" ] || { echo "d8 failed" >&2; exit 1; }''')

# package step
s = s.replace('''python3 "$APP/assemble.py" "$WORK" "$WORK/apk/unsigned.apk"''',
              '''python3 "$APP/assemble.py" "$WORK" "$WORK/apk/unsigned.apk"''')

# align + sign: Windows paths
s = s.replace('''"$BT/zipalign" -f -p 4 "$WORK/apk/unsigned.apk" "$OUT/bigrice-native-aligned.apk"''',
              '''"$BT/zipalign.exe" -f -p 4 "$(to_win "$WORK/apk/unsigned.apk")" "$(to_win "$OUT/bigrice-native-aligned.apk")"''')
s = s.replace('''"$BT/apksigner" sign \\
  --ks "$KEYSTORE" --ks-pass "pass:$KS_PASS" --key-pass "pass:$KEY_PASS" --ks-key-alias "$KS_ALIAS" \\
  --v1-signing-enabled true --v2-signing-enabled true \\
  --out "$OUT/bigrice-native-$MODE.apk" "$OUT/bigrice-native-aligned.apk"''',
              '''"$BT/apksigner.bat" sign \\
  --ks "$(to_win "$KEYSTORE")" --ks-pass "pass:$KS_PASS" --key-pass "pass:$KEY_PASS" --ks-key-alias "$KS_ALIAS" \\
  --v1-signing-enabled true --v2-signing-enabled true \\
  --out "$(to_win "$OUT/bigrice-native-$MODE.apk")" "$(to_win "$OUT/bigrice-native-aligned.apk")"''')

s = s.replace('''"$BT/aapt2" dump badging "$OUT/bigrice-native-$MODE.apk" | head -2''',
              '''"$BT/aapt2.exe" dump badging "$(to_win "$OUT/bigrice-native-$MODE.apk")" | head -2''')

p.write_text(s, encoding='utf-8')
print('patched build.sh')
