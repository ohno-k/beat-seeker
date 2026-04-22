# frontend/ 直下スクリプト一覧

frontend（Vue 3 + TypeScript）ディレクトリ直下に置かれている調査・データ整備用スクリプトの説明です。
フロント本体のビルド／起動は `npm run dev` / `npm run build` で、本書のスクリプトはすべて **データファイル生成補助用のワンショットスクリプト**。

対象データファイル:
- `src/data/difficulty_table.json` — 独自難易度表
- `src/data/song_data.json` — 楽曲マスタ
- `src/data/sp11.json` / `sp12.json` — SP☆11／☆12 のメタ
- `src/data/notes.txt` — 譜面ノーツ数の原本

## 1. ビルド用スクリプト（**現役、`npm run build` から呼ばれる**）

| ファイル | 用途 |
|----------|------|
| [scripts/prerender.mjs](scripts/prerender.mjs) | `vite build` 後に静的 HTML を生成する pre-render スクリプト。`package.json` の `build` 定義から呼ばれる。 |

## 2. データ整備スクリプト（**ワンショット／データ更新時のみ実行**）

### 2.1 楽曲データ更新

| ファイル | 用途 |
|----------|------|
| [update_songs.py](update_songs.py) | 新曲追加時に、スクリプト先頭のヒアストリングに貼り付けた「タイトル/NOTES数」を `song_data.json` に追記する。 |
| [update_songs2.py](update_songs2.py) | `difficulty_table.json` と `sp11.json`/`sp12.json` の整合性チェック。欠落している曲を `missing_songs.txt` に出力。 |
| [update_difficulty_table.js](update_difficulty_table.js) | sp11/sp12.json と difficulty_table.json を突き合わせ、非公式難易度を本体 JSON に反映（ESM 形式）。 |
| [fix_titles.py](fix_titles.py) | `song_data.json` 内の **タイトル表記ゆれ**（`ACTØ` → `ACT0` 等）を一括置換。スクリプト先頭の辞書に対応表。 |

### 2.2 検証・突き合わせ

| ファイル | 用途 |
|----------|------|
| [find_mismatches.py](find_mismatches.py) | スクショで「スコア率 0.0%」と表示された曲を `difficulty_table.json` と照合し、データ不整合を検出。 |
| [evaluate_oono.js](evaluate_oono.js) | `song_data.json` と `difficulty_table.json` を読み込み、大野ポイント（旧評価指標）を算出（ESM 版）。 |
| [calc_max_v2.cjs](calc_max_v2.cjs) | `difficulty_table.json` の☆11.0以上の曲について、重み上位100曲の合計を計算（BEAT-Tier 満点の基準値）。 |

### 2.3 CSV ↔ JSON 変換

| ファイル | 用途 |
|----------|------|
| [csv_to_json.py](csv_to_json.py) | `notes.txt`（タブ区切り CSV）を JSON 形式に変換。`sp11.json`/`sp12.json` 更新時の前処理。 |
| [generate_csv.py](generate_csv.py) | `difficulty_table.json` を CSV 形式に変換。外部ツールでの編集用。 |

### 2.4 アドホック検索（**UNUSED: 一度きりの調査用、汎用性なし**）

| ファイル | 用途 |
|----------|------|
| [extract_lines.py](extract_lines.py) | `notes.txt` から特定キーワード（Punch/Rasp/POL/POT/FiZZ）を含む行を抽出するだけの5行スクリプト。 |
| [extract_lines2.py](extract_lines2.py) | 同上、キーワードが fizz/pot/pøt/0и に変わったもの。 |

## 3. 重複・旧版候補（**UNUSED**）

| ファイル | 判定 | 根拠 |
|----------|------|------|
| [evaluate_oono.cjs](evaluate_oono.cjs) | **UNUSED** | `evaluate_oono.js`（ESM版）の旧 CommonJS 版。中身はほぼ同一。 |

## 4. 一時ファイル（**git トラッキング対象外推奨**）

以下は `.gitignore` 対象。既存ファイルは `git rm --cached` が必要。

- `debug_*.txt`, `diag.txt`, `hex_check.txt`, `debug_absolute.txt`, `debug_sasoribi.txt`, `debug_shakunetsu.txt`, `debug_somni.txt`, `debug_check.txt` — デバッグ出力
- `error_out.txt`, `errors.txt` — エラーダンプ
- `results.txt`, `results_enc.txt`, `mismatch_results.txt`, `missing_json.txt`, `foo.txt`, `oono_points.txt` — スクリプト実行結果
- `build.log`, `install.log`, `out.txt`, `test_node.txt`, `test_write.txt`, `node_version.txt` — 環境確認出力
- `tsconfig.app.tsbuildinfo`, `tsconfig.node.tsbuildinfo` — TypeScript 増分ビルドキャッシュ
- `dist/` — Vite ビルド出力
- `node_modules/` — 依存

## 5. 実行の前提

- Python 3.8+（スクリプトのほとんどは標準ライブラリのみ）
- Node.js 18+（`.js`/`.cjs` スクリプトは `node` で直接実行）
- **作業ディレクトリは `frontend/`** を前提にしているものが多い（相対パス `./src/data/...`）。スクリプトによっては絶対パス `c:\Users\ohno\...` がハードコードされているものもある（`extract_lines*.py`, `update_songs2.py`）。**他環境で動かす場合は書き換えが必要**。
