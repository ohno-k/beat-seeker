<script setup lang="ts">
/**
 * 【コンポーネントの役割】 ログインユーザー用の「運営お問い合わせ」フローティングウィジェット。
 *
 * 大会機能の {@code CompetitionChatWidget.vue} と同じ発想で、アプリ右下に固定ボタンを表示し、
 * クリックでチャットパネル (LINE 風 UI) を開閉する。
 * - ユーザーが運営へメッセージ送信 (送信時にサーバ側で運営へメール通知)
 * - 運営からの返信はポーリングで受信して表示
 * - 自分の発言は右寄せ、運営の発言は左寄せのバブルで表示
 *
 * App.vue のメイン画面領域に 1 つだけ配置する想定 (ログイン済み・非管理者のみ表示)。
 * 管理者は返信する側なのでこのウィジェットは出さない。
 */
import { ref, onMounted, onBeforeUnmount, nextTick } from 'vue';
import { useSupportChat, type SupportMessageDto } from '../composables/useSupportChat';
import { useToast } from '../composables/useToast';
import { formatJstTime } from '../utils/jstTime';
import { mdiClose, mdiMessageProcessingOutline } from '@mdi/js';

const { fetchMyChat, sendMyChat, markMyChatRead } = useSupportChat();
const toast = useToast();

const isOpen = ref(false);
const messages = ref<SupportMessageDto[]>([]);
const draft = ref('');
const isSending = ref(false);
/** 運営からの未読返信件数 (閉じているときのバッジ用)。 */
const unreadCount = ref(0);

const listEl = ref<HTMLElement | null>(null);
let pollTimer: ReturnType<typeof setInterval> | null = null;

const scrollToBottom = async () => {
  await nextTick();
  if (listEl.value) listEl.value.scrollTop = listEl.value.scrollHeight;
};

/** チャット取得。ポーリングでも呼ぶためエラーはサイレント (送信時のみトースト)。 */
const loadChat = async () => {
  try {
    const data = await fetchMyChat();
    const grew = data.messages.length > messages.value.length;
    messages.value = data.messages;
    if (isOpen.value) {
      unreadCount.value = 0;
      if (grew) scrollToBottom();
    } else {
      unreadCount.value = data.unreadCount;
    }
  } catch {
    /* ポーリング失敗は無視 */
  }
};

const toggleOpen = async () => {
  isOpen.value = !isOpen.value;
  if (isOpen.value) {
    // 開いた時点で未読があったかを覚えておく (loadChat が unreadCount を 0 にするため先に退避)。
    const hadUnread = unreadCount.value > 0;
    await loadChat();
    // 開いたら運営返信を既読化してバッジをクリア (サーバの read_by_user も更新)。
    if (hadUnread) {
      try { await markMyChatRead(); } catch { /* noop */ }
    }
    unreadCount.value = 0;
    scrollToBottom();
  }
};

const handleSend = async () => {
  const body = draft.value.trim();
  if (!body || isSending.value) return;
  isSending.value = true;
  try {
    const msg = await sendMyChat(body);
    messages.value.push(msg);
    draft.value = '';
    scrollToBottom();
  } catch (e) {
    toast.error((e as Error).message);
  } finally {
    isSending.value = false;
  }
};

/** Enter で送信 / Shift+Enter で改行。 */
const onKeydown = (e: KeyboardEvent) => {
  if (e.key === 'Enter' && !e.shiftKey) {
    e.preventDefault();
    handleSend();
  }
};

/** 送信時刻を「15:30」形式（JST 固定）で表示する。 */
const formatTime = (iso: string): string => formatJstTime(iso);

onMounted(() => {
  loadChat();
  // 開閉に関わらず一定間隔で取得し、新着 (運営返信) を受け取る
  pollTimer = setInterval(loadChat, 20000);
});
onBeforeUnmount(() => {
  if (pollTimer) clearInterval(pollTimer);
});
</script>

<template>
  <div class="support-chat-widget">
    <!-- フローティングボタン -->
    <v-btn
      icon
      color="primary"
      @click="toggleOpen"
      class="fixed bottom-4 right-4 z-40 w-14 h-14 shadow-lg overflow-visible"
      :aria-label="isOpen ? 'お問い合わせを閉じる' : '運営へお問い合わせ'"
    >
      <v-icon v-if="!isOpen" :icon="mdiMessageProcessingOutline" />
      <v-icon v-else :icon="mdiClose" />
      <!-- 未読バッジ -->
      <span
        v-if="unreadCount > 0 && !isOpen"
        class="absolute -top-1 -right-1 min-w-[20px] h-5 px-1 rounded-full bg-rose-500 border-2 border-white dark:border-slate-900 text-white text-[10px] font-bold flex items-center justify-center"
      >{{ unreadCount > 99 ? '99+' : unreadCount }}</span>
    </v-btn>

    <!-- チャットパネル -->
    <transition name="chat-pop">
      <v-card
        v-if="isOpen"
        class="fixed bottom-20 right-4 z-40 w-[92vw] max-w-[360px] h-[70vh] max-h-[520px] flex flex-col rounded-2xl shadow-2xl overflow-hidden border border-slate-200 dark:border-slate-700 bg-white dark:bg-slate-800"
      >
        <!-- ヘッダ -->
        <div class="px-4 py-3 bg-blue-600 dark:bg-blue-500 text-white flex items-center justify-between">
          <div class="flex items-center gap-2">
            <v-icon :icon="mdiMessageProcessingOutline" size="20" />
            <p class="font-bold text-sm">運営へお問い合わせ</p>
          </div>
          <v-btn icon variant="text" size="small" @click="toggleOpen" class="text-white/80 hover:text-white -my-2 -mr-2" aria-label="閉じる">
            <v-icon :icon="mdiClose" />
          </v-btn>
        </div>

        <!-- 案内文 -->
        <div class="px-4 py-2 bg-blue-50 dark:bg-slate-900/60 border-b border-slate-100 dark:border-slate-700">
          <p class="text-[11px] text-slate-600 dark:text-slate-300 leading-relaxed">
            ご質問・不具合報告・ご要望など、運営へお気軽にお問い合わせください。返信はこのチャットに届きます。
          </p>
        </div>

        <!-- メッセージ一覧 -->
        <div ref="listEl" class="flex-1 overflow-y-auto px-3 py-3 space-y-2 bg-slate-50 dark:bg-slate-900/40">
          <p v-if="messages.length === 0" class="text-center text-[11px] text-slate-400 italic py-8">
            まだメッセージはありません。<br />お気軽にお問い合わせください。
          </p>
          <div
            v-for="m in messages"
            :key="m.id"
            class="flex flex-col"
            :class="m.sender === 'user' ? 'items-end' : 'items-start'"
          >
            <span v-if="m.sender === 'admin'" class="text-[9px] font-bold text-indigo-500 dark:text-indigo-300 mb-0.5 px-1">運営</span>
            <div
              class="max-w-[80%] px-3 py-2 rounded-2xl text-[13px] leading-relaxed whitespace-pre-wrap break-words"
              :class="m.sender === 'user'
                ? 'bg-blue-600 text-white rounded-br-sm'
                : 'bg-white dark:bg-slate-700 text-slate-800 dark:text-slate-100 border border-slate-200 dark:border-slate-600 rounded-bl-sm'"
            >{{ m.body }}</div>
            <span class="text-[9px] text-slate-400 mt-0.5 px-1">{{ formatTime(m.createdAt) }}</span>
          </div>
        </div>

        <!-- 入力 -->
        <div class="p-2 border-t border-slate-200 dark:border-slate-700 bg-white dark:bg-slate-800">
          <div class="flex items-end gap-2">
            <v-textarea
              v-model="draft"
              @keydown="onKeydown"
              rows="1"
              auto-grow
              max-rows="4"
              no-resize
              placeholder="メッセージを入力 (Enterで送信)"
              class="flex-1 text-[13px]"
            />
            <v-btn
              color="primary"
              @click="handleSend"
              :disabled="isSending || !draft.trim()"
              :loading="isSending"
              class="shrink-0 text-xs"
            >送信</v-btn>
          </div>
        </div>
      </v-card>
    </transition>
  </div>
</template>

<style scoped>
.chat-pop-enter-active,
.chat-pop-leave-active {
  transition: opacity 0.15s ease, transform 0.15s ease;
}
.chat-pop-enter-from,
.chat-pop-leave-to {
  opacity: 0;
  transform: translateY(8px) scale(0.98);
}
</style>
