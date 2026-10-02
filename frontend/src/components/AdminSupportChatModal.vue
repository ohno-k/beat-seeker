<template>
  <v-dialog
    :model-value="isOpen"
    @update:model-value="(v: boolean) => { if (!v) $emit('close') }"
    max-width="768"
    :scrollable="false"
  >
      <v-card class="w-full flex flex-col overflow-hidden h-[85vh]">

        <!-- ヘッダ -->
        <v-card-title class="flex items-center gap-2 shrink-0">
          <v-icon :icon="mdiMessageProcessingOutline" color="primary" />
          お問い合わせ
          <v-chip
            v-if="totalUnread > 0"
            size="small"
            color="error"
            variant="flat"
          >未読 {{ totalUnread }}</v-chip>
          <div class="ml-auto flex items-center gap-2">
            <v-btn
              variant="tonal"
              size="small"
              @click="loadThreads"
            >再読込</v-btn>
            <v-btn
              icon
              variant="text"
              size="small"
              @click="$emit('close')"
              class="-mr-2"
              aria-label="閉じる"
            >
              <v-icon :icon="mdiClose" />
            </v-btn>
          </div>
        </v-card-title>
        <v-divider />

        <!-- 本体: 左スレッド一覧 / 右会話 (LINE 風) -->
        <div class="flex-1 grid grid-cols-1 sm:grid-cols-[240px_1fr] overflow-hidden">

          <!-- 左: スレッド一覧 -->
          <div class="border-b sm:border-b-0 sm:border-r border-slate-200 dark:border-slate-800 overflow-y-auto bg-slate-50 dark:bg-slate-900/50 max-h-[30vh] sm:max-h-none">
            <div v-if="loadingThreads" class="p-6 text-center text-xs text-slate-400">
              <v-progress-circular indeterminate size="20" width="2" class="mr-1" />読み込み中...
            </div>
            <p v-else-if="threads.length === 0" class="p-6 text-center text-xs text-slate-400 italic">
              まだお問い合わせはありません。
            </p>
            <v-list v-else density="compact" bg-color="transparent">
              <v-list-item
                v-for="th in threads"
                :key="th.userId"
                @click="selectThread(th.userId)"
                :active="selectedUserId === th.userId"
                color="primary"
              >
                <div class="flex items-center gap-2">
                  <div class="w-8 h-8 rounded-full bg-indigo-500 text-white text-xs font-bold flex items-center justify-center shrink-0">
                    {{ (th.displayName || 'U').charAt(0).toUpperCase() }}
                  </div>
                  <div class="flex-1 min-w-0">
                    <div class="flex items-center justify-between gap-1">
                      <span class="font-bold text-sm text-slate-800 dark:text-white truncate">{{ th.displayName || '名無し' }}</span>
                      <v-chip
                        v-if="th.unreadCount > 0"
                        size="x-small"
                        color="error"
                        variant="flat"
                        class="shrink-0"
                      >{{ th.unreadCount }}</v-chip>
                    </div>
                    <p class="text-[11px] text-slate-500 dark:text-slate-400 truncate">
                      <span v-if="th.lastSender === 'admin'" class="text-indigo-400">返信済: </span>{{ th.lastMessageBody }}
                    </p>
                  </div>
                </div>
              </v-list-item>
            </v-list>
          </div>

          <!-- 右: 選択スレッドの会話 -->
          <div class="flex flex-col overflow-hidden bg-slate-50 dark:bg-slate-900/40">
            <template v-if="selectedUserId !== null">
              <!-- 相手情報バー -->
              <div class="px-4 py-2.5 border-b border-slate-200 dark:border-slate-800 shrink-0 flex items-center gap-2">
                <span class="font-bold text-sm text-slate-800 dark:text-white">{{ selectedThread?.displayName || '名無し' }}</span>
                <span class="text-[11px] text-slate-400 font-mono">{{ selectedThread?.iidxId }}</span>
                <v-chip v-if="selectedThread?.danRank" size="x-small" label color="amber">{{ selectedThread?.danRank }}</v-chip>
                <v-chip v-if="selectedThread?.arenaRank" size="x-small" label color="blue">{{ selectedThread?.arenaRank }}</v-chip>
              </div>

              <!-- メッセージ一覧 -->
              <div ref="listEl" class="flex-1 overflow-y-auto px-4 py-4 space-y-2">
                <p v-if="loadingMessages" class="text-center text-[11px] text-slate-400 py-8">
                  <v-progress-circular indeterminate size="20" width="2" class="mr-1" />読み込み中...
                </p>
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
              <div class="p-2 border-t border-slate-200 dark:border-slate-800 shrink-0">
                <div class="flex items-end gap-2">
                  <v-textarea
                    v-model="replyDraft"
                    @keydown="onReplyKeydown"
                    rows="1"
                    auto-grow
                    max-rows="5"
                    no-resize
                    color="indigo"
                    density="compact"
                    hide-details
                    placeholder="返信を入力 (Enterで送信 / Shift+Enterで改行)"
                    class="flex-1"
                  />
                  <v-btn
                    color="indigo"
                    @click="handleSendReply"
                    :disabled="isSending || !replyDraft.trim()"
                    :loading="isSending"
                    class="shrink-0 mb-1"
                  >送信</v-btn>
                </div>
              </div>
            </template>

            <p v-else class="m-auto text-xs text-slate-400 italic px-4 py-8 text-center">
              左のお問い合わせを選ぶと会話が表示されます。
            </p>
          </div>
        </div>
      </v-card>
  </v-dialog>
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
import { formatJstShortDateTime } from '../utils/jstTime';
import { mdiClose, mdiMessageProcessingOutline } from '@mdi/js';

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

/** 送信日時を「9/20 15:30」形式（JST 固定）で表示する。 */
const formatTime = (iso: string): string => formatJstShortDateTime(iso);

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
