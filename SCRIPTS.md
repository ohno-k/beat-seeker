# tools/ スクリプト一覧

`tools/` に置かれている調査・運用・検証用スクリプトの概要です。
各スクリプトは単発実行用であり、アプリ本体（backend / frontend）のビルドや起動には使用されません。
**実行はリポジトリルートから行います**（例: `python tools/batch_analyze.py`）。入出力パス（`data/`, `sql/`, `chart_cache/`）はルート基準で解決されます。旧版・一度きり検証用スクリプトは `tools/legacy/` に隔離しています。

## 実行に必要な依存

| 種別 | 依存 |
|------|------|
| Python スクリプト | Python 3.8+ / `psycopg2`（DB接続系のみ） |
| Node スクリプト | ルートの `node_modules/`（`puppeteer` 等）／`scripts/node_modules/`（`pg`） |

---

## 1. 運用中のスクリプト

### 1.1 譜面傾向分析フロー（`chart_cache/` を生成）

| スクリプト | 用途 | 入出力 |
|------------|------|--------|
| [batch_analyze.py](tools/batch_analyze.py) | 全 SP 譜面をバッチ取得・分析。textage から HTML を取得し、全難易度（SPB/SPN/SPH/SPA/SPL）を一括パースして `chart_cache/` に出力。HTMLキャッシュにより再取得なしで再分析可能。 | 入力: textage.cc / 出力: `chart_cache/cache_index.json`, `chart_cache/raw/`, `chart_cache/profiles/` |
| [analyze_chart.py](tools/analyze_chart.py) | `bms2jsh.js` の `sp[]` デコーダ + interval / chord パターン分析。`batch_analyze.py` から import して使用。 | 入力: HTML文字列 / 出力: 傾向プロファイル（JSON） |
| [bms2jsh_raw.js](tools/bms2jsh_raw.js) | textage オリジナルの BMS → JSH デコーダのリファレンス実装。Python 側のデコード検証用。 | - |

### 1.2 難易度表投票フロー

| スクリプト | 用途 | 入出力 |
|------------|------|--------|
| [fetch_votes.py](tools/fetch_votes.py) | 本番 PostgreSQL から PROMOTE / DEMOTE 投票を集計取得。 | 出力: `data/votes_result.json` |
| [apply_votes_draft.py](tools/apply_votes_draft.py) | 投票結果に基づき難易度表ドラフトを作成（PROMOTE 多数→1段階昇格など）。 | 出力: `data/draft_changes.json` |
| [apply-notes-bonus-draft.js](scripts/apply-notes-bonus-draft.js) | 物量(ノーツ数)加点をドラフトに適用（1800以上+0.1、以降200ごと+0.1、上限13.1）。デフォルト dry-run、`--apply` でバックアップ profile 作成後に draft を書き換え（active には触れない）。 | 出力: `data/notes_bonus_changes.json`, `data/notes_bonus_report.md` |
| [slide-draft-to-active-counts.js](scripts/slide-draft-to-active-counts.js) | draft の序列を維持したまま各帯の曲数を active の定員に詰め直す（定員はスクリプト内 `CAPACITY_OVERRIDES` で上書き可）。デフォルト dry-run、`--apply` でバックアップ profile 作成後に draft を書き換え。 | 出力: `data/slide_changes.json`, `data/slide_report.md` |
| [nudge-range-up.js](scripts/nudge-range-up.js) | draft の指定帯範囲（`--from`〜`--to`）の各帯先頭 `--n` 曲をひとつ上の帯へ繰り上げ（序列不変の境界微調整）。デフォルト dry-run、`--apply` でバックアップ profile 作成後に draft を書き換え。 | 出力: `data/nudge_changes.json`, `data/nudge_report.md` |
| [optimize-band-sizes.js](scripts/optimize-band-sizes.js) | draft の序列固定のまま帯境界を座標降下法で最適化し、全ユーザーの BEAT-PT 変化（active→draft）のランキング加重 RMS を最小化（重み=順位^−`--rankpower`、デフォルト0.5）。BEAT-PT はシミュレーション SQL と同一ロジックをローカル再現。デフォルト dry-run、`--apply` でバックアップ profile 作成後に draft を書き換え。 | 出力: `data/optimize_band_sizes.json`, `data/optimize_band_sizes_report.md` |
| [rebuild-draft-maxminus-zinrai.js](scripts/rebuild-draft-maxminus-zinrai.js) | ZINRAI 向け全曲再判定（**13.1 は `MANUAL_TOP` の手動指定枠**、残りは MAX-率順に active の帯別曲数で詰める。定員を消費するのは既に数値帯にいた曲だけで、Uncategorized からの新規参入曲は自分の順位が指す帯へ相乗りしその帯を 1 曲増やす。物量加点は 1800 以上で +0.1、以降 200 ごとに +0.1、**到達上限 12.8**・減点なし）。物量補正の効かせ方が異なる案A（上乗せ・帯サイズ変動）と案B（詰め直し・帯サイズ維持）を計算し、`--apply` で両方を `profile:ZINRAI-planA` / `profile:ZINRAI-planB` に保存したうえで `--plan=A|B`（既定 B）を draft に書き込む。active には触れない。 | 出力: `data/zinrai_rebuild_changes.json`, `data/zinrai_rebuild_report.md` |
| [merge-uncategorized.js](scripts/merge-uncategorized.js) | Uncategorized 帯の1本化（2026-07-24 実施済み）。active/draft の `Uncategorized(IIDX 32)`/`(IIDX 33)` を削除（曲があれば移動してから）し、`Uncategorized(other)` を `Uncategorized` にリネーム。デフォルト dry-run、`--apply` で本適用。 | 出力: コンソールのみ |

**前提**: `psycopg2` が必要、PostgreSQL 接続情報がハードコードされている（`tools/apply_votes_draft.py:14`, `tools/fetch_votes.py:4`）。`apply-notes-bonus-draft.js` は Node + `pg`（接続情報ハードコード、本番 DB 直接更新）。

### 1.3 レポート・可視化

| スクリプト | 用途 | 入出力 |
|------------|------|--------|
| [build-growth-chart.js](scripts/build-growth-chart.js) | ユーザー増加と CSV 読み込み回数の推移グラフを PNG 出力。週次（JST 月曜始まり）で「累計ユーザー数 / 新規登録 / CSV 読み込み」の 3 段。スケールが 2 桁違うため 2 軸で重ねず軸を分ける。集計途中の最終週はグラフから除外（合計値には含む）、公開初週の突出は棒を破断させて実数を添える。`--out=` で出力先変更可。 | 出力: `docs/user_growth_YYYY-MM.png` |

**前提**: Node + `pg`（`scripts/node_modules`）+ `puppeteer`（リポジトリ直下の `node_modules`）。puppeteer 同梱のブラウザは未取得のため、ローカルの Chrome / Edge を使う（見つからない場合は `CHROME_PATH` で指定）。

### 1.4 検証系

| スクリプト | 用途 |
|------------|------|
| [find_textage_urls.py](tools/find_textage_urls.py) | `frontend/src/data/song_data.json` の textage URL 欠落を検出し、`titletbl.js` と照合してパッチ生成。 |
| [verify_notes.py](tools/verify_notes.py) | `chart_cache/profiles/` と `song_data.json` を突き合わせ、ノーツ数の検証結果を出力。 |
| [generate_invalid_check_sql.py](tools/generate_invalid_check_sql.py) | `sp11.json`/`sp12.json` からスコア率100%超過の検出 SQL (`sql/find_invalid_scores.sql`) を自動生成。※`BASE_DIR`(L5) が旧環境の絶対パスでハードコードされているため、実行前に修正が必要。 |

### 1.5 初期セットアップ（通常は一度きり）

| スクリプト | 用途 |
|------------|------|
| [generate_vapid.js](tools/generate_vapid.js) | Web Push 用の VAPID 鍵ペアを生成し `vapid_keys.txt` に書き出す。**鍵は `backend/application.yml` に設定済みのはずなので通常は再生成不要。** |

### 1.6 SQL（ワンショット実行）

| ファイル | 用途 |
|----------|------|
| [add_is_public_column.sql](sql/add_is_public_column.sql) | `scores` テーブルに `is_public` カラムを追加するマイグレーション |
| [cleanup_duplicates.sql](sql/cleanup_duplicates.sql) | 重複ユーザ削除（実行済み想定） |
| [find_invalid_scores.sql](sql/find_invalid_scores.sql) | 100%超スコア検出（`generate_invalid_check_sql.py` の出力） |

その他の SQL（マイグレーション・データ修復・調査用）も `sql/` にまとめています。

---

## 2. `scripts/` ディレクトリ（Top-Rankers データ取得パイプライン）

| スクリプト | 用途 |
|------------|------|
| [scripts/scrape-top-rankers.js](scripts/scrape-top-rankers.js) | `masaoblue/iidx-top-rankers-viewer` から全バージョン×全都道府県の CSV をダウンロード。再開可能。 |
| [scripts/merge-all-top-rankers.js](scripts/merge-all-top-rankers.js) | バージョン跨ぎで「歴代ベスト」CSV を生成（`scripts/top-rankers-data/0/` に出力）。 |
| [scripts/compress-top-rankers.js](scripts/compress-top-rankers.js) | 全 CSV を gzip 化し `backend/src/main/resources/top-rankers-data/` に配置。`manifest.json` も生成。 |
| [scripts/backfill-rate-tier.js](scripts/backfill-rate-tier.js) | 全ユーザーの `score_history_logs` の `total_rate_pt` を再計算して DB 更新。 |
| [scripts/scrape-arena-top-rankers.js](scripts/scrape-arena-top-rankers.js) | 管理者用。eagate のアリーナクラス TOP RANKER ランキング（上位1000人）を取り込み、プレイデータ公開プレイヤーを `virtual_arena_rankers` / `virtual_arena_ranker_scores` に保存（登録済み IIDX ID はスキップ）。`EAGATE_COOKIE` と PG 接続情報が必要。BEAT/RATE-PT はバックエンド `VirtualArenaRankerService` が集計。**eagate のページ DOM は要 HTML サンプル確定**（`PARSE_*` に TODO 明記）。 |
| [scripts/probe.js](scripts/probe.js) | データソースの存在確認（HTTPヘッダのみ取得）。 |
| [scripts/scrape-top-rankers-test.js](scripts/scrape-top-rankers-test.js) | スクレイピングのテスト版 |

**典型実行フロー**:

```bash
# Top-Rankers 初回セットアップ
node scripts/probe.js                       # 1. 存在確認
node scripts/scrape-top-rankers.js          # 2. CSVダウンロード
node scripts/merge-all-top-rankers.js       # 3. 歴代ベスト作成
node scripts/compress-top-rankers.js        # 4. gzip圧縮して backend resources へ配置

# Rate-Tier 再計算（全ユーザー）
node scripts/backfill-rate-tier.js
```

---

## 3. 未使用スクリプト（`tools/legacy/` に隔離）

以下は旧バージョンまたはワンショット検証用で、現在は使用されていません。`tools/legacy/` に移動済み。詳細は [UNUSED.md](UNUSED.md) を参照。

- `tools/legacy/calc_folder_rates.js`, `calc_folder_rates2.js`, `calc_folder_rates3.js` → 後継: `tools/calc_folder_rates_final.js`
- `tools/legacy/fetch_rankings.js`, `fetch_rankings_log.js`, `fetch_rankings_robust.js` → 後継: `scripts/scrape-top-rankers.js`
- `tools/legacy/scrapeTest.js`, `test_bookmarklet.js`, `test-parsing.js`, `calc_max.js` → ワンショット検証用
- `tools/legacy/TestWeights.java` → BEAT-Tier 重み付けの手元検証、本体統合済み（`TestWeights.class` はビルド成果物のため削除済み）

---

## 4. 実行時の注意

1. **DB 接続情報はスクリプト内にハードコード** されています（`tools/apply_votes_draft.py`, `tools/fetch_votes.py`, `scripts/backfill-rate-tier.js`）。本番 DB を直接更新するものもあるため、実行前に対象環境を必ず確認してください。
2. **出力ファイル（`*.json`, `*.csv`, `*.txt`）** のうち、ルート直下でコミットされていたログ・一時出力・DB出力は追跡解除済み（`git rm --cached` 実施済み）。スクリプトの入出力データは `data/`、生成 SQL は `sql/` に集約しています。
3. **`chart_cache/`** は 14,000+ ファイルを含む譜面傾向データで、現状 git にトラッキングされています。サイズが非常に大きいため、LFS または別管理への移行を検討してもよい対象です。
