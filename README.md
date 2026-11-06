# WebToApp Studio (Web/HTML to APK 進化版)

Web URLを入力するだけで、そのサイトを独立したAndroidアプリのように開けるランチャー/ビューアアプリです。

## 機能
- **Webアプリ作成**: URL・アプリ名・パッケージ名(ID)・アイコン色を設定して登録
- **AdBlock**: ON/OFF切替。主要広告ドメイン50種以上を `shouldInterceptRequest` で遮断
- **全画面表示**: ON/OFF切替(イマーシブモード)
- **ホーム画面ピン留め**: ShortcutManagerで各Webアプリを直接ホーム画面に追加
- **WebViewer**: アドレスバーなし・JavaScript/DOM Storage/WebGL対応・スワイプ更新・戻るボタンのページバックと終了確認
- **プリセット**: 「タニシ・サバイバー」(https://taniniya.github.io/tanishi-survivor/) を初期登録済み
- データはSharedPreferencesにJSONで永続化

## ビルド
```
gradle assembleDebug
# 出力: app/build/outputs/apk/debug/app-debug.apk
```

## APK
- [v1.0.0 リリース](https://github.com/taniniya/web-to-app/releases/tag/v1.0.0) からダウンロードできます。

### CI(任意)
`.github/workflows/build-apk.yml` を手動で追加すると、pushのたびにGitHub ActionsでAPKが自動ビルドされます。
