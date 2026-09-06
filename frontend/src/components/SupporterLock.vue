<script setup lang="ts">
/**
 * 【コンポーネントの役割】 サポーター限定機能の代わりに表示する「ロックカード」。
 *
 * 非サポーターが supporterOnly なタブを開いたときに本体ビューの代わりに描画され、
 * 機能がサポーター限定である旨と Ko-fi への導線を提示する。
 *
 * Ko-fi ボタンの挙動は Sidebar.vue のサポーター導線と同一に揃えている:
 *  - supporterToken を既に持つユーザー → 確認モーダルでトークンを提示してからコピー + 外部遷移
 *  - トークン未発行（未ログイン等） → そのまま ko-fi.com を新規タブで開く
 *
 * トークンは Ko-fi の支援メッセージに貼り付けてもらう識別子で、
 * KofiWebhookController がこれを照合して is_supporter を立てる（backend 側）。
 */
import { ref } from 'vue';
import { useI18n } from '../composables/useI18n';
import { useModalEscape } from '../composables/useModalEscape';

const props = defineProps<{
  /** ログイン中ユーザー（supporterToken の有無で導線を分岐する）。未ログインなら null。 */
  user?: { supporterToken?: string | null } | null;
  /** ロックした機能名。指定するとカード見出しの下に「◯◯ はサポーター限定です」と補足表示する。 */
  featureName?: string;
}>();

const { t } = useI18n();

/** Ko-fi 支援トークンをコピー済みの一時フラグ（5 秒で戻る）。 */
const kofiCopied = ref(false);
/** Ko-fi 確認モーダルの表示フラグ。 */
const showKofiModal = ref(false);

// Ko-fi 確認モーダル: Esc キーで閉じる。
useModalEscape(() => showKofiModal.value, () => { showKofiModal.value = false; });

/**
 * 【関数の役割】 Ko-fi ボタン押下時のハンドラ。
 * supporterToken を持つユーザーには確認モーダルを先に見せ、
 * 持たないユーザー（未ログイン等）は直接 ko-fi.com を開く。
 */
const handleKofiClick = () => {
  if (props.user?.supporterToken) {
    showKofiModal.value = true;
  } else {
    window.open('https://ko-fi.com/beat_seeker', '_blank');
  }
};

/**
 * 【関数の役割】 確認モーダルの「開く」を押したときの処理。
 * トークンをクリップボードへコピーしつつ新規タブで ko-fi.com を開く。
 * コピー失敗はサイレントに無視する（外部遷移自体は継続させる）。
 */
const confirmKofiOpen = () => {
  const token = props.user?.supporterToken;
  if (token) {
    navigator.clipboard.writeText(token).then(() => {
      kofiCopied.value = true;
      setTimeout(() => { kofiCopied.value = false; }, 5000);
    }).catch(() => {});
  }
  showKofiModal.value = false;
  window.open('https://ko-fi.com/beat_seeker', '_blank');
};
</script>

<template>
  <div class="w-full max-w-2xl mx-auto animate-fade-in">
    <div class="bg-white dark:bg-slate-800 rounded-md border border-slate-200 dark:border-slate-700 p-8 sm:p-12 text-center">
      <!-- 鍵アイコン -->
      <div class="w-20 h-20 mx-auto mb-6 bg-amber-50 dark:bg-amber-900/30 rounded-md flex items-center justify-center">
        <svg xmlns="http://www.w3.org/2000/svg" aria-hidden="true" class="h-10 w-10 text-amber-500" fill="none" viewBox="0 0 24 24" stroke="currentColor">
          <path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M12 15v2m-6 4h12a2 2 0 002-2v-6a2 2 0 00-2-2H6a2 2 0 00-2 2v6a2 2 0 002 2zm10-10V7a4 4 0 00-8 0v4h8z" />
        </svg>
      </div>

      <h2 class="text-2xl font-bold text-slate-900 dark:text-white mb-2">{{ t('supporter.lockedTitle') }}</h2>
      <p v-if="featureName" class="text-sm font-semibold text-amber-600 dark:text-amber-400 mb-3">{{ featureName }}</p>
      <p class="text-slate-500 dark:text-slate-400 font-medium mb-6 leading-relaxed">{{ t('supporter.lockedDesc') }}</p>

      <button
        type="button"
        @click="handleKofiClick"
        class="inline-flex items-center gap-2 px-6 py-3 bg-amber-500 hover:bg-amber-600 text-white font-semibold rounded-md transition-colors"
      >
        <svg xmlns="http://www.w3.org/2000/svg" aria-hidden="true" class="h-5 w-5" fill="none" viewBox="0 0 24 24" stroke="currentColor">
          <path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M4.318 6.318a4.5 4.5 0 000 6.364L12 20.364l7.682-7.682a4.5 4.5 0 00-6.364-6.364L12 7.636l-1.318-1.318a4.5 4.5 0 00-6.364 0z" />
        </svg>
        {{ t('supporter.kofiButton') }}
      </button>

      <!-- サポーター認証トークンの案内（発行済みユーザーのみ）。
           tokenHint は末尾がコロンなので、続けてトークン本体を等幅で並べる。 -->
      <p v-if="user?.supporterToken" class="mt-4 text-[11px] text-slate-400 dark:text-slate-500 font-medium">
        {{ t('supporter.tokenHint') }}<span class="font-mono font-bold text-amber-600 dark:text-amber-400 select-all">{{ user.supporterToken }}</span>
      </p>
      <p v-if="kofiCopied" class="mt-2 text-[11px] font-bold text-emerald-600 dark:text-emerald-400">
        {{ t('supporter.tokenCopied') }}
      </p>
    </div>

    <!-- Ko-fi 確認モーダル: トークンを見せてからコピー + 外部遷移する -->
    <Teleport to="body">
      <Transition
        enter-active-class="transition-opacity duration-200"
        enter-from-class="opacity-0"
        enter-to-class="opacity-100"
        leave-active-class="transition-opacity duration-150"
        leave-from-class="opacity-100"
        leave-to-class="opacity-0"
      >
        <div
          v-if="showKofiModal"
          role="dialog"
          aria-modal="true"
          aria-labelledby="supporter-lock-kofi-title"
          class="fixed inset-0 z-[100] flex items-center justify-center p-4"
        >
          <div class="absolute inset-0 bg-slate-900/50 backdrop-blur-sm" @click="showKofiModal = false"></div>
          <div class="relative bg-white dark:bg-slate-800 rounded-md border border-slate-200 dark:border-slate-700 shadow-xl max-w-sm w-full p-6 space-y-4">
            <div class="flex items-center gap-3">
              <div class="w-10 h-10 bg-amber-100 dark:bg-amber-900/30 rounded-md flex items-center justify-center">
                <svg xmlns="http://www.w3.org/2000/svg" aria-hidden="true" class="h-5 w-5 text-amber-500" fill="none" viewBox="0 0 24 24" stroke="currentColor">
                  <path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M13 16h-1v-4h-1m1-4h.01M21 12a9 9 0 11-18 0 9 9 0 0118 0z" />
                </svg>
              </div>
              <h3 id="supporter-lock-kofi-title" class="text-lg font-bold text-slate-900 dark:text-white">{{ t('supporter.modalTitle') }}</h3>
            </div>

            <p class="text-sm text-slate-600 dark:text-slate-300 leading-relaxed">{{ t('supporter.modalDesc') }}</p>

            <div class="bg-amber-50 dark:bg-amber-900/20 border border-amber-200 dark:border-amber-800/50 rounded-md p-3 text-center">
              <p class="text-[11px] font-semibold text-amber-700 dark:text-amber-400 mb-1">{{ t('supporter.modalTokenLabel') }}</p>
              <p class="text-lg font-mono font-bold text-amber-700 dark:text-amber-300 select-all tabular-nums">{{ user?.supporterToken }}</p>
            </div>

            <div class="flex gap-2">
              <button
                @click="showKofiModal = false"
                class="flex-1 px-4 py-2.5 text-sm font-semibold text-slate-600 dark:text-slate-400 bg-slate-100 dark:bg-slate-700 rounded-md hover:bg-slate-200 dark:hover:bg-slate-600 transition-colors"
              >
                {{ t('supporter.modalCancel') }}
              </button>
              <button
                @click="confirmKofiOpen"
                class="flex-1 px-4 py-2.5 text-sm font-semibold text-white bg-amber-500 rounded-md hover:bg-amber-600 transition-colors"
              >
                {{ t('supporter.modalConfirm') }}
              </button>
            </div>
          </div>
        </div>
      </Transition>
    </Teleport>
  </div>
</template>
