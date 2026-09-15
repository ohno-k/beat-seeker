# ZINRAI 移行 当日スケジュール（2026-09-16 水 07:00 JST に自動実行）

作成: 2026-09-15 / 元計画: [次回作ZINRAI移行計画.md](./次回作ZINRAI移行計画.md)
時刻はすべて JST。DB の timestamp 列は UTC 基準（JVM が UTC）。

---

## 0. 方式（2026-09-15 夜に確定・実装済み）

**07:00 に人手なしで切り替わる。** 前夜（9/15）に本体をデプロイしておき、切替日時を過ぎた瞬間に
フロント・バックエンド・DB の 3 つが同じ日時を見て自動的に切り替わる。

| 層 | 仕組み |
| --- | --- |
| フロント | `iidxVersions.ts` の `CURRENT_VERSION` が「ページ読み込み時に 07:00 を過ぎていれば 34、それまでは 33」。再デプロイ不要。開きっぱなしのタブは再読み込みまで 33 のまま |
| バックエンド | `IidxVersions.current()` が同じ判定（`app.version-transition.launch-at` を `VersionSwitchConfigurer` が流し込む）。過去作取り込みの上限・履歴行の `version` などがこれに追随 |
| DB（自動実行） | `VersionTransitionScheduler` が 1 分ポーリングで 07:00 以降に **snapshot → copy-scores → reset** を 1 度だけ実行（`system_task_runs` に記録、二重実行なし、落ちていても次の起動で追いつく） |

自動実行の中身:

1. **snapshot** … `score_history_logs` の各ユーザー最新行（0PT 行は除外）を `version_pt_snapshots`(version=33) へ。順位付き。下限 100 人
2. **copy-scores** … `scores`（score>0・arcade のみ・譜面ごと最高）を `past_scores`(version=33) へ複製。約 110 万行。`SET LOCAL statement_timeout='900s'` で 30 秒制限を回避
3. **reset** … `score_history_logs.version` の null を 33 で埋める → `TRUNCATE scores` / `TRUNCATE user_song_ranks` → `users` の PT を 0 → 履歴ユーザー全員に **0PT 行**（version=34, tag=`version-transition`）→ 曲統計キャッシュ 4 種を再構築
4. 難易度表の適用は **管理画面から手動**（`apply-difficulty` は false）

ランキング SQL は「最新行の値が > 0」に組み替えてあるので、0PT 行を入れた瞬間に全ランキングが空になり、
新作の CSV を入れた人から順に並ぶ。成長記録ページは作品セレクト（初期 ZINRAI／Sparkle Shower を選べる）。

**止めたいとき**: Render の環境変数 `APP_VERSION_TRANSITION_DRY_RUN=true`（再起動で反映。件数ログだけ出して DB は触らない）。
**日時を動かしたいとき**: `APP_VERSION_TRANSITION_LAUNCH_AT=2026-09-16T09:00` のように（フロントの日時は `iidxVersions.ts` 側の定数なので同時に変える）。

---

## 1. 今夜（9/15）やること

| # | 作業 | 状態 |
| --- | --- | --- |
| 1 | 難易度表 draft の最終確認（ユーザー作業）。確定したら管理画面で `profile:final-zinrai-20260915` に保存 | 進行中 |
| 2 | 実装一式（§5）を **コミット → push**。Render backend / beat-seeker.com フロントの自動デプロイを両方 Live まで見届ける | コード完成・未コミット |
| 3 | デプロイ後のログに `[世代切替] 有効: 2026-09-16T07:00 JST に 33→34 の切り替えを実行予定（dryRun=false, 初期化=true, 難易度表の自動適用=false）` が出ていること | 未 |
| 4 | `GET /api/admin/version-transition/status?version=33` で `currentVersion: 33`, `switchAt: 2026-09-16T07:00`, `snapshotRows: 0`, `pastScoreRows: 0`, `usersWithHistory: ≈1066`, `resetHistoryRows: 0` | 未 |
| 5 | Render 環境変数の確認: `LEAGUE_SEASON_START=2026-09-21`。`APP_VERSION_TRANSITION_*` は **設定しない**（yml 既定で動く）。`LEAGUE_REQUIRE_PAST_SCORES_TO_JOIN` が true になっていないこと | 未 |
| 6 | Render で DB のバックアップ／リカバリポイントを確認（プランで可能なら手動バックアップ） | 未 |
| 7 | 告知: 「9/16 07:00 に移行。33 のスコアは『歴代』へ移動、BEAT-PT/RATE-PT は初期化。33 の最終 CSV は 07:00 までに取り込みを。ZINRAI の CSV は 07:00 以降に。リーグ #6 は 9/21 12:00 開始」 | 未 |

注意: 今夜デプロイしても **07:00 までは何も変わらない**（フロントは 33 のまま、スケジューラは待機）。
BETA 表記の削除と利用規約の一文だけはデプロイ直後から反映される。

---

## 2. 当日タイムライン（起きていなくても進む。確認だけ）

| 時刻 | 何が起きるか | 確認 |
| --- | --- | --- |
| 07:00 | フロントを読み込むと現行作が ZINRAI に。バックエンドも 34 を返す | サイドバーの歴代タブに「33 Sparkle Shower」が過去作として出る |
| 07:00〜07:01 | スケジューラが snapshot を実行（数秒） | status API の `snapshotRows` ≈ 1,066 |
| 07:01〜07:10 | copy-scores（100 万行 INSERT、数分） | `pastScoreRows` ≈ 1,095,000（dry-run 相当の値は status の `currentScoreRows` ではなく実行ログ参照） |
| 直後 | reset（数十秒）→ キャッシュ再構築 | `currentScoreRows: 0`, `resetHistoryRows` ≈ 1,066。ランキングが空、自分のプロフィールが 0 pt |
| 07:15 | **手動: 管理画面「難易度表を適用」** → 第 6 版が更新履歴に自動記録。scores が空なので再計算は一瞬 | 更新履歴ページに第 6 版 |
| 07:20 | 動作確認（§3） | — |
| 公式待ち | ZINRAI の e-amusement が開いたら CSV を 1 つ取得し「バージョン」列の表記を確認（`ZINRAI` 想定、大小は不問）。違えば `VERSION_LABELS` をホットフィックス。自分の 34 CSV を取り込んでスコアが入ることを確認 | 34 CSV が現行作として通る |
| 当日中 | 告知「移行完了」。docs / メモリ更新 | — |

Render のインスタンスがスリープしていると 07:00 ちょうどには走らない（次にアクセスがあった時に追いつく）。
確実にしたければ 07:00 に一度サイトを開く。

**ログの見方**（Render Logs）: `[世代切替] snapshot 完了: 対象 N 人 / 書き込み N 件` → `copy-scores 完了: 複製 N 行` → `初期化完了: scores N 行・user_song_ranks N 行を削除 / …` → `曲統計キャッシュを再構築した` → `自動実行できる手順は完了`。
FAILED が出た場合は `system_task_runs` の該当行を消せば次のポーリングで再試行される（copy は `ON CONFLICT DO NOTHING` なので再実行で埋まる）。**reset は 1 度きり**（0PT 行のガードはあるが TRUNCATE は戻せない）。

---

## 3. 切替後の確認項目

- サイドバー・スマホヘッダーの BETA リボンが無い。About の「正式版 公開中」。利用規約の「ベータ版」の一文が無い
- 「歴代」タブ／`/past-scores/33` に **33 Sparkle Shower** が過去作として出る。「歴代ベストを反映」トグルが 33 を拾う
- ランキング（BEAT-PT / RATE-PT / 精度 / KENBAN / SARA）が空 → 34 CSV を入れた人から並ぶ
- 成長記録: 初期表示 ZINRAI（空）、セレクトで Sparkle Shower に切り替えると前作の履歴が全部見える
- CSV 取り込み: 33 の CSV（新曲未プレー）を入れると「どの作品の CSV ですか？」モーダル（Sparkle Shower → 歴代へ／ZINRAI → 現行へ）。30 日後に自動で消える
- リーグページに「次回の開催回 #6 は 9/21 12:00 に開始予定」
- ティアアイコンの外枠: ダッシュボードとランキングで前作ティアの色の枠が出る（BEAT と RATE で別の色）。サポーターは斜めの光沢。自分の外枠は今作で PT が動いても変わらない
- ランキングの作品セレクトで「33 Sparkle Shower」を選ぶと終了時点の順位表（BEAT/RATE/KENBAN/SARA）が出る。人数が約 1,066 人（BEAT）であること
- `GET /api/admin/version-transition/status?version=33` の `runs` に snapshot / copy-scores / reset が SUCCESS
- Android アプリ・ブックマークレットの導線が `/game/2dx/34/` を向く
- 過去作 CSV（32 以前）の取り込みが従来どおり動く

---

## 4. 触らないもの・残るもの

- `past_scores` / `version_pt_snapshots` / `league_*`（#1〜#5 の結果・DIVISION・PT）/ `timeline_events` / `activity_logs` / `user_song_options` / `result_images` / 大会系 / `difficulty_*`
- `user_comparison_stats`（334 万行・8/29 から日次更新が失敗中）。33 時代の比較が残るが、日次バッチが通れば空になる
- 前作の成長記録（`score_history_logs` の 33 行）はそのまま。`version=33` で引ける

---

## 5. 今夜デプロイする実装（2026-09-15 実装・コンパイル確認済み）

| 領域 | 内容 |
| --- | --- |
| バックエンド | `IidxVersions` を切替日時方式に（`current()` / `maxPast()` / `NEXT=34` / `PREVIOUS=33`、`VersionSwitchConfigurer` が `launch-at` を流し込む） |
| | `ScoreHistoryLog.version` 列を追加。保存時（アップロード／難易度改訂の再計算）に現行作を入れる。起動時に null を 33 でバックフィル（`DataInitializer`） |
| | `/api/scores/history?version=`・`/api/admin/users/{id}/history?version=`（省略時は現行作）。フレンド／公開／共有の履歴は現行作固定 |
| | ランキング SQL 5 本＋ARENA 平均 2 本: `> 0` を「最新行の値」に対して掛ける（0PT 行で初期化できるように） |
| | `VersionTransitionService`: `SET LOCAL statement_timeout='900s'`、初期化ステップ `resetCurrentVersionData`、0PT 行は snapshot の対象外 |
| | `VersionTransitionScheduler`: reset ステップ、完了後はポーリング停止、reset 後に曲統計キャッシュ 4 種を再構築 |
| | `application.yml` の `app.version-transition` 既定（launch-at 2026-09-16T07:00 / dry-run false / reset-scores true / apply-difficulty false） |
| フロント | `iidxVersions.ts`: `CURRENT_VERSION` を切替日時で決定、34 ZINRAI のラベル・短縮 `ZR`・色、`HISTORY_VERSIONS`、`isInVersionSwitchGrace()`（30 日） |
| | 成長記録（`UploadHistory.vue`）に作品セレクト（初期 ZINRAI、Sparkle Shower を選択可） |
| | 取り込み確認モーダル（`ImportVersionConfirmModal.vue`）に「前作／現行作を選ぶ」モード。`App.vue` が稼働後 30 日間・前作判定のときだけ出す |
| | BETA リボン削除（`App.vue` / `Sidebar.vue`）、About バッジを「正式版 公開中」、利用規約の「ベータ版」の一文を削除（3 言語） |
| | `eagateScraper.ts` のフォールバックを 34 に |
| ティアアイコン | **外枠＝前作ティア**（`RankIcon.vue` の `frameRankName` / `frameTier`。色は前作ティアのパレット、光量はサブティア 1〜5。前作の記録がある人は全員、今作で上がっても前作固定）。**光沢＝サポーター**（本体を斜めに横切る反射、`isSupporter`）。旧「サポーター金縁」と設定「サポーター枠を表示」は廃止 |
| | 前作 PT の供給: `PreviousVersionPtService`（`version_pt_snapshots` の version=現行−1 を 5 分キャッシュ）。`/api/auth/me`・公開プロフィール・共有・フレンド一覧/検索・BEAT/RATE/KENBAN/SARA/AVERAGE/難易度別ランキング・リーグ順位表/DIVISION ランキング/昇降格ニュースの行に `previousBeatPt` / `previousRatePt` を同梱 |
| | 配線: ダッシュボード（BEAT/RATE 両方、共有ページは閲覧対象の値）、成長記録、ランキング一覧（BEAT/RATE）、難易度別ランキング、フレンド一覧、リーグ各表（2xs lite = フィルタ無しの半透明ストロークで軽量） |

07:00 のスナップショットまでは `previousBeatPt` が全員 null なので外枠は出ない。スナップショット後は 5 分以内（再起動なら即時）に全員へ付く。

| 前作ランキング | ランキングページに作品セレクト（初期 ZINRAI、Sparkle Shower を選ぶと終了時点のランキング）。`/api/scores/ranking?version=33`・`/rate-ranking`・`/kenban-ranking`・`/sara-ranking` が `version_pt_snapshots` から現行と同じ行の形で返す（`PreviousVersionPtService#archivedRanking`。表示名・公開設定・サポーターは現在の users を優先、前日比は 0、仮想 TOP ランカーは混ぜない）。AVERAGE・シミュレーションはアーカイブが無いので現行作のまま（注記を表示） |

---

## 6. 週内（後回しでよいもの）

- ティアコメント／管理画面のコメント欄のアイコンにはまだ外枠を付けていない（コメント API に前作 PT を足せば付く）
- 前作ランキングの精度（precision）と AVERAGE はスナップショットに無いので出せない（必要なら snapshot に列を足して 35 以降で対応）
- INFINITAS 取り込み UI の廃止（`useInfinitasMonitor.ts` ほか）
- 新規譜面の 200 プレイごと自動配置バッチ（閾値は移行計画 §8.1 の MAX-率境界）
- `user_comparison_stats` 日次バッチのタイムアウト修正（8/29 から毎日 FAILED）
- **9/21（月）0:00〜12:00 に管理画面「編成」を押し直す**（9/14 0:00 の自動編成が 33 データで組んだ draft が残っているため）→ 12:00 に #6 自動開始
- 落ち着いたら `self-best-includes-past` / `baseline-includes-past` を false に戻す
- 落ち着いたら `iidxVersions.ts` / `IidxVersions.java` の切替日時ロジックを 34 固定に畳む（任意）
