# beat-seeker 外部公開 API

このドキュメントは、iidx-memo 等の **連携先アプリ** が beat-seeker のユーザー情報を
読み取るために利用する公開 API の仕様です。

- ベース URL（本番・推奨）: `https://beat-seeker.com`
- ベース URL（本番・直叩き）: `https://beat-seeker.onrender.com`
- ベース URL（開発）: `http://localhost:8080`
- すべて JSON 応答（`Content-Type: application/json`）
- 認証: **個人 API トークン**（Bearer 方式）

### ベース URL についての補足

beat-seeker はフロント（`beat-seeker.com`）とバックエンド（`beat-seeker.onrender.com`）を
別サービスとして運用していますが、`beat-seeker.com/api/*` は Static Site のリライト設定で
バックエンドに透過プロキシしているため、連携アプリからは **どちらの URL を使っても**
同じレスポンスが返ります。URL を覚えやすい `beat-seeker.com` を推奨します。

## 認証

連携アプリは、エンドユーザーから beat-seeker で発行した **個人 API トークン**
（プレフィックス `bs_live_` で始まる文字列）を受け取り、リクエストヘッダで送ります。

```
Authorization: Bearer bs_live_xxxxxxxxxxxxxxxxxxxxxxxxxxxxxxx
```

### トークンの発行・失効

エンドユーザーは beat-seeker の **プロフィール画面 → 「外部連携トークン」 →
「連携トークンを管理」** から発行します。発行時に表示される平文トークンは
**1 度きり** で再表示できません（DB にはハッシュのみ保存）。

| 操作 | エンドポイント | 認証 | 備考 |
| --- | --- | --- | --- |
| 発行 | `POST /api/integrations/tokens` | beat-seeker ログイン | 平文を 1 回返す |
| 一覧 | `GET /api/integrations/tokens` | 同上 | 平文は返らない |
| 失効 | `DELETE /api/integrations/tokens/{id}` | 同上 | ソフト失効 |

連携アプリ側で必要な操作は **「ユーザーから受け取った平文トークンを保管し、
以降のリクエストに付与する」だけ** です。

### トークン漏洩時

ユーザーが beat-seeker のプロフィール画面でトークンを失効させると、即時に
すべての `/api/external/**` 呼び出しが 401 を返すようになります。

---

## エンドポイント

### POST /api/external/v1/sync-options

iidx-memo 等の連携アプリが、ユーザーの譜面オプション選択（鍵盤乱・皿乱・正規 等）を
beat-seeker に一括同期するためのエンドポイント。

beat-seeker は同期されたオプションを楽曲詳細画面の「オプション」セクションに
読み取り専用で表示します（beat-seeker 単独では編集できません）。

#### リクエスト

```json
{
  "options": [
    { "title": "灼熱Beach Side Bunny", "difficulty": "ANOTHER",    "options": ["乱"] },
    { "title": "灼熱Beach Side Bunny", "difficulty": "LEGGENDARIA", "options": ["正規"] }
  ]
}
```

| フィールド | 型 | 必須 | 説明 |
| --- | --- | --- | --- |
| `options` | array | yes | 同期するレコードの配列 |
| `options[].title` | string | yes | 楽曲タイトル（IIDX 公式表記） |
| `options[].difficulty` | string | yes | `BEGINNER` / `NORMAL` / `HYPER` / `ANOTHER` / `LEGGENDARIA` |
| `options[].options` | string[] | yes | オプション文字列の配列（例: `["乱", "鏡"]`）。空配列のレコードはスキップされる |

#### レスポンス（200 OK）

```json
{ "synced": 2 }
```

`synced` は実際に upsert された件数。バリデーション NG（title 欠落 / difficulty 不正 /
options 空）でスキップされたレコードはカウントに含まれない。

#### upsert セマンティクス

- `(user_id, title, difficulty)` の組で既存レコードがあれば options を上書き。
- 無ければ新規 INSERT。
- **削除は行わない**。連携アプリ側で options を消したい場合、現状の仕様では
  beat-seeker 側の表示は古い状態のまま残る。明示削除 API が必要なら別途設計する。

#### ステータスコード

| コード | 内容 |
| --- | --- |
| 200 | 成功 |
| 400 | body 自体が不正（{"options": null} 等） |
| 401 | トークン不一致 / 失効 / 期限切れ |

#### 例（curl）

```bash
curl -s -X POST "https://beat-seeker.com/api/external/v1/sync-options" \
     -H "Authorization: Bearer bs_live_xxxxxxxxxxxxxxxx" \
     -H "Content-Type: application/json" \
     -d '{
       "options": [
         {"title":"灼熱Beach Side Bunny","difficulty":"ANOTHER","options":["乱"]}
       ]
     }'
```

---

### GET /api/external/v1/song-detail

トークン所有者の楽曲詳細（ユーザー情報・譜面メタ・自分のスコア・順位・
オプション投票集計・履歴・譜面傾向サマリ）を一括で返します。

#### クエリパラメータ

| 名前 | 型 | 必須 | 説明 |
| --- | --- | --- | --- |
| `title` | string | yes | 楽曲タイトル（IIDX 公式表記） |
| `difficulty` | string | yes | `BEGINNER` / `NORMAL` / `HYPER` / `ANOTHER` / `LEGGENDARIA` |

#### レスポンス（200 OK）

```json
{
  "user": {
    "iidxId": "1234-5678",
    "djName": "DJ-OONO",
    "danRank": "皆伝",
    "arenaRank": "A1",
    "totalBeatPt": 16234.5,
    "totalKenbanPt": 8120.3,
    "totalSaraPt": 8014.2,
    "totalRatePt": 9532.1,
    "totalPrecisionPt": 6217.8
  },
  "song": {
    "title": "灼熱Beach Side Bunny",
    "artist": "Sota Fujimori",
    "genre": "HARD DANCE",
    "difficulty": "ANOTHER",
    "level": 12,
    "difficultyLevel": "12",
    "informalRank": "12.4",
    "notes": 1857,
    "bpm": "153",
    "textage": "20/beach_a.html?...",
    "wr": 3680,
    "avg": 3210
  },
  "score": {
    "score": 3402,
    "djLevel": "AAA",
    "clearType": "HARD CLEAR",
    "missCount": 87,
    "pgreat": 1545,
    "great": 312,
    "playCount": 42,
    "uploadedAt": "2026-05-12T22:31:14"
  },
  "rank": {
    "rank": 124,
    "total": 5821,
    "calculatedAt": "2026-05-13T03:00:00"
  },
  "options": ["乱"],
  "optionVotes": {
    "counts": { "REGULAR": 3, "MIRROR": 1, "RANDOM": 12, "R-RANDOM": 0, "S-RANDOM": 2 },
    "totalVotes": 15,
    "myVotes": ["RANDOM"]
  },
  "history": [
    { "uploadedAt": "2026-05-12T22:31:14", "score": 3402, "beatPt": 162.8, "ratePt": 95.2 },
    { "uploadedAt": "2026-04-30T19:02:01", "score": 3380, "beatPt": 161.4, "ratePt": 94.8 }
  ],
  "chartTendency": {
    "bpmMain": 153,
    "isSoflan": false,
    "notes": 1857,
    "scratchPct": 12.4,
    "chordPct": 38.6,
    "singlePct": 49.0,
    "jackPct": 2.1,
    "trillPct": 6.3,
    "stairsPct": 11.8,
    "dstairsPct": 1.4,
    "cnNotes": null,
    "tagsJson": "[\"scratch_moderate\",\"has_32nd\"]"
  }
}
```

#### 各ブロックの省略可否

| ブロック | null の条件 |
| --- | --- |
| `user` | 必ず存在 |
| `song` | 該当譜面が存在しなければ 404（このフィールドが null になることはない） |
| `score` | ユーザーが未プレイの場合は `null` |
| `rank` | 順位キャッシュ未生成（ANOTHER / LEGGENDARIA 以外は基本 `null`） |
| `options` | sync-options で同期がなければ `[]` |
| `optionVotes` | 投票が 0 件でも構造は返る（counts 全 0 / totalVotes 0 / myVotes `[]`）。DB 障害時のみ `null` |
| `history` | プレイ履歴がなければ `[]` |
| `chartTendency` | 譜面傾向プロファイル未登録の場合は `null` |
| `song.informalRank` | 非公式難易度表（`difficulty_ranks`）未登録の譜面は `null` |

### `optionVotes`（オプション投票集計）について

beat-seeker のユーザーが譜面ごとに投票している「推奨オプション」の集計です
（アプリ内の楽曲詳細画面に表示されるものと同じデータ）。

| フィールド | 説明 |
| --- | --- |
| `counts` | オプション別の得票数。キーは `REGULAR` / `MIRROR` / `RANDOM` / `R-RANDOM` / `S-RANDOM` の 5 種固定 |
| `totalVotes` | 投票したユニークユーザー数。1 人が複数オプションに投票しても 1 と数える（割合表示の分母に使う） |
| `myVotes` | トークン所有者自身が投票しているオプションの配列（複数選択可）。未投票なら `[]` |

補足:

- 1 ユーザーが複数オプションに投票できるため、`counts` の合計は `totalVotes` 以上になり得ます。
- 値は **トークン所有者のプレイサイド視点** に変換済みです。DB には常に 1P 視点で保存されており、
  所有者が 2P 設定の場合は `REGULAR` と `MIRROR` を入れ替えて返します（RANDOM 系は変換対象外）。

### `level` / `difficultyLevel` / `informalRank` の違い

| フィールド | 出典 | 例 | 用途 |
| --- | --- | --- | --- |
| `level` | 公式 `★` 表記 | `12` | 公式の星 1〜12 |
| `difficultyLevel` | `song_definitions.difficulty_level`（拡張欄、レガシー） | `"12"` | 一部譜面のみ小数値が入っていることがあるが、基本は公式 level と同じ |
| `informalRank` | 非公式難易度表 `difficulty_ranks.rank_value` | `"12.4"` `"12.2"` | beat-seeker が独自管理している小数点付きの非公式難易度 |

#### ステータスコード

| コード | 内容 |
| --- | --- |
| 200 | 正常応答（スコアやランクは null になり得る） |
| 400 | `difficulty` が許容値外 |
| 401 | トークン不一致 / 失効 / 期限切れ |
| 404 | `title` × `difficulty` に該当する譜面マスタが無い |

#### 例（curl）

```bash
curl -s "https://beat-seeker.com/api/external/v1/song-detail?title=灼熱Beach%20Side%20Bunny&difficulty=ANOTHER" \
     -H "Authorization: Bearer bs_live_xxxxxxxxxxxxxxxx"
```

---

### GET /api/external/v1/score-summaries

**曲一覧画面向け**に、トークン所有者の全譜面スコア概要（**アーケードの記録のみ**）を
1 リクエストで返します。

`song-detail` は 1 譜面 1 リクエストで履歴・譜面傾向・オプション投票まで組み立てるため、
一覧のために全譜面を叩くと上流タイムアウトを起こします。本エンドポイントは
**一覧に必要な項目だけ**を返す軽量版です。曲詳細画面では引き続き `song-detail` を使ってください
（置き換えではなく用途分割）。

#### クエリパラメータ

| 名前 | 型 | 必須 | 説明 |
| --- | --- | --- | --- |
| `difficulties` | string | no | カンマ区切りの難易度名。既定値 `ANOTHER,LEGGENDARIA`。大文字小文字・前後空白・重複は正規化される |

#### レスポンス（200 OK）

```json
{
  "user": { "iidxId": "1234-5678", "djName": "DJ-OONO" },
  "difficulties": ["ANOTHER", "LEGGENDARIA"],
  "source": "arcade",
  "generatedAt": "2026-08-02T23:45:36.213142",
  "count": 2082,
  "playedCount": 812,
  "summaries": [
    {
      "textage": "30/_cmflg.html?1AC00",
      "title": "#CMFLG",
      "difficulty": "ANOTHER",
      "level": 12,
      "informalRank": "12.2",
      "clearType": "HARD CLEAR",
      "score": 3200,
      "djLevel": "AA",
      "missCount": 30,
      "beatPt": 154.02,
      "ratePt": 2.95,
      "updatedAt": "2026-08-02T23:45:36.213142"
    },
    {
      "textage": "26/_and_int.html?1AB00",
      "title": "& Intelligence",
      "difficulty": "ANOTHER",
      "level": 11,
      "informalRank": "11.9",
      "clearType": null,
      "score": null,
      "djLevel": null,
      "missCount": null,
      "beatPt": null,
      "ratePt": null,
      "updatedAt": null
    }
  ]
}
```

#### 全件スナップショット

`summaries` は **指定難易度の全譜面**（`song_definitions` の active リビジョン）を含みます。
未プレイ譜面もスコア項目 `null` で必ず 1 行返るため、連携先は差分マージ無しで
「返ってきた配列 = その難易度の全譜面」として扱えます。

配列は **曲名 → 難易度の昇順**で安定ソートされているので、前回取得ぶんとの差分比較にそのまま使えます。

#### フィールド

| フィールド | 出典 | null の条件 |
| --- | --- | --- |
| `textage` / `title` / `difficulty` / `level` | `song_definitions` | 必ず存在（`textage` はマスタ未設定時のみ `null`） |
| `informalRank` | 非公式難易度表 | 未登録譜面は `null`。ANOTHER / LEGGENDARIA 以外は常に `null` |
| `clearType` / `score` / `djLevel` / `missCount` / `updatedAt` | `scores`（arcade のみ） | アーケード記録が無ければ `null` |
| `beatPt` / `ratePt` | サーバー側で都度計算 | 下記「PT の算出条件」参照 |

`song-detail` の `score` ブロックにある `pgreat` / `great` / `playCount` は一覧では返しません。
必要なら曲詳細で `song-detail` を叩いてください。

#### アーケードの記録のみを返す

beat-seeker は同一譜面にアーケード記録（e-amusement CSV / ブックマークレット）と
INFINITAS 記録（画面共有 OCR）を別レコードとして並存させますが、本 API は
**アーケードの記録だけ**を返します。レスポンス直下の `source` が `"arcade"` 固定なのはこのためで、
全行共通の値なので行ごとには持たせていません。

注意点:

- **INFINITAS でしかプレーしていない譜面は「未プレイ」として返ります**（スコア項目すべて `null`）。
- 同一譜面で INFINITAS 側の EX スコアの方が高い場合でも、返るのはアーケードの値です。
  beat-seeker アプリ内の表示・総 BEAT-PT は「両ソースのうち高い方」を採用しているため、
  そのようなユーザーでは当方の画面と値が食い違います。

#### PT の算出条件

`beatPt` / `ratePt` は DB に保存しておらず、リクエストのたびにアプリ内の集計と同じ式で計算します。
以下の場合は `null` になります。

- `clearType` が `NO PLAY` / `---`、またはアーケードのスコア行そのものが無い
- 譜面マスタにノーツ数が無くスコアレートを算出できない
- `ratePt`: ANOTHER / LEGGENDARIA 以外（RATE-PT の対象外difficulty）
- `beatPt`: 公式 Lv11 以上の HYPER（BEAT-PT の集計対象外）

`null` と `0` は意味が違います。**`null` は「算出対象外」、`0` は「プレー済みだが基準未満」**です
（BEAT-PT はスコアレート 66.666% 以下、または非公式難易度が未登録なら `0`。
RATE-PT はスコアレート 77.77% 未満なら `0`）。

値は小数第 2 位で丸めています。表示用の丸めは連携先の裁量で行ってください。

なお `clearType` は未プレイ判定とは独立に、スコア行があればそのまま返します
（`"NO PLAY"` もランプの一種として表示できるようにするため）。

#### ステータスコード

| コード | 内容 |
| --- | --- |
| 200 | 正常応答（未プレイ譜面はスコア項目が `null`） |
| 400 | `difficulties` に未知の難易度名が含まれる / 有効な難易度が 0 個 |
| 401 | トークン不一致 / 失効 / 期限切れ |

#### レスポンスサイズ

`ANOTHER,LEGGENDARIA`（約 2,100 譜面）で **非圧縮 約 470KB / gzip 約 45KB** です。
サーバー側で gzip を有効にしているので、`Accept-Encoding: gzip` を必ず付けてください。

#### 例（curl）

```bash
curl -s --compressed \
     "https://beat-seeker.com/api/external/v1/score-summaries?difficulties=ANOTHER,LEGGENDARIA" \
     -H "Authorization: Bearer bs_live_xxxxxxxxxxxxxxxx"
```

---

## エラー応答の共通形

```json
{ "error": "Unknown difficulty", "allowed": ["BEGINNER","NORMAL","HYPER","ANOTHER","LEGGENDARIA"] }
```

`error` は人間可読の英語メッセージ。詳細フィールドは状況に応じて追加されます。

## レート制限

現状ハード制限は設けていません。連携アプリ側で **譜面詳細レベルのリクエストは
ユーザー操作の都度で OK** ですが、`song-detail` を一覧画面で全曲分ループするのは避けてください。
一覧用途には `score-summaries`（1 リクエストで全譜面ぶん）を使ってください。
将来必要に応じて Bucket4j 等の固定上限を導入します。

## CORS

ブラウザから直接叩く場合は、連携アプリのドメインを beat-seeker 側の CORS
許可リストに追加する必要があります。サーバ→サーバ呼び出しの場合は不要です。

## バージョニング

- パス `/api/external/v1/...` の `v1` を切ることで後方互換を維持します。
- 互換性を壊す変更が必要な場合は `v2` を新設し、旧版は最低 6 ヶ月並走させる予定です。

## 連絡先

仕様に関する質問・要望は GitHub Issues、もしくは beat-seeker の運営に連絡してください。
