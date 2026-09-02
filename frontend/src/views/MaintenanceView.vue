<script setup lang="ts">
/**
 * 【Viewの役割】 メンテナンス中であることを利用者に伝える全画面ページ。
 *
 * 機能:
 *  - 「メンテナンス中です。」を画面中央に大きく表示する。
 *  - 通常の UI（サイドバー・タブ等）は一切描画しない。表示の出し分けは `main.ts` が行う。
 *  - ライト/ダークどちらのテーマでも読めるよう `useDarkMode` で初期テーマを適用する。
 *
 * 依存:
 *  - `useDarkMode` — <html> への `dark` class 付与（onMounted で自動実行）。
 *  - `useMaintenance` — 表示するかどうかの判定（呼び出し元の `main.ts` 側で使用）。
 */
import { useDarkMode } from '../composables/useDarkMode';

// 呼び出すだけで onMounted 時に localStorage / OS 設定から初期テーマが適用される。
useDarkMode();

/**
 * 【関数の役割】 「再読み込み」ボタンから呼ばれ、ページを取得し直す。
 * メンテナンスが明けたかどうかを利用者自身で確認できるようにするための導線。
 */
const reload = () => {
  window.location.reload();
};
</script>

<template>
  <div
    class="min-h-screen bg-slate-50 dark:bg-slate-900 flex items-center justify-center px-6 py-12 transition-colors duration-200"
  >
    <div class="w-full max-w-md text-center">
      <!-- アイコン: 工具の絵文字。装飾なので読み上げ対象から外す -->
      <div class="text-6xl mb-6" aria-hidden="true">🔧</div>

      <h1 class="text-2xl sm:text-3xl font-bold text-slate-800 dark:text-slate-100 mb-4">
        メンテナンス中です。
      </h1>

      <p class="text-slate-600 dark:text-slate-400 leading-relaxed">
        ご不便をおかけしますが、しばらくお待ちください。
      </p>
      <p class="mt-2 text-sm text-slate-500 dark:text-slate-500">
        beat-seeker is currently under maintenance. Please try again later.
      </p>

      <!-- 復旧確認用。ページを再取得するだけのシンプルなリロードボタン -->
      <button
        type="button"
        class="mt-8 px-5 py-2.5 rounded-lg bg-blue-600 hover:bg-blue-700 text-white text-sm font-semibold transition-colors"
        @click="reload"
      >
        再読み込み
      </button>

      <p class="mt-10 text-xs text-slate-400 dark:text-slate-600">beat-seeker</p>
    </div>
  </div>
</template>
