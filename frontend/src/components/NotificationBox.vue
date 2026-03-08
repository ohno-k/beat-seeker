<script setup lang="ts">
import { onMounted, onUnmounted, ref } from 'vue';
import { useFriends } from '../composables/useFriends';

const props = defineProps<{
  isOpen: boolean;
}>();

const emit = defineEmits<{
  (e: 'close'): void;
}>();

const { pendingRequests, fetchPendingRequests, acceptRequest, rejectRequest } = useFriends();
const isActionLoading = ref<number | null>(null);

onMounted(() => {
  fetchPendingRequests();
  // Poll for new requests every 30 seconds
  const interval = setInterval(fetchPendingRequests, 30000);
  onUnmounted(() => clearInterval(interval));
});

const handleAccept = async (id: number) => {
  isActionLoading.value = id;
  try {
    await acceptRequest(id);
  } finally {
    isActionLoading.value = null;
  }
};

const handleReject = async (id: number) => {
  isActionLoading.value = id;
  try {
    await rejectRequest(id);
  } finally {
    isActionLoading.value = null;
  }
};
</script>

<template>
  <div v-if="isOpen" class="absolute top-16 right-0 w-80 mt-2 bg-white dark:bg-slate-800 rounded-2xl shadow-2xl border border-slate-200 dark:border-slate-700 z-50 overflow-hidden animate-in slide-in-from-top-2 duration-200">
    <div class="p-4 border-b border-slate-100 dark:border-slate-700 flex justify-between items-center bg-slate-50 dark:bg-slate-900/50">
      <h4 class="text-sm font-black text-slate-800 dark:text-white uppercase tracking-wider">通知</h4>
      <button @click="emit('close')" class="text-slate-400 hover:text-slate-600 transition-colors">
        <svg xmlns="http://www.w3.org/2000/svg" class="h-4 w-4" fill="none" viewBox="0 0 24 24" stroke="currentColor">
          <path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M6 18L18 6M6 6l12 12" />
        </svg>
      </button>
    </div>
    
    <div class="max-h-96 overflow-y-auto">
      <div v-if="pendingRequests.length === 0" class="p-8 text-center">
        <div class="w-12 h-12 bg-slate-50 dark:bg-slate-900 rounded-full flex items-center justify-center mx-auto mb-3">
          <svg xmlns="http://www.w3.org/2000/svg" class="h-6 w-6 text-slate-300" fill="none" viewBox="0 0 24 24" stroke="currentColor">
            <path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M15 17h5l-1.405-1.405A2.032 2.032 0 0118 14.158V11a6.002 6.002 0 00-4-5.659V5a2 2 0 10-4 0v.341C7.67 6.165 6 8.388 6 11v3.159c0 .538-.214 1.055-.595 1.436L4 17h5m6 0v1a3 3 0 11-6 0v-1m6 0H9" />
          </svg>
        </div>
        <p class="text-xs text-slate-400 dark:text-slate-500 font-bold">新しい通知はありません</p>
      </div>
      
      <div v-else class="divide-y divide-slate-100 dark:divide-slate-700">
        <div v-for="req in pendingRequests" :key="req.id" class="p-4 hover:bg-slate-50 dark:hover:bg-slate-700/30 transition-colors">
          <div class="flex flex-col gap-3">
            <div class="flex items-start gap-3">
              <div class="w-8 h-8 bg-blue-100 dark:bg-blue-900/50 rounded-full flex items-center justify-center text-blue-600 dark:text-blue-400 text-xs font-bold">
                {{ req.senderName?.charAt(0) || 'U' }}
              </div>
              <div class="flex-1 min-w-0">
                <p class="text-xs text-slate-600 dark:text-slate-300 leading-normal">
                  <span class="font-bold text-slate-900 dark:text-white">{{ req.senderName || 'ユーザー' }}</span> さんからフレンド申請が届いています。
                </p>
                <!-- Message Bubble -->
                <div v-if="req.message" class="mt-2 p-2 bg-blue-50 dark:bg-blue-900/30 rounded-lg border border-blue-100 dark:border-blue-800 relative">
                  <div class="absolute -top-1 left-3 w-2 h-2 bg-blue-50 dark:bg-blue-900/30 border-t border-l border-blue-100 dark:border-blue-800 rotate-45"></div>
                  <p class="text-[10px] text-blue-800 dark:text-blue-300 italic">"{{ req.message }}"</p>
                </div>
              </div>
            </div>
            
            <div class="flex gap-2 pl-11">
              <button 
                @click="handleAccept(req.id)"
                :disabled="isActionLoading === req.id"
                class="flex-1 py-1.5 px-3 bg-blue-600 hover:bg-blue-700 text-white text-[11px] font-bold rounded-lg transition-all shadow-sm"
              >
                承認
              </button>
              <button 
                @click="handleReject(req.id)"
                :disabled="isActionLoading === req.id"
                class="flex-1 py-1.5 px-3 bg-slate-100 dark:bg-slate-700 hover:bg-slate-200 dark:hover:bg-slate-600 text-slate-700 dark:text-slate-200 text-[11px] font-bold rounded-lg transition-all"
              >
                拒否
              </button>
            </div>
          </div>
        </div>
      </div>
    </div>
  </div>
</template>
