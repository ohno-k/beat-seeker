# ソースコード日本語コメント追加プロジェクト 進捗表

## 目的

beat-seeker プロジェクトの全ソースコード（約135ファイル・数万行）に対し、**日本語による手とり足とりコメント**を追加する作業の進捗を管理する。

- 粒度: **(c) ファイル先頭＋メソッド先頭＋分岐や複雑なロジックに行単位コメント**（ユーザー確定）
- 既存の英語コメント: **(ii) 日本語に置き換え**（ユーザー確定。意味はそのまま保持）
- 実行方針: **(C) Backend と Frontend を並行実行**（ユーザー確定。エージェント複数起動）
- **ソースコード・変数名の改変は禁止**（コメント追加のみ）

各パート完了時は、ステータスを `✅ 完了 YYYY-MM-DD` に更新する。

---

## パート全体マップ

| Part | 領域 | ファイル数 | 規模感 | ステータス |
|------|------|-----------|-------|-----------|
| **Part 1** | Backend Controller 基本層 + DTO | 15 | 中 | ✅ 完了 2026-04-23 |
| **Part 2** | Backend 巨大 Controller | 5 | 大（ScoreController 855行など） | ✅ 完了 2026-04-23 |
| **Part 3** | Backend Service | 8 | 特大（ChartTendencyService 1082行） | ✅ 完了 2026-04-23 |
| **Part 4** | Backend Repository | 16 | 中（ScoreRepository 405行が最大） | ✅ 完了 2026-04-23 |
| **Part 5** | Backend Entity | 17 | 小〜中 | ✅ 完了 2026-04-23 |
| **Part 6** | Backend Config/Root + Frontend Utils/Router | 10 | 中（beatTier.ts 543行） | ✅ 完了 2026-04-23 |
| **Part 7** | Frontend Composables | 13 | 中（useFriends 348、useScores 290） | ✅ 完了 2026-04-23 |
| **Part 8** | Frontend Views | 19 | 大（ScorePredictionView 847行） | ✅ 完了 2026-04-23 |
| **Part 9** | Frontend 巨大コンポーネント | 2 | 超特大（ScoreSummary 1963行、App 1645行） | ✅ 完了 2026-04-23 |
| **Part 10** | Frontend Components 残り + Locales | 約30+3 | 中〜大 | ✅ 完了 2026-04-23 |

**ステータス凡例**: ⏸ 未着手 / 🟡 作業中 / ✅ 完了

**🎉 全 10 パート完了**（2026-04-23）: 159 ファイル変更 / +8,581 / −1,056 行（コード本体は無改変、コメント追加のみ）

---

## Part 1: Backend Controller 基本層 + DTO

**パス**: `backend/src/main/java/com/beatseeker/backend/controller/`

| # | ファイル | 行数 | 状態 |
|---|---------|------|------|
| 1.1 | ActivityController.java | - | ✅ 完了 2026-04-23 |
| 1.2 | ArenaController.java | - | ✅ 完了 2026-04-23 |
| 1.3 | AuthController.java | 254 | ✅ 完了 2026-04-23 |
| 1.4 | GameDataController.java | - | ✅ 完了 2026-04-23 |
| 1.5 | KofiWebhookController.java | - | ✅ 完了 2026-04-23 |
| 1.6 | NotificationController.java | - | ✅ 完了 2026-04-23 |
| 1.7 | OptionVoteController.java | - | ✅ 完了 2026-04-23 |
| 1.8 | TierCommentController.java | - | ✅ 完了 2026-04-23 |
| 1.9 | TierVoteController.java | - | ✅ 完了 2026-04-23 |
| 1.10 | UserController.java | - | ✅ 完了 2026-04-23 |
| 1.11 | LoginRequest.java (DTO) | - | ✅ 完了 2026-04-23 |
| 1.12 | ProfileUpdateRequest.java (DTO) | - | ✅ 完了 2026-04-23 |
| 1.13 | RecalculatePointsRequest.java (DTO) | - | ✅ 完了 2026-04-23 |
| 1.14 | RegisterRequest.java (DTO) | - | ✅ 完了 2026-04-23 |
| 1.15 | SaveHistoryLogRequest.java (DTO) | - | ✅ 完了 2026-04-23 |

**Part 1 所見（エージェント報告）**:
- `GameDataController#checkAdminAccess` で管理者 ID が `18L` ハードコード。今後の運用で設定値化する余地あり。
- 一部 Controller 内部に DTO クラスが埋め込み定義されている（例: 通知系）。将来 DTO 層に切り出す選択肢あり。

---

## Part 2: Backend 巨大 Controller

| # | ファイル | 行数 | 状態 |
|---|---------|------|------|
| 2.1 | ScoreController.java | 855 | ✅ 完了 2026-04-23（EP 25, メソッド 30） |
| 2.2 | FriendController.java | 463 | ✅ 完了 2026-04-23（EP 14, メソッド 15） |
| 2.3 | AdminController.java | 313 | ✅ 完了 2026-04-23（EP 10, メソッド 11） |
| 2.4 | ChartTendencyController.java | 284 | ✅ 完了 2026-04-23（EP 10, メソッド 12） |
| 2.5 | TestController.java（UNUSED 済みだが、コメントは追加する） | 12 | ✅ 完了 2026-04-23（UNUSED マーカー維持） |

**Part 2 所見（エージェント報告）**:
- **管理者判定の重複**: 3 Controller で管理者判定が独立実装されている（`ScoreController` は `ADMIN_IIDX_ID` 文字列、`AdminController` / `ChartTendencyController` は `user.id==18L`）。さらに **ID と IIDX_ID の 2 種類のキー**が混在しているため、Role テーブルか共通ガードへの集約余地が大きい。
- **セキュリティ懸念（要対処）**: `ADMIN_USER_ID=18L` / `ADMIN_IIDX_ID="5787-1145"` のハードコード → 環境変数化すべき。また `/api/scores/debug-ranking` と `/api/scores/debug-user-scores/{userId}` が**認証なしで個人スコアを読める状態**。本番では認証ガードまたは削除推奨。
- **`saveHistoryLog` の責務肥大**: 「集計」「スナップショット保存」「ランクアップ判定」「管理者メール」を 1 メソッドで抱えている。Service 層への抽出余地あり。`diffJson` の ObjectMapper 二重パースも重複。
- **フレンド一覧の N+1 的な非効率**: フレンド各人の最新 BEAT-PT を `findByUserOrderByUploadedAtAsc` で全履歴取得して末尾を見る実装 → **フレンド数 × 履歴件数**でメモリ・I/O を消費。`users.total_beat_pt` キャッシュ列を使う方が高速。
- **`@NonNull` のゆらぎ**: IDE が多数の NonNull 変換警告を出している。コメント追加とは独立のクリーンアップ対象。

---

## Part 3: Backend Service

**パス**: `backend/src/main/java/com/beatseeker/backend/service/`

| # | ファイル | 行数 | 状態 |
|---|---------|------|------|
| 3.1 | ChartTendencyService.java | 1082→1184 | ✅ 完了 2026-04-23（public 11, private 10） |
| 3.2 | TopRankersBeatPtService.java | 480→594 | ✅ 完了 2026-04-23（public 7, private 6） |
| 3.3 | ScoreRecalculationService.java | 413→536 | ✅ 完了 2026-04-23（public 5, private 5） |
| 3.4 | GameDataService.java | 341→440 | ✅ 完了 2026-04-23（public 11, private 3） |
| 3.5 | SkillTreeService.java | 323→379 | ✅ 完了 2026-04-23（public 1, private 10） |
| 3.6 | SongRankBatchService.java | 41→70 | ✅ 完了 2026-04-23 |
| 3.7 | EmailService.java | 201→273 | ✅ 完了 2026-04-23（public 3, private 1） |
| 3.8 | PushNotificationService.java | 62→119 | ✅ 完了 2026-04-23（public 2） |

**Part 3 所見（エージェント報告）**:
- **BEAT-PT / RATE-PT 計算ロジックの重複**: `WEIGHTS` 静的初期化、`calculatePoints`、`calculateScoreRateTierPoints`、`SCORE_RATE_THRESHOLDS` が `ScoreRecalculationService` と `TopRankersBeatPtService` に重複定義。共通ユーティリティへ抽出推奨。
- **ChartTendencyService の類似度計算マジックナンバー**: 4 グループ × 独立難易度項の乗算設計で `0.15`、`3.0`、`-3000.0`、`5.0` 等のペナルティ係数が点在。定数化が必要。
- **TopRankersBeatPtService の起動時初期化**: 5 回リトライで CSV + Repository を同期読みするが、DB 未初期化時のレースを完全には防げない。`ApplicationReadyEvent` への移行 or 指数バックオフが堅牢。
- **SongRankBatchService#recalculateAll**: TRUNCATE + INSERT-SELECT 一本勝負で並行更新ロック衝突を未考慮。`recalculateAllAsync` 連打時の排他は要検討。
- **EmailService のテンプレート埋め込み**: メール文面が Java ソース内ハードコード → 文面変更のたび再デプロイ。`resources/templates/*.html` + FreeMarker/Thymeleaf 化が理想。
- **SkillTreeService の貪欲チェーン構築が O(N²)**: 全譜面数千件を呼び出しごとに再計算。結果キャッシュ導入でレスポンス改善見込み。
- 既存の Null 安全警告と `ChartTendencyService L656 actualRate` 未使用変数は別タスクでクリーンアップ候補。

---

## Part 4: Backend Repository

**パス**: `backend/src/main/java/com/beatseeker/backend/repository/`

| # | ファイル | 行数 | 状態 |
|---|---------|------|------|
| 4.1 | ScoreRepository.java | 405 | ✅ 完了 2026-04-23（@Query 15, 派生 6） |
| 4.2 | ActivityLogRepository.java | - | ✅ 完了 2026-04-23 |
| 4.3 | AppNotificationRepository.java | - | ✅ 完了 2026-04-23 |
| 4.4 | ArenaMatchRepository.java | - | ✅ 完了 2026-04-23 |
| 4.5 | ChartTendencyProfileRepository.java | - | ✅ 完了 2026-04-23 |
| 4.6 | DifficultyRankRepository.java | - | ✅ 完了 2026-04-23 |
| 4.7 | FriendRequestRepository.java | - | ✅ 完了 2026-04-23 |
| 4.8 | FriendshipRepository.java | - | ✅ 完了 2026-04-23 |
| 4.9 | OptionVoteRepository.java | - | ✅ 完了 2026-04-23 |
| 4.10 | ScoreHistoryLogRepository.java | - | ✅ 完了 2026-04-23（@Query 7） |
| 4.11 | SongDefinitionRepository.java | - | ✅ 完了 2026-04-23 |
| 4.12 | TierCommentRepository.java | - | ✅ 完了 2026-04-23 |
| 4.13 | TierVoteRepository.java | - | ✅ 完了 2026-04-23 |
| 4.14 | UserRepository.java | - | ✅ 完了 2026-04-23 |
| 4.15 | UserSongRankRepository.java | - | ✅ 完了 2026-04-23 |
| 4.16 | VirtualRivalRepository.java | - | ✅ 完了 2026-04-23 |

**Part 4 所見（エージェント報告）**:
- `ScoreRepository` の beat_pt 計算 SQL（`findAllSongRankingAggregates` / `calculateDifficultySimulation` / `findRawSongScoresWithBeatTier`）で `weight_map` VALUES と score_rate 計算式が重複。**DB ビューまたは関数化**して DRY 化する余地が大きい。
- `ScoreHistoryLogRepository` の 3 種のランキング（beat/precision/rate）で「現時点順位 / 前日順位 / 差分」が完全に同型。CTE テンプレート化または動的 SQL ビルダで統合可能。
- `ChartTendencyProfileRepository#findByTagContaining` は `LIKE '%...%'` でインデックスが効かない。タグ件数増加時は PostgreSQL の JSON 演算子（`jsonb @>`）やフルテキストインデックスへの移行が必要。
- `UserSongRankRepository` は読み出し側メソッドが `findByUserId` のみ。`insertAllUserSongRanks` / `truncateUserSongRanks` がキャッシュ再構築である性質上、曲別絞り込みメソッドの追加需要が見込まれる。
- `FriendshipRepository#findByUser` は片方向のみで、相手視点検索が欠落。双方向フレンド判定を `OR` 条件でまとめる `@Query` 追加が望ましい。

**変更検証**: `git diff --stat` で 962 追記 / 5 削除。削除 5 行はコメント JavaDoc 化に伴う既存コメント置換・空行のみ。**コード本体は無改変**。

---

## Part 5: Backend Entity

**パス**: `backend/src/main/java/com/beatseeker/backend/entity/`

| # | ファイル | 状態 |
|---|---------|------|
| 5.1 | User.java | ✅ 完了 2026-04-23 |
| 5.2 | Score.java | ✅ 完了 2026-04-23 |
| 5.3 | ScoreHistoryLog.java | ✅ 完了 2026-04-23 |
| 5.4 | SongDefinition.java | ✅ 完了 2026-04-23 |
| 5.5 | DifficultyRank.java | ✅ 完了 2026-04-23 |
| 5.6 | DifficultyRankSong.java | ✅ 完了 2026-04-23 |
| 5.7 | ChartTendencyProfile.java | ✅ 完了 2026-04-23 |
| 5.8 | FriendRequest.java | ✅ 完了 2026-04-23 |
| 5.9 | Friendship.java | ✅ 完了 2026-04-23 |
| 5.10 | VirtualRival.java | ✅ 完了 2026-04-23 |
| 5.11 | AppNotification.java | ✅ 完了 2026-04-23 |
| 5.12 | ActivityLog.java | ✅ 完了 2026-04-23 |
| 5.13 | ArenaMatch.java | ✅ 完了 2026-04-23 |
| 5.14 | OptionVote.java | ✅ 完了 2026-04-23 |
| 5.15 | TierComment.java | ✅ 完了 2026-04-23 |
| 5.16 | TierVote.java | ✅ 完了 2026-04-23 |
| 5.17 | UserSongRank.java | ✅ 完了 2026-04-23 |

**Part 5 所見（エージェント報告）**:
- `TierComment`・`TierVote`・`UserSongRank` は `User` を `Long userId` で参照しており、`@ManyToOne` の JPA リレーションではない（ユーザー削除時のカスケードが効かない設計）。参照整合性はアプリ層で担保する運用。

---

## Part 6: Backend Config/Root + Frontend Utils/Router

| # | ファイル | 行数 | 状態 |
|---|---------|------|------|
| 6.1 | backend/.../config/JwtUtil.java | 52 | ✅ **サンプル完了** |
| 6.2 | backend/.../config/JwtAuthFilter.java | 43 | ✅ 完了 2026-04-23 |
| 6.3 | backend/.../config/SecurityConfig.java | 115 | ✅ 完了 2026-04-23 |
| 6.4 | backend/.../BackendApplication.java | - | ✅ 完了 2026-04-23 |
| 6.5 | backend/.../DataInitializer.java | - | ✅ 完了 2026-04-23 |
| 6.6 | frontend/src/utils/beatTier.ts | 543 | ✅ 完了 2026-04-23（関数14, 閾値テーブル4） |
| 6.7 | frontend/src/utils/bookmarklet.ts | 7 | ✅ 完了 2026-04-23 |
| 6.8 | frontend/src/utils/csvParser.ts | 61 | ✅ 完了 2026-04-23 |
| 6.9 | frontend/src/utils/scoreData.ts | 173 | ✅ 完了 2026-04-23 |
| 6.10 | frontend/src/router/index.ts | 36 | ✅ 完了 2026-04-23 |

**Part 6 所見（エージェント報告）**:
- **SecurityConfig の認可順序が複雑**: `/api/scores/**` のような広マッチと個別 `permitAll`（例: `/api/scores/ranking`）が混在し、順序依存で動作している。新規エンドポイント追加時、個別 `permitAll` を広マッチ `authenticated` より前に置く必要がある点が見落とされやすい。
- **CORS の `allowedHeaders('*')` + `allowCredentials(true)`**: Spring Security では通るが、仕様上はワイルドカードと credentials の同時利用は非推奨。`Authorization` 等を列挙した方が安全。
- **beatTier.ts のマジックナンバー集中**: Weight ステップ `12.49`、スコア閾値 `66.666/77.77/88.88/94.44`、Legend 閾値 `99.75/94.44`、t^4 の指数、上位 100 譜面制限など、バランスに効くパラメータが点在。定数を 1 箇所に集約すると調整が容易。
- **csvParser の DP 判定が曲「22DUNK」に依存**: この曲がマスターデータから消えたり難易度改定があった瞬間にチェックが実質無効化される。複数曲判定や構造的判定（全難易度のレベル分布で判定する等）への置換が望ましい。
- **DataInitializer は例外を握り潰しすぎ**: JSON 読み込み失敗・DB 投入失敗が `System.err` の println だけで終わるため、本番でマスターデータが空のままサービスが動き続けるリスクがある。Logger 経由の WARN/ERROR 出力が必要。

---

## Part 7: Frontend Composables

**パス**: `frontend/src/composables/`

| # | ファイル | 行数 | 状態 |
|---|---------|------|------|
| 7.1 | useDarkMode.ts | 41 | ✅ **サンプル完了** |
| 7.2 | constants.ts | 2 | ✅ 完了 2026-04-23 |
| 7.3 | useAppUpdate.ts | 26 | ✅ 完了 2026-04-23 |
| 7.4 | useAprilFools.ts | 62 | ✅ 完了 2026-04-23 |
| 7.5 | useAuth.ts | 179 | ✅ 完了 2026-04-23 |
| 7.6 | useFriends.ts | 348 | ✅ 完了 2026-04-23 |
| 7.7 | useGameData.ts | 113 | ✅ 完了 2026-04-23 |
| 7.8 | useI18n.ts | 44 | ✅ 完了 2026-04-23 |
| 7.9 | useRateSongRanking.ts | 79 | ✅ 完了 2026-04-23 |
| 7.10 | useRateTierVisibility.ts | 31 | ✅ 完了 2026-04-23 |
| 7.11 | useScoreUpload.ts | 82 | ✅ 完了 2026-04-23 |
| 7.12 | useScores.ts | 290 | ✅ 完了 2026-04-23 |
| 7.13 | useSongRanking.ts | 49 | ✅ 完了 2026-04-23 |

**Part 7 所見（エージェント報告）**:
- `useScores` と `useFriends` に責務の重複あり（フレンド関連スコア処理が両者に散っている）。将来的な統合余地。
- `useFriends` に VAPID 公開鍵がハードコードされている。環境変数化の余地あり。
- `useAprilFools` は現状 `ENABLE_APRIL_FOOLS = false` で完全に無効化されている。残置コード扱い。

---

## Part 8: Frontend Views

**パス**: `frontend/src/views/`

| # | ファイル | 状態 |
|---|---------|------|
| 8.1 | DashboardView.vue | ✅ 完了 2026-04-23 |
| 8.2 | ScoresView.vue | ✅ 完了 2026-04-23 |
| 8.3 | RankingView.vue | ✅ 完了 2026-04-23 |
| 8.4 | HistoryView.vue | ✅ 完了 2026-04-23 |
| 8.5 | ProfileView.vue | ✅ 完了 2026-04-23 |
| 8.6 | FriendsView.vue | ✅ 完了 2026-04-23 |
| 8.7 | ChangelogView.vue | ✅ 完了 2026-04-23 |
| 8.8 | TermsView.vue | ✅ 完了 2026-04-23 |
| 8.9 | AboutView.vue | ✅ 完了 2026-04-23 |
| 8.10 | ResetPasswordView.vue | ✅ 完了 2026-04-23 |
| 8.11 | ChartListView.vue | ✅ 完了 2026-04-23 |
| 8.12 | ArcadeView.vue | ✅ 完了 2026-04-23 |
| 8.13 | ArenaView.vue | ✅ 完了 2026-04-23 |
| 8.14 | DifficultyTableView.vue | ✅ 完了 2026-04-23 |
| 8.15 | RankComparisonView.vue | ✅ 完了 2026-04-23 |
| 8.16 | ScorePredictionView.vue（847行） | ✅ 完了 2026-04-23 |
| 8.17 | SkillTreeView.vue | ✅ 完了 2026-04-23 |
| 8.18 | SongAverageView.vue | ✅ 完了 2026-04-23 |
| 8.19 | TierVotingView.vue | ✅ 完了 2026-04-23 |

**Part 8 所見（エージェント報告）**:
- **管理者閲覧モードの分岐散在**: `ScorePredictionView` / `ArenaView` / `ScoresView` で `viewingUserId` / `viewingMode` による API エンドポイント分岐が重複。`buildApiBase(viewingUserId, path)` の composable に集約すべき。
- **難易度コード変換（ANOTHER=4, LEGGENDARIA=10）の重複**: `ArenaView` / `SongAverageView` / `ScorePredictionView` で独自実装。`useGameData` に `getMaxScore(title, difficultyName)` を追加してシングルソース化推奨。
- **大型 View のコンポーネント分割余地**: `ScorePredictionView.vue`(847行)、`ArcadeView.vue`(612行)、`TierVotingView.vue`(452行) は分割の余地大。特に ScorePrediction の「傾向プロファイルカード」「類似度デバッグモーダル」「予測スコアパネル」は独立子コンポーネント化候補。
- **`any[]` の多用**: `ArenaView` の `players/songScores` 周辺で `(m.players as any[]).find(...)` が頻出。backend 側と DTO 型を共有すべき。
- **a11y 欠落**: アコーディオンに `aria-expanded`/`aria-controls` なし、絵文字/Unicode 記号のみで機能を示すボタンに代替テキストなし、テーブル `th` に `scope` 指定なし、`cursor-pointer` のみで `role="button"`/`tabindex` 未設定。
- **ページネーション/ソート処理の重複**: `ChartListView` / `SongAverageView` / `RankComparisonView` で類似ロジックを個別実装。`usePagedSortedRows<T>()` 共通 composable にまとめる余地あり。

---

## Part 9: Frontend 巨大コンポーネント

| # | ファイル | 行数 | 状態 |
|---|---------|------|------|
| 9.1 | frontend/src/App.vue | 1645→1917 | ✅ 完了 2026-04-23（ref/関数/watch JSDoc 約40, template 区切り約30） |
| 9.2 | frontend/src/components/ScoreSummary.vue | 1963→2275 | ✅ 完了 2026-04-23（ref/computed/関数/watch JSDoc 約45, template 区切り約20） |

**Part 9 所見（エージェント報告）**:
- `ScoreSummary.vue` の `rankingList` computed は**仮想ユーザーのバッジ分類**（allTimeGlobal / globalAllTime / allTimeArea / versionTop / top）が複雑。重複排除ルール「歴代全国と県別が一致すれば全国を間引く」「バージョン全国と同 version 県別が同スコアなら全国を間引く」を 7 ステップで明文化した。
- `top100ScoreNeededMap` に**二分探索 5 ステップ解説**を付与。`filteredScores` には列別ソート比較を明示。
- `<template>` は **10 以上のブロック区切りコメント**（フィルタ/検索、モードタブ、データテーブル、ページネーション、詳細モーダル、内部タブ 4 種、判定内訳、オプション投票、目標 PT 電卓、メモ、Sticky フッタ）で可読性向上。
- 副作用（`document.body.style.overflow`、`localStorage`、API フェッチ）は全て関数 JSDoc で明示。
- Sticky ヘッダ周辺で一度 HTML ブロック重複ミスがあったが、即時修正・タブボタン 4 個で `grep` 確認済み。

---

## Part 10: Frontend Components 残り + Locales

**パス**: `frontend/src/components/`

| # | ファイル | 状態 |
|---|---------|------|
| 10.1 | About.vue | ✅ 完了 2026-04-23 |
| 10.2 | ActivityFeed.vue | ✅ 完了 2026-04-23 |
| 10.3 | AdminGameDataModal.vue | ✅ 完了 2026-04-23 |
| 10.4 | AdminSongRanksView.vue | ✅ 完了 2026-04-23 |
| 10.5 | AdminUserListModal.vue | ✅ 完了 2026-04-23 |
| 10.6 | AprilFoolsOverlay.vue | ✅ 完了 2026-04-23 |
| 10.7 | BeatTierInfoModal.vue | ✅ 完了 2026-04-23 |
| 10.8 | Changelog.vue | ✅ 完了 2026-04-23 |
| 10.9 | CsvDropzone.vue | ✅ 完了 2026-04-23 |
| 10.10 | FriendComparisonModal.vue | ✅ 完了 2026-04-23 |
| 10.11 | FriendSearchModal.vue | ✅ 完了 2026-04-23 |
| 10.12 | Friends.vue | ✅ 完了 2026-04-23 |
| 10.13 | LoginModal.vue | ✅ 完了 2026-04-23 |
| 10.14 | NotificationBox.vue | ✅ 完了 2026-04-23 |
| 10.15 | OnboardingModal.vue | ✅ 完了 2026-04-23 |
| 10.16 | ProfileDashboard.vue | ✅ 完了 2026-04-23 |
| 10.17 | ProfileEditModal.vue | ✅ 完了 2026-04-23 |
| 10.18 | RankIcon.vue | ✅ 完了 2026-04-23 |
| 10.19 | RankUpAdvice.vue | ✅ 完了 2026-04-23 |
| 10.20 | RankingList.vue | ✅ 完了 2026-04-23 |
| 10.21 | RateSongRankingList.vue | ✅ 完了 2026-04-23 |
| 10.22 | RateTierInfoModal.vue | ✅ 完了 2026-04-23 |
| 10.23 | ScoreDashboard.vue | ✅ 完了 2026-04-23 |
| 10.24 | Sidebar.vue | ✅ 完了 2026-04-23 |
| 10.25 | SongRankingList.vue | ✅ 完了 2026-04-23 |
| 10.26 | Terms.vue | ✅ 完了 2026-04-23 |
| 10.27 | TierCommentModal.vue | ✅ 完了 2026-04-23 |
| 10.28 | UnifiedImport.vue | ✅ 完了 2026-04-23 |
| 10.29 | UnofficialDifficultyTable.vue | ✅ 完了 2026-04-23 |
| 10.30 | UploadHistory.vue | ✅ 完了 2026-04-23 |
| 10.31 | UploadResultModal.vue | ✅ 完了 2026-04-23 |
| 10.32 | frontend/src/locales/ja.ts | ✅ 完了 2026-04-23 |
| 10.33 | frontend/src/locales/en.ts | ✅ 完了 2026-04-23 |
| 10.34 | frontend/src/locales/ko.ts | ✅ 完了 2026-04-23 |

**Part 10 所見（エージェント報告 + 抜き打ち検証）**:
- 32 コンポーネント + 3 Locales、全件に JSDoc / HTML コメントを付与済み。抜き打ちで About.vue / LoginModal.vue / TierCommentModal.vue / UploadResultModal.vue を確認し、日本語コメントが反映されていることを確認。
- **Rate-Tier オーバーフロー仕様の明文化**: `ScoreDashboard.vue` の rateTierPoints 算出で、100% 達成曲が 100 件超過した際に 1 件あたり +1 pt 加算される backend 側仕様（`ScoreRecalculationService`）との整合をコメントで明記。
- **管理者判定のハードコード重複**: `Sidebar.vue` / `RankingList.vue` で `user.id === 18` または `iidxId === '5787-1145'` が重複。ロール情報が backend 側にないことを示唆 → Part 2 所見と同じ課題。
- **難易度コード `ANOTHER=4` / `LEGGENDARIA=10`**: `UnofficialDifficultyTable.vue` で `[L]` サフィックス運用を明記。Part 8 所見と同じく複数箇所で重複 → composable 集約余地。
- **updatedCount=0 の意味**: `ProfileDashboard.vue` の `HistoryRecord` 型で `updatedCount=0` が「難易度表改定などのメンテナンス的変化」を示す旨をコメント化（`UploadHistory.vue` と整合）。
- **RankUpAdvice の 2 フェーズアルゴリズム**: `getScoreCap → buildCandidates → pickBestSuggestions` の流れを詳細解説。
- **Locales**: 翻訳値は一切変更せず、ファイル先頭の JSDoc と主要セクションコメントのみ追加。

---

## コメント記述方針（共通）

### Java

```java
/**
 * 【クラスの役割】
 *
 * このクラスは ◯◯ を担当する。
 * - 責務A: ...
 * - 責務B: ...
 * 依存: SomeRepository（DBアクセス）、SomeService（業務ロジック）
 */
@RestController
public class SomeController {

    /**
     * 【メソッドの役割】 ...
     *
     * @param xxx 説明
     * @return 説明
     * @throws YyyException こういう時に発生
     */
    public Foo doSomething(String xxx) {
        // 手順1: ◯◯する理由。なぜここで△△を確認しているか
        if (xxx == null) { ... }
        // 手順2: ...
    }
}
```

### TypeScript / Vue

```ts
/**
 * 【Composable の役割】
 *
 * ダークモードの ON/OFF を管理する。
 * - localStorage に保存される
 * - OSのカラースキーム設定を初期値として尊重する
 * - 切り替え時に <html> の class="dark" を同期する
 */
export function useXxx() {
  /** 説明 */
  const state = ref(false);

  /**
   * 【関数の役割】 ...
   */
  const doSomething = () => {
    // ここで ◯◯ を確認しているのは △△ のため
    ...
  };

  return { state, doSomething };
}
```

### Vue `<template>` 部分

```vue
<template>
  <!-- 画面上部のサマリーカード: BEAT-PT / RATE-PT を表示 -->
  <div>...</div>

  <!-- モーダル: CSV アップロード UI。@upload で親にイベント通知 -->
  <CsvDropzone @upload="handleUpload" />
</template>
```

---

## 留意事項

- ソース本体（式・変数名・ロジック）は**絶対に改変しない**。コメント追加のみ。
- ライセンスヘッダや pragma 指定が既にあるファイルは、その下に追記する。
- コメントは簡潔さより**理解のしやすさ**を優先（「なぜ」が伝わる説明を）。
- 英語コメントは日本語に翻訳置換する。ただし **JavaDoc タグ（`@param` `@return` 等）と Vue の `defineProps` 型注釈などの言語構文は変更しない**。
- 処理の順序や分岐の理由が非自明な箇所には、**番号付き手順コメント**（「手順1: ◯◯」）を入れる。
