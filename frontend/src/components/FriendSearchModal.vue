<script setup lang="ts">
/**
 * 【コンポーネントの役割】 フレンド検索モーダル。
 *
 * 機能:
 *  - 表示名 または IIDX ID（ハイフン無し）の完全一致で検索
 *  - 検索結果一覧から「申請」ボタンで友達申請を送信
 *  - 既にフレンド／申請済みの場合はそれぞれバッジ表示
 *  - 任意メッセージ（最大 100 文字）を添付可能
 *
 * props:
 *  - isOpen: モーダルの開閉状態
 * emits:
 *  - close: 閉じる要求
 *  - request-sent: 申請送信成功時（親の通知件数更新用）
 */
import { ref } from 'vue';
import { useFriends, type Friend } from '../composables/useFriends';
import { getRankInfo } from '../utils/beatTier';

const props = defineProps<{
  isOpen: boolean;
}>();

const emit = defineEmits<{
  (e: 'close'): void;
  (e: 'request-sent'): void;
}>();

const { searchUsers, sendFriendRequest, error } = useFriends();
/** 検索クエリ（表示名 or IIDX ID）。 */
const searchQuery = ref('');
/** バックエンドから返ってきた検索結果一覧。 */
const searchResults = ref<Friend[]>([]);
/** 検索中フラグ（スピナー表示と二重送信防止）。 */
const isSearching = ref(false);
/** 申請成功後 3 秒間だけ表示する成功メッセージ。 */
const successMsg = ref('');

/**
 * 【関数の役割】 検索クエリを API に送り、結果を searchResults に格納する。
 * 空クエリは無視。結果 0 件は「見つかりませんでした」エラー扱いで表示する。
 */
const handleSearch = async () => {
  if (!searchQuery.value.trim()) return;
  isSearching.value = true;
  error.value = null;
  searchResults.value = [];
  try {
    const results = await searchUsers(searchQuery.value.trim());
    if (error.value) {
      return;
    }
    searchResults.value = results;
    if (searchResults.value.length === 0) {
        error.value = 'ユーザーが見つかりませんでした。表示名またはIIDX IDが正しいか（完全一致のみ）、確認してください。';
    }
  } catch (e: any) {
    error.value = 'エラーが発生しました。時間を置いて再度お試しください。';
  } finally {
    isSearching.value = false;
  }
};

/** 申請対象ユーザーごとの任意メッセージ。user.id をキーにした辞書。 */
const requestMessages = ref<Record<number, string>>({});

/**
 * 【関数の役割】 指定フレンドに友達申請を送信する。
 * 成功したら該当行の hasSentRequest をローカルで true にし、3 秒だけトーストを出す。
 * 失敗は composable 側の error に委ねる。
 */
const handleSendRequest = async (friend: Friend) => {
  try {
    const message = requestMessages.value[friend.id] || '';
    await sendFriendRequest(friend.id, message);
    successMsg.value = `${friend.displayName}さんに申請を送りました！`;
    friend.hasSentRequest = true;
    emit('request-sent');
    setTimeout(() => { successMsg.value = ''; }, 3000);
  } catch (e: any) {
    // エラーは composable が error に反映済みなのでここでは握り潰す。
  }
};

</script>

<template>
  <Teleport to="body">
    <div v-if="isOpen" class="fixed inset-0 z-[100] flex items-center justify-center p-4 bg-slate-900/60 backdrop-blur-sm animate-in fade-in duration-200" @click.self="emit('close')">
      <div class="bg-white dark:bg-slate-800 rounded-2xl shadow-2xl w-full max-w-2xl overflow-hidden flex flex-col max-h-[85vh] transition-colors duration-200 border border-slate-200 dark:border-slate-700">
        
        <div class="p-4 sm:p-6 border-b border-slate-200 dark:border-slate-700 flex justify-between items-center bg-slate-50 dark:bg-slate-900/50">
          <h3 class="text-xl font-black text-slate-800 dark:text-white tracking-tight">フレンドを検索</h3>
          <button @click="emit('close')" class="p-2 text-slate-400 hover:text-slate-600 dark:hover:text-slate-200 transition-colors">
            <svg xmlns="http://www.w3.org/2000/svg" class="h-6 w-6" fill="none" viewBox="0 0 24 24" stroke="currentColor">
              <path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M6 18L18 6M6 6l12 12" />
            </svg>
          </button>
        </div>

        <div class="p-6 space-y-6 overflow-y-auto">
          <!-- 検索フォーム（虫眼鏡アイコン付きテキスト + 検索ボタン） -->
          <form @submit.prevent="handleSearch" class="flex gap-2">
            <div class="relative flex-1">
              <div class="absolute inset-y-0 left-0 pl-3 flex items-center pointer-events-none">
                <svg xmlns="http://www.w3.org/2000/svg" class="h-5 w-5 text-slate-400" fill="none" viewBox="0 0 24 24" stroke="currentColor">
                  <path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M21 21l-6-6m2-5a7 7 0 11-14 0 7 7 0 0114 0z" />
                </svg>
              </div>
              <input 
                type="text" 
                v-model="searchQuery"
                placeholder="表示名 または IIDX ID (ハイフン不要) ※完全一致"
                class="w-full pl-10 pr-4 py-3 bg-slate-50 dark:bg-slate-900 border border-slate-200 dark:border-slate-700 rounded-xl focus:ring-2 focus:ring-blue-500 focus:bg-white dark:focus:bg-slate-950 transition-all text-slate-800 dark:text-slate-200"
              />
            </div>
            <button 
              type="submit"
              :disabled="isSearching"
              class="px-6 py-3 bg-blue-600 hover:bg-blue-700 disabled:bg-blue-400 text-white font-bold rounded-xl shadow-md transition-all flex items-center gap-2"
            >
              <span v-if="isSearching" class="w-4 h-4 border-2 border-white/30 border-t-white rounded-full animate-spin"></span>
              検索
            </button>
          </form>

          <!-- エラー／成功メッセージ表示領域 -->
          <div v-if="error" class="p-4 bg-red-50 dark:bg-red-900/30 text-red-700 dark:text-red-400 rounded-xl border border-red-200 dark:border-red-800/50 flex items-center gap-3">
            <svg xmlns="http://www.w3.org/2000/svg" class="h-5 w-5" viewBox="0 0 20 20" fill="currentColor">
              <path fill-rule="evenodd" d="M18 10a8 8 0 11-16 0 8 8 0 0116 0zm-7 4a1 1 0 11-2 0 1 1 0 012 0zm-1-9a1 1 0 00-1 1v4a1 1 0 102 0V6a1 1 0 00-1-1z" clip-rule="evenodd" />
            </svg>
            <span class="font-medium">{{ error }}</span>
          </div>

          <div v-if="successMsg" class="p-4 bg-emerald-50 dark:bg-emerald-900/30 text-emerald-700 dark:text-emerald-400 rounded-xl border border-emerald-200 dark:border-emerald-800/50 flex items-center gap-3">
            <svg xmlns="http://www.w3.org/2000/svg" class="h-5 w-5" viewBox="0 0 20 20" fill="currentColor">
              <path fill-rule="evenodd" d="M10 18a8 8 0 100-16 8 8 0 000 16zm3.707-9.293a1 1 0 00-1.414-1.414L9 10.586 7.707 9.293a1 1 0 00-1.414 1.414l2 2a1 1 0 001.414 0l4-4z" clip-rule="evenodd" />
            </svg>
            <span class="font-medium">{{ successMsg }}</span>
          </div>

          <!-- 検索結果一覧（アバター頭文字 + 表示名 + Beat-PT + ランク + 申請ボタン） -->
          <div class="space-y-3">
            <div v-for="result in searchResults" :key="result.id" 
              class="flex items-center gap-4 p-4 bg-white dark:bg-slate-800 border border-slate-100 dark:border-slate-700 rounded-2xl hover:border-blue-200 dark:hover:border-blue-900 transition-all shadow-sm"
            >
              <div class="w-10 h-10 bg-slate-100 dark:bg-slate-700 rounded-full flex items-center justify-center font-bold text-slate-500 dark:text-slate-400">
                {{ result.displayName?.charAt(0) || 'U' }}
              </div>
              
              <div class="flex-1 min-w-0">
                <div class="flex items-center gap-2">
                  <span class="text-sm font-bold text-slate-900 dark:text-white">{{ result.displayName }}</span>
                </div>
                <div class="flex items-center gap-3 mt-1">
                  <span class="text-xs font-black text-blue-600 dark:text-blue-400">{{ result.totalBeatPt.toLocaleString() }} pt</span>
                  <span :class="[getRankInfo(result.totalBeatPt).color, 'text-[10px] font-black uppercase']">
                    {{ getRankInfo(result.totalBeatPt).name }} {{ getRankInfo(result.totalBeatPt).tier }}
                  </span>
                </div>
                <!-- 申請メッセージ入力欄（100 文字まで、任意） -->
                <div v-if="!result.isFriend && !result.hasSentRequest" class="mt-2">
                  <input 
                    type="text" 
                    v-model="requestMessages[result.id]"
                    placeholder="申請メッセージ (任意)"
                    maxlength="100"
                    class="w-full text-[10px] py-1.5 px-3 bg-slate-50 dark:bg-slate-900 border border-slate-100 dark:border-slate-700 rounded-lg focus:ring-1 focus:ring-blue-500 transition-all text-slate-600 dark:text-slate-400"
                  />
                </div>
              </div>

              <div class="flex flex-col gap-2">
                <button 
                  v-if="!result.isFriend && !result.hasSentRequest" 
                  @click="handleSendRequest(result)"
                  class="px-4 py-2 bg-blue-50 dark:bg-blue-900/30 text-blue-600 dark:text-blue-400 hover:bg-blue-600 hover:text-white dark:hover:bg-blue-600 transition-all rounded-lg text-sm font-bold border border-blue-100 dark:border-blue-800 shadow-sm"
                >
                  申請
                </button>
                <span v-else-if="result.isFriend" class="text-xs font-bold text-emerald-500 bg-emerald-50 dark:bg-emerald-900/30 px-3 py-1.5 rounded-lg border border-emerald-100 dark:border-emerald-800">
                  フレンド
                </span>
                <span v-else class="text-xs font-bold text-amber-500 bg-amber-50 dark:bg-amber-900/30 px-3 py-1.5 rounded-lg border border-amber-100 dark:border-amber-800">
                  申請済み
                </span>
              </div>
            </div>
          </div>
        </div>
      </div>
    </div>
  </Teleport>
</template>
