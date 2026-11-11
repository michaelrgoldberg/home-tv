#!/bin/bash
set -e
SDK=/home/claude/sdk
BT=$SDK/android-15
ANDROID_JAR=$SDK/android-34/android.jar
APP=/home/claude/tvapp
OUT=/home/claude/tvbuild
KS=/home/claude/tvapp/hometv2.keystore
KSPASS="${KSPASS:?set KSPASS}"
rm -rf "$OUT"; mkdir -p "$OUT/gen" "$OUT/classes"
cd "$APP"

echo "[1/7] compile resources"
"$BT/aapt2" compile --dir res -o "$OUT/res.zip"

echo "[2/7] link resources + manifest"
"$BT/aapt2" link -o "$OUT/base.apk" -I "$ANDROID_JAR" \
  --manifest AndroidManifest.xml --java "$OUT/gen" \
  --min-sdk-version 22 --target-sdk-version 30 \
  "$OUT/res.zip"

echo "[3/7] javac"
javac -source 8 -target 8 -nowarn -cp "$ANDROID_JAR" -d "$OUT/classes" \
  $(find src "$OUT/gen" -name '*.java') 2>&1 | grep -vi "warning:\|bootstrap\|Picked up JAVA_TOOL" || true

echo "[4/7] d8 -> dex"
"$BT/d8" --min-api 22 --lib "$ANDROID_JAR" --output "$OUT" \
  $(find "$OUT/classes" -name '*.class') 2>&1 | grep -vi "Picked up JAVA_TOOL" || true

echo "[5/7] add classes.dex to apk"
cd "$OUT"; cp base.apk unaligned.apk
"$BT/aapt2" version >/dev/null 2>&1
cd "$OUT" && zip -j unaligned.apk classes.dex >/dev/null

echo "[6/7] zipalign"
"$BT/zipalign" -f 4 unaligned.apk aligned.apk

echo "[7/7] sign"
"$BT/apksigner" sign --ks "$KS" --ks-pass "pass:$KSPASS" --min-sdk-version 22 \
  --out hometv_v10.apk aligned.apk 2>&1 | grep -vi "Picked up JAVA_TOOL" || true

echo "DONE: $OUT/hometv_v10.apk"
md5sum "$OUT/hometv_v10.apk"
