<script setup lang="ts">
import { ref, onMounted } from 'vue';
import { useAuth } from '../composables/useAuth';

const { user, refresh } = useAuth();

const displayName = ref('');
const iidxId = ref('');
const danRank = ref('初段');
const arenaRank = ref('C5');
const isSubmitting = ref(false);
const errorMsg = ref('');

const formatIidxId = (e: Event) => {
  const target = e.target as HTMLInputElement;
  let val = target.value.replace(/[^\d]/g, ''); // Remove non-digits
  
  if (val.length > 4) {
    val = val.substring(0, 4) + '-' + val.substring(4, 8);
  }
  
  iidxId.value = val;
  target.value = val;
};

onMounted(() => {
  if (user.value) {
    displayName.value = user.value.displayName || '';
  }
});

const danRanks = [
  '七級', '六級', '五級', '四級', '三級', '二級', '一級',
  '初段', '二段', '三段', '四段', '五段', '六段', '七段', '八段', '九段', '十段', '中伝', '皆伝'
];

const arenaRanks = [
  'A1', 'A2', 'A3', 'A4', 'A5',
  'B1', 'B2', 'B3', 'B4', 'B5',
  'C1', 'C2', 'C3', 'C4', 'C5',
  'D1', 'D2', 'D3', 'D4', 'D5'
];

const submitProfile = async () => {
  errorMsg.value = '';
  
  if (!iidxId.value.match(/^\d{4}-\d{4}$/)) {
    errorMsg.value = 'IIDX IDは「数字4桁 - 数字4桁」の形式で入力してください。';
    return;
  }
  
  if (!displayName.value.trim()) {
    errorMsg.value = 'ユーザー名を入力してください。';
    return;
  }

  isSubmitting.value = true;
  
  try {
    // VITE_API_BASE should be explicitly configured in Render environment variables
    const API_BASE = import.meta.env.VITE_API_BASE ?? 'http://localhost:8080';
    const res = await fetch(`${API_BASE}/api/auth/me/profile`, {
      method: 'PUT',
      headers: {
        'Content-Type': 'application/json'
      },
      credentials: 'include',
      body: JSON.stringify({
        displayName: displayName.value,
        iidxId: iidxId.value,
        danRank: danRank.value,
        arenaRank: arenaRank.value
      })
    });
    
    if (!res.ok) {
      throw new Error('プロフィールの更新に失敗しました。');
    }
    
    await refresh(); // Reload user state to dismiss modal
  } catch (err: any) {
    errorMsg.value = err.message || 'エラーが発生しました。';
  } finally {
    isSubmitting.value = false;
  }
};
</script>

<template>
  <div class="fixed inset-0 z-50 flex items-center justify-center p-4 bg-slate-900/50 backdrop-blur-sm animate-in fade-in duration-200">
    <div class="bg-white dark:bg-slate-800 rounded-2xl shadow-xl w-full max-w-md overflow-hidden flex flex-col max-h-[90vh] transition-colors duration-200">
      
      <div class="px-6 py-5 border-b border-slate-100 dark:border-slate-700 bg-slate-50 dark:bg-slate-800/80 transition-colors duration-200">
        <h3 class="text-xl font-bold text-slate-800 dark:text-slate-100">
          プロフィール設定 ✨
        </h3>
        <p class="text-sm text-slate-500 dark:text-slate-400 mt-1">
          Beat-Seekerへようこそ！初めにプレイヤー情報を設定してください。
        </p>
      </div>
      
      <div class="p-6 overflow-y-auto">
        <form @submit.prevent="submitProfile" class="space-y-5">
          
          <div v-if="errorMsg" class="p-3 bg-red-50 dark:bg-red-900/30 text-red-700 dark:text-red-400 text-sm rounded-xl flex items-center gap-2 transition-colors duration-200">
            <svg xmlns="http://www.w3.org/2000/svg" class="h-4 w-4 shrink-0" viewBox="0 0 20 20" fill="currentColor">
              <path fill-rule="evenodd" d="M18 10a8 8 0 11-16 0 8 8 0 0116 0zm-7 4a1 1 0 11-2 0 1 1 0 012 0zm-1-9a1 1 0 00-1 1v4a1 1 0 102 0V6a1 1 0 00-1-1z" clip-rule="evenodd" />
            </svg>
            {{ errorMsg }}
          </div>

          <div>
            <label class="block text-sm font-semibold text-slate-700 dark:text-slate-300 mb-1.5 transition-colors duration-200">ユーザー名</label>
            <input type="text" v-model="displayName" required
              class="w-full px-4 py-2.5 rounded-xl border border-slate-200 dark:border-slate-600 bg-slate-50 dark:bg-slate-900 focus:bg-white dark:focus:bg-slate-800 focus:ring-2 focus:ring-blue-500 dark:focus:ring-blue-400 focus:border-blue-500 dark:focus:border-blue-400 transition-colors text-slate-800 dark:text-slate-100"
              placeholder="表示名" />
          </div>
          
          <div>
            <label class="block text-sm font-semibold text-slate-700 dark:text-slate-300 mb-1.5 transition-colors duration-200">IIDX ID</label>
            <input type="text" :value="iidxId" @input="formatIidxId" required placeholder="1234-5678" pattern="\d{4}-\d{4}" maxlength="9"
              class="w-full px-4 py-2.5 rounded-xl border border-slate-200 dark:border-slate-600 bg-slate-50 dark:bg-slate-900 focus:bg-white dark:focus:bg-slate-800 focus:ring-2 focus:ring-blue-500 dark:focus:ring-blue-400 focus:border-blue-500 dark:focus:border-blue-400 transition-colors text-slate-800 dark:text-slate-100" />
            <p class="text-xs text-slate-500 dark:text-slate-400 mt-1.5 ml-1 transition-colors duration-200">自動的にハイフンが挿入されます</p>
          </div>
          
          <div class="grid grid-cols-2 gap-4">
            <div>
              <label class="block text-sm font-semibold text-slate-700 dark:text-slate-300 mb-1.5 transition-colors duration-200">段位</label>
              <select v-model="danRank" 
                class="w-full px-4 py-2.5 rounded-xl border border-slate-200 dark:border-slate-600 bg-slate-50 dark:bg-slate-900 focus:bg-white dark:focus:bg-slate-800 focus:ring-2 focus:ring-blue-500 dark:focus:ring-blue-400 focus:border-blue-500 dark:focus:border-blue-400 transition-colors text-slate-800 dark:text-slate-100 cursor-pointer appearance-none">
                <option v-for="rank in danRanks" :key="rank" :value="rank">{{ rank }}</option>
              </select>
            </div>
            <div>
              <label class="block text-sm font-semibold text-slate-700 dark:text-slate-300 mb-1.5 transition-colors duration-200">アリーナランク</label>
              <select v-model="arenaRank" 
                class="w-full px-4 py-2.5 rounded-xl border border-slate-200 dark:border-slate-600 bg-slate-50 dark:bg-slate-900 focus:bg-white dark:focus:bg-slate-800 focus:ring-2 focus:ring-blue-500 dark:focus:ring-blue-400 focus:border-blue-500 dark:focus:border-blue-400 transition-colors text-slate-800 dark:text-slate-100 cursor-pointer appearance-none">
                <option v-for="rank in arenaRanks" :key="rank" :value="rank">{{ rank }}</option>
              </select>
            </div>
          </div>
          
          <div class="pt-2">
            <button type="submit" :disabled="isSubmitting"
              class="w-full py-3 px-4 bg-blue-600 dark:bg-blue-500 hover:bg-blue-700 dark:hover:bg-blue-600 disabled:bg-blue-400 dark:disabled:bg-blue-700 text-white font-bold rounded-xl shadow-sm hover:shadow transition-all flex items-center justify-center gap-2">
              <span v-if="isSubmitting" class="w-4 h-4 border-2 border-white/30 border-t-white rounded-full animate-spin"></span>
              設定を完了する
            </button>
          </div>
          
        </form>
      </div>
    </div>
  </div>
</template>
