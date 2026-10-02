<script setup lang="ts">
/**
 * 【コンポーネントの役割】 新規登録直後に表示する 3 ステップのオンボーディング。
 *
 * 機能:
 *  - Step 1: スコアを取り込む（最重要。アプリの価値体験はここから始まる）
 *  - Step 2: PWA インストール（`beforeinstallprompt` を受け取った場合のみボタン活性化）
 *  - Step 3: プッシュ通知の許可を要求し、結果でバッジ切替
 *  - iOS は beforeinstallprompt 非対応なので、ホーム画面追加の手順案内を表示
 *
 * props:
 *  - isOpen: 開閉フラグ
 *  - deferredPrompt: App 側で取り置いた `beforeinstallprompt` イベント（未対応なら null）
 * emits:
 *  - close: 「後で」「はじめる」どちらでも同じく閉じる
 *  - open-upload: Step 1 から UnifiedImport を開く要求。親がモーダルを閉じてアップロード UI を開く
 */
import { ref, computed } from 'vue';
import { useFriends } from '../composables/useFriends';
import { useModalEscape } from '../composables/useModalEscape';
import { mdiCheck } from '@mdi/js';

const props = defineProps<{
  isOpen: boolean;
  deferredPrompt: any;
}>();

const emit = defineEmits<{
  (e: 'close'): void;
  (e: 'open-upload'): void;
}>();

// Esc キーで閉じる（背景クリックと同等）。
useModalEscape(() => props.isOpen, () => emit('close'));

// プッシュ通知購読をサーバに登録するための関数を取得。
const { requestNotificationPermission } = useFriends();

/** 通知許可リクエストを処理中かどうか。 */
const isSubscribing = ref(false);
/** 現在の Notification 許可状態。'granted' になるとボタンが「設定済み」表示になる。 */
const notificationStatus = ref(typeof Notification !== 'undefined' ? (Notification.permission || 'default') : 'default');

/** 端末が iOS か判定。iOS 13 以降の iPad は Mac を名乗るため `ontouchend` 検出を併用。 */
const isIOS = computed(() => {
  return [
    'iPad Simulator',
    'iPhone Simulator',
    'iPod Simulator',
    'iPad',
    'iPhone',
    'iPod'
  ].includes(navigator.platform)
  // iOS 13 以降の iPad は navigator.platform が 'MacIntel' を返すため、タッチ判定で補完。
  || (navigator.userAgent.includes("Mac") && "ontouchend" in document);
});

/**
 * 【関数の役割】 PWA インストールプロンプトを表示し、ユーザーが承諾したらモーダルを閉じる。
 * deferredPrompt は親（App.vue 等）が `beforeinstallprompt` をキャッチして渡す。
 */
const handleInstall = async () => {
  if (!props.deferredPrompt) return;
  props.deferredPrompt.prompt();
  const { outcome } = await props.deferredPrompt.userChoice;
  if (outcome === 'accepted') {
    emit('close');
  }
};

/**
 * 【関数の役割】 Web プッシュ通知の許可リクエストを行い、成功時にバッジを granted に切替。
 */
const handleEnableNotifications = async () => {
  isSubscribing.value = true;
  try {
    const success = await requestNotificationPermission();
    if (success) {
      notificationStatus.value = 'granted';
    }
  } catch (e) {
    console.error(e);
  } finally {
    isSubscribing.value = false;
  }
};

</script>

<template>
  <v-dialog
    :model-value="isOpen"
    max-width="512"
    aria-labelledby="onboarding-title"
    @update:model-value="(v) => { if (!v) emit('close') }"
  >
    <v-card class="overflow-hidden flex flex-col transition-all duration-300 scale-in-center">

      <div class="p-8 text-center border-b border-slate-100 dark:border-slate-700">
        <div class="w-20 h-20 bg-blue-100 dark:bg-blue-900/50 rounded-md flex items-center justify-center mx-auto mb-6 text-blue-600 dark:text-blue-400">
          <svg xmlns="http://www.w3.org/2000/svg" aria-hidden="true" class="h-10 w-10" fill="none" viewBox="0 0 24 24" stroke="currentColor">
            <path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M5 3v4M3 5h4M6 17v4m-2-2h4m5-16l2.286 6.857L21 12l-7.714 2.143L11 21l-2.286-6.857L1 12l7.714-2.143L11 3z" />
          </svg>
        </div>
        <h2 id="onboarding-title" class="text-3xl font-bold text-slate-800 dark:text-white mb-2 tracking-tight">登録ありがとうございます！</h2>
        <p class="text-slate-500 dark:text-slate-400 font-medium">beat-seekerを最大限に活用するための設定です。</p>
      </div>

      <v-card-text class="p-8 space-y-6 max-h-[60vh] overflow-y-auto custom-scrollbar">
        <!-- ステップ 1: スコアを取り込む（最重要。アプリの価値体験の起点） -->
        <v-card class="bg-blue-50 dark:bg-blue-950/40 p-6 border-2 border-blue-200 dark:border-blue-800/60 transition-colors ring-1 ring-blue-100 dark:ring-blue-900/30">
          <div class="flex items-start gap-4">
            <div class="w-10 h-10 bg-blue-600 dark:bg-blue-500 rounded-md flex items-center justify-center shrink-0 text-white">
              <svg xmlns="http://www.w3.org/2000/svg" class="h-6 w-6" fill="none" viewBox="0 0 24 24" stroke="currentColor">
                <path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M7 16a4 4 0 01-.88-7.903A5 5 0 1115.9 6L16 6a5 5 0 011 9.9M15 13l-3-3m0 0l-3 3m3-3v12" />
              </svg>
            </div>
            <div class="flex-1">
              <div class="flex items-center gap-2 mb-1">
                <h3 class="font-bold text-slate-800 dark:text-slate-100 italic">1. スコアを取り込む</h3>
                <v-chip label variant="flat" color="primary" class="h-auto px-2 py-0.5 text-[10px] font-bold">まずはここから</v-chip>
              </div>
              <p class="text-xs text-slate-600 dark:text-slate-300 leading-relaxed mb-4">
                CSV / ブックマークレット / OCR のいずれかで過去のプレイ履歴を取り込みます。
                1 件でも取り込めば BEAT-TIER や成長記録など、すべての機能が動き始めます。
              </p>

              <v-btn color="primary" block class="text-sm active:scale-95" @click="emit('open-upload')">
                スコアを取り込む
              </v-btn>
            </div>
          </div>
        </v-card>

        <!-- ステップ 2: PWA としてホーム画面にインストール -->
        <v-card class="bg-slate-50 dark:bg-slate-900/50 p-6 transition-colors">
          <div class="flex items-start gap-4">
            <div class="w-10 h-10 bg-indigo-100 dark:bg-indigo-900/50 rounded-md flex items-center justify-center shrink-0 text-indigo-600 dark:text-indigo-400">
              <svg xmlns="http://www.w3.org/2000/svg" class="h-6 w-6" fill="none" viewBox="0 0 24 24" stroke="currentColor">
                <path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M12 18h.01M8 21h8a2 2 0 002-2V5a2 2 0 00-2-2H8a2 2 0 00-2 2v14a2 2 0 002 2z" />
              </svg>
            </div>
            <div class="flex-1">
              <h3 class="font-bold text-slate-800 dark:text-slate-100 mb-1 italic">2. アプリとしてインストール</h3>
              <p class="text-xs text-slate-500 dark:text-slate-400 leading-relaxed mb-4">
                ホーム画面に追加すると、Webブラウザの枠がなくなり、フルスクリーンで快適にスコア管理ができます。
              </p>

              <div v-if="deferredPrompt">
                <v-btn block class="text-sm bg-indigo-600 hover:bg-indigo-700 text-white active:scale-95" @click="handleInstall">
                  インストールする
                </v-btn>
              </div>
              <div v-else-if="isIOS" class="p-3 bg-white dark:bg-slate-800 rounded-md border border-indigo-100 dark:border-indigo-900/50 text-xs">
                <p class="text-indigo-600 dark:text-indigo-400 font-bold mb-1 flex items-center gap-1">
                  <svg xmlns="http://www.w3.org/2000/svg" class="h-3.5 w-3.5" viewBox="0 0 20 20" fill="currentColor">
                    <path d="M15 8a3 3 0 10-2.977-2.63l-4.94 2.47a3 3 0 100 4.319l4.94 2.47a3 3 0 10.895-1.789l-4.94-2.47a3.027 3.027 0 000-.74l4.94-2.47C13.456 7.68 14.19 8 15 8z" />
                  </svg>
                  iPhone/iPadの方:
                </p>
                ブラウザの「共有」ボタンから「ホーム画面に追加」をタップしてください。
              </div>
              <div v-else class="text-xs text-slate-400 dark:text-slate-500 italic">
                既にインストール済みか、このブラウザではサポートされていません。
              </div>
            </div>
          </div>
        </v-card>

        <!-- ステップ 3: プッシュ通知を有効化 -->
        <v-card class="bg-slate-50 dark:bg-slate-900/50 p-6 transition-colors">
          <div class="flex items-start gap-4">
            <div class="w-10 h-10 bg-emerald-100 dark:bg-emerald-900/50 rounded-md flex items-center justify-center shrink-0 text-emerald-600 dark:text-emerald-400">
              <svg xmlns="http://www.w3.org/2000/svg" class="h-6 w-6" fill="none" viewBox="0 0 24 24" stroke="currentColor">
                <path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M15 17h5l-1.405-1.405A2.032 2.032 0 0118 14.158V11a6.002 6.002 0 00-4-5.659V5a2 2 0 10-4 0v.341C7.67 6.165 6 8.388 6 11v3.159c0 .538-.214 1.055-.595 1.436L4 17h5m6 0v1a3 3 0 11-6 0v-1m6 0H9" />
              </svg>
            </div>
            <div class="flex-1">
              <h3 class="font-bold text-slate-800 dark:text-slate-100 mb-1 italic">3. プッシュ通知を有効化</h3>
              <p class="text-xs text-slate-500 dark:text-slate-400 leading-relaxed mb-4">
                ライバル申請が届いた際にリアルタイムで通知を受け取れます。※後からオフにすることも可能です。
              </p>

              <v-btn
                block
                class="text-sm active:scale-95"
                :class="notificationStatus === 'granted'
                  ? 'bg-emerald-100 dark:bg-emerald-500/20 text-emerald-600 dark:text-emerald-400 cursor-default opacity-100'
                  : 'bg-emerald-600 hover:bg-emerald-700 text-white disabled:opacity-50'"
                :disabled="isSubscribing || notificationStatus === 'granted'"
                @click="handleEnableNotifications"
              >
                <v-progress-circular v-if="isSubscribing" size="16" width="2" color="white" class="mr-2" />
                <v-icon v-else-if="notificationStatus === 'granted'" :icon="mdiCheck" size="16" class="mr-2" />
                {{ notificationStatus === 'granted' ? '設定済み' : '通知を有効にする' }}
              </v-btn>
            </div>
          </div>
        </v-card>
      </v-card-text>

      <v-card-actions class="p-8 bg-slate-50 dark:bg-slate-900 border-t border-slate-100 dark:border-slate-700 flex gap-4">
        <v-btn variant="text" size="large" class="flex-1 text-slate-500 hover:text-slate-800 dark:hover:text-slate-200" @click="emit('close')">
          後でする
        </v-btn>
        <v-btn color="primary" size="large" class="flex-1 active:scale-95" @click="emit('close')">
          はじめる！
        </v-btn>
      </v-card-actions>

    </v-card>
  </v-dialog>
</template>

<style scoped>
.scale-in-center {
	animation: scale-in-center 0.5s cubic-bezier(0.250, 0.460, 0.450, 0.940) both;
}

@keyframes scale-in-center {
  0% {
    transform: scale(0.9);
    opacity: 0;
  }
  100% {
    transform: scale(1);
    opacity: 1;
  }
}

/* Custom Scrollbar */
.custom-scrollbar::-webkit-scrollbar {
  width: 6px;
}
.custom-scrollbar::-webkit-scrollbar-track {
  background: transparent;
}
.custom-scrollbar::-webkit-scrollbar-thumb {
  background: #cbd5e1;
  border-radius: 10px;
}
.dark .custom-scrollbar::-webkit-scrollbar-thumb {
  background: #334155;
}
</style>
