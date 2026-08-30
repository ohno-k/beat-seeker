# 次回作 IIDX 34 ZINRAI 移行計画（構想メモ）

作成: 2026-08-19 / 更新: 2026-08-30（先行実装を反映）/ ステータス: **稼働前の実装は完了。残りは稼働日の運用作業**
対象: IIDX 34 **ZINRAI** 初日 — **現在の予測は 2026-09-16**（KONAMI の稼働告知で確定させること）

> **朗報**: 過去作スコアの基盤は既に完成している（`past_scores` テーブル、「歴代」タブ、作品バッジ、
> `CURRENT_VERSION` 定数）。移行の大部分は **「バージョン番号を 34 に上げ、33 を `past_scores` へ移す」**
> という既存レールに乗せる作業になる。

---

## 1. スコアの世代分離 — 既存の「歴代スコア」機構に載せる

- 現行作 **33 Sparkle Shower のスコアは「過去スコア」扱い**にする。
- 見せ方は **既存の歴代スコア（過去作スコア）と同じ**。専用の新機能は不要。

既にあるもの:

| 層 | 実体 |
| --- | --- |
| DB | `past_scores` テーブル（`PastScore` entity） |
| API | `PastScoreController` / `PastScoreRepository`（`/api/scores/past/best` ほか） |
| フロント | `usePastScores.ts`（歴代自己ベストの組み立て）、`PastScoreManager.vue`、「歴代」タブ、「歴代ベストを反映」トグル、作品バッジ |
| 定数 | `frontend/src/utils/iidxVersions.ts` / `backend/.../service/IidxVersions.java` |

**設計上の効き目**: `past_scores` は **ランキング・BEAT-PT・リーグ・大会の集計に構造上参加しない**。
したがって「33 を past_scores へ移す」だけで、集計 SQL を一切触らずに世代が切り替わる。

### やること

- `scores` にある 33 のスコアを **`past_scores`（version=33）へ移送**する。
  → ユーザーに 33 の CSV を再取り込みさせない（サーバ側の移送バッチが必要）。
- バージョン定数を 34 に上げる（下記 §7）。

## 2. ポイントの初期化

- **BEAT-PT / RATE-PT を初期化**する。
- `scores` を空にすれば **自動的に初期化される**（集計は現行作のみを見るため）。
- ⚠ **§4 のスナップショット保存が完了してから**実行すること。
- **2026-08-30 実装済み**: [VersionTransitionService#resetCurrentScores](../backend/src/main/java/com/beatseeker/backend/service/VersionTransitionService.java)。
  詳細は §9。既定では動かず、`APP_VERSION_TRANSITION_RESET_SCORES=true` にして初めて対象になる。

## 3. beta 版の終了

- **ZINRAI 初日をもって beta 版扱いを終了**し、正式サービスとする。
- **変更は beta 表記の削除だけ**。機能開放・課金・利用規約の変更は**含まない**。
- **2026-08-30 実装済み**: ロゴタイルの「BETA」リボン（`Sidebar.vue` とモバイルヘッダの 2 か所）を削除し、
  About のヒーローバッジ `about.heroBadge` を 3 言語とも「正式サービス 公開中」相当へ変更した。
- ⚠ **未対応（意図的）**: 利用規約の免責事項 `terms.disclaimerItem1` に残る
  「基本ベータ版として提供されます」の一文。**利用規約の変更は含まない**という方針に触れるため、
  文面を変えるかどうかは別途判断すること。

## 4. 過去作ランキングのアーカイブ

- **Sparkle Shower 終了時点の全員の BEAT-PT / RATE-PT をスナップショット保存**し、過去作ランキングとして閲覧可能にする。
- **一世代限りではなく、34 → 35 と積み上がるアーカイブとして設計する**
  → スナップショットに **作品バージョンID** を持たせ、作品を選んでランキングを見られる形にする。
- **ティア／サブティアも一緒に保存する**（§5 で前作ティアの色が必要になるため）。

**2026-08-30 実装済み（閲覧 UI）**。

- [PastRankingController.java](../backend/src/main/java/com/beatseeker/backend/controller/PastRankingController.java)
  — `GET /api/past-rankings/versions` / `/{version}` / `/previous-tiers`。未ログインでも閲覧可。
- [PastRanking.vue](../frontend/src/components/PastRanking.vue) と `/past-ranking` ルート、
  サイドバーの「もっと見る」に導線。作品セレクタと BEAT / RATE の切替を持つ。
- 表示名と IIDX ID は**撮影時点の値**、公開範囲とサポーター表示は**現在の設定**に従う。
- アーカイブが 0 件の現在は「まだ保存された過去作のランキングはありません」と出るだけ。

> ⚠ **実行順序**: 「4. 保存」→「1. 移送」→「2. 初期化」。初期化してからでは復元できない。

## 5. ティアアイコンの外枠と光沢

### 外枠 — 前作の到達点を示す（サポーター限定ではない）

- 現在の**外枠の金色を廃止**する。
- 外枠は**前作（Sparkle Shower）終了時のティアの色**で光らせる。
- **サブティア 1〜5 に応じて光量を強くする**（高いほど強発光）。
- **今作でティアが上がっても、外枠は前作のまま固定**（作品をまたいで更新しない）。
- **前作の BEAT-TIER があれば全員に付ける**（2026-08-19 変更。従来のサポーター限定を廃止）。
  前作の記録が無い人には外枠が付かない。

### 光沢 — サポーターであることを示す

- アイコン**本体の表面を斜めに横切る反射**。ティアや前作の記録に関係なく、サポーターであれば全員。
- **サポーターかどうかが干渉するのは光沢だけ**になる。

→ 組み合わせは「外枠のみ」「光沢のみ」「両方」「どちらも無し」の 4 通り。

**2026-08-30 実装済み**。

- [RankIcon.vue](../frontend/src/components/RankIcon.vue): 金グラデーション `supporter-grad-*` と
  外周グロー `supporter-glow-*` を前作ティア色の `past-grad-*` / `past-glow-*` へ置き換え、
  発動条件を `isSupporter` から `pastRankName` の有無へ付け替えた。光沢は `isSupporter` で別途付く。
  サブティア 1→5 でぼかし幅と濃さが段階的に上がる。Beginner は最弱・Legend は最強で固定
  （どちらもサブティアを持たないため）。
- [usePastTiers.ts](../frontend/src/composables/usePastTiers.ts): 前作ティアの供給元。
  `GET /api/past-rankings/previous-tiers` を 1 回だけ叩き、userId / iidxId の両方で引ける。
  サーバは **PT だけを返し、ティアの導出は `beatTier.ts` に一本化**している（閾値調整で表示がずれないため）。
- 反映済みの画面: ランキング一覧・ダッシュボード・アップロード履歴・難易度別ランキング。
- **アーカイブが 1 件も無い間は誰にも外枠が付かない**ので、スナップショット前にデプロイしても
  「サポーターの金枠が光沢に変わる」以外の見た目の変化は起きない。
- ⚠ ユーザー設定 `users.show_supporter_border`（プロフィールのトグル）は**カラム名はそのまま**で、
  意味だけ「金枠の表示」から「光沢の表示」へ移した。表示文言は 3 言語とも更新済み。

## 6. INFINITAS スコア読み込みの廃止

- **INFINITAS のスコア読み込みは廃止の方向**。
- 影響範囲: `useInfinitasMonitor.ts`（getDisplayMedia + OCR での STAGE RESULT 検知）、
  `infinitasAutoImport.ts`、`scores.source = 'infinitas'` のデータ、関連 UI。
- 副作用: 配信オーバーレイ構想が想定していた「INFINITAS 配信中のリアルタイム検知をデータ源にする」
  という前提が崩れる。アーケード向けの「スマホからリモコンで現在の曲を選ぶ」案が主軸になる。

## 7. バージョン番号の切り替え（同期定義が 2 箇所）

| ファイル | 変更内容 |
| --- | --- |
| `frontend/src/utils/iidxVersions.ts` | `CURRENT_VERSION` 33→34 / `VERSION_LABELS` に `34: 'ZINRAI'` / `VERSION_SHORT` に短縮ラベル / `SUPPORTED_VERSIONS` の配列に 34 |
| `backend/.../service/IidxVersions.java` | `CURRENT` 33→34 / `NAMES` に 34 |

- `MAX_PAST = CURRENT - 1` なので、**33 は自動的に「過去作として取り込み可」になる**。
- ⚠ `VERSION_LABELS` は**閉じた集合**で、未知ラベル = 未対応の新作として**取り込みを拒否**する設計。
  ZINRAI のラベルを入れないと 34 の CSV が全て弾かれる。
- ラベルは**公式 CSV の「バージョン」列の実表記**に合わせる（大文字小文字が不規則。
  例: `tricoro` / `copula` は小文字始まり、`Sparkle Shower` は全大文字ではない）。稼働後に実物で確認すること。
- **2026-08-30 実装済み**: 2 箇所とも 34 へ切り替え、`VERSION_SHORT` に `34: 'ZI'`、
  `SUPPORTED_VERSIONS` に 34 を追加した。33 は 34 にプライマリカラー（blue）を譲って
  バッジ・グラフ色とも violet へ移した。
- ⚠ `34: 'ZINRAI'` は**稼働前に置いた暫定値**。稼働後に公式 CSV の実表記で必ず確認すること
  （表記が違うと 34 の CSV が全て弾かれる）。
- ⚠ このコミットは**マージした瞬間に現行作が 34 になる**。稼働日まではマージしないこと。

## 8. 難易度の大幅改定と、新規譜面の自動配置

### 8.1 ZINRAI 初日の全曲再判定 — **draft 作成済み（2026-08-19）**

- **MAX-率を計算し、その閾値で難易度を決定**する。**対象は全曲**。
- 使うデータは **Sparkle Shower までに集まった実データ**（初日時点で新作のデータはゼロのため）。
- **既存曲は改定時に確定し、以後は再判定しない。**

**確定ルール**

1. **13.1 は手動指定** — **Mare Nectaris / 惑星鉄道 / SμG@R RU$# / 駅猫のワルツ** の 4 曲を固定。
   それ以外の曲は MAX-率が 0.0% でも物量加点が乗っても **13.0 が上限**（元 13.1 候補だった Thor's Hammer / DIAVOLO は 13.0）。
2. 残りは **MAX-率 昇順 → 同率は平均スコアレート昇順**で 1 列化し、**現行 active の帯別曲数を定員**として 13.0 以下へ上から詰める。
3. **定員を消費するのは既に数値帯にいた曲だけ**。**Uncategorized から新しく表に入る 17 曲は、自分の MAX-率順位が指す帯へ「相乗り」する**
   — 定員を食わないので既存曲を押し出さず、その帯だけが 1 曲増える。
4. **物量加点**: **1800 以上で +0.1、以降 200 ごとに +0.1**。**加点で到達できるのは 12.8 まで**（12.9 以上へは押し上げない。MAX-率だけで 12.9 以上にいる曲は据え置き）。**少ノーツ側の減点は設けない**。
   → +0.1 が **195 曲**、+0.2 が **71 曲**、+0.3 が **21 曲**、+0.4 が **3 曲**（計 290 曲）。うち **14 曲**が 12.8 で頭打ち、**31 曲**は元から 12.8 以上で加点が効かない。
5. MAX-率のデータが無い曲は Uncategorized に残置 — 該当は **`Any%` の 1 曲のみ**。

**2 案を保存済み**（物量加点の効かせ方が違う）

| | 帯サイズ | 13.1 / 13.0 / 12.9 / 12.8 | 移動量 |
| --- | --- | --- | --- |
| **案A 上乗せ** | 加点の分だけ変動（12.8 が膨張） | 4 / 8 / 10 / **67** | 難化576 易化363 据置355 |
| **案B 詰め直し**（現 draft） | **既存曲は active と一致**、新規参入分だけ増 | 4 / 8 / 10 / 35 | 難化472 易化441 据置381 |

- 実行スクリプト: [scripts/rebuild-draft-maxminus-zinrai.js](../scripts/rebuild-draft-maxminus-zinrai.js)（dry-run 既定 / `--apply` / `--plan=A|B`）
- レポート: [data/zinrai_rebuild_report.md](../data/zinrai_rebuild_report.md)、明細: `data/zinrai_rebuild_changes.json`
- プロファイル: `profile:ZINRAI-planA` / `profile:ZINRAI-planB`（管理画面のプロファイル読込で切替可）
- 最大移動: **+1.0**（Catch Me[L] 11.5 → 12.5）／ **-0.6**
- 主因の内訳: MAX-率 **865** ／ 物量 **21** ／ 詰め直し **23** ／ 13.1 手動 **4**
- ⚠ **副作用**: 13.1 へ手動で 4 曲抜いたぶん定員の連鎖が末端で不足し、**最下帯 11.0 が 18 → 14 曲**（＋新規参入 1 曲で 15 曲）になる。
- ⚠ **active には未適用**。ユーザーの目視確認待ち。

### 帯サイズを固定しない方式（試算のみ・不採用）

- **案C 閾値方式**（現行帯の MAX-率中央値を境界に）: 13.0 ≤ 0.3%、12.9 ≤ 1.4%、… 11.1 ≤ 53.8%。13.0 が 8 → 3、12.8 が 34 → 51 と上位帯の粒度が変わる。
- **案D 等幅閾値は成立しない**: MAX-率は下に強く偏る（中央値 24.8%／p90 41.9%／最大 72.8%）ため、0〜73% を 22 等分すると 13.1 が 73 曲・11.0 が 2 曲、1,286 曲が難化する。
- **案E 均等配置**（各帯 60 曲）も難化 805 曲で現行との連続性が失われる。
- ただし **§8.2 の「200 プレイごとの新曲自動配置」は閾値方式と相性が良い**（定員方式だと新曲 1 曲ごとに既存曲を押し出す）。改定は案B、以後の新曲は閾値、という併用も取れる。

### 8.2 新規譜面の継続的な再配置

- **200 プレイされるごと**に、その時点の MAX-率を算出して**しかるべき難易度帯へ格納**する。
- **200 の単位は既存の MAX-率の分母と同じ** = `score > 0` の全プレイ行（`COUNT(*)`、ユーザー重複を排除しない）。
- **実力解禁譜面は自動配置の対象外**（上位層しかプレイせずサンプルが偏るため）→ **手動で配置する**。

### MAX-率の定義（取り違え注意）

「曲別平均スコアレート」ページの **MAX- 列** = **MAX-（スコア率 ≥ 94.44%、判定式 `score*9 >= notes*17`）を達成したプレイヤーの割合**
= `maxMinusCount / totalCount`。**低いほど高難度**。**平均スコアレートとは別物**。
SQL: `ScoreRepository.findSongMaxMinusCounts`。
同ページの並び順 = **maxMinusRate 昇順 → 同率は avgScoreRate 昇順**。

### 作業手順の注意

- 改定は **draft リビジョン**で組み立て、適用前に `profile:<名前>` スナップショットへバックアップ。
- **MAX-率は 0.0% の同率群が多い**ため、単純な閾値だけでは上位帯が潰れる。
  タイブレークに **avgScoreRate 昇順**を併用する（＝ページの並び順と同じ）。
- MAX-率順への全面作り直しは **BEAT-PT を上位者から下位者へ再分配する**（2026-07-07 検証済み）。
  ただし **ZINRAI では BEAT-PT 自体が初期化される**ため、今回はこの制約を気にせず素直に採用できる。
- 適用後は更新履歴に「第 N 版」を追加する（`difficulty_revisions.json`、曲名は本番 API と照合）。

---

## 9. 自動実行の仕組み（2026-08-19 実装済み・既定では休眠）

稼働日に合わせて安全な手順を自動実行するための土台を実装した。**設定を入れるまで一切動作せず、利用者から見える挙動は何も変わらない。**

### 追加したもの

| ファイル | 役割 |
| --- | --- |
| [VersionPtSnapshot.java](../backend/src/main/java/com/beatseeker/backend/entity/VersionPtSnapshot.java) | 過去作の最終 PT アーカイブ（`version_pt_snapshots`）。作品ごとに積み上がる |
| [SystemTaskRun.java](../backend/src/main/java/com/beatseeker/backend/entity/SystemTaskRun.java) | 一度きり実行タスクの記録（`system_task_runs`）。二重実行の防止 |
| [VersionTransitionService.java](../backend/src/main/java/com/beatseeker/backend/service/VersionTransitionService.java) | 各手順の実装。いずれも**追記のみ・非破壊** |
| [VersionTransitionScheduler.java](../backend/src/main/java/com/beatseeker/backend/service/VersionTransitionScheduler.java) | 稼働日を待つポーリングタイマー |
| [VersionTransitionAdminController.java](../backend/src/main/java/com/beatseeker/backend/controller/VersionTransitionAdminController.java) | 管理者向けの状況確認 API（`GET /api/admin/version-transition/status`） |
| [PastRankingController.java](../backend/src/main/java/com/beatseeker/backend/controller/PastRankingController.java) | 過去作ランキングの公開 API（2026-08-30 追加） |

`VersionPtSnapshotRepository` / `SystemTaskRunRepository` も追加。

### 自動化する手順

1. **スナップショット** — `score_history_logs` のユーザーごと最新行から最終 PT を `version_pt_snapshots` へ焼き付ける。順位も付与（現行ランキングと同じ RANK() 方式、RATE-PT は `> 0` のみ採番）
2. **スコアの複製** — `scores` → `past_scores`（version=33）。譜面ごとの最高スコアのみ、未プレー除外。**元データは消さない**
3. **難易度表の適用** — 既存の `GameDataService.applyDraftDifficultyTable()` に委譲（既定では無効）
4. **スコアの初期化**（2026-08-30 追加・既定では無効） — `scores` / `user_song_ranks` /
   `user_comparison_stats` を削除し、`users` の PT 系カラムをリセットする。**唯一の破壊的手順**

### 手順 4（スコアの初期化）の設計

- **履歴は消さず、全項目 0 の「世代リセット行」を全員に 1 行ずつ足す**（`score_history_logs.tag = 'VERSION-RESET'`）。
  ランキングは**ユーザーごと最新行**を見るため、履歴を放置すると `scores` を空にしても順位表に前作の PT が
  出たままになる。かといって履歴を消すと成長記録から前作の推移が丸ごと失われる。0 の行を足せば
  順位表は 0 から始まり、過去の推移はそのまま残る。
- タグを分けてあるので、初日のアップロード（タグ null）がこの行を上書きしない。
- `users.last_uploaded_at` は NULL に戻す（「まだ ZINRAI のスコアを上げていない」が正）。
- 楽曲集計キャッシュ 3 種（曲別ランキング / 平均スコアレート / アリーナ平均）は
  トランザクション確定後に `refreshSongCaches()` で作り直す。
- **歯止めが 2 段**: `APP_VERSION_TRANSITION_RESET_SCORES=true` にしない限り対象にならず、
  さらに前作の退避（スナップショットと `past_scores`）が DB に無ければ例外で中断する。
- **この手順だけ冪等ではない**（呼ぶたびにリセット行が増える）。二重実行の防止は `system_task_runs` に委ねる。
- ⚠ リーグの進行中の週に実行すると、その週の基準スコアと実データが食い違う。
  **週の切れ目に合わせる**か、実行後にその週を締めること。

### 自動化しない手順

フロントエンドの `CURRENT_VERSION` はビルド時定数のため、タイマーでは変えられない（再デプロイが必要）。
beta 表記の削除も同じくデプロイ側の作業になる。完全自動化するにはバージョンを API 経由で受け取る改修が要る。

### 設定（環境変数）

| 環境変数 | 既定 | 意味 |
| --- | --- | --- |
| `APP_VERSION_TRANSITION_LAUNCH_AT` | 未設定 | 起動日時（JST, 例 `2026-09-16T05:00`）。**未設定＝この機能ごと無効**（Bean すら生成されない） |
| `APP_VERSION_TRANSITION_DRY_RUN` | `true` | 件数をログに出すだけで DB を変更しない。実行済み記録も残らない |
| `APP_VERSION_TRANSITION_FROM_VERSION` | `33` | 移行元 |
| `APP_VERSION_TRANSITION_TO_VERSION` | `34` | 移行先 |
| `APP_VERSION_TRANSITION_MIN_USERS` | `100` | スナップショット対象がこの人数未満なら中断 |
| `APP_VERSION_TRANSITION_APPLY_DIFFICULTY` | `false` | 難易度表 draft の自動適用 |
| `APP_VERSION_TRANSITION_RESET_SCORES` | `false` | スコアの初期化（破壊的。明示的に有効化したときだけ実行） |

**稼働日は cron 式ではなく環境変数**なので、日付がずれても再デプロイ不要（Render の環境変数変更＋再起動のみ）。

### 設計上の要点

- **cron ではなくポーリング**（1 分間隔）。「予定時刻を過ぎていて、かつ未実行なら走らせる」方式なので、発火の瞬間にインスタンスが落ちていても次に起きたときに追いつく
- **冪等**。`system_task_runs` に SUCCESS が記録されたら二度と走らない。`RUNNING` のまま残っている場合は中断の可能性があるため自動再実行せず警告のみ
- **dry-run では実行済み記録を残さない**ので、件数を確かめてから本番実行に切り替えられる
- **下限人数のガード**。対象が `MIN_USERS` に満たなければ SKIPPED として中断する

### リーグモードの世代またぎ（2026-08-19 実装済み）

新作稼働時、リーグは「実力の参照」と「結果の判定」で見るデータを分ける。

| 用途 | 参照するデータ | 状態 |
| --- | --- | --- |
| **新規参加時の DIVISION 配属** | 歴代最高 BEAT-PT（現行作と過去作アーカイブの高いほう） | 実装済み・常時有効 |
| **課題曲選定の自己ベスト** | 歴代自己ベスト（現行作＋過去作の最高 EX） | 実装済み・**フラグで無効** |
| **リザルト有効ライン**（週開始時点のスコア） | **現行作のみ** | 変更不要（元から現行作のみ） |

- 新規参加: [LeagueService.java](../backend/src/main/java/com/beatseeker/backend/service/LeagueService.java) に `allTimeBeatPt()` を追加。
  `users.total_beat_pt` と `version_pt_snapshots` の最大値の高いほうを使う。
  **アーカイブが 1 件も無い現在は現行作の値がそのまま使われるので挙動は変わらない。**
- 課題曲選定: [LeagueSongDrawService.java](../backend/src/main/java/com/beatseeker/backend/service/LeagueSongDrawService.java) で
  `past_scores` も突き合わせて作品をまたいだ最高 EX を採る。
  **`app.league.self-best-includes-past`（既定 `false`）で無効**。現在は現行作のみを見るので挙動は変わらない。
  世代切り替えに合わせて `APP_LEAGUE_SELF_BEST_INCLUDES_PAST=true` にする。
- リザルト有効ライン: `LeagueWeekLifecycleService#snapshotBaselines` は元から `scores`（アーケードのみ）を読む。
  **週内にプレーしたかの判定に過去作を混ぜてはならない**ため、フラグの影響も受けない。

なぜ分けるのか: 稼働直後は現行作のスコアが空なので、実力参照まで現行作に限ると
「全員最下位 DIVISION」「全曲が全員未プレー扱いで選曲が実力に合わない」という状態になる。
一方、結果判定に過去作を混ぜると週内にプレーしていない記録が有効になってしまう。

### 使い方の手順

1. `APP_VERSION_TRANSITION_LAUNCH_AT` を設定して再起動 → dry-run で件数がログに出る
2. `GET /api/admin/version-transition/status` で件数と実行記録を確認
3. 問題なければ `APP_VERSION_TRANSITION_DRY_RUN=false` にして再起動 → 予定時刻に本番実行

### 動作確認済み

`mvn compile` 成功、ローカル（H2）で起動確認済み。`LAUNCH_AT` 未設定時は**スケジューラの Bean が生成されない**ことをログで確認した。

---

## チェックリスト

### 今すぐ（稼働前に前倒しで）

- [x] **MAX-率による全曲再判定の draft を作成**（2026-08-19 実施、案A/案B を profile に保存、draft = 案B）
- [ ] ユーザーが目視確認 → 案A/案B の決定（§8.1）

### 稼働前に実装しておくもの（コード側）

- [x] **PT スナップショットの保存機構**（§9・作品バージョンID付き・積み上げ式）
- [x] `scores`(33) → `past_scores`(version=33) の複製機構（§9）
- [x] **スコアの初期化**（§9 手順 4。既定は無効・前段の退避が無ければ中断）
- [x] **過去作ランキング閲覧 UI**（§4。`/past-ranking`）
- [x] **ティアアイコンの外枠を前作ティア色の発光へ**（§5。サブティアで光量可変）
- [x] バージョン定数を 34 に切り替え（§7。フロント／バックエンドの 2 箇所）
- [x] beta 表記の削除（§3。利用規約の免責事項は意図的に未対応）
- [ ] INFINITAS スコア読み込みの廃止（初日の対象外。廃止時期は別途告知）
- [ ] 新規譜面の 200 プレイごと自動再判定バッチ（§8.2。実力解禁譜面は除外・手動配置）

### 稼働日の運用作業

- [ ] ZINRAI 稼働日の確定（予測 2026-09-16）／公式 CSV の「バージョン」列の実表記を確認し、
      `VERSION_LABELS[34]` の暫定値 `'ZINRAI'` を実物に合わせる
- [ ] `APP_VERSION_TRANSITION_LAUNCH_AT` を設定 → dry-run で件数確認 → 本番実行に切替
- [ ] `APP_VERSION_TRANSITION_RESET_SCORES=true`（スコア初期化を有効化。dry-run で件数を見てから）
- [ ] `APP_LEAGUE_SELF_BEST_INCLUDES_PAST=true`（リーグの課題曲選定に過去作を含める）
- [ ] バージョン 34 のブランチをマージして再デプロイ（マージした瞬間に現行作が 34 になる）
- [ ] 難易度表の改定を適用 → 更新履歴に第 N 版を追加
