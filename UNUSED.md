# 未使用ファイル・メソッド一覧

本プロジェクトで **未使用** または **要整理** と判定されたファイル／メソッドの一覧です。
2026-04-23 時点の調査結果。

- 各ファイルには冒頭に `// UNUSED: ...` 等のコメントを追記しています（grepで発見可能）。
- ファイル自体は削除せず、ここに一覧化しています。不要が確定したタイミングで削除してください。

---

## 1. 確実に未使用

### 1.1 Backend
| パス | 区分 | 根拠 |
|------|------|------|
| [backend/src/main/java/com/beatseeker/backend/TestController.java](backend/src/main/java/com/beatseeker/backend/TestController.java) | Controller | `/api/test-root` エンドポイント1つのみ。"Root controller is active" を返すだけの動作確認用。本番不要。 |

### 1.2 旧バリエーション（最終版が別にある）

| パス | 後継 | 根拠 |
|------|------|------|
| [calc_folder_rates.js](calc_folder_rates.js) | [calc_folder_rates_final.js](calc_folder_rates_final.js) | 旧v1 |
| [calc_folder_rates2.js](calc_folder_rates2.js) | `calc_folder_rates_final.js` | 旧v2 |
| [calc_folder_rates3.js](calc_folder_rates3.js) | `calc_folder_rates_final.js` | 旧v3 |
| [fetch_rankings_log.js](fetch_rankings_log.js) | [fetch_rankings.js](fetch_rankings.js) (要確認) | ログ版、実運用は本体が使われている想定 |
| [fetch_rankings_robust.js](fetch_rankings_robust.js) | `fetch_rankings.js` (要確認) | 堅牢版として派生したものの、どちらが現役か不明 |

### 1.3 テスト用／調査用スクリプト（**一度きりの検証用、恒常的な用途なし**）

| パス | 目的 | 備考 |
|------|------|------|
| [scrapeTest.js](scrapeTest.js) | スクレイピング動作確認 | 714B、ワンショット |
| [test_bookmarklet.js](test_bookmarklet.js) | ブックマークレットの検証 | Mar 21 作成 |
| [test-parsing.js](test-parsing.js) | パーサ単体テスト | Mar 24 |
| [calc_max.js](calc_max.js) | 最大値計算ユーティリティ | 1.1KB、他参照なし |
| [backend/test_login.js](backend/test_login.js) | `/api/auth/login` の動作確認 | 手動テスト |
| [backend/test_me.js](backend/test_me.js) | `/api/me` の動作確認 | 手動テスト |
| [backend/test_register.py](backend/test_register.py) | `/api/auth/register` の動作確認 | 手動テスト |
| [backend/test_req.js](backend/test_req.js) | 汎用 API fetch | 手動テスト |
| [backend/test_req2.js](backend/test_req2.js) | 汎用 API fetch（派生版） | 手動テスト |

### 1.4 誤った産物

| パス | 原因 |
|------|------|
| [C:tmpscores_data.json](C:tmpscores_data.json) | Windows のパス文字列 `C:\tmp\scores_data.json` を指定したつもりがクォートされず、そのままファイル名になった事故ファイル |
| [TestWeights.class](TestWeights.class) | Java コンパイル成果物、`.gitignore` 対象 |
| [TestWeights.java](TestWeights.java) | BEAT-Tier 重み付けの手元検証スクリプト、本番コードに統合済みなら未使用 |
| [index.html](index.html)（ルート直下、0バイト） | 空ファイル、誤作成 |
| [err.txt](err.txt)（0バイト） | 空ファイル |

---

## 2. 要確認（使用状況がコードから断定できない）

| パス | 要確認ポイント |
|------|----------------|
| [backend/src/main/java/com/beatseeker/backend/controller/KofiWebhookController.java](backend/src/main/java/com/beatseeker/backend/controller/KofiWebhookController.java) | Ko-fi Webhook。HTTP エンドポイントなので grep では使用有無が分からない。Ko-fi 連携機能を運用しているか要確認 |
| [apply_votes_draft.py](apply_votes_draft.py) / [fetch_votes.py](fetch_votes.py) / [draft_changes.json](draft_changes.json) / [votes_result.json](votes_result.json) | 難易度表投票のドラフト適用フロー。定期運用中か、一度きりの移行スクリプトか確認 |
| [generate_vapid.js](generate_vapid.js) | VAPIDキー生成。既に `application.yml` にキーが入っていれば再生成は通常不要 |
| [frontend/update_songs.py](frontend/update_songs.py) vs [frontend/update_songs2.py](frontend/update_songs2.py) | どちらが現役か未判定（194行 vs 127行） |
| [frontend/extract_lines.py](frontend/extract_lines.py) vs [frontend/extract_lines2.py](frontend/extract_lines2.py) | どちらが現役か未判定 |
| [frontend/evaluate_oono.js](frontend/evaluate_oono.js) vs [frontend/evaluate_oono.cjs](frontend/evaluate_oono.cjs) | `.js` が ESM 新版、`.cjs` は旧版の可能性 |

---

## 3. リファクタリング候補（未使用ではないが要整理）

### 3.1 巨大ファイル（責務過多の可能性）

| パス | 行数 | 推奨 |
|------|------|------|
| [frontend/src/components/ScoreSummary.vue](frontend/src/components/ScoreSummary.vue) | 1963 | 子コンポーネントに分割（スコア表示・集計・グラフで分離） |
| [frontend/src/App.vue](frontend/src/App.vue) | 1645 | タブ別 View 切替を router 側に移譲、またはレイアウトコンポーネント化 |
| [backend/.../service/ChartTendencyService.java](backend/src/main/java/com/beatseeker/backend/service/ChartTendencyService.java) | 1082 | 譜面傾向分析・スコア予測・スキルツリー算出の3責務に分離 |
| [backend/.../controller/ScoreController.java](backend/src/main/java/com/beatseeker/backend/controller/ScoreController.java) | 855 | スコア履歴／ランキング／差分通知で Controller を分割 |
| [backend/.../controller/FriendController.java](backend/src/main/java/com/beatseeker/backend/controller/FriendController.java) | 463 | FriendRequest／Friendship／VirtualRival で分割 |

### 3.2 1層無駄な wrapper

| パス | 内容 |
|------|------|
| [frontend/src/components/About.vue](frontend/src/components/About.vue) ⇔ [frontend/src/views/AboutView.vue](frontend/src/views/AboutView.vue) | `AboutView` が `components/About` を1件だけ import。統合可能 |
| [frontend/src/components/Terms.vue](frontend/src/components/Terms.vue) ⇔ [frontend/src/views/TermsView.vue](frontend/src/views/TermsView.vue) | 同上 |

### 3.3 国際化キーの未使用チェック（未実施）

- [frontend/src/locales/ja.ts](frontend/src/locales/ja.ts) / [en.ts](frontend/src/locales/en.ts) / [ko.ts](frontend/src/locales/ko.ts) は各1000行超。実際に参照されていないキーが含まれている可能性あり。静的解析が必要。

---

## 4. Git トラッキングからの除外推奨（`git rm --cached`）

`.gitignore` に追加済み。以下は **過去にコミットされてしまっている** ため、別途 `git rm --cached` で untrack する必要があります。

| パス | 件数 | 理由 |
|------|------|------|
| `node_modules/` | 4,403 | ルートの依存ライブラリ |
| `frontend/node_modules/` | 4,956 | フロント依存 |
| `scripts/node_modules/` | 142 | スクリプト依存 |
| `backend/apache-maven-3.9.6/` | 89 | Maven 本体、`setup_maven.ps1` が都度DL |
| `backend/target/` | 95 | Maven ビルド成果物 |
| `frontend/dist/` | 11 | Vite ビルド成果物 |
| `__pycache__/` | 2 | Python コンパイルキャッシュ |
| `backend/*.log`, `*.txt`（ログ系） | ~15 | 実行ログの残骸 |
| ルートの `*_logs*.txt`, `latest_*.txt`, `ui_*_trace*.txt` 等 | ~20 | 同上 |

**合計 約 9,700 ファイル**。本整理は1コミットで実施すると差分が巨大化するため、カテゴリ単位でのコミットを推奨。
