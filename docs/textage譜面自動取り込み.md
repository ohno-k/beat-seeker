# textage 譜面の自動取り込み（譜面傾向プロファイルの拡充）

bemaniwiki 同期（[bemaniwiki新曲自動取り込み.md](bemaniwiki新曲自動取り込み.md)）が楽曲マスタに足した譜面について、
[textage.cc](https://textage.cc/score/) から譜面データを取得・解析し、譜面傾向プロファイル（`chart_tendency_profiles`）を
自動で作る仕組み。2026-09-29 導入。あわせて、2026-04 に一括投入した既存プロファイルの保存方法を見直した（後半）。

プロファイルはスコア予測・BEAT-TIER・スキルツリー・伸びしろ分析・曲詳細の「譜面傾向」で使われる。
プロファイルが無い譜面はこれらの対象外になるので、新曲はこれまで手動でプロファイルを投入するまで出てこなかった。

## 流れ

1. **洗い出し**: 公開中（active）の SP 譜面（B/N/H/A/L）のうち、ノーツ数が登録済みで、
   プロファイルが無いもの（新曲・譜面追加）と旧方式で解析されたもの（後述）を曲ごとにまとめる
2. **ページの特定**（照合できるまで順に試す）
   1. その譜面・同じ曲の他の譜面に登録済みの textage（`20/_decoher.html?1AC00` → `20/_decoher.html`）
   2. 既存プロファイル（旧方式を含む）が指しているページ。楽曲マスタの textage が失われた譜面の手がかり（下記「楽曲マスタの textage の欠落」）
   3. textage の曲一覧 `titletbl.js` の候補。曲名（TITLE、または TITLE+SUBTITLE）の正規化一致。
      無ければ ARTIST と GENRE の一致（公式 CSV が特殊文字を置き換えた曲名の救済。この場合は 2 譜面以上の一致を要求）
3. **実行と照合**: ページに埋め込まれた JS を、textage の閲覧スクリプト（bms2jsh.js）と同じ難易度フラグ付きで実行し、
   `sp[]`（譜面）・`c1[]`（CN）・`ln[]`（小節長）・`notes`（ページが宣言するノーツ数）を取り出す。
   **ページのノーツ数が楽曲マスタのノーツ数と一致**（差が 1% 以内、最低 2 ノーツまで許容）した譜面だけ採用する
4. **解析と保存**: 採用した譜面の傾向（密度・皿・和音・縦連/トリル/階段・CN・小節別ノーツ数・タグ）を計算して保存し、
   楽曲マスタの textage も記録する（active・draft の両方。編集中の draft を適用しても消えないように）
5. **記録**: bemaniwiki 同期と同じ `wiki_song_sync_runs` に `source = 'textage'` で残す。新しい譜面を解析した回は管理者にメール

照合に使う難易度フラグ（bms2jsh.js の URL 解釈どおり。いずれも SP なので `k=1`）:

| 難易度 | URL の文字 | フラグ |
|---|---|---|
| BEGINNER | B | `l=1; g=1` |
| NORMAL | N | `l=1; hps=1` |
| HYPER | H | `hps=1` |
| ANOTHER | A | `a=1` |
| LEGGENDARIA | X | `a=1; kuro=1` |

その難易度がページに無いときは、ページの JS が既定の譜面（多くは HYPER / ANOTHER）を選ぶのでノーツ数が合わず、保留になる。

## 実行タイミングと設定

`application.yml` の `app.textage-sync.*`（環境変数で上書き可）。

| 設定 | 環境変数 | 既定 | 意味 |
|---|---|---|---|
| `enabled` | `TEXTAGE_SYNC_ENABLED` | `true` | 定期実行の有効/無効。`app.scheduling.enabled=false` でも止まる |
| `cron` | `TEXTAGE_SYNC_CRON` | `0 50 4,16 * * *` | JST の 4:50 / 16:50 |
| `base-url` | `TEXTAGE_SYNC_BASE_URL` | `https://textage.cc/score/` | |
| `auto-apply` | `TEXTAGE_SYNC_AUTO_APPLY` | `true` | `false` なら定期実行は取得・解析・記録だけ |
| `max-pages` | `TEXTAGE_SYNC_MAX_PAGES` | `120` | 定期実行 1 回で取得するページ数の上限（titletbl.js を含む） |
| `manual-max-pages` | `TEXTAGE_SYNC_MANUAL_MAX_PAGES` | `20` | 管理画面からの実行の上限（30 秒前後） |
| `request-interval-ms` | `TEXTAGE_SYNC_REQUEST_INTERVAL_MS` | `1200` | textage へのリクエスト間隔 |
| `reanalyze-legacy` | `TEXTAGE_SYNC_REANALYZE_LEGACY` | `true` | 旧方式のプロファイルも解析し直すか |

取りに行く順番は「新しい譜面を含む曲 → 譜面分析ページに出ていない譜面（楽曲マスタに textage が無い ANOTHER / LEGGENDARIA）がある曲
（★11/12 が欠けている曲 → ☆10 以下が欠けている曲）→ 一度も取りに行っていないページ → 最後に取りに行ったのが古いページ」。
N/H/B だけが欠けている曲は譜面分析の一覧に関係しないので優先しない。
新方式で照合済みのプロファイルがあるのに楽曲マスタの textage だけ空の譜面は、取得せずにプロファイルのリンクを書き戻す
（実行記録の `restoredLinks`）。

### 楽曲マスタの textage の欠落（2026-09-30）

譜面分析ページの曲一覧は「★11/12 の A/L で楽曲マスタに textage がある譜面」なので、textage が無いと一覧に出ない。
2026-09-30 時点で本番の 1,335 譜面中 176 譜面が該当し、うち 174 譜面は 2024-11 の同梱 song_data では textage があった
（いつ消えたかは DB に履歴が無く不明）。173 譜面は旧方式プロファイルに正しいページが残っていたが、同期は楽曲マスタの textage しか
手がかりにせず、しかも旧方式の再解析の順番待ち（初回実行時点で 1,853 曲）の中にあった。そこで、プロファイルのページを
titletbl.js より先に試し（照合はノーツ数で同じく行う）、これらの曲を旧方式の再解析だけの曲より先に回すようにした。
本番データで計画を作ると、該当 165 曲のうち 163 曲がページを持ち、新曲 46 曲の直後に並ぶ（約 165 ページ＝定期実行 2 回ほど）。
残る BENiZAKURA（textage 1421 / 登録 1405 ノーツ）と Shogun's Last Dawn（textage 未掲載とみられる）は照合で保留のまま。

同日、譜面分析ページを ANOTHER / LEGGENDARIA の全レベルに広げた（☆10 以下の絞り込みつき）。☆10 以下の A/L は本番で
830 譜面中 41 譜面しか textage が無かった（☆10 以下は全難易度で 4,941 譜面中 195。2024-11 の同梱 song_data では 4,760 譜面にあった）。
同期の計画では、A/L が欠けている 901 曲（★11/12 163 曲・☆10 以下 738 曲）すべてがページの手がかり（楽曲マスタかプロファイル）を持ち、
欠けている A/L は約 950 譜面。新曲と合わせて約 945 ページで、既定の 1 回 120 ページ・1 日 2 回なら 4 日前後。
照合できなかった譜面のページは、新しい譜面なら 20 時間（管理画面からの実行は待たない）、旧方式の再解析なら 14 日空けてから
再試行する（textage に載っていない難易度のページを毎回取りに行かないため）。最後に取りに行った日時は `textage_page_attempts` に残る。

## 管理画面

ゲームデータ管理 → 楽曲追加タブ → 「楽曲・譜面の自動同期」パネルで取得元「textage 譜面」を選ぶ。
「差分を確認」は取得・解析まで行い DB を変更しない。結果の欄は、新規に解析 / 再解析（旧方式から）/
textage のページを特定・訂正 / 保留（理由つき）/ 取得上限で次回に回した曲 / 警告。

API は bemaniwiki 同期と共通: `POST /api/admin/game-data/songs/wiki-sync` に `{"source": "textage", "dryRun": true}`。

## 既存プロファイルの保存方法の見直し（2026-09-29）

### 見つかった問題

2026-04 に `tools/batch_analyze.py` で作り、`chart_cache/all_profiles.json`（18MB）を管理画面から一括投入した 6,065 行を調べた。

1. **解析の取り違え・欠け**: 旧方式はページの JS を正規表現で追っていたため、`if(k){…if(l){NORMAL の上書き}…}else{DP}`
   のような分岐を取り違える。キャッシュ済みページ（`chart_cache/html/`）で再計算すると、デコードしたノーツ数が公式ノーツ数と
   一致したのは 6,033 譜面中 558 譜面だけで、2,275 譜面は 20% 以上ずれていた（例: 22DUNK [N] を DP 譜面から取っていた）。
   JS を実行する方式では、ページのノーツ数が公式と一致するのが 5,806 譜面（96%）。
2. **プレースホルダ**: 1,584 行は解析値が空（`events = 0`、インターバル分布なし、小節別ノーツ数が全部 0）。
3. **リンク誤り**: 楽曲マスタの textage が別ページを指している譜面がある。旧来の LEGGENDARIA 専用ページ（†）に
   N/H/A が紐づいている曲（GRID KNIGHT、龍と少女とデコヒーレンス、THE DEEP STRIKER など 7 曲）や、
   譜面が変わった曲（SHOOTING STAR → 33/shoot_rg）。同期はこれらを照合で見つけて訂正する（8 曲 23 譜面）。
4. **取り込みが全置換**: 管理画面の JSON 取り込みは「全削除 → 全件 INSERT」で、ファイルに無い行（自動で足した譜面など）を消していた。
5. **リポジトリの肥大**: `chart_cache/` に同じデータが 4 段（html 34MB → raw 29MB → profiles 42MB → all_profiles.json 18MB）、
   計 14,700 ファイル・123MB が追跡されていた。html/ は UTF-8 と Shift_JIS が混在している（取得スクリプトによって違う）。

### 対応

- **DB を正とする**: プロファイルに `analyzer_version`（`textage-js-1`）と `analyzed_at` を追加。旧方式の行は `NULL`。
  同期が新しい譜面を優先しつつ、取得上限の残りで旧方式の行を解析し直す（約 2,000 ページ。1 日 2 回 × 120 ページで 9 日前後）。
  照合できなかった旧方式の行（textage に無い難易度・リンク誤りで訂正先も見つからない）は、消さずに残して保留に理由を出す。
  キャッシュ済みページでのシミュレーションでは、6,063 譜面中 5,828 譜面を再解析、23 譜面のリンクを訂正、235 譜面が保留
  （33 作目の NORMAL/BEGINNER が textage に未掲載、など）。
- **取り込みを追加・上書きに**: `POST /api/admin/chart-tendencies/import(-json)` は textage をキーに追加・上書きし、削除しない。
  新方式で解析済みの行は、旧方式のファイル（`analyzer_version` なし）では上書きしない（結果の `keptNewer`）。
- **同じ譜面の重複行を作らない**: 同期が保存するとき、同じ (曲名, 難易度) で別の textage キーの行は削除する
  （予測計算は曲名 + 難易度でプロファイルを引くため）。
- **エンティティの修正**: `ChartTendencyProfile` は DB から読んだ行も「新規」扱いのままで、Spring Data の `delete()` が
  何もしなかった。`@PostLoad` / `@PostPersist` で新規フラグを落とすようにした。
- **リポジトリ**: `chart_cache/raw/` と `chart_cache/all_profiles.json` の追跡を外した（再生成可能な派生物。`.gitignore` 済み）。
  `chart_cache/html/`（オフラインで再解析・検証できる元データ）と `chart_cache/profiles/`（2026-04 時点の投入データの控え）は残す。
  以後の新しい譜面は DB にだけ入るので、`profiles/` は最新ではない。

### 旧方式のツールの位置づけ

`tools/batch_analyze.py` / `tools/analyze_chart.py` と `chart_cache/*.py` は 2026-04 の一括投入の記録として残すが、
譜面傾向の計算は `ChartTendencyAnalyzer`（Java）が正。計算式・丸め・タグの閾値・JSON のキーは Python 版と同じで、
同じ `sp[]` / `c1[]` を渡したときの出力が、キャッシュ済み 9,565 譜面（1,913 ページ × 5 難易度）すべてで一致することを確認している。
違うのは入力の取り出し方（正規表現 → JS 実行）だけ。

## コード

- `service/TextagePageRunner.java` — ページの JS を Rhino で実行して `sp[]` / `c1[]` / `ln[]` / `notes` を取り出す。
  Java のクラスが見えないスコープ・インタプリタモード・命令数の上限つき（無限ループ・外部コードへの対策）
- `service/ChartTendencyAnalyzer.java` — 小節デコードと傾向の計算（Python 版の移植）
- `service/TextageTitleTable.java` — `titletbl.js`（Shift_JIS）の解析と候補ページの検索
- `service/TextageChartSync.java` — 洗い出し・ページ特定・照合・解析（DB と HTTP に依存しない）
- `service/TextageChartSyncService.java` — 定期実行・取得・保存・記録・通知
- `service/ChartTendencyService.saveAnalyzedProfiles` / `upsertProfiles` — 保存
- `entity/TextagePageAttempt.java` — 最後に取りに行った日時
- テスト: `TextagePageRunnerTest` / `ChartTendencyAnalyzerTest`（Python 版の出力と比較）/ `TextageTitleTableTest` /
  `TextageChartSyncTest` / `ChartTendencyProfileStorageTest`（H2）。フィクスチャは `src/test/resources/textage/`
  （実ページ 4 枚と titletbl.js の抜粋）

## 既知の制約

- textage に譜面が載るまでは保留になる（新曲の NORMAL/BEGINNER が後回しになっていることが多い）。載れば次回以降に拾う。
- ページのノーツ数と公式ノーツ数が 1% を超えて違う譜面（textage の誤記・譜面変更）は採用しない。
- `titletbl.js` の形式が変わると、textage 未登録の曲のページを探せない（警告に出る。登録済みリンクの曲は影響なし）。
- 曲名が textage と大きく違い、ARTIST・GENRE も違う曲は見つけられない。管理画面で textage を手入力すれば、次回その
  ページで照合する。
- 傾向軸（`TendencyAxisService`）はプロファイルを 30 分メモリに持つので、新しいプロファイルはその後に反映される
  （予測・スキルツリーはリクエストごとに DB から読む）。
