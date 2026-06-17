<script setup lang="ts">
/**
 * 【コンポーネントの役割】 アップデート内容をお知らせする「What's New」モーダル。
 *
 * 挙動:
 *  - ログイン済みユーザーが、未読のアップデート告知があるときに 1 回だけ表示する
 *  - 閉じると localStorage `whatsnew:seen` に告知 ID を保存し、以降は表示しない
 *  - 新しい告知を出すときは {@link ANNOUNCEMENT_ID} と本文・画像を差し替える
 *    （ID が変わると、過去に閉じた人にも再度表示される）
 *
 * 今回の告知内容: リザルト画像のアップロード機能。
 */
import { ref, watch } from 'vue';
import { useAuth } from '../composables/useAuth';
import { useI18n } from '../composables/useI18n';

/** この告知の識別子。新しいアップデート告知を出すときはこの値を変える。 */
const ANNOUNCEMENT_ID = 'result-images';
/** 最後に閲覧した告知 ID を保存する localStorage キー。 */
const STORAGE_KEY = 'whatsnew:seen';
/** 告知に使う画像（public/ 配下。差し替え時はこのファイルを置き換える）。 */
const IMAGE_SRC = '/whatsnew/result-image-feature.jpg';

const { isLoggedIn } = useAuth();
const { t } = useI18n();

const visible = ref(false);

/** ログイン済み かつ 未読の告知があれば表示する。 */
function maybeShow() {
  if (!isLoggedIn.value) return;
  try {
    if (localStorage.getItem(STORAGE_KEY) !== ANNOUNCEMENT_ID) {
      visible.value = true;
    }
  } catch {
    /* localStorage 不可環境では何もしない */
  }
}

// 初回ロードは /api/auth/me 解決待ちのため、ログイン状態が確定したタイミングで判定する。
watch(isLoggedIn, () => maybeShow(), { immediate: true });

function close() {
  visible.value = false;
  try {
    localStorage.setItem(STORAGE_KEY, ANNOUNCEMENT_ID);
  } catch {
    /* ignore */
  }
}
</script>

<template>
  <Teleport to="body">
    <div
      v-if="visible"
      class="fixed inset-0 z-[130] flex items-center justify-center bg-slate-900/70 backdrop-blur-sm p-4 animate-fade-in"
      @click.self="close"
    >
      <div class="bg-white dark:bg-slate-800 rounded-2xl shadow-2xl w-full max-w-lg overflow-hidden flex flex-col max-h-[92vh]">
        <!-- ヘッダ: アップデートバッジ + 閉じる -->
        <div class="flex items-center justify-between px-5 py-3 border-b border-slate-200 dark:border-slate-700">
          <span class="px-3 py-1 rounded-full text-[10px] font-black tracking-widest bg-blue-600 text-white">
            {{ t('whatsnew.badge') }}
          </span>
          <button
            type="button"
            class="w-8 h-8 rounded-full hover:bg-slate-100 dark:hover:bg-slate-700 text-slate-500 dark:text-slate-400 flex items-center justify-center text-xl leading-none transition-colors"
            aria-label="close"
            @click="close"
          >
            ×
          </button>
        </div>

        <!-- スクロール本体: 画像 + 説明 -->
        <div class="overflow-y-auto">
          <img :src="IMAGE_SRC" alt="" class="w-full h-auto block bg-slate-100 dark:bg-slate-900" />
          <div class="p-5 sm:p-6">
            <h2 class="text-lg sm:text-xl font-black text-slate-800 dark:text-slate-100 mb-2">
              {{ t('whatsnew.title') }}
            </h2>
            <p class="text-sm text-slate-600 dark:text-slate-300 leading-relaxed whitespace-pre-line">
              {{ t('whatsnew.body') }}
            </p>
          </div>
        </div>

        <!-- フッタ: 確認ボタン -->
        <div class="p-4 border-t border-slate-200 dark:border-slate-700">
          <button
            type="button"
            class="w-full rounded-xl px-4 py-2.5 text-sm font-bold bg-blue-600 hover:bg-blue-700 text-white transition-colors"
            @click="close"
          >
            {{ t('whatsnew.cta') }}
          </button>
        </div>
      </div>
    </div>
  </Teleport>
</template>
