<script setup lang="ts">
/**
 * 【コンポーネントの役割】 非公式難易度表の 1 曲（タイトル + 難易度）に紐づくコメントスレッド。
 *
 * 機能:
 *  - GET /api/tier-comments でコメント取得 → 下部にスクロール
 *  - ログインしていれば POST で新規コメント投稿
 *  - 投稿者の Beat-PT に応じてランクアイコンを表示（プロフ紐付け）
 *
 * props:
 *  - show: 開閉フラグ
 *  - title / difficultyName: 対象楽曲の識別子（例: "冥" / "LEGGENDARIA"）
 * emits:
 *  - close: 閉じる
 *  - update: コメント投稿成功時（親の件数バッジ更新用）
 */
import { ref, watch, nextTick } from 'vue';
import { useAuth, API_BASE } from '../composables/useAuth';
import RankIcon from './RankIcon.vue';
import { getRankInfo } from '../utils/beatTier';

const props = defineProps<{
  show: boolean;
  title: string;
  difficultyName: string;
}>();

const emit = defineEmits<{
  (e: 'close'): void;
  (e: 'update'): void;
}>();

const { isLoggedIn, authHeaders } = useAuth();

/** 取得済みのコメント配列。 */
const comments = ref<Array<{ id: number; userId: number; totalBeatPt: number; content: string; createdAt: string }>>([]);
/** 取得中フラグ（スピナー表示）。 */
const isLoading = ref(false);
/** テキストエリアの下書き。 */
const newComment = ref('');
/** 送信中フラグ（二重送信防止）。 */
const isSubmitting = ref(false);
/** 直近のエラーメッセージ。 */
const errorMsg = ref('');
/** コメントリストのスクロール DOM 参照。最新を自動表示するために使う。 */
const commentsContainer = ref<HTMLElement | null>(null);

/**
 * 【関数の役割】 指定楽曲のコメントを取得し、末尾までスクロールする。
 * title が空なら何もしない（初期表示の競合対策）。
 */
const fetchComments = async () => {
  if (!props.title) return;
  isLoading.value = true;
  errorMsg.value = '';
  try {
    const res = await fetch(`${API_BASE}/api/tier-comments?title=${encodeURIComponent(props.title)}&difficultyName=${encodeURIComponent(props.difficultyName)}`);
    if (res.ok) {
      comments.value = await res.json();
      await nextTick();
      if (commentsContainer.value) {
        commentsContainer.value.scrollTop = commentsContainer.value.scrollHeight;
      }
    } else {
      errorMsg.value = 'Failed to load comments';
    }
  } catch (err) {
    errorMsg.value = 'Failed to load comments';
  } finally {
    isLoading.value = false;
  }
};

// モーダルが開かれたタイミングで自動取得。immediate: true で初回も発火。
watch(() => props.show, (newVal) => {
  if (newVal) {
    fetchComments();
  }
}, { immediate: true });

/**
 * 【関数の役割】 コメントを POST 送信する。
 * 文字数上限 1000 をフロントでもチェック。成功時は下書きをクリアして再取得。
 */
const submitComment = async () => {
  if (!newComment.value.trim() || isSubmitting.value) return;
  if (newComment.value.length > 1000) {
    errorMsg.value = 'Comment is too long (max 1000 chars)';
    return;
  }
  
  isSubmitting.value = true;
  errorMsg.value = '';
  try {
    const res = await fetch(`${API_BASE}/api/tier-comments`, {
      method: 'POST',
      headers: authHeaders({ 'Content-Type': 'application/json' }),
      body: JSON.stringify({
        title: props.title,
        difficultyName: props.difficultyName,
        content: newComment.value.trim()
      })
    });
    
    if (res.ok) {
      newComment.value = '';
      await fetchComments();
      emit('update');
    } else {
      const errRes = await res.json().catch(() => ({}));
      errorMsg.value = errRes.error || 'Failed to post comment';
    }
  } catch (err) {
    errorMsg.value = 'Network error while posting comment';
  } finally {
    isSubmitting.value = false;
  }
};

/**
 * 【関数の役割】 バックエンドが返す ISO 風日時を、ブラウザのロケールで整形する。
 * バックエンドが Z 抜きで返すことがあるため、末尾に 'Z' を補って UTC として解釈。
 */
const formatDate = (dateString: string) => {
  const ds = dateString.endsWith('Z') ? dateString : dateString + 'Z';
  const d = new Date(ds);
  return d.toLocaleString();
};

</script>

<template>
  <Teleport to="body">
    <div v-if="show" class="fixed inset-0 z-50 flex items-center justify-center p-4 bg-slate-900/50 backdrop-blur-sm" @mousedown.self="emit('close')">
      <div class="bg-white dark:bg-slate-800 rounded-md w-full max-w-2xl max-h-[90vh] flex flex-col shadow-xl overflow-hidden animate-in fade-in zoom-in-95 duration-200">
      
      <!-- ヘッダー（曲名 + 難易度バッジ + ×ボタン） -->
      <div class="px-6 py-4 border-b border-slate-200 dark:border-slate-700 bg-slate-50 dark:bg-slate-800/80 flex items-center justify-between">
        <div>
          <h2 class="text-lg font-bold text-slate-800 dark:text-white flex items-center gap-2">
            <span class="text-xs px-2 py-0.5 rounded font-bold"
                  :class="difficultyName === 'LEGGENDARIA' ? 'bg-purple-100 text-purple-700 dark:bg-purple-900/30 dark:text-purple-300' : 'bg-orange-100 text-orange-700 dark:bg-orange-900/30 dark:text-orange-300'">
              {{ difficultyName === 'LEGGENDARIA' ? 'LEG' : 'ANO' }}
            </span>
            {{ title }}
          </h2>
          <p class="text-xs text-slate-500 dark:text-slate-400 mt-1">コメントスレッド</p>
        </div>
        <button @click="emit('close')" class="p-2 text-slate-400 hover:text-slate-600 dark:hover:text-slate-200 hover:bg-slate-200 dark:hover:bg-slate-700 rounded-full transition-colors">
          <svg xmlns="http://www.w3.org/2000/svg" width="20" height="20" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><path d="M18 6 6 18"/><path d="m6 6 12 12"/></svg>
        </button>
      </div>

      <!-- コメント表示領域（チャット風） -->
      <div ref="commentsContainer" class="flex-1 overflow-y-auto p-6 space-y-4 bg-white dark:bg-slate-800">
        <div v-if="isLoading" class="flex justify-center py-8">
          <div class="w-6 h-6 border-2 border-blue-200 border-t-blue-500 rounded-full animate-spin"></div>
        </div>

        <div v-else-if="comments.length === 0" class="text-center py-12 text-slate-400 dark:text-slate-500 text-sm">
          まだコメントがありません。<br>最初の意見を書き込んでみましょう！
        </div>

        <template v-else>
          <div v-for="comment in comments" :key="comment.id" class="flex flex-col gap-1 mt-3 first:mt-0">
            <div class="flex items-center gap-2 text-xs mb-0.5">
              <RankIcon v-if="comment.totalBeatPt !== undefined" :rank-name="getRankInfo(comment.totalBeatPt).name" :tier="getRankInfo(comment.totalBeatPt).tier" size="sm" disable-party />
              <span class="text-slate-400 dark:text-slate-500">{{ formatDate(comment.createdAt) }}</span>
            </div>
            <div class="bg-slate-100 dark:bg-slate-700/50 text-slate-800 dark:text-slate-200 px-4 py-3 rounded-md rounded-tl-sm w-fit max-w-[90%] whitespace-pre-wrap text-sm leading-relaxed">
              {{ comment.content }}
            </div>
          </div>
        </template>
      </div>

      <!-- 入力フォーム（未ログイン時は案内を表示） -->
      <div class="p-4 bg-slate-50 dark:bg-slate-800 border-t border-slate-200 dark:border-slate-700">
        <div v-if="errorMsg" class="mb-2 text-xs text-red-500 dark:text-red-400 px-2">{{ errorMsg }}</div>
        
        <div v-if="!isLoggedIn" class="text-sm text-center py-3 bg-amber-50 dark:bg-amber-900/20 text-amber-600 dark:text-amber-400 rounded-md border border-amber-200 dark:border-amber-700/50 font-medium">
          ログインするとコメントを投稿できます
        </div>
        
        <div v-else class="flex items-end gap-3">
          <textarea
            v-model="newComment"
            placeholder="意見や議論を書き込む..."
            class="flex-1 min-h-[44px] max-h-32 px-4 py-3 rounded-md border border-slate-300 dark:border-slate-600 bg-white dark:bg-slate-900 text-sm text-slate-900 dark:text-white focus:outline-none focus:ring-2 focus:ring-blue-500 resize-none"
            @keydown.enter.ctrl.prevent="submitComment"
            rows="1"
          ></textarea>
          <button
            @click="submitComment"
            :disabled="!newComment.trim() || isSubmitting"
            class="shrink-0 h-11 px-5 bg-blue-600 hover:bg-blue-700 disabled:bg-slate-300 dark:disabled:bg-slate-700 text-white font-bold rounded-md transition-colors disabled:cursor-not-allowed flex items-center gap-2"
          >
            <span>送信</span>
          </button>
        </div>
        <div v-if="isLoggedIn" class="mt-2 text-right text-[10px] text-slate-400">Ctrl + Enter で送信</div>
      </div>
      
    </div>
  </div>
  </Teleport>
</template>
