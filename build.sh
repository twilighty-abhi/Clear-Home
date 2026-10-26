#!/bin/bash
# Builds build/ClearHome.apk using only the Android SDK command-line tools
# (no Gradle). Uses Android Studio's bundled Java if JAVA_HOME isn't set.
set -euo pipefail
cd "$(dirname "$0")"

VERSION_CODE=86
VERSION_NAME=0.86

SDK="${ANDROID_HOME:-$HOME/Library/Android/sdk}"
BT="$SDK/build-tools/$(ls "$SDK/build-tools" | sort -V | tail -1)"
ANDROID_JAR="$SDK/platforms/$(ls "$SDK/platforms" | sort -V | tail -1)/android.jar"
export JAVA_HOME="${JAVA_HOME:-/Applications/Android Studio.app/Contents/jbr/Contents/Home}"
export PATH="$JAVA_HOME/bin:$PATH"

OUT=build
rm -rf "$OUT"
mkdir -p "$OUT/classes" "$OUT/dex"

echo "• Compiling resources"
"$BT/aapt2" compile --dir app/res -o "$OUT/res.zip"
"$BT/aapt2" link -o "$OUT/unsigned.apk" -I "$ANDROID_JAR" \
  --manifest app/AndroidManifest.xml -A app/assets \
  --min-sdk-version 24 --target-sdk-version 34 \
  --version-code "$VERSION_CODE" --version-name "$VERSION_NAME" \
  "$OUT/res.zip"

echo "• Compiling Java"
javac -nowarn --release 11 -classpath "$ANDROID_JAR" -d "$OUT/classes" $(find app/src -name '*.java')

echo "• Dexing"
"$BT/d8" --release --min-api 24 --lib "$ANDROID_JAR" --output "$OUT/dex" $(find "$OUT/classes" -name '*.class')
(cd "$OUT/dex" && zip -q ../unsigned.apk classes.dex)

echo "• Signing"
if [ ! -f keystore/clearhome.keystore ]; then
  mkdir -p keystore
  keytool -genkeypair -keystore keystore/clearhome.keystore -storepass android -keypass android \
    -alias clearhome -keyalg RSA -keysize 2048 -validity 10000 -dname "CN=Clear Home" >/dev/null 2>&1
fi
"$BT/zipalign" -f -p 4 "$OUT/unsigned.apk" "$OUT/aligned.apk"
"$BT/apksigner" sign --ks keystore/clearhome.keystore --ks-pass pass:android --key-pass pass:android \
  --out "$OUT/ClearHome.apk" "$OUT/aligned.apk"

echo "✓ $OUT/ClearHome.apk ($(du -h "$OUT/ClearHome.apk" | cut -f1 | xargs))"
