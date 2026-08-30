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

アプリの取り込みは **公式のスコアデータ CSV ダウンロード**（`POST djdata/score_download.html` に `style=SP`）
を使います。難易度別ページを 12 レベル分ページングして巡回するブックマークレット方式と違い、
リクエスト 1 回で終わり、ミスカウント・ジャンル・アーティスト・バージョン・プレー回数・最終プレー日時まで
埋まった公式 CSV がそのまま得られます。ブックマークレットは従来どおり難易度別ページ方式のままです
（スコアは毎作リセットされるがクリアランプは永続するため、「スコア0・ランプあり」の譜面も拾えるという
利点があるため）。

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

## 実機で試す

### 前提: 先にフロントを反映すること

アプリは beat-seeker のページを表示し、収集スクリプト `/native-scraper.js` も **beat-seeker から実行時に取得** します。
どちらも今回の変更で初めて入るものなので、**フロントを先にデプロイしないとアプリ側は動きません**
（ボタンが出ない／`script download failed` になる）。順番は次のどちらかです。

- 本番へ反映してから試す … `https://beat-seeker.com` に今回のフロントをデプロイ → APK はデフォルト設定のままでよい
- ローカルで試す … PC で `cd frontend && npm run dev -- --host` を起動し、後述の `-PappUrl` で PC の LAN IP を指定する

### 方法1: GitHub Actions で APK を作る（Android SDK 不要・いちばん簡単）

1. GitHub の **Actions** タブ → **Android debug APK** → **Run workflow**
2. `app_url` は本番で試すなら空のまま。別環境を見せたい場合だけ URL を入れる
3. 完了後、そのランの **Artifacts** から `beat-seeker-debug-apk` をダウンロードして展開

`android/` 配下を触ってこのブランチに push したときにも自動で走ります。

### 方法2: 手元でビルドする

Android Studio で `android/` を開いて Run するのが最短です。コマンドラインの場合:

```bash
cd android
./gradlew assembleDebug
# → app/build/outputs/apk/debug/app-debug.apk
```

Android SDK の場所を `android/local.properties` に書いてください（Android Studio で開けば自動生成されます）。

```properties
sdk.dir=/Users/you/Library/Android/sdk
```

ローカルの開発サーバに向ける場合（PC の LAN IP を指定。`localhost` は端末自身を指すので不可）:

```bash
./gradlew assembleDebug -PappUrl=http://192.168.1.10:5173
```

debug ビルドは平文 HTTP を許可し、applicationId に `.debug` が付くので**本番版と同じ端末に並べて入れられます**。

### 端末へのインストール

USB デバッグを有効にした端末を繋いで:

```bash
cd android
./gradlew installDebug          # ビルドとインストールを一度に
# あるいは既にある APK を入れる
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

USB を使わない場合は APK をそのまま端末へ転送し、ファイルマネージャから開いてください。
初回は「提供元不明のアプリのインストール」の許可を求められます。

### 動作確認の手順

1. アプリを起動し、beat-seeker にログインする
2. 取り込みモーダルを開くと、最上部に青い **「1タップで取り込む」** が出る（出なければフロントが未反映）
3. 押す → 初回は eagate のログイン画面が出るのでログイン → そのまま収集が始まり、完了までボタン内に進捗が出る
4. 2 回目以降は Cookie が残っているのでログイン画面は出ない

### うまく動かないときの調べ方

PC の Chrome で `chrome://inspect` を開くと、端末の WebView（beat-seeker 側・eagate 側の両方）を
DevTools で覗けます。`adb logcat` と併せて見るのが手っ取り早いです。よくある原因:

- ボタンが出ない … 表示しているフロントに今回の変更が入っていない
- `script download failed` … `/native-scraper.js` が配信されていない（フロントのビルドで生成される）
- ログイン画面が出続ける … `Eagate.isLoginUrl` のパス判定が実際のリダイレクト先と合っていない

## 未着手 / 今後

- **実機での動作確認は未実施**。特に eagate の未ログイン時リダイレクト先パス（`Eagate.isLoginUrl`）は
  実際の挙動に合わせて調整が必要になる可能性があります。
- リリース署名設定（`signingConfigs`）、Play Console への登録。
  現状ビルドできるのは debug APK だけです。
- `WorkManager` による定期バックグラウンド同期（タップ 0 での自動取り込み）。
- アイコンは `frontend/public/icon-512.png` から機械的に縮小したもの。
  必要なら Android Studio の Image Asset で adaptive icon を作り直してください。

## 配布とインストール導線

Google Play には出していないため、**APK の直リンクをサイト側から配る**方式をとっています。

| 経路 | 用途 |
| --- | --- |
| `.github/workflows/android-release-apk.yml` | 固定タグ `android-latest` のリリースへ `beat-seeker.apk` を上書き公開（手動実行）。配布 URL が変わらないので、更新はワークフローを再実行するだけ |
| `.github/workflows/android-debug-apk.yml` | Actions の Artifacts へ APK を置く開発者向け（ダウンロードに GitHub ログインが必要なため配布には使えない） |

サイト側は `frontend/src/composables/useNativeBridge.ts` の `canInstallApp`
（＝ UA が Android かつ `BeatSeekerNative` 未注入 ＝ アプリ未導入のブラウザ）で判定し、
取り込みモーダル（`UnifiedImport.vue`）にダウンロードリンクを出します。
配布 URL は `VITE_ANDROID_APK_URL` で差し替え可能（空文字にすると導線ごと非表示）。

デバッグ署名の APK のため、端末側で「提供元不明のアプリのインストール」を許可する必要があります。
