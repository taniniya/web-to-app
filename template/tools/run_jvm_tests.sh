#!/usr/bin/env bash
# run_jvm_tests.sh — 生成エンジン(ApkCore / WebAssets / SiteDownloader)を PC 上で検証する。
#
# アプリ内と同じ ApkCore.build() を通して APK を生成し、
# aapt / apksigner / zipalign でインストール可能な形かを確かめる。
# v2.2: URL からのオフライン化 (SiteDownloader) をローカル HTTP サーバで検証し、
#       その取得結果をそのまま APK に固めるケースまで通す。
#
# 使い方:
#   JAVA_HOME=... ANDROID_HOME=... bash template/tools/run_jvm_tests.sh
set -euo pipefail

HERE="$(cd "$(dirname "$0")" && pwd)"
ROOT="$(cd "$HERE/../.." && pwd)"
BT="${ANDROID_HOME:-/workspace/tools/android-sdk}/build-tools/34.0.0"
OUT="${OUT_DIR:-/workspace/build-test/v22}"
APKSIG="${APKSIG_JAR:-$(find "${GRADLE_USER_HOME:-$HOME/.gradle}" \
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
  "$ROOT/app/src/main/java/com/taniniya/webtoapp/SiteDownloader.java" \
  "$HERE/jvm/GeneratorHarness.java" \
  "$HERE/jvm/SiteDownloadHarness.java" \
  "$HERE/jvm/CoreTest.java"

echo "== logic test (WebAssets / SiteDownloader) =="
"$JAVA_HOME/bin/java" -cp "$OUT/classes:$APKSIG" CoreTest

echo "== offline download test (local HTTP server) =="
python3 "$HERE/offline_test.py" --classes "$OUT/classes" --apksig "$APKSIG" --out "$OUT/offline"

echo "== generate APKs (ApkCore) =="
"$JAVA_HOME/bin/java" -cp "$OUT/classes:$APKSIG" GeneratorHarness \
  "$TEMPLATE" "$OUT/out" "$KEYSTORE" "$HERE/sample-web" "$OUT/offline"

echo "== verify: URL モード (minSdk 21 / v2.2 新設定) =="
python3 "$HERE/verify_apk.py" "$OUT/out/url-mode-min21.apk" \
  --package com.webtoapp.tanishisurvivor --label "タニシ・サバイバー" \
  --min-sdk 21 --target-sdk 34 --require-v1 \
  --config 'url="https://taniniya.github.io/tanishi-survivor/"' \
  --config 'adBlock=false' --config 'orientation="auto"' \
  --config 'keepScreenOn=false' --config 'pullToRefresh=true' \
  --config 'progressBar=true' --config 'desktopMode=false' --config 'darkMode="auto"'

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

echo "== verify: minSdk 34 / target 補正 / 広告ブロックON / PC表示 / ダーク強制 =="
python3 "$HERE/verify_apk.py" "$OUT/out/edge-min34.apk" \
  --package com.webtoapp.banana --label "バナナサーバー" \
  --min-sdk 34 --target-sdk 34 \
  --config 'adBlock=true' --config 'fullscreen=false' \
  --config 'keepScreenOn=false' --config 'orientation="landscape"' \
  --config 'pullToRefresh=false' --config 'progressBar=false' \
  --config 'desktopMode=true' --config 'darkMode="on"'

echo "== verify: URL からオフライン化したサイトの APK =="
python3 "$HERE/verify_apk.py" "$OUT/out/offline-site.apk" \
  --package com.webtoapp.offlinesite --label "オフラインサイト" \
  --min-sdk 21 --target-sdk 34 \
  --config 'url="file:///android_asset/web/index.html"' \
  --config 'keepScreenOn=false' --config 'pullToRefresh=true' \
  --config 'progressBar=false' --config 'desktopMode=true' --config 'darkMode="on"' \
  --asset "assets/web/index.html:$OUT/offline/index.html"

echo
echo "ALL TESTS PASSED"
