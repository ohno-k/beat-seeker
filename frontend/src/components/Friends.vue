<script setup lang="ts">
import { ref, onMounted } from 'vue';
import { useFriends } from '../composables/useFriends';
import FriendSearchModal from './FriendSearchModal.vue';
import { getRankInfo } from '../utils/beatTier';

const { friends, isLoading, fetchFriends } = useFriends();
const isSearchModalOpen = ref(false);

onMounted(() => {
  fetchFriends();
});

const formatDate = (dateStr: string | null) => {
  if (!dateStr) return '未アップロード';
  const date = new Date(dateStr);
  return date.toLocaleString('ja-JP', {
    year: 'numeric', month: '2-digit', day: '2-digit',
    hour: '2-digit', minute: '2-digit'
  });
};
</script>

<template>
  <div class="space-y-6">
    <div class="flex justify-between items-center bg-white dark:bg-slate-800 p-6 rounded-2xl shadow-sm border border-slate-200 dark:border-slate-700 transition-colors duration-200">
      <div>
        <h2 class="text-2xl font-bold text-slate-900 dark:text-white">フレンド一覧</h2>
        <p class="text-slate-500 dark:text-slate-400 text-sm mt-1">ライバルの進捗を確認しましょう</p>
      </div>
      <button 
        @click="isSearchModalOpen = true"
        class="p-3 bg-blue-600 hover:bg-blue-700 text-white rounded-xl shadow-lg shadow-blue-500/20 transition-all flex items-center gap-2 font-bold"
      >
        <svg xmlns="http://www.w3.org/2000/svg" class="h-5 w-5" fill="none" viewBox="0 0 24 24" stroke="currentColor">
          <path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M12 4v16m8-8H4" />
        </svg>
        フレンド追加
      </button>
    </div>

    <div v-if="isLoading && friends.length === 0" class="flex flex-col items-center justify-center p-12 bg-white dark:bg-slate-800 rounded-2xl border border-slate-200 dark:border-slate-700">
      <div class="w-10 h-10 border-4 border-blue-100 border-t-blue-600 rounded-full animate-spin mb-4"></div>
      <p class="text-slate-500 dark:text-slate-400">読み込み中...</p>
    </div>

    <div v-else-if="friends.length === 0" class="flex flex-col items-center justify-center p-12 bg-white dark:bg-slate-800 rounded-2xl border border-slate-200 dark:border-slate-700 border-dashed">
      <div class="w-16 h-16 bg-slate-100 dark:bg-slate-700 rounded-full flex items-center justify-center text-slate-400 mb-4">
        <svg xmlns="http://www.w3.org/2000/svg" class="h-8 w-8" fill="none" viewBox="0 0 24 24" stroke="currentColor">
          <path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M12 4.354a4 4 0 110 5.292M15 21H3v-1a6 6 0 0112 0v1zm0 0h6v-1a6 6 0 00-9-5.197M13 7a4 4 0 11-8 0 4 4 0 018 0z" />
        </svg>
      </div>
      <p class="text-slate-500 dark:text-slate-400 font-bold">フレンドがまだいません</p>
      <p class="text-slate-400 dark:text-slate-500 text-sm mt-1">右上のボタンからフレンドを探してみましょう！</p>
    </div>

    <div v-else class="grid grid-cols-1 md:grid-cols-2 lg:grid-cols-3 gap-4">
      <div v-for="friend in friends" :key="friend.id" 
        class="bg-white dark:bg-slate-800 p-5 rounded-2xl shadow-sm border border-slate-100 dark:border-slate-700 hover:shadow-md transition-all group"
      >
        <div class="flex items-center gap-4 mb-4">
          <div class="w-12 h-12 bg-gradient-to-br from-blue-500 to-indigo-600 rounded-full flex items-center justify-center text-white text-lg font-bold shadow-md">
            {{ friend.displayName?.charAt(0) || 'U' }}
          </div>
          <div class="flex-1 min-w-0">
            <h3 class="font-bold text-slate-900 dark:text-white truncate group-hover:text-blue-600 transition-colors">{{ friend.displayName }}</h3>
            <p class="text-xs text-slate-500 dark:text-slate-400 font-mono">{{ friend.iidxId }}</p>
          </div>
        </div>
        
        <div class="space-y-3">
          <div class="flex items-center justify-between p-3 bg-slate-50 dark:bg-slate-900/50 rounded-xl">
             <div class="flex flex-col">
               <span class="text-[10px] font-black text-slate-400 dark:text-slate-500 uppercase tracking-wider">BEAT-PT</span>
               <span class="text-lg font-black text-blue-600 dark:text-blue-400">{{ friend.totalBeatPt.toLocaleString() }} <span class="text-xs font-normal">pt</span></span>
             </div>
             <div class="flex flex-col items-end">
               <span class="text-[10px] font-black text-slate-400 dark:text-slate-500 uppercase tracking-wider">TIER</span>
               <div :class="[getRankInfo(friend.totalBeatPt).color, 'font-black text-sm']">
                 {{ getRankInfo(friend.totalBeatPt).name }} {{ getRankInfo(friend.totalBeatPt).tier }}
               </div>
             </div>
          </div>
          
          <div class="flex items-center justify-between text-xs text-slate-500 dark:text-slate-400 px-1">
            <span>最終更新</span>
            <span>{{ formatDate(friend.lastUploadedAt) }}</span>
          </div>
        </div>
      </div>
    </div>

    <FriendSearchModal 
      :is-open="isSearchModalOpen"
      @close="isSearchModalOpen = false"
      @request-sent="fetchFriends"
    />
  </div>
</template>
