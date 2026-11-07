#!/usr/bin/env bash
# run_jvm_tests.sh — 生成エンジン(ApkCore / WebAssets)を PC 上で検証する。
#
# アプリ内と同じ ApkCore.build() を通して APK を生成し、
# aapt / apksigner / zipalign でインストール可能な形かを確かめる。
#
# 使い方:
#   JAVA_HOME=... ANDROID_HOME=... bash template/tools/run_jvm_tests.sh
set -euo pipefail

HERE="$(cd "$(dirname "$0")" && pwd)"
ROOT="$(cd "$HERE/../.." && pwd)"
BT="${ANDROID_HOME:-/workspace/tools/android-sdk}/build-tools/34.0.0"
OUT="${OUT_DIR:-/workspace/build-test/v21}"
APKSIG="${APKSIG_JAR:-$(find "$(dirname "${GRADLE_USER_HOME:-$HOME/.gradle}")" \
  -name 'apksig-*.jar' 2>/dev/null | head -1)}"
TEMPLATE="$ROOT/app/src/main/assets/template.apk"
KEYSTORE="$ROOT/app/src/main/assets/debug.keystore"

if [ -z "$APKSIG" ]; then
  echo "apksig jar が見つかりません。APKSIG_JAR=... を指定してください" >&2
  exit 1
fi

rm -rf "$OUT"
mkdir -p "$OUT/classes"

echo "== compile =="
"$JAVA_HOME/bin/javac" -d "$OUT/classes" \
  -cp "$APKSIG" \
  "$ROOT/app/src/main/java/com/taniniya/webtoapp/ApkCore.java" \
  "$ROOT/app/src/main/java/com/taniniya/webtoapp/WebAssets.java" \
  "$HERE/jvm/GeneratorHarness.java" \
  "$HERE/jvm/CoreTest.java"

echo "== WebAssets logic test =="
"$JAVA_HOME/bin/java" -cp "$OUT/classes" CoreTest

echo "== generate APKs (ApkCore) =="
"$JAVA_HOME/bin/java" -cp "$OUT/classes:$APKSIG" GeneratorHarness \
  "$TEMPLATE" "$OUT/out" "$KEYSTORE" "$HERE/sample-web"

echo "== verify: URL モード (minSdk 21) =="
python3 "$HERE/verify_apk.py" "$OUT/out/url-mode-min21.apk" \
  --package com.webtoapp.tanishisurvivor --label "タニシ・サバイバー" \
  --min-sdk 21 --target-sdk 34 --require-v1 \
  --config 'url="https://taniniya.github.io/tanishi-survivor/"' \
  --config 'adBlock=false' --config 'orientation="auto"'

echo "== verify: ローカル HTML モード (minSdk 26 / target 33) =="
python3 "$HERE/verify_apk.py" "$OUT/out/local-html-min26.apk" \
  --package com.webtoapp.offlinecalc --label "オフライン計算機" \
  --min-sdk 26 --target-sdk 33 \
  --config 'url="file:///android_asset/web/index.html"' \
  --config 'zoomEnabled=false' --config 'orientation="portrait"' \
  --asset "assets/web/index.html:$HERE/sample-web/index.html" \
  --asset "assets/web/style.css:$HERE/sample-web/style.css" \
  --asset "assets/web/script.js:$HERE/sample-web/script.js" \
  --asset "assets/web/メモ.txt:$HERE/sample-web/メモ.txt"

echo "== verify: minSdk 34 / target 補正 / 広告ブロックON =="
python3 "$HERE/verify_apk.py" "$OUT/out/edge-min34.apk" \
  --package com.webtoapp.banana --label "バナナサーバー" \
  --min-sdk 34 --target-sdk 34 \
  --config 'adBlock=true' --config 'fullscreen=false' \
  --config 'keepScreenOn=false' --config 'orientation="landscape"'

echo
echo "ALL TESTS PASSED"
