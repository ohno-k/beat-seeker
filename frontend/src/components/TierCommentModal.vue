<script setup lang="ts">
import { ref, watch, nextTick } from 'vue';
import { useAuth, API_BASE } from '../composables/useAuth';

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

const comments = ref<Array<{ id: number; userId: number; displayName: string; content: string; createdAt: string }>>([]);
const isLoading = ref(false);
const newComment = ref('');
const isSubmitting = ref(false);
const errorMsg = ref('');
const commentsContainer = ref<HTMLElement | null>(null);

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

watch(() => props.show, (newVal) => {
  if (newVal) {
    fetchComments();
  }
}, { immediate: true });

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

const formatDate = (dateString: string) => {
  const d = new Date(dateString);
  return d.toLocaleString();
};

</script>

<template>
  <Teleport to="body">
    <div v-if="show" class="fixed inset-0 z-50 flex items-center justify-center p-4 bg-slate-900/50 backdrop-blur-sm" @mousedown.self="emit('close')">
      <div class="bg-white dark:bg-slate-800 rounded-2xl w-full max-w-2xl max-h-[90vh] flex flex-col shadow-2xl overflow-hidden animate-in fade-in zoom-in-95 duration-200">
      
      <!-- Header -->
      <div class="px-6 py-4 border-b border-slate-200 dark:border-slate-700 bg-slate-50 dark:bg-slate-800/80 flex items-center justify-between">
        <div>
          <h2 class="text-lg font-black text-slate-800 dark:text-white flex items-center gap-2">
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

      <!-- Messages Body -->
      <div ref="commentsContainer" class="flex-1 overflow-y-auto p-6 space-y-4 bg-white dark:bg-slate-800">
        <div v-if="isLoading" class="flex justify-center py-8">
          <div class="w-6 h-6 border-2 border-blue-200 border-t-blue-500 rounded-full animate-spin"></div>
        </div>

        <div v-else-if="comments.length === 0" class="text-center py-12 text-slate-400 dark:text-slate-500 text-sm">
          まだコメントがありません。<br>最初の意見を書き込んでみましょう！
        </div>

        <template v-else>
          <div v-for="comment in comments" :key="comment.id" class="flex flex-col gap-1">
            <div class="flex items-center gap-2 text-xs">
              <span class="font-bold text-slate-700 dark:text-slate-300">{{ comment.displayName }}</span>
              <span class="text-slate-400 dark:text-slate-500">{{ formatDate(comment.createdAt) }}</span>
            </div>
            <div class="bg-slate-100 dark:bg-slate-700/50 text-slate-800 dark:text-slate-200 px-4 py-3 rounded-2xl rounded-tl-sm w-fit max-w-[90%] whitespace-pre-wrap text-sm leading-relaxed">
              {{ comment.content }}
            </div>
          </div>
        </template>
      </div>

      <!-- Input Area -->
      <div class="p-4 bg-slate-50 dark:bg-slate-800 border-t border-slate-200 dark:border-slate-700">
        <div v-if="errorMsg" class="mb-2 text-xs text-red-500 dark:text-red-400 px-2">{{ errorMsg }}</div>
        
        <div v-if="!isLoggedIn" class="text-sm text-center py-3 bg-amber-50 dark:bg-amber-900/20 text-amber-600 dark:text-amber-400 rounded-xl border border-amber-200 dark:border-amber-700/50 font-medium">
          ログインするとコメントを投稿できます
        </div>
        
        <div v-else class="flex items-end gap-3">
          <textarea
            v-model="newComment"
            placeholder="意見や議論を書き込む..."
            class="flex-1 min-h-[44px] max-h-32 px-4 py-3 rounded-2xl border border-slate-300 dark:border-slate-600 bg-white dark:bg-slate-900 text-sm text-slate-900 dark:text-white focus:outline-none focus:ring-2 focus:ring-blue-500 resize-none"
            @keydown.enter.ctrl.prevent="submitComment"
            rows="1"
          ></textarea>
          <button
            @click="submitComment"
            :disabled="!newComment.trim() || isSubmitting"
            class="shrink-0 h-11 px-5 bg-blue-600 hover:bg-blue-700 disabled:bg-slate-300 dark:disabled:bg-slate-700 text-white font-bold rounded-xl transition-colors disabled:cursor-not-allowed flex items-center gap-2"
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
