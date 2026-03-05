<script setup lang="ts">
import { ref } from 'vue';
import { useAuth } from '../composables/useAuth';

const props = defineProps<{
  isOpen: boolean;
}>();

const emit = defineEmits<{
  (e: 'close'): void;
}>();

const { login, registerUser } = useAuth();

const mode = ref<'login' | 'register'>('login');
const isSubmitting = ref(false);
const errorMsg = ref('');

// Form fields
const inputIidxId = ref('');
const password = ref('');
const displayName = ref('');
const danRank = ref('初段');
const arenaRank = ref('C5');
const playSide = ref('1P');

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

const formatIidxId = (e: Event) => {
  const target = e.target as HTMLInputElement;
  let val = target.value.replace(/[^\d]/g, ''); // Remove non-digits
  
  if (val.length > 4) {
    val = val.substring(0, 4) + '-' + val.substring(4, 8);
  }
  
  inputIidxId.value = val;
  target.value = val;
};

const handleSubmit = async () => {
  errorMsg.value = '';
  
  if (!inputIidxId.value.match(/^\d{4}-\d{4}$/)) {
    errorMsg.value = 'IIDX IDは「数字4桁 - 数字4桁」の形式で入力してください。';
    return;
  }
  
  if (!password.value) {
    errorMsg.value = 'パスワードを入力してください。';
    return;
  }

  isSubmitting.value = true;
  
  try {
    if (mode.value === 'login') {
      await login(inputIidxId.value, password.value);
    } else {
      if (!displayName.value.trim()) {
        throw new Error('ユーザー名を入力してください。');
      }
      await registerUser({
        iidxId: inputIidxId.value,
        password: password.value,
        displayName: displayName.value,
        danRank: danRank.value,
        arenaRank: arenaRank.value,
        playSide: playSide.value
      });
    }
    emit('close');
  } catch (err: any) {
    errorMsg.value = err.message || 'エラーが発生しました。';
  } finally {
    isSubmitting.value = false;
  }
};

const switchMode = (newMode: 'login' | 'register') => {
  mode.value = newMode;
  errorMsg.value = '';
  // Keep iidxId and password when switching, clear others
};
</script>

<template>
  <div v-if="isOpen" class="fixed inset-0 z-50 flex items-center justify-center p-4 bg-slate-900/50 backdrop-blur-sm animate-in fade-in duration-200" @click.self="emit('close')">
    <div class="bg-white dark:bg-slate-800 rounded-2xl shadow-xl w-full max-w-md overflow-hidden flex flex-col max-h-[90vh] transition-colors duration-200">
      
      <!-- Tabs -->
      <div class="flex border-b border-slate-200 dark:border-slate-700 bg-slate-50 dark:bg-slate-900 transition-colors duration-200">
        <button 
          @click="switchMode('login')"
          :class="['flex-1 py-4 text-sm font-bold transition-colors duration-200', 
                   mode === 'login' ? 'text-blue-600 dark:text-blue-400 border-b-2 border-blue-600 dark:border-blue-400 bg-white dark:bg-slate-800' : 'text-slate-500 hover:text-slate-700 dark:hover:text-slate-300']"
        >
          ログイン
        </button>
        <button 
          @click="switchMode('register')"
          :class="['flex-1 py-4 text-sm font-bold transition-colors duration-200', 
                   mode === 'register' ? 'text-blue-600 dark:text-blue-400 border-b-2 border-blue-600 dark:border-blue-400 bg-white dark:bg-slate-800' : 'text-slate-500 hover:text-slate-700 dark:hover:text-slate-300']"
        >
          新規登録
        </button>
      </div>
      
      <div class="p-6 overflow-y-auto">
        <form @submit.prevent="handleSubmit" class="space-y-5">
          
          <div v-if="errorMsg" class="p-3 bg-red-50 dark:bg-red-900/30 text-red-700 dark:text-red-400 text-sm rounded-xl flex items-center gap-2 transition-colors duration-200">
            <svg xmlns="http://www.w3.org/2000/svg" class="h-4 w-4 shrink-0" viewBox="0 0 20 20" fill="currentColor">
              <path fill-rule="evenodd" d="M18 10a8 8 0 11-16 0 8 8 0 0116 0zm-7 4a1 1 0 11-2 0 1 1 0 012 0zm-1-9a1 1 0 00-1 1v4a1 1 0 102 0V6a1 1 0 00-1-1z" clip-rule="evenodd" />
            </svg>
            {{ errorMsg }}
          </div>

          <div>
            <label class="block text-sm font-semibold text-slate-700 dark:text-slate-300 mb-1.5 transition-colors duration-200">IIDX ID</label>
            <input type="text" :value="inputIidxId" @input="formatIidxId" required placeholder="1234-5678" pattern="\d{4}-\d{4}" maxlength="9"
              class="w-full px-4 py-2.5 rounded-xl border border-slate-200 dark:border-slate-600 bg-slate-50 dark:bg-slate-900 focus:bg-white dark:focus:bg-slate-800 focus:ring-2 focus:ring-blue-500 dark:focus:ring-blue-400 focus:border-blue-500 dark:focus:border-blue-400 transition-colors text-slate-800 dark:text-slate-100 placeholder-slate-400" />
            <p v-if="mode === 'register'" class="text-xs text-slate-500 dark:text-slate-400 mt-1.5 ml-1 transition-colors duration-200">自動的にハイフンが挿入されます</p>
          </div>

          <div>
            <label class="block text-sm font-semibold text-slate-700 dark:text-slate-300 mb-1.5 transition-colors duration-200">パスワード</label>
            <input type="password" v-model="password" required placeholder="••••••••" minlength="4"
              class="w-full px-4 py-2.5 rounded-xl border border-slate-200 dark:border-slate-600 bg-slate-50 dark:bg-slate-900 focus:bg-white dark:focus:bg-slate-800 focus:ring-2 focus:ring-blue-500 dark:focus:ring-blue-400 focus:border-blue-500 dark:focus:border-blue-400 transition-colors text-slate-800 dark:text-slate-100 placeholder-slate-400" />
          </div>
          
          <template v-if="mode === 'register'">
            <div>
              <label class="block text-sm font-semibold text-slate-700 dark:text-slate-300 mb-1.5 transition-colors duration-200">ユーザー名</label>
              <input type="text" v-model="displayName" required
                class="w-full px-4 py-2.5 rounded-xl border border-slate-200 dark:border-slate-600 bg-slate-50 dark:bg-slate-900 focus:bg-white dark:focus:bg-slate-800 focus:ring-2 focus:ring-blue-500 dark:focus:ring-blue-400 focus:border-blue-500 dark:focus:border-blue-400 transition-colors text-slate-800 dark:text-slate-100"
                placeholder="表示名" />
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

            <div>
              <label class="block text-sm font-semibold text-slate-700 dark:text-slate-300 mb-1.5 transition-colors duration-200">プレイサイド</label>
              <div class="flex gap-4">
                <label class="flex items-center gap-2 cursor-pointer group">
                  <input type="radio" v-model="playSide" value="1P"
                    class="h-4 w-4 text-blue-600 border-slate-300 dark:border-slate-600 focus:ring-blue-500 cursor-pointer" />
                  <span class="text-sm font-bold text-slate-600 dark:text-slate-300 group-hover:text-slate-900 dark:group-hover:text-white transition-colors">1P</span>
                </label>
                <label class="flex items-center gap-2 cursor-pointer group">
                  <input type="radio" v-model="playSide" value="2P"
                    class="h-4 w-4 text-blue-600 border-slate-300 dark:border-slate-600 focus:ring-blue-500 cursor-pointer" />
                  <span class="text-sm font-bold text-slate-600 dark:text-slate-300 group-hover:text-slate-900 dark:group-hover:text-white transition-colors">2P</span>
                </label>
              </div>
              <p class="text-xs text-slate-500 dark:text-slate-400 mt-1">おすすめオプション投票の正規/ミラー変換に使用されます</p>
            </div>
          </template>
          
          <div class="pt-2 flex gap-3">
            <button type="button" @click="emit('close')"
              class="flex-1 py-3 px-4 bg-slate-100 dark:bg-slate-700 hover:bg-slate-200 dark:hover:bg-slate-600 text-slate-700 dark:text-slate-200 font-bold rounded-xl transition-colors">
              キャンセル
            </button>
            <button type="submit" :disabled="isSubmitting"
              class="flex-[2] py-3 px-4 bg-blue-600 dark:bg-blue-500 hover:bg-blue-700 dark:hover:bg-blue-600 disabled:bg-blue-400 dark:disabled:bg-blue-700 text-white font-bold rounded-xl shadow-sm hover:shadow transition-all flex items-center justify-center gap-2">
              <span v-if="isSubmitting" class="w-4 h-4 border-2 border-white/30 border-t-white rounded-full animate-spin"></span>
              {{ mode === 'login' ? 'ログイン' : '登録してログイン' }}
            </button>
          </div>
          
        </form>
      </div>
    </div>
  </div>
</template>
