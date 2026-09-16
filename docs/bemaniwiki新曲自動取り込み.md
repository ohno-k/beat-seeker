# bemaniwiki 新曲リストの自動取り込み

BEMANIWiki 2nd の「[beatmania IIDX 34 ZINRAI/新曲リスト](https://bemaniwiki.com/?beatmania+IIDX+34+ZINRAI/%E6%96%B0%E6%9B%B2%E3%83%AA%E3%82%B9%E3%83%88)」を
定期的に取得し、新しく載った曲・譜面を beat-seeker の公開中（active）楽曲マスタへ自動で反映する仕組み。2026-09-16 導入。

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
- **譜面単位で判定する**: 1 曲でも「N と A は取り込むが H はノーツ数未記載なので保留」のように分かれる。
  保留した譜面は wiki が埋まった次回以降の実行で自動的に追加される。
- **保留にする譜面**: 灰色表記（未解禁の隠し譜面。ノーツ数だけ解析で判明しているもの）、レベルに `?` が付いている、
  レベルまたはノーツ数が空欄。
- **`[CN]` `[BSS]` `[HCN]` `[CN?]` などの注記**はレベル数字から取り除いて読む。`-` は「譜面なし」。
- **2 つの表は曲名で結合する**: レベル表と総ノーツ数表は並び順が一致しないことがあるので TITLE で突き合わせる
  （完全一致 → 全角半角・空白・波ダッシュを正規化して一致）。
- **既存曲との突き合わせ**: 曲名の完全一致 → 正規化一致。正規化でしか一致しない場合は既存マスタの表記を採用して
  二重登録を避け、警告として記録する（公式 CSV の曲名と一致させるため、保存する曲名は元の表記のまま）。
- **既存譜面の更新**: レベル・ノーツ数・GENRE・ARTIST・BPM が wiki と違えば wiki の値で上書きする（wiki が訂正されたときに追従）。
  曲名は変更しない。
- **draft は経由しない**: 管理者が管理画面で編集途中の draft を「楽曲を適用」で巻き込まないよう、active に直接書く。
- **難易度表**: 新規に追加した（またはレベルが 11/12 に変わった）ANOTHER / LEGGENDARIA は active 難易度表の
  `Uncategorized` に入れる（管理画面の「新曲追加」と同じ。LEGGENDARIA は `曲名[L]`）。既に表にある曲は動かさない。
- **BEAT-PT の再計算はしない**（新曲は Uncategorized なので配点に影響しない）。

## 実行タイミングと設定

`application.yml` の `app.wiki-song-sync.*`（環境変数で上書き可）。

| 設定 | 環境変数 | 既定 | 意味 |
|---|---|---|---|
| `enabled` | `WIKI_SONG_SYNC_ENABLED` | `true` | 定期実行の有効/無効。`app.scheduling.enabled=false`（prod-db プロファイル）でも止まる |
| `cron` | `WIKI_SONG_SYNC_CRON` | `0 20 0,6,12,18 * * *` | JST の Spring cron（秒 分 時 日 月 曜日）。既定は 1 日 4 回 |
| `url` | `WIKI_SONG_SYNC_URL` | ZINRAI 新曲リスト | 取得ページ。次回作ではここを差し替える |
| `auto-apply` | `WIKI_SONG_SYNC_AUTO_APPLY` | `true` | `false` なら定期実行は差分の記録だけで DB を変更しない |

1 回の実行は 130KB ほどのページを 1 回取得するだけ（User-Agent は `beat-seeker-song-sync/1.0`）。

## 管理画面からの操作

ゲームデータ管理 → 楽曲追加タブ → 「bemaniwiki 新曲同期」パネル。

- **差分を確認**: 取得・解析・突き合わせまで行い、追加/更新/保留/警告の内訳を表示する。DB は変更しない。
- **今すぐ同期**: 定期実行と同じ処理をその場で実行して反映する。
- **最近の実行**: 直近 5 件の履歴（日時、定期/手動、反映あり/変更なし/失敗、件数）。

反映があった実行では、管理者ユーザー（`admin.user-id`）にメールアドレスが登録されていれば内訳をメールする。

API（管理者のみ）:

- `POST /api/admin/game-data/songs/wiki-sync` — body `{"dryRun": true}` で確認のみ。実行中なら 409
- `GET /api/admin/game-data/songs/wiki-sync/runs` — 直近 20 件の実行記録

## 実行記録（`wiki_song_sync_runs`）

| 列 | 内容 |
|---|---|
| `status` | `SUCCESS`（追加・更新あり）/ `NO_CHANGE` / `FAILED` |
| `trigger_kind` / `dry_run` | `scheduled` / `manual`、確認のみか |
| `page_hash` / `page_changed` | 解析結果の SHA-256 と、前回の記録から変わったか |
| `songs_on_page` / `added_count` / `updated_count` / `held_count` | 件数 |
| `summary_json` | 追加・更新・保留・配信前・警告・Uncategorized 追加の明細 |
| `error_message` | 失敗時の例外 |

テーブルは `ddl-auto: update` により初回デプロイで自動生成される（prod-db プロファイルは `ddl-auto: none` なので手動実行は失敗する）。

## コード

- `service/BemaniwikiSongListParser.java` — HTML（PukiWiki の表）の解析。Spring/DB に依存しない
- `service/WikiSongSyncService.java` — 取得・突き合わせ・反映・記録・通知・定期実行
- `service/GameDataService.applyWikiChartChanges` — active への書き込みと Uncategorized 追加
- `controller/GameDataController` — `wiki-sync` / `wiki-sync/runs`
- `entity/WikiSongSyncRun.java`, `repository/WikiSongSyncRunRepository.java`
- `frontend/src/components/AdminGameDataModal.vue` — パネル
- テスト: `BemaniwikiSongListParserTest`（`src/test/resources/bemaniwiki/zinrai_new_songs_2026-09-16.html` は 2026-09-16 時点の実ページ）

## 既知の制約

- 曲名は wiki の表記をそのまま保存する。公式 CSV の表記と字が違う曲があれば、スコアが紐づかないので
  `SongTitleAliases`（バックエンド・フロント両方）に対応を足すか、管理画面で曲名を直す。
- 実力解禁・イベント解禁の譜面は wiki が灰色表記を外すまで入らない。先に必要なら管理画面から手動追加する
  （追加後は同じ (曲名, 難易度) として同期が値を追従する）。
- ページの表構成（見出し `SP`/`NOTE(SP)`、`TITLE`/`GENRE`/`ARTIST`）が変わると解析が例外になり、
  実行記録に `FAILED` として残る（ログにも出る）。
