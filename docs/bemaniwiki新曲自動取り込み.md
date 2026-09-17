# bemaniwiki 新曲リスト・旧曲リストの自動取り込み

BEMANIWiki 2nd の次のページを定期的に取得し、新しく載った曲・譜面や訂正を beat-seeker の公開中（active）楽曲マスタへ
自動で反映する仕組み。新曲リストは 2026-09-16、旧曲リストは 2026-09-17 導入。

| 取得元（`source`） | ページ | 頻度（既定） |
|---|---|---|
| `new` 新曲リスト | [beatmania IIDX 34 ZINRAI/新曲リスト](https://bemaniwiki.com/?beatmania+IIDX+34+ZINRAI/%E6%96%B0%E6%9B%B2%E3%83%AA%E3%82%B9%E3%83%88)（レベル表と総ノーツ数表が同じページ） | 1 日 4 回（0:20 / 6:20 / 12:20 / 18:20） |
| `old` 旧曲リスト | [旧曲リスト](https://bemaniwiki.com/?beatmania+IIDX+34+ZINRAI/%E6%97%A7%E6%9B%B2%E3%83%AA%E3%82%B9%E3%83%88)（レベル表）+ [旧曲総ノーツ数リスト](https://bemaniwiki.com/?beatmania+IIDX+34+ZINRAI/%E6%97%A7%E6%9B%B2%E7%B7%8F%E3%83%8E%E3%83%BC%E3%83%84%E6%95%B0%E3%83%AA%E3%82%B9%E3%83%88)（総ノーツ数表） | 1 日 1 回（5:40） |

2 つの取得元は別々に実行・記録する。取り込みのルールは共通で、旧曲リストにだけ追加の安全装置がある（後述）。
旧曲リストで拾えるのは、レベル変更・ノーツ数の訂正・旧曲への譜面追加（LEGGENDARIA など）・復活曲。

## 取り込む項目

| wiki の列 | 保存先（`song_definitions`） |
|---|---|
| TITLE / GENRE / ARTIST / BPM | `title` / `genre` / `artist` / `bpm` |
| SP の B / N / H / A / L のレベル | `level`（難易度コード 1 / 2 / 3 / 4 / 10 の行） |
| NOTE(SP) の B / N / H / A / L | `notes` |

DP、TIME、MOVIE、LAYER、WR、AVG、係数、textage は扱わない（従来どおり手動・別ツール）。

## 取り込みのルール

- **配信前の曲は見送る**: 表の区切り行に日付（`2026/09/16配信(稼働初期)`）があればその日以降に取り込む。
  日付が無く「後日登場予定」「予定」「未定」を含む区切りの下にある曲は、配信日付きの区切りへ移るまで取り込まない。
  旧曲リストの区切り行は作品名（`beatmania IIDX 17 SIRIUS`）なので、すべて配信済みとして扱う。
- **譜面単位で判定する**: 1 曲でも「N と A は取り込むが H はノーツ数未記載なので保留」のように分かれる。
  保留した譜面は wiki が埋まった次回以降の実行で自動的に追加される。
- **保留にする譜面**: 灰色表記（未解禁の隠し譜面。ノーツ数だけ解析で判明しているもの）、レベルに `?` が付いている、
  レベルまたはノーツ数が空欄。ただし**登録済みの譜面**は、ノーツ数だけが未記載でもレベルの比較は続ける（保留にしない）。
  旧曲リストの金太字（無条件解禁されていない譜面）や赤・青・緑・茶の色付きレベルは、灰色ではないので通常どおり取り込む。
- **`[CN]` `[BSS]` `[HCN]` `[CN?]` などの注記**はレベル数字から取り除いて読む。`-` は「譜面なし」。
- **譜面別の注記が並ぶ複数行セル**: GENRE / TITLE / ARTIST のセルで、2 行目以降が難易度マーカー（`[N]` `[SPA]` など）で
  始まるものは「譜面ごとに表記が違う」という注記（crew、DENIM、Evans、BEAUTIFUL ANGEL）。曲としての値は 1 行目だけを使う
  （1 行目から注記のときはその行のマーカーを外す。crew の GENRE → `ANTHEM`）。マーカーで始まらない改行は折り返しなので空白でつなぐ。
- **rowspan / colspan**: ムービーを共有する 2 曲の MOVIE / LAYER が rowspan でまとめられ、下の行の td が少ないことがある
  （Timepiece phase II と同 (CN Ver.)）。列をずらさずに読む。
- **2 つの表は曲名で結合する**: レベル表と総ノーツ数表は並び順や表記が一致しないことがあるので TITLE で突き合わせる
  （完全一致 → 全角半角・空白・波ダッシュを正規化して一致）。
- **既存曲との突き合わせ**（`WikiTitleResolver`）: 次の順で既存マスタの曲を探し、完全一致以外は既存マスタの表記を採用して
  二重登録を避ける（公式 CSV の曲名と一致させるため、保存する曲名は元の表記のまま）。
  1. 曲名の完全一致
  2. 正規化一致（全角半角・大文字小文字・空白・波ダッシュ）
  3. 指紋一致: ARTIST と GENRE が（正規化して）一致し、同じ難易度のノーツ数が 2 譜面以上一致する既存曲が 1 曲だけある。
     候補は「そのページのどの曲とも 1・2 で対応していない既存曲」に限る

  3 は公式 CSV が特殊文字を ASCII に置き換えている曲のため（`ÆTHER` → `ATHER`、`焱影` → `火影`、`LOVE♡SHINE` → `LOVE SHINE`。
  2026-09-17 時点で旧曲に 35 曲）。ノーツ数だけだと別の曲どうしで 2 譜面一致する組が 40 組以上あるので（Evans と crew と V は
  譜面そのものを共有）、ARTIST と GENRE の一致を必須にしている。読み替えは、新曲リストでは「警告」、旧曲リストでは件数が多いので
  「曲名の読み替え」（`titleMatches`）として記録する。
- **既存譜面の更新**: レベル・ノーツ数・GENRE・ARTIST・BPM が wiki と違えば wiki の値で上書きする（wiki が訂正されたときに追従）。
  曲名は変更しない。BPM が `※`（譜面により異なる）のときは、登録済みの具体的な値を上書きしない。
- **draft は経由しない**: 管理者が管理画面で編集途中の draft を「楽曲を適用」で巻き込まないよう、active に直接書く。
- **難易度表**: 新規に追加した（またはレベルが 11/12 に変わった）ANOTHER / LEGGENDARIA は active 難易度表の
  `Uncategorized` に入れる（管理画面の「新曲追加」と同じ。LEGGENDARIA は `曲名[L]`）。既に表にある曲は動かさない。
  登録済みの ANOTHER / LEGGENDARIA のレベルが ★11/12 に絡んで変わったとき（例: ★11→12）は、表の配置は自動では動かさず
  「難易度表の配置を確認してください」という警告を出す。
- **BEAT-PT の再計算はしない**（新曲は Uncategorized なので配点に影響しない。ノーツ数の訂正は次回の難易度表適用時の全体再計算で反映）。

### 旧曲リストだけの安全装置

- **自動反映の上限**: 定期実行 1 回で変更のかかる曲数が `max-auto-songs`（既定 30）を超えたら反映を見送り、
  `NEEDS_REVIEW` として記録・通知する。表構成の変化などで全曲を書き換えてしまう事故の歯止め。
  管理画面で差分を確認してから「今すぐ同期」を押せば反映される（手動実行に上限は無い）。
  通知メールはページ内容が前回から変わったときだけ送る。
- **マスタに無い曲を追加するときの警告**: 旧曲リストの曲が 1〜3 のどれでも既存曲に対応しなかった場合は復活曲として追加するが、
  曲名の表記違いによる二重登録の可能性があるので警告に出す。

## 実行タイミングと設定

`application.yml` の `app.wiki-song-sync.*`（環境変数で上書き可）。

| 設定 | 環境変数 | 既定 | 意味 |
|---|---|---|---|
| `enabled` | `WIKI_SONG_SYNC_ENABLED` | `true` | 新曲リストの定期実行の有効/無効。`app.scheduling.enabled=false`（prod-db プロファイル）でも止まる |
| `cron` | `WIKI_SONG_SYNC_CRON` | `0 20 0,6,12,18 * * *` | 新曲リスト。JST の Spring cron（秒 分 時 日 月 曜日） |
| `url` | `WIKI_SONG_SYNC_URL` | ZINRAI 新曲リスト | 次回作ではここを差し替える |
| `auto-apply` | `WIKI_SONG_SYNC_AUTO_APPLY` | `true` | `false` なら定期実行は差分の記録だけで DB を変更しない（新曲・旧曲で共通） |
| `old-songs.enabled` | `WIKI_OLD_SONG_SYNC_ENABLED` | `true` | 旧曲リストの定期実行の有効/無効 |
| `old-songs.cron` | `WIKI_OLD_SONG_SYNC_CRON` | `0 40 5 * * *` | 旧曲リスト。1 日 1 回 |
| `old-songs.list-url` | `WIKI_OLD_SONG_SYNC_LIST_URL` | ZINRAI 旧曲リスト | 次回作ではここを差し替える |
| `old-songs.notes-url` | `WIKI_OLD_SONG_SYNC_NOTES_URL` | ZINRAI 旧曲総ノーツ数リスト | 同上 |
| `old-songs.max-auto-songs` | `WIKI_OLD_SONG_SYNC_MAX_AUTO_SONGS` | `30` | 定期実行で自動反映する曲数の上限。`0` で無制限 |

新曲リストは 130KB ほどのページを 1 回、旧曲リストは 2.4MB と 1.8MB のページを 2 秒あけて 1 回ずつ取得する
（User-Agent は `beat-seeker-song-sync/1.0`）。旧曲は総ノーツ数 → レベル表の順に 1 ページずつ解析し、DOM を同時に 2 つ持たない。
2 ページの解析と突き合わせは合わせて 1 秒弱。

## 管理画面からの操作

ゲームデータ管理 → 楽曲追加タブ → 「bemaniwiki 楽曲同期」パネル。見出し横の「新曲リスト / 旧曲リスト」で取得元を切り替える。

- **差分を確認**: 取得・解析・突き合わせまで行い、追加/更新/保留/警告/曲名の読み替えの内訳を表示する。DB は変更しない。
- **今すぐ同期**: 定期実行と同じ処理をその場で実行して反映する（旧曲リストの自動反映の上限はかからない）。
- **最近の実行**: 選択中の取得元の直近 5 件（日時、定期/手動、反映あり/変更なし/要確認（未反映）/失敗、件数）。

反映があった実行（および `NEEDS_REVIEW`）では、管理者ユーザー（`admin.user-id`）にメールアドレスが登録されていれば内訳をメールする。

API（管理者のみ）:

- `POST /api/admin/game-data/songs/wiki-sync` — body `{"dryRun": true, "source": "old"}`。`source` は省略時 `new`。
  不正な `source` は 400、実行中なら 409
- `GET /api/admin/game-data/songs/wiki-sync/runs` — 直近 20 件の実行記録（新曲・旧曲の両方。`source` 付き）

## 実行記録（`wiki_song_sync_runs`）

| 列 | 内容 |
|---|---|
| `source` | `new` / `old`。2026-09-17 追加（DB 既定値 `'new'`。それ以前の行は新曲リストの記録） |
| `status` | `SUCCESS`（追加・更新あり）/ `NO_CHANGE` / `NEEDS_REVIEW`（上限超過で未反映）/ `FAILED` |
| `trigger_kind` / `dry_run` | `scheduled` / `manual`、確認のみか |
| `page_hash` / `page_changed` | 解析結果の SHA-256 と、同じ取得元の前回の記録から変わったか |
| `songs_on_page` / `added_count` / `updated_count` / `held_count` | 件数 |
| `summary_json` | 追加・更新・保留・配信前・警告・曲名の読み替え・Uncategorized 追加の明細 |
| `error_message` | 失敗時の例外 |

テーブルと列は `ddl-auto: update` によりデプロイ時に自動で作られる（prod-db プロファイルは `ddl-auto: none` なので、
列の追加前に手動実行すると失敗する）。

## コード

- `service/BemaniwikiSongListParser.java` — HTML（PukiWiki の表）の解析。Spring/DB に依存しない。
  1 ページ版 `parse(html)` と、2 ページ版 `parseNotesPage(html)` → `parse(html, notesPage)`
- `service/WikiTitleResolver.java` — wiki の曲名 → 既存マスタの曲名（完全一致 / 正規化一致 / 指紋一致）
- `service/WikiSongSyncService.java` — 取得・突き合わせ（`planChanges`）・反映・記録・通知・定期実行
- `service/GameDataService.applyWikiChartChanges` — active への書き込みと Uncategorized 追加
- `controller/GameDataController` — `wiki-sync` / `wiki-sync/runs`
- `entity/WikiSongSyncRun.java`, `repository/WikiSongSyncRunRepository.java`
- `frontend/src/components/AdminGameDataModal.vue` — パネル
- テスト:
  - `BemaniwikiSongListParserTest`（`zinrai_new_songs_2026-09-16.html` は 2026-09-16 時点の新曲リストの実ページ）
  - `BemaniwikiOldSongListParserTest`（`zinrai_old_songs_2026-09-17_excerpt.html` / `zinrai_old_songs_notes_2026-09-17_excerpt.html` は
    2026-09-17 時点の実ページから楽曲の行を 31 曲分に間引いたもの。行の HTML は原文のまま）
  - `WikiSongSyncPlanTest`（突き合わせ: 指紋一致・二重登録の防止・レベル変更・譜面追加・BPM `※`・保留）

## 2026-09-17 時点の旧曲リスト（導入時の計測）

- 旧曲リスト 1805 曲（1st style 〜 32 Pinky Crush。**33 Sparkle Shower の節はまだ wiki に無い**＝曲数「?」）、5,800 譜面。
- 本番マスタとの差分: 追加 1（Medicine of love [L] ★11）、更新 49 譜面 / 20 曲、曲名の読み替え 43（正規化 8 + 指紋 35）、二重登録 0。
  更新の中身は、レベル変更 1（Time To Empress [L] ★11→12）、本番側のノーツ数の誤り（crew・DENIM・Evans が別の曲の値、
  Macho Monky [H] 11233→1123、Friction[!]Function、[ ]DENTITY、PARANOIA survivor MAX）、ARTIST / GENRE の表記 13 曲。

## 既知の制約

- 曲名は wiki の表記をそのまま保存する。公式 CSV の表記と字が違う新曲があれば、スコアが紐づかないので
  `SongTitleAliases`（バックエンド・フロント両方）に対応を足すか、管理画面で曲名を直す
  （直した後は指紋一致で同じ曲として追従する）。
- 実力解禁・イベント解禁の譜面は wiki が灰色表記を外すまで入らない。先に必要なら管理画面から手動追加する
  （追加後は同じ (曲名, 難易度) として同期が値を追従する）。
- **削除曲は扱わない**: マスタにあって wiki に無い曲は何もしない（33 の節が未掲載の現状では削除と区別できない）。
- 既存マスタに (曲名, 難易度) が重複した行がある曲（`Do it!! Do it!!` の L、`オーバーライド` の N）は、その譜面に変更が
  かかったとき ID 最大の行を残して他を削除する（`applyWikiChartChanges` の従来動作）。
- ページの表構成（見出し `SP`/`NOTE(SP)`、`TITLE`/`GENRE`/`ARTIST`）が変わると解析が例外になり、
  実行記録に `FAILED` として残る（ログにも出る）。
