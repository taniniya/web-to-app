#!/usr/bin/env bash
# テンプレートAPK (app/src/main/assets_template/template.apk) をビルドするスクリプト。
# aapt2 + javac + d8 を直接使うことで、リソースパス (res/mipmap-*/ic_launcher.png) を
# 短縮されない形で保持し、ジェネレーターアプリがパスでアイコンを差し替えられるようにする。
#
# v2.1: minSdk を 21 (Android 5.0) に引き下げ、古い端末でもインストール・起動できるようにした。
#       ジェネレーター側で uses-sdk の値を APK ごとに書き換えるため、ここでは最小値を 21 にしておく。
#
# 使い方: JAVA_HOME=... ANDROID_HOME=... bash build-template.sh
set -euo pipefail

HERE="$(cd "$(dirname "$0")" && pwd)"
BT="$ANDROID_HOME/build-tools/34.0.0"
PLATFORM="$ANDROID_HOME/platforms/android-34/android.jar"
OUT="$HERE/build/template"
RES="$HERE/app/src/main/res"
SRC="$HERE/app/src/main/java"
MIN_API=21
TARGET_API=34

rm -rf "$OUT"
mkdir -p "$OUT/classes" "$OUT/dex"

echo "[1/4] javac"
"$JAVA_HOME/bin/javac" -source 8 -target 8 -classpath "$PLATFORM" -d "$OUT/classes" \
  "$SRC/com/webtoapp/standalone/MainActivity.java" \
  "$SRC/com/webtoapp/standalone/AdBlocker.java"

echo "[2/4] d8 (--min-api $MIN_API)"
"$BT/d8" --release --lib "$PLATFORM" --min-api "$MIN_API" \
  --output "$OUT/dex" $(find "$OUT/classes" -name '*.class')

echo "[3/4] aapt2 compile + link"
"$BT/aapt2" compile --dir "$RES" -o "$OUT/res.zip"
"$BT/aapt2" link \
  -o "$OUT/template-base.apk" \
  --manifest "$HERE/app/src/main/AndroidManifest.xml" \
  -I "$PLATFORM" \
  --min-sdk-version "$MIN_API" \
  --target-sdk-version "$TARGET_API" \
  --version-code 1 \
  --version-name 2.1 \
  --auto-add-overlay \
  "$OUT/res.zip"

echo "[4/4] make_template.py"
python3 "$HERE/tools/make_template.py" \
  "$OUT/template-base.apk" \
  "$OUT/dex/classes.dex" \
  "$HERE/app/src/main/assets/app_config.json" \
  "$HERE/app/src/main/assets_template/template.apk"
