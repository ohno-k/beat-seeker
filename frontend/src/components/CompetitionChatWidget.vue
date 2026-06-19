<script setup lang="ts">
/**
 * 【コンポーネントの役割】 TL 専用ページ用の「運営チャット」フローティングウィジェット (チャットボット風 UI)。
 *
 * 右下に固定ボタンを表示し、クリックでチャットパネルを開閉する。
 * - TL が運営へメッセージを送信 (送信時にサーバ側で運営へメール通知が飛ぶ)
 * - 運営からの返信はポーリングで受信して表示
 * - 自分(TL)の発言は右寄せ、運営の発言は左寄せのバブルで表示
 *
 * 認証は TL トークン (props.token) のみ。ログイン不要。
 */
import { ref, onMounted, onBeforeUnmount, nextTick, watch } from 'vue';
import { useCompetitionTl, type ChatMessageDto } from '../composables/useCompetitionTl';
import { useToast } from '../composables/useToast';

const props = defineProps<{ token: string }>();

const { fetchChat, sendChat } = useCompetitionTl();
const toast = useToast();

const isOpen = ref(false);
const messages = ref<ChatMessageDto[]>([]);
const draft = ref('');
const isSending = ref(false);
/** 既読として確認済みの件数。これを超える新着があれば未読バッジを出す。 */
const seenCount = ref(0);
const hasUnread = ref(false);

const listEl = ref<HTMLElement | null>(null);
let pollTimer: ReturnType<typeof setInterval> | null = null;

const scrollToBottom = async () => {
  await nextTick();
  if (listEl.value) listEl.value.scrollTop = listEl.value.scrollHeight;
};

/** チャット取得。ポーリングでも呼ぶためエラーはサイレント (送信時のみトースト)。 */
const loadChat = async () => {
  try {
    const next = await fetchChat(props.token);
    const grew = next.length > messages.value.length;
    messages.value = next;
    if (isOpen.value) {
      seenCount.value = next.length;
      hasUnread.value = false;
      if (grew) scrollToBottom();
    } else if (next.length > seenCount.value) {
      // 閉じている間に届いた新着 (主に運営返信) は未読扱い
      hasUnread.value = true;
    }
  } catch {
    /* ポーリング失敗は無視 */
  }
};

const toggleOpen = async () => {
  isOpen.value = !isOpen.value;
  if (isOpen.value) {
    await loadChat();
    seenCount.value = messages.value.length;
    hasUnread.value = false;
    scrollToBottom();
  }
};

const handleSend = async () => {
  const body = draft.value.trim();
  if (!body || isSending.value) return;
  isSending.value = true;
  try {
    const msg = await sendChat(props.token, body);
    messages.value.push(msg);
    seenCount.value = messages.value.length;
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
  pollTimer = setInterval(loadChat, 15000);
});
onBeforeUnmount(() => {
  if (pollTimer) clearInterval(pollTimer);
});

watch(() => props.token, () => {
  messages.value = [];
  seenCount.value = 0;
  hasUnread.value = false;
  loadChat();
});
</script>

<template>
  <div class="competition-chat-widget">
    <!-- フローティングボタン -->
    <button
      type="button"
      @click="toggleOpen"
      class="fixed bottom-4 right-4 z-40 w-14 h-14 rounded-full shadow-lg flex items-center justify-center text-2xl transition-transform hover:scale-105 bg-gradient-to-br from-blue-500 to-indigo-600 text-white"
      :aria-label="isOpen ? 'チャットを閉じる' : '運営チャットを開く'"
    >
      <span v-if="!isOpen">💬</span>
      <span v-else>✕</span>
      <!-- 未読バッジ -->
      <span
        v-if="hasUnread && !isOpen"
        class="absolute -top-0.5 -right-0.5 w-3.5 h-3.5 rounded-full bg-rose-500 border-2 border-white dark:border-slate-900"
      ></span>
    </button>

    <!-- チャットパネル -->
    <transition name="chat-pop">
      <div
        v-if="isOpen"
        class="fixed bottom-20 right-4 z-40 w-[92vw] max-w-[360px] h-[70vh] max-h-[520px] flex flex-col rounded-2xl shadow-2xl overflow-hidden border border-slate-200 dark:border-slate-700 bg-white dark:bg-slate-800"
      >
        <!-- ヘッダ -->
        <div class="px-4 py-3 bg-gradient-to-r from-blue-500 to-indigo-600 text-white flex items-center justify-between">
          <div class="flex items-center gap-2">
            <span class="text-lg">💬</span>
            <p class="font-black text-sm tracking-wide">運営チャット</p>
          </div>
          <button type="button" @click="toggleOpen" class="text-white/80 hover:text-white text-lg leading-none" aria-label="閉じる">✕</button>
        </div>

        <!-- 案内文 -->
        <div class="px-4 py-2 bg-blue-50 dark:bg-slate-900/60 border-b border-slate-100 dark:border-slate-700">
          <p class="text-[11px] text-slate-600 dark:text-slate-300 leading-relaxed">
            システム運営者に質問や連絡がある場合はこちらのチャットをご利用ください。
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
            :class="m.sender === 'tl' ? 'items-end' : 'items-start'"
          >
            <span v-if="m.sender === 'admin'" class="text-[9px] font-black tracking-wider text-indigo-500 dark:text-indigo-300 mb-0.5 px-1">運営</span>
            <div
              class="max-w-[80%] px-3 py-2 rounded-2xl text-[13px] leading-relaxed whitespace-pre-wrap break-words"
              :class="m.sender === 'tl'
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
              class="shrink-0 px-3 py-2 rounded-xl text-xs font-bold bg-blue-600 text-white hover:bg-blue-700 disabled:bg-slate-300 dark:disabled:bg-slate-600 disabled:cursor-not-allowed"
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
