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
import { mdiClose, mdiMagnify } from '@mdi/js';

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
  <v-dialog :model-value="isOpen" @update:model-value="(v) => { if (!v) emit('close') }" max-width="672">
    <v-card class="bg-white dark:bg-slate-800 max-h-[85vh] transition-colors duration-200">
      <div class="p-4 sm:p-6 border-b border-slate-200 dark:border-slate-700 flex justify-between items-center bg-slate-50 dark:bg-slate-900/50">
        <h3 class="text-xl font-bold text-slate-800 dark:text-white tracking-tight">フレンドを検索</h3>
        <v-btn icon variant="text" size="small" aria-label="閉じる" @click="emit('close')" class="text-slate-400 hover:text-slate-600 dark:hover:text-slate-200">
          <v-icon :icon="mdiClose" />
        </v-btn>
      </div>

      <v-card-text class="p-6 space-y-6 overflow-y-auto">
        <!-- 検索フォーム（虫眼鏡アイコン付きテキスト + 検索ボタン） -->
        <form @submit.prevent="handleSearch" class="flex gap-2 items-center">
          <v-text-field
            v-model="searchQuery"
            type="text"
            placeholder="表示名 または IIDX ID (ハイフン不要) ※完全一致"
            :prepend-inner-icon="mdiMagnify"
            density="comfortable"
            class="flex-1"
          />
          <v-btn
            type="submit"
            color="primary"
            size="large"
            :loading="isSearching"
            :disabled="isSearching"
            class="px-6"
          >
            検索
          </v-btn>
        </form>

        <!-- エラー／成功メッセージ表示領域 -->
        <v-alert v-if="error" type="error" class="font-medium">
          {{ error }}
        </v-alert>

        <v-alert v-if="successMsg" type="success" class="font-medium">
          {{ successMsg }}
        </v-alert>

        <!-- 検索結果一覧（アバター頭文字 + 表示名 + Beat-PT + ランク + 申請ボタン） -->
        <div class="space-y-3">
          <v-card v-for="result in searchResults" :key="result.id"
            class="flex items-center gap-4 p-4 bg-white dark:bg-slate-800 hover:border-blue-200 dark:hover:border-blue-900 transition-all"
          >
            <div class="w-10 h-10 shrink-0 bg-slate-100 dark:bg-slate-700 rounded-full flex items-center justify-center font-bold text-slate-500 dark:text-slate-400">
              {{ result.displayName?.charAt(0) || 'U' }}
            </div>

            <div class="flex-1 min-w-0">
              <div class="flex items-center gap-2">
                <span class="text-sm font-bold text-slate-900 dark:text-white">{{ result.displayName }}</span>
              </div>
              <div class="flex items-center gap-3 mt-1">
                <span class="text-xs font-bold text-blue-600 dark:text-blue-400">{{ result.totalBeatPt.toLocaleString() }} pt</span>
                <span :class="[getRankInfo(result.totalBeatPt).color, 'text-[10px] font-bold']">
                  {{ getRankInfo(result.totalBeatPt).name }} {{ getRankInfo(result.totalBeatPt).tier }}
                </span>
              </div>
              <!-- 申請メッセージ入力欄（100 文字まで、任意） -->
              <div v-if="!result.isFriend && !result.hasSentRequest" class="mt-2">
                <v-text-field
                  v-model="requestMessages[result.id]"
                  type="text"
                  placeholder="申請メッセージ (任意)"
                  maxlength="100"
                  class="text-xs"
                />
              </div>
            </div>

            <div class="flex flex-col gap-2">
              <v-btn
                v-if="!result.isFriend && !result.hasSentRequest"
                @click="handleSendRequest(result)"
                color="primary"
                variant="tonal"
                class="text-sm"
              >
                申請
              </v-btn>
              <v-chip v-else-if="result.isFriend" label color="success" class="font-bold">
                フレンド
              </v-chip>
              <v-chip v-else label color="warning" class="font-bold">
                申請済み
              </v-chip>
            </div>
          </v-card>
        </div>
      </v-card-text>
    </v-card>
  </v-dialog>
</template>
