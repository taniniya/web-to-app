# WebToApp Studio

**URL・HTMLファイル・設定を入れるだけで、単独で動く Android アプリ (APK) を端末内で生成・署名して書き出す Android アプリ。**

v2.1 で「アドレス入力の分かりやすさ」「HTML/CSS/JS のオフライン同梱」「起動しない不具合の修正」「対応Androidバージョンの細かい設定」を追加しました。

## できること (v2.1)

- 🌐 **Webサイト (URL) → APK**: アドレスを入れるだけ。`https://` は自動補完、URLからアプリ名も自動で推測
- 📁 **ローカルファイル → APK**: HTML / CSS / JS を選択（複数選択可）、または ZIP を選択。**ZIPはそのまま展開**してアプリに同梱し、`index.html` を起点に**完全オフライン**で動くアプリを生成
- 🏷️ **アプリ名・パッケージ名**: アプリ名からパッケージ名を自動サジェスト（手動変更も可）
- 🖼️ **アイコン差し替え**: 選んだ画像を mdpi〜xxxhdpi (48〜192px) に自動リサイズして差し込み
- 🛡️ **広告ブロック (Beta)**: 約 60 種の広告ドメインを遮断。**ベータ機能のため標準はオフ**
- ⚙️ **詳細設定**:
  - 最小対応Androidバージョン (minSdkVersion) … Android 5.0 / 7.0 / 8.0 / 10 / 12 / 14
  - ターゲットバージョン (targetSdkVersion) … Android 14 / 13 / 12
  - 画面の向き … 自動回転 / 縦固定 / 横固定
  - 全画面表示 / 画面の常時点灯 / ピンチズーム許可 … それぞれ ON/OFF
- ✍️ **その場で署名**: v1 / v2 / v3 署名スキームで自動署名（apksig 使用）
- 📲 **今すぐインストール**: ビルド完了ダイアログからそのままインストーラーを起動、共有も可能

生成した APK は `Download/WebToApp/<アプリ名>.apk` に保存されます。

## v2.1 の修正（「APKが起動できない」問題）

1. **全画面制御の NullPointerException を解消** — `Window.getInsetsController()` は DecorView が付く前だと null を返すため、`setContentView()` 後と `onWindowFocusChanged()` でのみ呼び、null チェックと try-catch で保護（テンプレート `MainActivity`）。
2. **minSdkVersion を 26 → 21 に引き下げ** — Android 5.0 以降でインストール可能に。テンプレートの dex も `--min-api 21` で再ビルド。
3. **`file:` / `about:` スキームを WebView 内で許可** — ローカルHTMLアプリの遷移が外部ブラウザに飛ばされない。
4. **WebSettings に `setAllowFileAccess` / `setAllowContentAccess` / `setAllowFileAccessFromFileURLs` / `setAllowUniversalAccessFromFileURLs` を設定** — オフラインアプリの読み込みを確実に。
5. **起動時エラーでも落ちない** — `onCreate` 全体を try-catch で保護し、WebView が使えない端末では理由と再試行ボタンを画面表示。
6. **`uses-sdk` の値を生成時に書き換え** — 選んだ minSdkVersion / targetSdkVersion をマニフェストの整数属性（resId `0x0101020c` / `0x01010270`）として直接書き換え。

## 仕組み (v2.1 アーキテクチャ)

端末内で Gradle / aapt / zipalign を動かすことはできないため、次の構成で Pure Java だけで APK 生成を実現しています。

1. **`app/src/main/assets/template.apk`** — 単独動作する超軽量 WebView テンプレートアプリ（`com.webtoapp.standalone.template`、依存ライブラリなし、約 12 KB）。`assets/app_config.json` を読んで動作する。
2. **`ApkCore.java`** — **Android API に依存しない** APK 生成エンジン:
   - テンプレートのバイナリ XML (AXML) StringPool を書き換えてパッケージ名・アプリ名を差し替え
   - `<uses-sdk>` の `minSdkVersion` / `targetSdkVersion` を属性値ごと書き換え
   - アイコン PNG を 5 解像度で差し替え
   - `assets/app_config.json` に URL・全画面・常時点灯・ズーム・向き・広告ブロックを注入
   - `assets/web/**` に選択した HTML / CSS / JS を同梱
   - **`ApkWriter`**: 無圧縮エントリ (resources.arsc) の 4 バイト境界アライメントを維持する最小 ZIP ライタ（zipalign 相当。targetSdk 30+ のインストール要件）。非ASCIIファイル名は UTF-8 フラグ付き
   - **`com.android.tools.build:apksig`** で v1 + v2 (+v3) 署名（埋め込みデバッグキーストアを使用）
3. **`WebAssets.java`** — ZIP展開、共通ルートフォルダ除去、`index.html` 決定、`<title>` / URL からのアプリ名推測（こちらも Android 非依存）
4. **`ApkGenerator.java`** — assets 読み出し・画像リサイズ・保存だけを行う Android 側ラッパー

`ApkCore` と `WebAssets` は Android 非依存なので、**PC 上で同じコードをそのまま実行して生成物を検証できます**。

## ビルド

```bash
# JDK 17 / Android SDK 34 / Gradle 8.7
gradle assembleDebug
# 出力: app/build/outputs/apk/debug/app-debug.apk
```

### テンプレート APK の再ビルド（開発者向け）

```bash
export JAVA_HOME=... ANDROID_HOME=...
bash template/build-template.sh
# → template/app/src/main/assets_template/template.apk
# これを app/src/main/assets/template.apk にコピーしてから本体をビルドする
```

### 生成エンジンのテスト（PC 上で実行）

```bash
JAVA_HOME=... ANDROID_HOME=... APKSIG_JAR=... bash template/tools/run_jvm_tests.sh
```

- `template/tools/jvm/CoreTest.java` … ZIP展開・index検出・アプリ名推測のロジックテスト
- `template/tools/jvm/GeneratorHarness.java` … `ApkCore.build()` で実際に APK を 3 種類生成
- `template/tools/verify_apk.py` … `aapt dump badging`（パッケージ名/アプリ名/min・targetSdk）、`aapt dump xmltree`（マニフェスト破損チェック）、`apksigner verify`、`zipalign -c 4`、同梱 asset のバイト一致を検証

## リリース

- [v2.1.0](https://github.com/taniniya/web-to-app/releases/tag/v2.1.0) — アドレス入力UI刷新 / ローカルHTML・ZIP対応 / 起動クラッシュ修正 / 詳細設定（min・target SDK、向き、常時点灯、ズーム）/ 広告ブロックをBeta化
- [v2.0.0](https://github.com/taniniya/web-to-app/releases/tag/v2.0.0) — APK ジェネレーター化（単独 APK のビルド・署名・書き出し、アイコン差し込み）
- [v1.0.0](https://github.com/taniniya/web-to-app/releases/tag/v1.0.0) — アプリ内 WebView ランチャー

---

AI 生成です　使用する場合は自己責任
