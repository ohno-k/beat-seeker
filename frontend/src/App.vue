<script setup lang="ts">
import { ref } from 'vue';
import CsvDropzone from './components/CsvDropzone.vue';
import ScoreSummary from './components/ScoreSummary.vue';
import ScoreDashboard from './components/ScoreDashboard.vue';
import { parseScoreCsv } from './utils/csvParser';
import type { ScoreData } from './types/ScoreData';
import { useAuth } from './composables/useAuth';
import { useScoreUpload } from './composables/useScoreUpload';

const scoreData = ref<ScoreData[]>([]);
const isParsing = ref(false);
const errorMsg = ref('');
const activeTab = ref<'dashboard' | 'table'>('dashboard');
const totalBeatTierPoints = ref(0);

const { user, isLoggedIn, login, logout } = useAuth();
const { upload } = useScoreUpload();

const handleFileDropped = async (file: File) => {
  errorMsg.value = '';
  isParsing.value = true;
  totalBeatTierPoints.value = 0;
  
  try {
    const data = await parseScoreCsv(file);
    scoreData.value = data;
    console.log(`Successfully parsed ${data.length} songs.`);
  } catch (err: any) {
    console.error('Failed to parse CSV:', err);
    errorMsg.value = err.message || 'CSVファイルの解析中にエラーが発生しました。';
  } finally {
    isParsing.value = false;
  }
};

const saveToDb = async () => {
  if (!scoreData.value.length) return;
  try {
    const res = await upload(scoreData.value);
    alert(`保存完了: ${res.saved} 件のスコアが保存されました`);
  } catch (e: any) {
    alert(`保存エラー: ${e.message}`);
  }
};

const resetData = () => {
  scoreData.value = [];
  errorMsg.value = '';
  totalBeatTierPoints.value = 0;
};
</script>

<template>
  <div class="min-h-screen bg-slate-50 flex flex-col">
    <!-- Header -->
    <header class="bg-white border-b border-slate-200 sticky top-0 z-10 shadow-sm">
      <div class="max-w-7xl mx-auto px-4 sm:px-6 lg:px-8 h-16 flex items-center justify-between">
        <div class="flex items-center gap-2 cursor-pointer group" @click="activeTab = 'dashboard'">
          <div class="w-8 h-8 bg-blue-600 rounded-lg flex items-center justify-center text-white font-bold text-xl shadow-sm group-hover:bg-blue-700 transition-colors">
            B
          </div>
          <span class="text-xl font-extrabold bg-clip-text text-transparent bg-gradient-to-r from-blue-700 to-blue-500 tracking-tight group-hover:from-blue-600 group-hover:to-blue-400 transition-all">
            beat-seeker
          </span>
        </div>
        
        <div class="flex items-center gap-4">
          <template v-if="!isLoggedIn">
            <button class="text-sm font-medium text-slate-600 hover:text-slate-900 transition-colors" @click="login">ログイン</button>
          </template>
          <template v-else>
            <div class="flex items-center gap-2">
              <img :src="user?.avatarUrl" alt="avatar" class="w-6 h-6 rounded-full" />
              <span class="text-sm text-slate-600">{{ user?.displayName }}</span>
              <button class="text-sm font-medium text-slate-600 hover:text-slate-900 transition-colors" @click="logout">ログアウト</button>
            </div>
          </template>
        </div>
      </div>
    </header>

    <!-- Main Content -->
    <main class="flex-1 max-w-7xl w-full mx-auto px-4 sm:px-6 lg:px-8 py-12 flex flex-col items-center justify-center">
      
      <!-- Hero Section (Visible only when no data) -->
      <div v-if="!scoreData.length" class="text-center mb-12 max-w-2xl animate-fade-in">
        <h1 class="text-4xl font-extrabold text-slate-900 tracking-tight sm:text-5xl mb-4">
          スコアデータを<span class="text-blue-600">可視化</span>しよう
        </h1>
        <p class="text-lg text-slate-600 leading-relaxed">
          最新のCSVデータをドロップするだけで、あなたの実力値を自動でグラフ化・分析します。
        </p>
      </div>

      <!-- Dropzone or Parsing State -->
      <div v-if="!scoreData.length" class="w-full max-w-3xl animate-fade-in">
        <div v-if="isParsing" class="flex flex-col items-center justify-center p-12 bg-white rounded-2xl shadow-sm border border-slate-200">
          <div class="w-10 h-10 border-4 border-blue-200 border-t-blue-600 rounded-full animate-spin mb-4"></div>
          <p class="text-slate-600 font-medium tracking-wide">CSVデータを解析中...</p>
        </div>
        
        <CsvDropzone v-else @file-dropped="handleFileDropped" />
        
        <!-- Error Message -->
        <div 
          v-if="errorMsg" 
          class="mt-6 p-4 bg-red-50 text-red-700 border border-red-200 rounded-xl flex items-center gap-3 animate-fade-in"
        >
          <svg xmlns="http://www.w3.org/2000/svg" class="h-5 w-5 text-red-500 shrink-0" viewBox="0 0 20 20" fill="currentColor">
            <path fill-rule="evenodd" d="M10 18a8 8 0 100-16 8 8 0 000 16zM8.707 7.293a1 1 0 00-1.414 1.414L8.586 10l-1.293 1.293a1 1 0 101.414 1.414L10 11.414l1.293 1.293a1 1 0 001.414-1.414L11.414 10l1.293-1.293a1 1 0 00-1.414-1.414L10 8.586 8.707 7.293z" clip-rule="evenodd" />
          </svg>
          <span class="font-medium text-sm sm:text-base">{{ errorMsg }}</span>
        </div>
      </div>

      <!-- Score Results View -->
      <div v-else class="w-full flex flex-col items-center animate-fade-in">
        
        <!-- Header & Tabs -->
        <div class="w-full max-w-6xl flex flex-col sm:flex-row sm:items-center justify-between gap-4 mb-6">
          <div class="flex items-center gap-4 bg-slate-200/50 p-1 rounded-xl">
            <button 
              @click="activeTab = 'dashboard'"
              class="px-5 py-2 rounded-lg font-medium text-sm transition-all shadow-sm"
              :class="activeTab === 'dashboard' ? 'bg-white text-blue-700' : 'text-slate-600 hover:text-slate-900 transparent'"
            >
              ダッシュボード
            </button>
            <button 
              @click="activeTab = 'table'"
              class="px-5 py-2 rounded-lg font-medium text-sm transition-all shadow-sm"
              :class="activeTab === 'table' ? 'bg-white text-blue-700' : 'text-slate-600 hover:text-slate-900 transparent'"
            >
              スコア一覧
            </button>
          </div>
            <button 
              @click="resetData"
              class="px-5 py-2.5 bg-white hover:bg-slate-50 text-slate-700 text-sm font-medium rounded-xl transition-all border border-slate-200 hover:border-slate-300 shadow-sm whitespace-nowrap"
            >
              別のファイルをアップロード
            </button>
            <template v-if="isLoggedIn && scoreData.length">
              <button @click="saveToDb" class="px-5 py-2.5 bg-blue-600 hover:bg-blue-700 text-white text-sm font-medium rounded-xl transition-all border border-blue-600 shadow-sm whitespace-nowrap">
                保存
              </button>
            </template>
          </div>

        <!-- Dashboard Tab -->
        <ScoreDashboard 
          v-show="activeTab === 'dashboard'"
          :scores="scoreData" 
          :totalPoints="totalBeatTierPoints"
          class="w-full max-w-6xl"
        />

        <!-- Table Tab -->
        <ScoreSummary 
          v-show="activeTab === 'table'"
          :scores="scoreData" 
          @reset="resetData" 
          @update:totalPoints="points => totalBeatTierPoints = points"
          class="w-full"
        />
        
      </div>
      
    </main>

    <!-- Footer -->
    <footer class="bg-white border-t border-slate-200 py-8">
      <div class="max-w-7xl mx-auto px-4 sm:px-6 lg:px-8 text-center">
        <p class="text-sm text-slate-500">
          © 2026 beat-seeker.
        </p>
      </div>
    </footer>
  </div>
</template>

<style scoped>
.animate-fade-in {
  animation: fadeIn 0.4s ease-out forwards;
}

@keyframes fadeIn {
  from {
    opacity: 0;
    transform: translateY(10px);
  }
  to {
    opacity: 1;
    transform: translateY(0);
  }
}
</style>
