<script setup lang="ts">
import { ref } from 'vue';
import CsvDropzone from './components/CsvDropzone.vue';
import ScoreSummary from './components/ScoreSummary.vue';
import ScoreDashboard from './components/ScoreDashboard.vue';
import ProfileDashboard from './components/ProfileDashboard.vue';
import LoginModal from './components/LoginModal.vue';
import UploadHistory from './components/UploadHistory.vue';
import Changelog from './components/Changelog.vue';
import UploadResultModal from './components/UploadResultModal.vue';
import AdminUserListModal from './components/AdminUserListModal.vue';
import Terms from './components/Terms.vue';
import { parseScoreCsv } from './utils/csvParser';
import type { ScoreData } from './types/ScoreData';
import { flattenScores } from './utils/scoreData';
import type { UploadDiffResult, UpdatedSong } from './types/UploadDiff';
import { getRankInfo, calculateTotalPoints, calculatePoints } from './utils/beatTier';
import { useAuth } from './composables/useAuth';
import { useScoreUpload } from './composables/useScoreUpload';
import { useScores } from './composables/useScores';
import { useDarkMode } from './composables/useDarkMode';
import { watch } from 'vue';

const scoreData = ref<ScoreData[]>([]);
const isParsing = ref(false);
const errorMsg = ref('');
const activeTab = ref<'dashboard' | 'table' | 'profile' | 'history' | 'changelog' | 'terms'>('dashboard');
const totalBeatTierPoints = ref(0);

const diffResult = ref<UploadDiffResult | null>(null);
const isDiffModalOpen = ref(false);
const isLoginModalOpen = ref(false);
const isAdminModalOpen = ref(false);

const viewingUserId = ref<number | null>(null);
const viewingUserName = ref<string>('');

const { user, isLoggedIn, logout, isLoading: authLoading } = useAuth();
const { upload } = useScoreUpload();
const { fetchMyScores, fetchUserScores, isFetching } = useScores();
const { isDarkMode, toggleDarkMode } = useDarkMode();

const loadSavedScores = async () => {
  try {
    let data;
    if (viewingUserId.value !== null) {
      data = await fetchUserScores(viewingUserId.value);
    } else {
      data = await fetchMyScores();
    }
    
    // Always clear old data first
    scoreData.value = [];
    totalBeatTierPoints.value = 0;
    
    if (data && data.length > 0) {
      scoreData.value = data;
      // Calculate total points for the loaded data
      totalBeatTierPoints.value = calculateTotalPoints(flattenScores(data));
    }
  } catch (e) {
    console.error("Failed to load saved scores", e);
  }
};

const handleSelectUser = async (selectedUser: any) => {
  isAdminModalOpen.value = false;
  viewingUserId.value = selectedUser.id;
  viewingUserName.value = selectedUser.displayName || selectedUser.iidxId;
  await loadSavedScores();
};

const returnToMyData = async () => {
  viewingUserId.value = null;
  viewingUserName.value = '';
  await loadSavedScores();
};

watch(isLoggedIn, (newVal) => {
  if (newVal) {
    viewingUserId.value = null;
    viewingUserName.value = '';
    loadSavedScores();
    
    // Check if we just logged in via Google OAuth redirect
    const urlParams = new URLSearchParams(window.location.search);
    if (urlParams.get('login') === 'success') {
      activeTab.value = 'dashboard';
      
      // Clean up the URL without reloading the page
      window.history.replaceState({}, document.title, window.location.pathname);
    }
  } else {
    viewingUserId.value = null;
    viewingUserName.value = '';
    scoreData.value = [];
    totalBeatTierPoints.value = 0;
  }
});

const clearTypeRankings: Record<string, number> = {
  'FULLCOMBO CLEAR': 7,
  'EX HARD CLEAR': 6,
  'HARD CLEAR': 5,
  'CLEAR': 4,
  'EASY CLEAR': 3,
  'ASSIST CLEAR': 2,
  'FAILED': 1,
  'NO PLAY': 0,
  '---': 0
};

const handleFileDropped = async (file: File) => {
  errorMsg.value = '';
  isParsing.value = true;
  
  try {
    const newData = await parseScoreCsv(file);
    console.log(`Successfully parsed ${newData.length} songs.`);
    
    // Calculate Diff (compare with current scoreData)
    const oldFlat = flattenScores(scoreData.value);
    const newFlat = flattenScores(newData);
    
    const oldScoreMap = new Map();
    oldFlat.forEach(r => oldScoreMap.set(`${r.title}_${r.difficultyName}`, r));
    
    const updatedSongs: UpdatedSong[] = [];
    
    newFlat.forEach(newR => {
        // Report on level 11 and 12 improvements primarily, but you can see all in table
        const oldR = oldScoreMap.get(`${newR.title}_${newR.difficultyName}`);
        
        const oldScore = oldR ? oldR.score : 0;
        const oldClearType = oldR ? oldR.clearType : 'NO PLAY';
        const oldBeatPt = oldR ? oldR.beatTierPoints : 0;
        
        const newScore = newR.score;
        const newClearType = newR.clearType;
        const newBeatPt = newR.beatTierPoints;
        
        const oldClearRank = clearTypeRankings[oldClearType] || 0;
        const newClearRank = clearTypeRankings[newClearType] || 0;
        const clearTypeImproved = newClearRank > oldClearRank;
        const scoreImproved = newScore > oldScore;
        
        const scoreIncrease = scoreImproved ? newScore - oldScore : 0;
        const beatPtIncrease = newBeatPt > oldBeatPt ? newBeatPt - oldBeatPt : 0;
        
        // Only report if there is an actual improvement in score or lamp
        if (scoreImproved || clearTypeImproved) {
            updatedSongs.push({
                title: newR.title,
                difficulty: newR.difficultyName,
                oldScore,
                newScore,
                scoreIncrease,
                oldClearType,
                newClearType,
                clearTypeImproved,
                oldBeatPt,
                newBeatPt,
                beatPtIncrease
            });
        }
    });

    // Sort updated songs by beatPtIncrease descending, then scoreIncrease
    updatedSongs.sort((a, b) => {
        if (b.beatPtIncrease !== a.beatPtIncrease) return b.beatPtIncrease - a.beatPtIncrease;
        return b.scoreIncrease - a.scoreIncrease;
    });

    const oldTotalBeatPt = calculateTotalPoints(oldFlat);
    const newTotalBeatPt = calculateTotalPoints(newFlat);
    const oldTier = getRankInfo(oldTotalBeatPt);
    const newTier = getRankInfo(newTotalBeatPt);

    if (isLoggedIn.value && newData.length > 0) {
      // PRO-UPGRADE: Use backend-provided diff for accuracy against DB
      isParsing.value = true; // Keep loading state
      try {
        const result = await upload(newData);
        console.log("Scores persisted to database.");
        
        // Map the backend diff to our UploadDiffResult format, adding beat points
        const backendUpdates = result.updatedSongs.map(s => {
          // We need original chart data to calculate Beat Points (maxScore, informalRank)
          const chartData = newFlat.find(nf => nf.title === s.title && nf.difficultyName === s.difficulty);
          const informalRank = chartData?.informalRank || (chartData?.difficultyLevel ? chartData.difficultyLevel.toFixed(1) : '12.0');
          const maxScore = chartData?.maxScore || (s.newScore > 0 ? s.newScore : 3000); // Fallback

          // Helper to get points
          const getPoints = (score: number) => {
             const scoreRate = (score / maxScore) * 100;
             return calculatePoints(scoreRate, informalRank);
          };

          const oldBeatPt = getPoints(s.oldScore);
          const newBeatPt = getPoints(s.newScore);

          return {
            ...s,
            oldBeatPt,
            newBeatPt,
            beatPtIncrease: Math.max(0, newBeatPt - oldBeatPt)
          };
        });

        // Filter and sort for the report
        const reportSongs = backendUpdates
          .filter(s => s.scoreIncrease > 0 || s.clearTypeImproved)
          .sort((a, b) => b.beatPtIncrease - a.beatPtIncrease || b.scoreIncrease - a.scoreIncrease);

        const currentTotalBeatPt = calculateTotalPoints(newFlat);
        diffResult.value = {
            oldTotalBeatPt,
            newTotalBeatPt: currentTotalBeatPt,
            totalBeatPtIncrease: Math.max(0, currentTotalBeatPt - oldTotalBeatPt),
            oldTier,
            newTier: getRankInfo(currentTotalBeatPt),
            updatedSongs: reportSongs
        };

        if (reportSongs.length > 0 || (oldFlat.length === 0 && newFlat.length > 0)) {
            isDiffModalOpen.value = true;
        }

        // Apply new data locally and refresh strictly to sync server IDs for memos
        scoreData.value = newData;
        totalBeatTierPoints.value = currentTotalBeatPt;
        await loadSavedScores(); 
      } catch (err) {
        console.error("Auto upload failed", err);
        errorMsg.value = '自動保存または差分の取得に失敗しました。';
      }
    } else {
        // Guest mode - stay with frontend calculation
        diffResult.value = {
            oldTotalBeatPt,
            newTotalBeatPt,
            totalBeatPtIncrease: Math.max(0, newTotalBeatPt - oldTotalBeatPt),
            oldTier,
            newTier,
            updatedSongs
        };
        
        if (updatedSongs.length > 0 || (oldFlat.length === 0 && newFlat.length > 0)) {
            isDiffModalOpen.value = true;
        }
        
        scoreData.value = newData;
        totalBeatTierPoints.value = newTotalBeatPt;
    }
  } catch (err: any) {
    console.error('Failed to parse or save CSV:', err);
    errorMsg.value = err.message || 'CSVの解析に失敗しました。';
  } finally {
    isParsing.value = false;
  }
};

const showUploadArea = ref(false);

const resetData = () => {
  if (isLoggedIn.value) {
    // If logged in, we shouldn't clear the data, just show the upload area
    showUploadArea.value = true;
  } else {
    // If guest, clear it to start over
    scoreData.value = [];
    totalBeatTierPoints.value = 0;
  }
  errorMsg.value = '';
};

const cancelUpload = () => {
  showUploadArea.value = false;
  errorMsg.value = '';
};
</script>

<template>
  <div class="min-h-screen bg-slate-50 dark:bg-slate-900 transition-colors duration-200 flex flex-col">
    <!-- Login / Registration Modal -->
    <LoginModal :is-open="isLoginModalOpen" @close="isLoginModalOpen = false" />

    <!-- Upload Diff Result Modal -->
    <UploadResultModal 
      :is-open="isDiffModalOpen" 
      :diff-data="diffResult" 
      @close="isDiffModalOpen = false" 
    />

    <!-- Admin User List Modal -->
    <AdminUserListModal
      :is-open="isAdminModalOpen"
      @close="isAdminModalOpen = false"
      @select="handleSelectUser"
    />

    <!-- Header -->
    <header class="bg-white dark:bg-slate-800 border-b border-slate-200 dark:border-slate-700 sticky top-0 z-10 shadow-sm transition-colors duration-200">
      <div class="max-w-7xl mx-auto px-4 sm:px-6 lg:px-8 h-16 flex items-center justify-between">
        <div class="flex items-center gap-2 cursor-pointer group" @click="activeTab = 'dashboard'">
          <div class="relative w-8 h-8 bg-blue-600 rounded-lg flex items-center justify-center text-white font-bold text-xl shadow-sm group-hover:bg-blue-700 transition-colors overflow-hidden">
            B
            <div 
              class="absolute bg-red-500 text-white text-[9px] font-black py-[2px] w-[46px] text-center transform -rotate-45 shadow-sm leading-none tracking-wider"
              style="bottom: 3px; right: -14px;"
            >
              BETA
            </div>
          </div>
          <span class="text-xl font-extrabold bg-clip-text text-transparent bg-gradient-to-r from-blue-700 to-blue-500 tracking-tight group-hover:from-blue-600 group-hover:to-blue-400 transition-all">
            beat-seeker
          </span>
        </div>
        
        <div class="flex items-center gap-4">
          <!-- Dark Mode Toggle -->
          <button @click="toggleDarkMode" class="p-2 text-slate-500 hover:text-slate-700 dark:text-slate-400 dark:hover:text-slate-200 transition-colors rounded-full hover:bg-slate-100 dark:hover:bg-slate-700 focus:outline-none">
            <svg v-if="isDarkMode" xmlns="http://www.w3.org/2000/svg" viewBox="0 0 24 24" fill="currentColor" class="h-5 w-5">
              <!-- Moon Icon -->
              <path fill-rule="evenodd" d="M9.528 1.718a.75.75 0 01.162.819A8.97 8.97 0 009 6a9 9 0 009 9 8.97 8.97 0 003.463-.69.75.75 0 01.981.98 10.503 10.503 0 01-9.694 6.46c-5.799 0-10.5-4.701-10.5-10.5 0-4.368 2.667-8.112 6.46-9.694a.75.75 0 01.818.162z" clip-rule="evenodd" />
            </svg>
            <svg v-else xmlns="http://www.w3.org/2000/svg" viewBox="0 0 24 24" fill="currentColor" class="h-5 w-5">
              <!-- Sun Icon -->
              <path d="M12 2.25a.75.75 0 01.75.75v2.25a.75.75 0 01-1.5 0V3a.75.75 0 01.75-.75zM7.5 12a4.5 4.5 0 119 0 4.5 4.5 0 01-9 0zM18.894 6.166a.75.75 0 00-1.06-1.06l-1.591 1.59a.75.75 0 101.06 1.061l1.591-1.59zM21.75 12a.75.75 0 01-.75.75h-2.25a.75.75 0 010-1.5H21a.75.75 0 01.75.75zM17.834 18.894a.75.75 0 001.06-1.06l-1.59-1.591a.75.75 0 10-1.061 1.06l1.59 1.591zM12 18a.75.75 0 01.75.75V21a.75.75 0 01-1.5 0v-2.25A.75.75 0 0112 18zM7.758 17.303a.75.75 0 00-1.061-1.06l-1.591 1.59a.75.75 0 001.06 1.061l1.591-1.59zM6 12a.75.75 0 01-.75.75H3a.75.75 0 010-1.5h2.25A.75.75 0 016 12zM6.697 7.757a.75.75 0 001.06-1.06l-1.59-1.591a.75.75 0 00-1.061 1.06l1.59 1.591z" />
            </svg>
          </button>
          
          <template v-if="!isLoggedIn && !authLoading">
            <button class="text-sm font-medium text-slate-600 hover:text-slate-900 dark:text-slate-300 dark:hover:text-white transition-colors" @click="isLoginModalOpen = true">ログイン</button>
          </template>
          <template v-else-if="isLoggedIn">
            <div class="flex items-center gap-3">
              <button 
                v-if="(user?.id == 18 || user?.iidxId === '5787-1145') && !viewingUserId"
                @click="isAdminModalOpen = true" 
                class="inline-flex items-center justify-center text-sm font-bold px-3 py-1.5 rounded-lg bg-indigo-50 text-indigo-600 hover:bg-indigo-100 dark:bg-indigo-900/30 dark:text-indigo-400 dark:hover:bg-indigo-900/50 border border-indigo-200 dark:border-indigo-800 transition-colors shadow-sm gap-1.5 group"
              >
                <svg xmlns="http://www.w3.org/2000/svg" class="h-4 w-4" fill="none" viewBox="0 0 24 24" stroke="currentColor" stroke-width="2">
                  <path stroke-linecap="round" stroke-linejoin="round" d="M15 12a3 3 0 11-6 0 3 3 0 016 0z" />
                  <path stroke-linecap="round" stroke-linejoin="round" d="M2.458 12C3.732 7.943 7.523 5 12 5c4.478 0 8.268 2.943 9.542 7-1.274 4.057-5.064 7-9.542 7-4.477 0-8.268-2.943-9.542-7z" />
                </svg>
              </button>
              <span class="text-sm font-semibold text-slate-700 dark:text-slate-200">{{ user?.displayName || user?.iidxId }}</span>
              <button class="text-sm font-medium px-3 py-1.5 rounded-lg bg-slate-100 dark:bg-slate-700 hover:bg-slate-200 dark:hover:bg-slate-600 text-slate-600 dark:text-slate-300 transition-colors" @click="logout">ログアウト</button>
            </div>
          </template>
        </div>
      </div>
    </header>

    <!-- Main Content -->
    <main class="flex-1 max-w-7xl w-full mx-auto px-4 sm:px-6 lg:px-8 py-12 flex flex-col items-center justify-center">

      <!-- Admin Viewing Banner -->
      <div v-if="viewingUserId" class="w-full max-w-6xl mb-6 flex flex-col sm:flex-row items-center justify-between gap-4 bg-gradient-to-r from-indigo-500 to-purple-600 p-4 rounded-xl shadow-md text-white border border-indigo-400 dark:border-indigo-700 animate-fade-in relative overflow-hidden">
        <div class="absolute right-0 top-0 bottom-0 w-32 bg-[radial-gradient(ellipse_at_center,_var(--tw-gradient-stops))] from-white/20 to-transparent pointer-events-none"></div>
        <div class="flex items-center gap-3 relative z-10 w-full justify-center sm:justify-start">
          <svg xmlns="http://www.w3.org/2000/svg" class="h-6 w-6 text-indigo-200 shrink-0" fill="none" viewBox="0 0 24 24" stroke="currentColor" stroke-width="2">
            <path stroke-linecap="round" stroke-linejoin="round" d="M15 12a3 3 0 11-6 0 3 3 0 016 0z" />
            <path stroke-linecap="round" stroke-linejoin="round" d="M2.458 12C3.732 7.943 7.523 5 12 5c4.478 0 8.268 2.943 9.542 7-1.274 4.057-5.064 7-9.542 7-4.477 0-8.268-2.943-9.542-7z" />
          </svg>
          <div class="flex flex-col">
            <span class="text-xs font-bold text-indigo-200 uppercase tracking-widest leading-none mb-1">管理者モード</span>
            <span class="text-base sm:text-lg font-bold">現在 <span class="text-white bg-white/20 px-2 py-0.5 rounded backdrop-blur-sm shadow-sm">{{ viewingUserName }}</span> さんのデータを閲覧中</span>
          </div>
        </div>
        <button 
          @click="returnToMyData" 
          class="shrink-0 relative z-10 px-4 py-2 bg-white/10 hover:bg-white/20 backdrop-blur-md text-white font-bold rounded-lg border border-white/30 transition-all shadow-sm flex items-center gap-2 whitespace-nowrap"
        >
          <svg xmlns="http://www.w3.org/2000/svg" class="h-4 w-4" fill="none" viewBox="0 0 24 24" stroke="currentColor" stroke-width="2">
            <path stroke-linecap="round" stroke-linejoin="round" d="M10 19l-7-7m0 0l7-7m-7 7h18" />
          </svg>
          自分のデータに戻る
        </button>
      </div>
      
      <!-- Main Views -->
      <template v-if="activeTab === 'changelog'">
        <Changelog class="w-full max-w-4xl animate-fade-in" />
      </template>
      
      <template v-else-if="activeTab === 'terms'">
        <Terms class="w-full max-w-4xl animate-fade-in" />
      </template>
      
      <template v-else-if="activeTab === 'history'">
        <UploadHistory class="w-full animate-fade-in" />
      </template>
      
      <template v-else>
        <!-- Hero Section (Visible only when no data) -->
        <div v-if="!scoreData.length" class="text-center mb-12 max-w-2xl animate-fade-in">
          <h1 class="text-4xl font-extrabold text-slate-900 dark:text-white tracking-tight sm:text-5xl mb-4">
            スコアデータを<span class="text-blue-600 dark:text-blue-400">可視化</span>しよう
          </h1>
          <p class="text-lg text-slate-600 dark:text-slate-400 leading-relaxed">
            最新のCSVデータをドロップするだけで、あなたの実力値を自動でグラフ化・分析します。
          </p>
        </div>

        <!-- Empty State or Explicit Upload State -->
        <div v-if="!scoreData.length || showUploadArea" class="w-full max-w-3xl animate-fade-in flex flex-col items-center">
          <div v-if="isParsing || isFetching || authLoading" class="w-full flex flex-col items-center justify-center p-12 bg-white dark:bg-slate-800 rounded-2xl shadow-sm border border-slate-200 dark:border-slate-700">
            <div class="w-10 h-10 border-4 border-blue-200 dark:border-blue-900 border-t-blue-600 dark:border-t-blue-400 rounded-full animate-spin mb-4"></div>
            <p class="text-slate-600 dark:text-slate-300 font-medium tracking-wide">データを読み込み中...</p>
          </div>
          
          <template v-else>
            <div class="w-full flex justify-between items-center mb-4" v-if="showUploadArea && scoreData.length > 0">
              <h2 class="text-lg font-bold text-slate-800 dark:text-white">CSVアップロード</h2>
              <button @click="cancelUpload" class="text-sm text-slate-500 hover:text-slate-700 dark:text-slate-400 dark:hover:text-slate-200">キャンセル</button>
            </div>
            <CsvDropzone @file-dropped="handleFileDropped" class="w-full" />
          </template>
          
          <!-- Error Message -->
          <div 
            v-if="errorMsg" 
            class="w-full mt-6 p-4 bg-red-50 dark:bg-red-900/30 text-red-700 dark:text-red-400 border border-red-200 dark:border-red-800 rounded-xl flex items-center gap-3 animate-fade-in"
          >
            <svg xmlns="http://www.w3.org/2000/svg" class="h-5 w-5 text-red-500 shrink-0" viewBox="0 0 20 20" fill="currentColor">
              <path fill-rule="evenodd" d="M10 18a8 8 0 100-16 8 8 0 000 16zM8.707 7.293a1 1 0 00-1.414 1.414L8.586 10l-1.293 1.293a1 1 0 101.414 1.414L10 11.414l1.293 1.293a1 1 0 001.414-1.414L11.414 10l1.293-1.293a1 1 0 00-1.414-1.414L10 8.586 8.707 7.293z" clip-rule="evenodd" />
            </svg>
            <span class="font-medium text-sm sm:text-base">{{ errorMsg }}</span>
          </div>
        </div>

        <!-- Score Results View (Visible when we have data and not explicitly uploading) -->
        <div v-if="scoreData.length > 0 && !showUploadArea" class="w-full flex flex-col items-center animate-fade-in">
          
          <!-- Header & Tabs -->
          <div class="w-full max-w-6xl flex flex-col sm:flex-row sm:items-center justify-between gap-4 mb-6">
            <!-- Tabs -->
            <div class="flex items-center gap-4 bg-slate-200/50 dark:bg-slate-800/50 p-1 rounded-xl overflow-x-auto whitespace-nowrap">
              <button 
                @click="activeTab = 'dashboard'"
                class="px-5 py-2 rounded-lg font-medium text-sm transition-all shadow-sm"
                :class="activeTab === 'dashboard' ? 'bg-white dark:bg-slate-700 text-blue-700 dark:text-blue-400' : 'text-slate-600 dark:text-slate-400 hover:text-slate-900 dark:hover:text-white transparent'"
              >
                ダッシュボード
              </button>
              <button 
                @click="activeTab = 'table'"
                class="px-5 py-2 rounded-lg font-medium text-sm transition-all shadow-sm"
                :class="activeTab === 'table' ? 'bg-white dark:bg-slate-700 text-blue-700 dark:text-blue-400' : 'text-slate-600 dark:text-slate-400 hover:text-slate-900 dark:hover:text-white transparent'"
              >
                スコア一覧
              </button>
            </div>
            
            <button 
              v-if="!viewingUserId"
              @click="resetData"
              class="px-5 py-2.5 bg-white dark:bg-slate-800 hover:bg-slate-50 dark:hover:bg-slate-700 text-slate-700 dark:text-slate-200 text-sm font-medium rounded-xl transition-all border border-slate-200 dark:border-slate-600 hover:border-slate-300 dark:hover:border-slate-500 shadow-sm whitespace-nowrap"
            >
              CSVをアップロード
            </button>
          </div>

          <!-- Dashboard Tab -->
          <div v-show="activeTab === 'dashboard'" class="w-full max-w-6xl flex flex-col items-center">
            <ScoreDashboard 
              :scores="scoreData" 
              :totalPoints="totalBeatTierPoints"
              class="w-full"
            />
          </div>

          <!-- Table Tab -->
          <ScoreSummary 
            v-show="activeTab === 'table'"
            :scores="scoreData" 
            @reset="resetData" 
            @update:totalPoints="points => totalBeatTierPoints = points"
            class="w-full"
          />
          
          <!-- Profile Tab -->
          <ProfileDashboard 
            v-if="activeTab === 'profile'"
            class="w-full max-w-6xl"
          />
        </div>
      </template>
      
    </main>

    <!-- Footer -->
    <footer class="bg-white dark:bg-slate-800 border-t border-slate-200 dark:border-slate-700 py-8 transition-colors duration-200">
      <div class="max-w-7xl mx-auto px-4 sm:px-6 lg:px-8 flex flex-col sm:flex-row items-center justify-between gap-4">
        <p class="text-sm text-slate-500 dark:text-slate-400">
          © 2026 beat-seeker.
        </p>
        <div class="flex items-center gap-4">
          <button 
            @click="activeTab = 'terms'"
            class="text-sm font-medium transition-colors"
            :class="activeTab === 'terms' ? 'text-blue-600 dark:text-blue-400' : 'text-slate-500 dark:text-slate-400 hover:text-slate-800 dark:hover:text-slate-200'"
          >
            利用規約・プライバシーポリシー
          </button>
          <button 
            @click="activeTab = 'changelog'"
            class="text-sm font-medium transition-colors"
            :class="activeTab === 'changelog' ? 'text-blue-600 dark:text-blue-400' : 'text-slate-500 dark:text-slate-400 hover:text-slate-800 dark:hover:text-slate-200'"
          >
            更新履歴
          </button>
        </div>
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
