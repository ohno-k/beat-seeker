# iidx-memo → beat-seeker オプション同期

iidx-memo に登録したオプション選択を beat-seeker に一括送信する仕組みの設計ドキュメントです。

---

## 全体の流れ

```
[設定画面] 同期ボタン押下
    ↓
handleSync()  ← app/settings/page.tsx
    ↓
syncOptions()  ← lib/beat-seeker.ts
    ↓  POST /api/beat-seeker/sync-options  (x-bs-token ヘッダ付き)
    ↓
[iidx-memo プロキシ]  ← app/api/beat-seeker/sync-options/route.ts
    ↓  POST https://beat-seeker.onrender.com/api/external/v1/sync-options
    ↓  Authorization: Bearer <token>
[beat-seeker API]
```

CORS 回避のため、クライアントは直接 beat-seeker に送らず、
iidx-memo のサーバーサイドプロキシ経由でリクエストを転送します。

---

## データ変換

設定画面の `handleSync` が Zustand ストアのメモデータを beat-seeker 形式に変換します。

| iidx-memo `difficulty` | beat-seeker `difficulty` |
|------------------------|--------------------------|
| `"A"` | `"ANOTHER"` |
| `"L"` | `"LEGGENDARIA"` |
| `"H"` | `"HYPER"` |
| `"N"` | `"NORMAL"` |
| `"B"` | `"BEGINNER"` |

`options` が空のメモはスキップします（`m.options.length > 0` のみ送信）。

### 送信ペイロード例

```json
{
  "options": [
    { "title": "灼熱Beach Side Bunny", "difficulty": "ANOTHER",    "options": ["乱"] },
    { "title": "灼熱Beach Side Bunny", "difficulty": "LEGGENDARIA", "options": ["正規"] }
  ]
}
```

---

## プロキシ仕様

**`POST /api/beat-seeker/sync-options`**

| 項目 | 値 |
|------|-----|
| 認証 | リクエストヘッダ `x-bs-token: <token>` |
| タイムアウト | 30 秒（AbortController） |
| upstream | `BEAT_SEEKER_BASE_URL` 環境変数 / デフォルト `https://beat-seeker.onrender.com` |

プロキシはトークンを `Authorization: Bearer <token>` に付け替えて upstream へ転送し、
レスポンスをそのまま返します。

### レスポンス

| ステータス | 内容 |
|------------|------|
| 200 | 同期成功。`{ "synced": <件数> }` |
| 401 | トークン不正（upstream または本プロキシ） |
| 400 | リクエストボディ不正 |
| 502 | upstream 到達不可またはタイムアウト |

---

## 関連ファイル

| ファイル | 役割 |
|---------|------|
| `lib/beat-seeker.ts` | `syncOptions()` 関数・型定義 |
| `app/api/beat-seeker/sync-options/route.ts` | iidx-memo プロキシ |
| `app/settings/page.tsx` | UI・`handleSync()` |
| `external_api.md` | beat-seeker 側 API 仕様（`POST /api/external/v1/sync-options`） |
