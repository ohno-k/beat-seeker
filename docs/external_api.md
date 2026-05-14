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

### GET /api/external/v1/song-detail

トークン所有者の楽曲詳細（ユーザー情報・譜面メタ・自分のスコア・順位・履歴・
譜面傾向サマリ）を一括で返します。

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
    "totalSaraPt": 8014.2
  },
  "song": {
    "title": "灼熱Beach Side Bunny",
    "artist": "Sota Fujimori",
    "genre": "HARD DANCE",
    "difficulty": "ANOTHER",
    "level": 12,
    "difficultyLevel": "12.4",
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
  "history": [
    { "uploadedAt": "2026-05-12T22:31:14", "score": 3402, "beatPt": 162.8 },
    { "uploadedAt": "2026-04-30T19:02:01", "score": 3380, "beatPt": 161.4 }
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
| `history` | プレイ履歴がなければ `[]` |
| `chartTendency` | 譜面傾向プロファイル未登録の場合は `null` |

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

## エラー応答の共通形

```json
{ "error": "Unknown difficulty", "allowed": ["BEGINNER","NORMAL","HYPER","ANOTHER","LEGGENDARIA"] }
```

`error` は人間可読の英語メッセージ。詳細フィールドは状況に応じて追加されます。

## レート制限

現状ハード制限は設けていません。連携アプリ側で **譜面詳細レベルのリクエストは
ユーザー操作の都度で OK** ですが、一覧画面で全曲分を並列に叩くなどは避けてください。
将来必要に応じて Bucket4j 等の固定上限を導入します。

## CORS

ブラウザから直接叩く場合は、連携アプリのドメインを beat-seeker 側の CORS
許可リストに追加する必要があります。サーバ→サーバ呼び出しの場合は不要です。

## バージョニング

- パス `/api/external/v1/...` の `v1` を切ることで後方互換を維持します。
- 互換性を壊す変更が必要な場合は `v2` を新設し、旧版は最低 6 ヶ月並走させる予定です。

## 連絡先

仕様に関する質問・要望は GitHub Issues、もしくは beat-seeker の運営に連絡してください。
