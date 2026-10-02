# Vuetify 移行（全画面の UI 部品を Vuetify に置き換える）

フロントエンドの UI 部品（ボタン・モーダル・入力欄・セレクト・タブ・カード・チップ・アラート・メニュー・表など）を
Tailwind の手組みから Vuetify 4 のコンポーネントに置き換えた。レイアウト・余白・文字の大きさ・個別の色は Tailwind のまま。

## 見た目の方針（Vuetify らしさを残す）

元の Tailwind の見た目に寄せず、**Vuetify（Material Design）標準の見た目**にする。

- 色・影・角丸・リップル・入力欄（filled）・ボタンの文字・余白は Vuetify の既定のまま。テーマも Vuetify 標準の light / dark。
- Vuetify のコンポーネントには、見た目を変える Tailwind のクラス（`bg-*`・`text-{色}`・`border*`・`rounded*`・`shadow*`・
  `font-*`・`text-{xs,sm,…}`・`px-*`/`py-*`・`h-*`・`hover:*`・`dark:*`・`transition*` など）を付けない。
  意図は props で表す: 色 → `color="primary/error/success/warning/info"`、大きさ → `size="small/large"`・`density="compact"`、
  種類 → `variant="flat/tonal/outlined/text/elevated"`、影 → `elevation`。
- Tailwind で残してよいのは配置だけ: `flex`・`grid`・`gap-*`・`m*-*`（外側の余白）・`w-*`/`max-w-*`/`min-w-0`・`self-*`/`ml-auto`・
  `hidden sm:inline` などのレスポンシブな出し分け・`truncate`。
- `v-card` の中身は `v-card-title` / `v-card-subtitle` / `v-card-text` / `v-card-actions` を使う（中のレイアウトは Tailwind でよい）。
- ヘッダー・サイドバー・本文の枠組み（App.vue のレイアウト）は手組みのまま。
- 置き換えない部分（下記）と、ただの文章・データ表示の要素（Vuetify のコンポーネントでない `div`・`span` など）の Tailwind はそのまま。

## 土台

- `src/plugins/vuetify.ts`: Vuetify 標準テーマ（light / dark）。コンポーネントの既定値は変えていない。
  - ダークモードは従来どおり `useDarkMode` が `<html>` の `dark` クラスで切り替え、Vuetify のテーマはそのクラスを監視して追従する。
- `index.html`: Material Design の標準フォント Roboto を読み込む。
- `vite.config.ts`: `vite-plugin-vuetify`（`autoImport: true`）。使ったコンポーネントだけが bundle に入る。
- `main.ts`: `vuetify/styles` を読み込み、`app.use(vuetify)`。
- `App.vue`: 全体を `<v-app>` で包む。

## CSS の優先順位

Vuetify 4 の CSS はすべてカスケードレイヤー（`vuetify-core` / `vuetify-components` / … / `vuetify-final`）に入っている。
Tailwind（v3）のユーティリティはレイヤー外なので、**同じ要素に Tailwind のクラスを付けると常に Tailwind が勝つ**。
だからこそ、Vuetify のコンポーネントに見た目の Tailwind クラスを付けると Vuetify らしさが消える（上の「見た目の方針」）。
Tailwind の base（preflight。`* { border-width: 0 }`・`button { background: transparent }` など）は `src/layers.css` で
Vuetify より下のレイヤー（`tw-base`）に置いている。これが Vuetify より上にあると、ボタンの色や枠線が消える。

## 置き換えの対応表

| 旧（Tailwind 手組み） | 新（Vuetify） | 注意 |
|---|---|---|
| `<button class="btn-primary">`・青塗りのボタン | `<v-btn color="primary">` | `@click`・`:disabled`・`type="submit"` はそのまま付け替える |
| `btn-secondary`・枠線のボタン | `<v-btn variant="outlined">` | |
| 文字だけのボタン・リンク風ボタン | `<v-btn variant="text">` | |
| アイコンだけのボタン（× など） | `<v-btn icon variant="text" size="small">` + `<v-icon :icon="mdiClose" />` | `aria-label` を残す。アイコンは `@mdi/js` から import |
| モーダル（`fixed inset-0` の背景 + パネル） | `<v-dialog>` + `<v-card>`（`v-card-title` / `v-card-text` / `v-card-actions`） | 下の「モーダル」参照 |
| `<input type="text/number/email/password">` | `<v-text-field>`（`type`・`v-model`・`label`/`placeholder`） | `v-model.number` はそのまま使える |
| `<textarea>` | `<v-textarea>` | |
| `<select>` | `<v-select :items="…" item-title item-value>` | `<option>` の並びは items 配列にする |
| `<input type="checkbox">` | `<v-checkbox>` / ON-OFF の切り替えは `<v-switch>` | |
| `<input type="radio">` | `<v-radio-group>` + `<v-radio>` | |
| `<input type="range">` | `<v-slider>` | `min`/`max`/`step` はそのまま |
| セグメント（ボタンを並べて 1 つ選ぶ） | `<v-btn-toggle v-model mandatory>` + `<v-btn :value>` | |
| タブ | `<v-tabs v-model>` + `<v-tab :value>`（中身は `v-window` か既存の `v-if` のまま） | |
| `.card`・枠線と角丸の箱 | `<v-card>`（必要なら `v-card-title` など） | 中のレイアウトは Tailwind のまま |
| `.badge`・小さなラベル | `<v-chip size="small" label>` | 色は `color` か Tailwind |
| お知らせ・注意書きの箱 | `<v-alert type="info/warning/error/success">` | |
| スピナー | `<v-progress-circular size="20" width="2">` | |
| 進捗バー | `<v-progress-linear>` | |
| ドロップダウン・ポップオーバー | `<v-menu>` + `<v-list>` | 開閉の ref は v-model に |
| `<table>` | `<v-table>`（中の `thead`/`tbody`/`tr`/`td` は既存のまま） | |
| `<details>` の開閉 | `<v-expansion-panels>`（任意） | 既定で開いていたものは `model-value` で開く |
| 区切り線 | `<v-divider>` | |
| トースト | `<v-snackbar>`（任意） | |

### モーダル

- 親が `v-if` で出し入れしているモーダル（`close` を emit する形）は、ダイアログ側で
  `<v-dialog :model-value="true" @update:model-value="(v) => { if (!v) emit('close') }" max-width="…">`
  とする（Esc・外側クリックで閉じる動作は既存と同じにする。既存が外側クリックで閉じないなら `persistent`）。
- `show` などの prop で開閉しているモーダルは `:model-value="show"` で受ける。
- 幅は既存の `max-w-*` に合わせて `max-width`（例: `max-w-lg` → `512`、`max-w-2xl` → `672`）。
- スマホで全画面にしていたものは `:fullscreen="$vuetify.display.xs"` など。

## 置き換えないもの

見た目そのものが成果物・独自描画のものは、既存のマークアップのまま残す。

- 画像出力（html2canvas）で撮る領域: `UploadReportShareImage.vue`、`ResultImageSection.vue` の撮影領域、`WrappedView.vue` の共有画像、`UploadResultModal.vue` の画像部分。
  Vuetify のコンポーネントは html2canvas で崩れやすい（`utils/html2canvasHelpers.ts` の補正の前提が変わる）。
  これらの画面でも、撮影領域の外にあるボタン・ダイアログは置き換える。
- canvas の描画（譜面再生・グラフ）、SVG のアイコン部品（`RankIcon.vue`、`DivisionIcon.vue`）。
- OBS 用の配信オーバーレイ（`Obs*View.vue`）の表示部分（背景透過で使う）。
- エイプリルフールの演出（`AprilFoolsOverlay.vue`）。

## ルール

- ロジック（script）・props・emits・イベント・`id`・`data-*` 属性は変えない。テンプレートの部品だけ置き換える。
- 要素に付いていた `ref` をコンポーネントに付け替える場合、script 側で DOM を触っているなら `.$el` 経由にする。
- 既存の Tailwind のクラス（余白・幅・文字の大きさ・`sm:` などのレスポンシブ・`dark:`）は、置き換えたコンポーネントに必要な分を残す。
- 文言は変えない（i18n の `t(...)` もそのまま）。
