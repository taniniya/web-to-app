# WebToApp Studio

**URL と設定を入れるだけで、単独で動く Android アプリ (APK) をその場で生成・署名して書き出す Android アプリ。**

v2.0 から「アプリ内で WebView を開くツール」から「**端末内で完結する APK ジェネレーター**」に生まれ変わりました。生成した APK は PC 不要でそのままインストールできます。

## できること

- 🔗 **URL → 単独 APK**: 入力した Web サイトを起動時に開く、独立したアプリを生成
- 🏷️ **アプリ名・パッケージ名**: アプリ名からパッケージ名を自動サジェスト（手動変更も可）
- 🖼️ **アイコン差し替え**: 端末から選んだ画像を mdpi 〜 xxxhdpi (48〜192px) に自動リサイズして差し込み
- 🛡️ **広告ブロック**: 約 60 種の広告ドメインを遮断する WebView で動作（ON/OFF 可）
- 📺 **全画面表示**: イマーシブ全画面で WebView を表示（ON/OFF 可）
- ✍️ **その場で署名**: v1 / v2 / v3 署名スキームで自動署名（apksig 使用）
- 📲 **今すぐインストール**: ビルド完了ダイアログからそのままインストーラーを起動、共有も可能

生成した APK は `Download/WebToApp/<アプリ名>.apk` に保存されます。

## 仕組み (v2.0 アーキテクチャ)

端末内で Gradle / aapt / zipalign を動かすことはできないため、次の構成で Pure Java だけで APK 生成を実現しています。

1. **`app/src/main/assets/template.apk`** — 単独動作する超軽量 WebView テンプレートアプリ（`com.webtoapp.standalone.template`、依存ライブラリなし、約 10 KB）。`assets/app_config.json` を読んで動作する。
2. **`ApkGenerator.java`** — 生成エンジン:
   - テンプレートのバイナリ XML (AXML) StringPool を書き換えてパッケージ名・アプリ名を差し替え（チャンクサイズとオフセットを再計算）
   - 選択したアイコン画像を 5 解像度にリサイズして `res/mipmap-*/ic_launcher.png` を上書き
   - `assets/app_config.json` に URL・アプリ名・広告ブロック・全画面設定を注入
   - **`ApkWriter`**: 無圧縮エントリ (resources.arsc) の 4 バイト境界アライメントを維持する最小 ZIP ライタ（zipalign 相当。targetSdk 30+ のインストール要件）
   - **`com.android.tools.build:apksig`** で v1 + v2 (+v3) 署名（埋め込みデバッグキーストアを使用）
3. リファレンス実装とテストは `template/tools/`（`make_template.py` / `end_to_end_test.py`）。

## ビルド

```bash
./gradlew assembleDebug   # または gradle assembleDebug
```

出力: `app/build/outputs/apk/debug/app-debug.apk`

### テンプレート APK の再ビルド（開発者向け）

```bash
export JAVA_HOME=... ANDROID_HOME=...
bash template/build-template.sh
# → template/app/src/main/assets_template/template.apk
# これを app/src/main/assets/template.apk にコピーしてから本体をビルドする
```

## リリース

- [v2.0.0](https://github.com/taniniya/web-to-app/releases/tag/v2.0.0) — APK ジェネレーター化（単独 APK のビルド・署名・書き出し、アイコン差し込み）
- [v1.0.0](https://github.com/taniniya/web-to-app/releases/tag/v1.0.0) — アプリ内 WebView ランチャー

---

AI 生成です　使用する場合は自己責任
