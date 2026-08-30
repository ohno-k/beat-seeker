# beat-seeker Android アプリ（1タップ取り込み）

beat-seeker（PWA）を WebView で表示し、そこに **「ボタン 1 回でスコア CSV 取り込みまで完了する」** 機能だけを足したラッパーアプリです。

## なぜアプリが必要か

ブラウザのクロスオリジン制約により、`beat-seeker.com` のページから e-amusement GATE のスコアページを
**ユーザーのログイン Cookie 付きで** 取得することはできません（CORS で応答が読めず、サードパーティ Cookie も送られない）。
そのため Web 版ではブックマークレット（＝eagate のページ上で自前のコードを走らせる）が必要で、
特にスマートフォンでは登録手順が煩雑でした。

アプリなら **非表示の WebView で eagate を開いて同じ収集スクリプトを注入できる** ため、
ユーザー操作は「アプリ内のボタンを 1 回押す」だけになります。これがこのアプリの唯一の存在理由です。

## 構成

| ファイル | 役割 |
| --- | --- |
| `MainActivity.kt` | beat-seeker を表示する WebView。`BeatSeekerNative` をこのページにだけ注入する |
| `EagateScraper.kt` | 非表示 WebView で eagate を開き、収集スクリプトを注入して結果を受け取る |
| `EagateLoginActivity.kt` | eagate 未ログイン時だけ表示するログイン画面 |
| `Eagate.kt` | オリジン判定（どこにネイティブ API を出してよいか／どこにスクリプトを注入してよいか） |

収集処理そのものはアプリに入っていません。Web 側の `frontend/src/utils/eagateScraper.ts`
（ブックマークレットと共通）をビルドした `https://beat-seeker.com/native-scraper.js` を
**実行時にダウンロードして注入** します。eagate の HTML 構造が変わってもフロントのデプロイだけで追従でき、
アプリの再リリースが要らないためです。

同じ理由で、開く eagate の URL（作品バージョンを含む）も Web 側が `startImport(eagateUrl)` の引数で渡します。
新作稼働時は `frontend/src/utils/iidxVersions.ts` の `CURRENT_VERSION` 更新だけで追従できます。

## 通信プロトコル

```
[beat-seeker ページ]                 [ネイティブ]                    [eagate WebView]
  BeatSeekerNative.startImport(url) ──▶ EagateScraper.start(url)
                                            ├─ ログインページ？ ──▶ EagateLoginActivity
                                            └─ native-scraper.js を evaluateJavascript ──▶ 収集開始
                                                                              │
                                          bsBridge.postMessage ◀───────────────┘
                                          {progress|needLogin|error|chunk}
  __beatSeekerNative.onProgress()   ◀── evaluateJavascript
  __beatSeekerNative.onResultChunk()◀── （結果 JSON は分割して受け渡し）
```

未ログインを検知した場合はログイン画面を出し、**ログイン成功後に自動で収集を再実行**します。
Web 側は `onNeedLogin` では待機を解除せず、ユーザーから見ると「ボタンを押す → ログイン → そのまま完了」になります
（ログインをキャンセルした場合だけ `onError("login cancelled")` で中断）。

結果 JSON はブックマークレットがクリップボードに入れるものと**同一形式**です。
そのため Web 側は既存の取り込み処理（`UnifiedImport.vue` の `processText`）へそのまま流すだけで、
CSV パース〜サーバ登録の既存パイプラインが動きます。**バックエンドの変更は不要です。**

## セキュリティ上の約束事（変更時は必ず維持すること）

- `addJavascriptInterface`（＝ページ全体に無条件でネイティブ API を露出する）は
  **beat-seeker のページを表示する WebView にだけ** 付ける。eagate 側の WebView には絶対に付けない。
- eagate 側との通信は `WebViewCompat.addWebMessageListener` を使い、許可オリジンを eagate に限定する。
- `MainActivity` の WebView は beat-seeker 以外へ遷移させない（外部リンクは端末のブラウザへ逃がす）。
- KONAMI ID の認証情報はログイン用 WebView（＝eagate 自身）にしか渡らない。アプリは保持も送信もしない。

## ビルド

Gradle Wrapper（バイナリ）はリポジトリに含めていません。初回は以下のどちらかで生成してください。

```bash
cd android
gradle wrapper --gradle-version 8.7   # ローカルに Gradle がある場合
# あるいは Android Studio で android/ を開く（Wrapper は自動生成される）
```

その後:

```bash
./gradlew assembleDebug
./gradlew installDebug
```

`local.properties` に Android SDK のパスが必要です（Android Studio で開けば自動生成されます）。

### ローカル開発時

`app/build.gradle.kts` の `APP_URL` / `SCRAPER_SCRIPT_URL` を開発サーバに向けてください。
`http://` を使う場合は cleartext 通信の許可（`android:usesCleartextTraffic` またはネットワークセキュリティ設定）が別途必要です。

## 未着手 / 今後

- **実機での動作確認は未実施**。特に eagate の未ログイン時リダイレクト先パス（`Eagate.isLoginUrl`）は
  実際の挙動に合わせて調整が必要になる可能性があります。
- リリース署名設定（`signingConfigs`）、Play Console への登録。
- `WorkManager` による定期バックグラウンド同期（タップ 0 での自動取り込み）。
- アイコンは `frontend/public/icon-512.png` から機械的に縮小したもの。
  必要なら Android Studio の Image Asset で adaptive icon を作り直してください。
