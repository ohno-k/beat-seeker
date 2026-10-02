# Vuetify 移行（全画面の UI 部品を Vuetify に置き換える）

フロントエンドの UI 部品（ボタン・モーダル・入力欄・セレクト・タブ・カード・チップ・アラート・メニュー・表など）を
Tailwind の手組みから Vuetify 4 のコンポーネントに置き換えた。レイアウト・余白・文字の大きさ・個別の色は Tailwind のまま。

## 土台

- `src/plugins/vuetify.ts`: テーマ（light / dark）と各コンポーネントの既定値。
  - 色は既存デザインに合わせる: primary = blue-700（dark は blue-600）、背景 slate-50 / slate-900、面 white / slate-800、罫線 slate-200 / slate-700。
  - ダークモードは従来どおり `useDarkMode` が `<html>` の `dark` クラスで切り替え、Vuetify のテーマはそのクラスを監視して追従する。
  - 既定値: ボタンは大文字化しない・影なし・角丸 md、入力欄は outlined + compact + `hide-details="auto"`、カードは outlined（影なし）、リップルなし。
- `vite.config.ts`: `vite-plugin-vuetify`（`autoImport: true`）。使ったコンポーネントだけが bundle に入る。
- `main.ts`: `vuetify/styles` を読み込み、`app.use(vuetify)`。
- `App.vue`: 全体を `<v-app>` で包む。

## CSS の優先順位

Vuetify 4 の CSS はすべてカスケードレイヤー（`vuetify-core` / `vuetify-components` / … / `vuetify-final`）に入っている。
Tailwind（v3）の出力はレイヤー外なので、**同じ要素に Tailwind のクラスを付ければ常に Tailwind が勝つ**。
Vuetify コンポーネントの見た目を部分的に変えたいときは、`class` に Tailwind のクラスを足せばよい（`!important` 不要）。

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
