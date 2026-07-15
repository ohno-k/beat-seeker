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

const formatTime = (iso: string): string => {
  try {
    return new Date(iso).toLocaleTimeString('ja-JP', { hour: '2-digit', minute: '2-digit' });
  } catch {
    return '';
  }
};

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
    <button
      type="button"
      @click="toggleOpen"
      class="fixed bottom-4 right-4 z-40 w-14 h-14 rounded-full flex items-center justify-center shadow-lg transition-colors bg-blue-600 hover:bg-blue-700 dark:bg-blue-500 dark:hover:bg-blue-400 text-white"
      :aria-label="isOpen ? 'お問い合わせを閉じる' : '運営へお問い合わせ'"
    >
      <svg v-if="!isOpen" xmlns="http://www.w3.org/2000/svg" class="h-6 w-6" fill="none" viewBox="0 0 24 24" stroke="currentColor" stroke-width="2">
        <path stroke-linecap="round" stroke-linejoin="round" d="M8 10h.01M12 10h.01M16 10h.01M9 16H5a2 2 0 01-2-2V6a2 2 0 012-2h14a2 2 0 012 2v8a2 2 0 01-2 2h-5l-5 3v-3z" />
      </svg>
      <span v-else class="text-xl leading-none">✕</span>
      <!-- 未読バッジ -->
      <span
        v-if="unreadCount > 0 && !isOpen"
        class="absolute -top-1 -right-1 min-w-[20px] h-5 px-1 rounded-full bg-rose-500 border-2 border-white dark:border-slate-900 text-white text-[10px] font-bold flex items-center justify-center"
      >{{ unreadCount > 99 ? '99+' : unreadCount }}</span>
    </button>

    <!-- チャットパネル -->
    <transition name="chat-pop">
      <div
        v-if="isOpen"
        class="fixed bottom-20 right-4 z-40 w-[92vw] max-w-[360px] h-[70vh] max-h-[520px] flex flex-col rounded-2xl shadow-2xl overflow-hidden border border-slate-200 dark:border-slate-700 bg-white dark:bg-slate-800"
      >
        <!-- ヘッダ -->
        <div class="px-4 py-3 bg-blue-600 dark:bg-blue-500 text-white flex items-center justify-between">
          <div class="flex items-center gap-2">
            <svg xmlns="http://www.w3.org/2000/svg" class="h-5 w-5" fill="none" viewBox="0 0 24 24" stroke="currentColor" stroke-width="2">
              <path stroke-linecap="round" stroke-linejoin="round" d="M8 10h.01M12 10h.01M16 10h.01M9 16H5a2 2 0 01-2-2V6a2 2 0 012-2h14a2 2 0 012 2v8a2 2 0 01-2 2h-5l-5 3v-3z" />
            </svg>
            <p class="font-bold text-sm">運営へお問い合わせ</p>
          </div>
          <button type="button" @click="toggleOpen" class="text-white/80 hover:text-white text-lg leading-none" aria-label="閉じる">✕</button>
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
            <textarea
              v-model="draft"
              @keydown="onKeydown"
              rows="1"
              placeholder="メッセージを入力 (Enterで送信)"
              class="flex-1 resize-none max-h-24 px-3 py-2 text-[13px] rounded-xl bg-slate-50 dark:bg-slate-900 border border-slate-300 dark:border-slate-600 outline-none focus:border-blue-400"
            ></textarea>
            <button
              type="button"
              @click="handleSend"
              :disabled="isSending || !draft.trim()"
              class="shrink-0 px-4 py-2 rounded-xl text-xs font-bold bg-blue-600 text-white hover:bg-blue-700 disabled:bg-slate-300 dark:disabled:bg-slate-600 disabled:cursor-not-allowed"
            >送信</button>
          </div>
        </div>
      </div>
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
