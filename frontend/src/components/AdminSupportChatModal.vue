<template>
  <Teleport to="body">
    <div
      v-if="isOpen"
      class="fixed inset-0 z-[110] bg-slate-900/60 dark:bg-slate-950/80 flex items-center justify-center p-4 backdrop-blur-sm"
      @click.self="$emit('close')"
    >
      <div class="bg-white dark:bg-slate-900 w-full max-w-3xl rounded-2xl shadow-xl flex flex-col overflow-hidden h-[85vh] animate-fade-in border border-slate-200 dark:border-slate-800">

        <!-- ヘッダ -->
        <div class="px-5 py-4 border-b border-slate-200 dark:border-slate-800 flex items-center justify-between shrink-0">
          <h2 class="text-lg font-bold text-slate-800 dark:text-white flex items-center gap-2">
            <svg xmlns="http://www.w3.org/2000/svg" class="h-6 w-6 text-blue-600 dark:text-blue-400" fill="none" viewBox="0 0 24 24" stroke="currentColor" stroke-width="2">
              <path stroke-linecap="round" stroke-linejoin="round" d="M8 10h.01M12 10h.01M16 10h.01M9 16H5a2 2 0 01-2-2V6a2 2 0 012-2h14a2 2 0 012 2v8a2 2 0 01-2 2h-5l-5 3v-3z" />
            </svg>
            お問い合わせ
            <span
              v-if="totalUnread > 0"
              class="text-[11px] font-bold px-2 py-0.5 rounded-full bg-rose-500 text-white"
            >未読 {{ totalUnread }}</span>
          </h2>
          <div class="flex items-center gap-2">
            <button
              type="button"
              @click="loadThreads"
              class="px-3 py-1.5 text-xs font-bold rounded-lg bg-slate-100 hover:bg-slate-200 dark:bg-slate-800 dark:hover:bg-slate-700 text-slate-600 dark:text-slate-300"
            >再読込</button>
            <button
              @click="$emit('close')"
              class="text-slate-400 hover:text-slate-600 dark:hover:text-slate-200 transition-colors p-2 -mr-2 rounded-lg hover:bg-slate-100 dark:hover:bg-slate-800"
            >
              <svg xmlns="http://www.w3.org/2000/svg" class="h-5 w-5" viewBox="0 0 20 20" fill="currentColor">
                <path fill-rule="evenodd" d="M4.293 4.293a1 1 0 011.414 0L10 8.586l4.293-4.293a1 1 0 111.414 1.414L11.414 10l4.293 4.293a1 1 0 01-1.414 1.414L10 11.414l-4.293 4.293a1 1 0 01-1.414-1.414L8.586 10 4.293 5.707a1 1 0 010-1.414z" clip-rule="evenodd" />
              </svg>
            </button>
          </div>
        </div>

        <!-- 本体: 左スレッド一覧 / 右会話 (LINE 風) -->
        <div class="flex-1 grid grid-cols-1 sm:grid-cols-[240px_1fr] overflow-hidden">

          <!-- 左: スレッド一覧 -->
          <div class="border-b sm:border-b-0 sm:border-r border-slate-200 dark:border-slate-800 overflow-y-auto bg-slate-50 dark:bg-slate-900/50 max-h-[30vh] sm:max-h-none">
            <div v-if="loadingThreads" class="p-6 text-center text-xs text-slate-400">読み込み中...</div>
            <p v-else-if="threads.length === 0" class="p-6 text-center text-xs text-slate-400 italic">
              まだお問い合わせはありません。
            </p>
            <button
              v-for="th in threads"
              :key="th.userId"
              type="button"
              @click="selectThread(th.userId)"
              class="w-full text-left px-3 py-2.5 border-b border-slate-100 dark:border-slate-800 transition-colors"
              :class="selectedUserId === th.userId
                ? 'bg-blue-50 dark:bg-blue-900/30'
                : 'hover:bg-slate-100 dark:hover:bg-slate-800/60'"
            >
              <div class="flex items-center gap-2">
                <div class="w-8 h-8 rounded-full bg-indigo-500 text-white text-xs font-bold flex items-center justify-center shrink-0">
                  {{ (th.displayName || 'U').charAt(0).toUpperCase() }}
                </div>
                <div class="flex-1 min-w-0">
                  <div class="flex items-center justify-between gap-1">
                    <span class="font-bold text-sm text-slate-800 dark:text-white truncate">{{ th.displayName || '名無し' }}</span>
                    <span
                      v-if="th.unreadCount > 0"
                      class="shrink-0 text-[10px] font-bold px-1.5 py-0.5 rounded-full bg-rose-500 text-white"
                    >{{ th.unreadCount }}</span>
                  </div>
                  <p class="text-[11px] text-slate-500 dark:text-slate-400 truncate">
                    <span v-if="th.lastSender === 'admin'" class="text-indigo-400">返信済: </span>{{ th.lastMessageBody }}
                  </p>
                </div>
              </div>
            </button>
          </div>

          <!-- 右: 選択スレッドの会話 -->
          <div class="flex flex-col overflow-hidden bg-slate-50 dark:bg-slate-900/40">
            <template v-if="selectedUserId !== null">
              <!-- 相手情報バー -->
              <div class="px-4 py-2.5 border-b border-slate-200 dark:border-slate-800 bg-white dark:bg-slate-900 shrink-0 flex items-center gap-2">
                <span class="font-bold text-sm text-slate-800 dark:text-white">{{ selectedThread?.displayName || '名無し' }}</span>
                <span class="text-[11px] text-slate-400 font-mono">{{ selectedThread?.iidxId }}</span>
                <span v-if="selectedThread?.danRank" class="px-1.5 py-0.5 bg-amber-100 text-amber-800 dark:bg-amber-900/30 dark:text-amber-400 text-[10px] font-bold rounded">{{ selectedThread?.danRank }}</span>
                <span v-if="selectedThread?.arenaRank" class="px-1.5 py-0.5 bg-blue-100 text-blue-800 dark:bg-blue-900/30 dark:text-blue-400 text-[10px] font-bold rounded">{{ selectedThread?.arenaRank }}</span>
              </div>

              <!-- メッセージ一覧 -->
              <div ref="listEl" class="flex-1 overflow-y-auto px-4 py-4 space-y-2">
                <p v-if="loadingMessages" class="text-center text-[11px] text-slate-400 py-8">読み込み中...</p>
                <p v-else-if="messages.length === 0" class="text-center text-[11px] text-slate-400 italic py-8">
                  まだメッセージはありません。
                </p>
                <div
                  v-for="m in messages"
                  :key="m.id"
                  class="flex flex-col"
                  :class="m.sender === 'admin' ? 'items-end' : 'items-start'"
                >
                  <span v-if="m.sender === 'user'" class="text-[9px] font-bold text-blue-500 dark:text-blue-300 mb-0.5 px-1">ユーザー</span>
                  <div
                    class="max-w-[80%] px-3 py-2 rounded-2xl text-[13px] leading-relaxed whitespace-pre-wrap break-words"
                    :class="m.sender === 'admin'
                      ? 'bg-indigo-600 text-white rounded-br-sm'
                      : 'bg-white dark:bg-slate-700 text-slate-800 dark:text-slate-100 border border-slate-200 dark:border-slate-600 rounded-bl-sm'"
                  >{{ m.body }}</div>
                  <span class="text-[9px] text-slate-400 mt-0.5 px-1">{{ formatTime(m.createdAt) }}</span>
                </div>
              </div>

              <!-- 返信入力 -->
              <div class="p-2 border-t border-slate-200 dark:border-slate-800 bg-white dark:bg-slate-900 shrink-0">
                <div class="flex items-end gap-2">
                  <textarea
                    v-model="replyDraft"
                    @keydown="onReplyKeydown"
                    rows="1"
                    placeholder="返信を入力 (Enterで送信 / Shift+Enterで改行)"
                    class="flex-1 resize-none max-h-28 px-3 py-2 text-[13px] rounded-xl bg-slate-50 dark:bg-slate-800 border border-slate-300 dark:border-slate-600 outline-none focus:border-indigo-400"
                  ></textarea>
                  <button
                    type="button"
                    @click="handleSendReply"
                    :disabled="isSending || !replyDraft.trim()"
                    class="shrink-0 px-4 py-2 rounded-xl text-xs font-bold bg-indigo-600 text-white hover:bg-indigo-700 disabled:bg-slate-300 dark:disabled:bg-slate-600 disabled:cursor-not-allowed"
                  >送信</button>
                </div>
              </div>
            </template>

            <p v-else class="m-auto text-xs text-slate-400 italic px-4 py-8 text-center">
              左のお問い合わせを選ぶと会話が表示されます。
            </p>
          </div>
        </div>
      </div>
    </div>
  </Teleport>
</template>

<script setup lang="ts">
/**
 * 【コンポーネントの役割】 管理者専用の「お問い合わせ」返信モーダル (LINE 風の 2 ペイン)。
 *
 * 左: ユーザー単位のスレッド一覧 (未読バッジ / 最終メッセージのプレビュー)。
 * 右: 選択スレッドの会話 + 返信入力。
 *
 * 開いている間はポーリングでスレッド一覧を更新し、新着お問い合わせを拾う。
 * スレッドを開くとそのユーザーのメッセージを既読化し、未読バッジをクリアする。
 *
 * props:
 *  - isOpen: モーダル開閉
 * emits:
 *  - close: 閉じる
 *  - unread-change: 全スレッド合計の未読数が変わったとき (親のバッジ更新用)
 */
import { ref, computed, watch, nextTick, onBeforeUnmount } from 'vue';
import { useSupportChat, type SupportThreadDto, type SupportMessageDto } from '../composables/useSupportChat';
import { useToast } from '../composables/useToast';

const props = defineProps<{ isOpen: boolean }>();
const emit = defineEmits<{
  (e: 'close'): void;
  (e: 'unread-change', total: number): void;
}>();

const { fetchThreads, fetchThread, sendReply, markThreadRead } = useSupportChat();
const toast = useToast();

const threads = ref<SupportThreadDto[]>([]);
const loadingThreads = ref(false);
const selectedUserId = ref<number | null>(null);
const messages = ref<SupportMessageDto[]>([]);
const loadingMessages = ref(false);
const replyDraft = ref('');
const isSending = ref(false);
const listEl = ref<HTMLElement | null>(null);
let pollTimer: ReturnType<typeof setInterval> | null = null;

const selectedThread = computed<SupportThreadDto | null>(() =>
  threads.value.find(t => t.userId === selectedUserId.value) ?? null);

const totalUnread = computed<number>(() =>
  threads.value.reduce((sum, t) => sum + t.unreadCount, 0));

// 未読合計が変わったら親に通知 (AdminUserListModal のバッジ更新用)。
watch(totalUnread, (v) => emit('unread-change', v));

const scrollToBottom = async () => {
  await nextTick();
  if (listEl.value) listEl.value.scrollTop = listEl.value.scrollHeight;
};

/** スレッド一覧を取得。ポーリングでも呼ぶためエラーはサイレント。 */
const loadThreads = async () => {
  loadingThreads.value = threads.value.length === 0;
  try {
    threads.value = await fetchThreads();
  } catch (e) {
    if (threads.value.length === 0) toast.error((e as Error).message);
  } finally {
    loadingThreads.value = false;
  }
};

/** スレッドを開く (会話取得 + 既読化 + 末尾へスクロール)。 */
const selectThread = async (userId: number) => {
  selectedUserId.value = userId;
  loadingMessages.value = true;
  messages.value = [];
  try {
    messages.value = await fetchThread(userId);
    await scrollToBottom();
    // 未読があれば既読化してローカルのバッジも 0 に
    const th = threads.value.find(t => t.userId === userId);
    if (th && th.unreadCount > 0) {
      await markThreadRead(userId);
      th.unreadCount = 0;
    }
  } catch (e) {
    toast.error((e as Error).message);
  } finally {
    loadingMessages.value = false;
  }
};

/** 返信を送信。 */
const handleSendReply = async () => {
  const body = replyDraft.value.trim();
  if (!body || isSending.value || selectedUserId.value === null) return;
  isSending.value = true;
  try {
    const msg = await sendReply(selectedUserId.value, body);
    messages.value.push(msg);
    replyDraft.value = '';
    // スレッド一覧のプレビューも更新
    const th = threads.value.find(t => t.userId === selectedUserId.value);
    if (th) {
      th.lastMessageBody = msg.body;
      th.lastMessageAt = msg.createdAt;
      th.lastSender = 'admin';
      th.messageCount += 1;
    }
    scrollToBottom();
  } catch (e) {
    toast.error((e as Error).message);
  } finally {
    isSending.value = false;
  }
};

const onReplyKeydown = (e: KeyboardEvent) => {
  if (e.key === 'Enter' && !e.shiftKey) {
    e.preventDefault();
    handleSendReply();
  }
};

const formatTime = (iso: string): string => {
  try {
    return new Date(iso).toLocaleString('ja-JP', { month: 'numeric', day: 'numeric', hour: '2-digit', minute: '2-digit' });
  } catch { return ''; }
};

// モーダル開閉に応じて初回ロード + ポーリング開始/停止。
watch(() => props.isOpen, (open) => {
  if (open) {
    loadThreads();
    pollTimer = setInterval(loadThreads, 20000);
  } else {
    if (pollTimer) { clearInterval(pollTimer); pollTimer = null; }
    selectedUserId.value = null;
    messages.value = [];
    replyDraft.value = '';
  }
}, { immediate: true });

onBeforeUnmount(() => {
  if (pollTimer) clearInterval(pollTimer);
});
</script>

<style scoped>
.animate-fade-in {
  animation: fadeIn 0.2s ease-out forwards;
}
@keyframes fadeIn {
  from { opacity: 0; transform: scale(0.98); }
  to { opacity: 1; transform: scale(1); }
}
</style>
