<script setup lang="ts">
import { ref } from 'vue';
import CsvDropzone from './components/CsvDropzone.vue';
import ScoreSummary from './components/ScoreSummary.vue';
import ScoreDashboard from './components/ScoreDashboard.vue';
import ProfileDashboard from './components/ProfileDashboard.vue';
import LoginModal from './components/LoginModal.vue';
import ProfileEditModal from './components/ProfileEditModal.vue';
import UploadHistory from './components/UploadHistory.vue';
import Changelog from './components/Changelog.vue';
import UploadResultModal from './components/UploadResultModal.vue';
import RankingList from './components/RankingList.vue';
import AdminUserListModal from './components/AdminUserListModal.vue';
import Sidebar from './components/Sidebar.vue';
import Terms from './components/Terms.vue';
import About from './components/About.vue';
import Friends from './components/Friends.vue';
import NotificationBox from './components/NotificationBox.vue';
import { parseScoreCsv } from './utils/csvParser';
import type { ScoreData } from './types/ScoreData';
import { flattenScores } from './utils/scoreData';
import type { UploadDiffResult, UpdatedSong } from './types/UploadDiff';
import { getRankInfo, calculateTotalPoints, calculatePoints } from './utils/beatTier';
import { useAuth } from './composables/useAuth';
import { useScoreUpload } from './composables/useScoreUpload';
import { useScores } from './composables/useScores';
import { useDarkMode } from './composables/useDarkMode';
import { useFriends } from './composables/useFriends';
import { watch, onMounted } from 'vue';

const scoreData = ref<ScoreData[]>([]);
const isParsing = ref(false);
const errorMsg = ref('');
const activeTab = ref<'dashboard' | 'table' | 'profile' | 'history' | 'ranking' | 'changelog' | 'terms' | 'about' | 'friends'>('dashboard');
const totalBeatTierPoints = ref(0);

const diffResult = ref<UploadDiffResult | null>(null);
const isDiffModalOpen = ref(false);
const isLoginModalOpen = ref(false);
const isProfileModalOpen = ref(false);
const isAdminModalOpen = ref(false);

const viewingUserId = ref<number | null>(null);
const viewingUserName = ref<string>('');
const isSidebarOpen = ref(false);

const { user, isLoggedIn, logout, isLoading: authLoading } = useAuth();
const { upload, saveHistoryLog } = useScoreUpload();
const { fetchMyScores, fetchUserScores, isFetching } = useScores();
const { isDarkMode, toggleDarkMode } = useDarkMode();
const { pendingRequests, fetchPendingRequests, updatePushSubscription } = useFriends();

const isNotificationOpen = ref(false);
const deferredPrompt = ref<any>(null);
const showInstallBanner = ref(false);

onMounted(() => {
  window.addEventListener('beforeinstallprompt', (e) => {
    e.preventDefault();
    deferredPrompt.value = e;
    showInstallBanner.value = true;
  });

  // Request notification permission if logged in
  if (isLoggedIn.value) {
    requestNotificationPermission();
  }
});

const requestNotificationPermission = async () => {
  if (!('Notification' in window)) return;
  const permission = await Notification.requestPermission();
  if (permission === 'granted' && 'serviceWorker' in navigator) {
    const registration = await navigator.serviceWorker.ready;
    const subscription = await registration.pushManager.subscribe({
      userVisibleOnly: true,
      applicationServerKey: 'BCp04c...' // Placeholder VAPID key
    });
    await updatePushSubscription(JSON.stringify(subscription));
  }
};

const installApp = async () => {
  if (!deferredPrompt.value) return;
  deferredPrompt.value.prompt();
  const { outcome } = await deferredPrompt.value.userChoice;
  if (outcome === 'accepted') {
    deferredPrompt.value = null;
    showInstallBanner.value = false;
  }
};

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
    fetchPendingRequests(); // Check for friends
    
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

        // Update local state by fetching ALL scores from the server to get an accurate total
        await loadSavedScores(); 
        const accurateTotalBeatPt = totalBeatTierPoints.value;

        diffResult.value = {
            oldTotalBeatPt,
            newTotalBeatPt: accurateTotalBeatPt,
            totalBeatPtIncrease: Math.max(0, accurateTotalBeatPt - oldTotalBeatPt),
            oldTier,
            newTier: getRankInfo(accurateTotalBeatPt),
            updatedSongs: reportSongs
        };

        if (reportSongs.length > 0 || (oldFlat.length === 0 && newFlat.length > 0)) {
            isDiffModalOpen.value = true;
        }

        // Save the history log to backend using the ACCURATE total from the full profile
        if (reportSongs.length > 0) {
            try {
                await saveHistoryLog(
                    accurateTotalBeatPt,
                    Math.max(0, accurateTotalBeatPt - oldTotalBeatPt),
                    reportSongs.length,
                    JSON.stringify(reportSongs)
                );
                console.log("History log saved successfully.");
            } catch (err) {
                 console.error("Failed to save history log", err);
            }
        }
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
    
    // Always hide upload area and return to dashboard view after successful parse
    showUploadArea.value = false;
    activeTab.value = 'dashboard';

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
  <div class="min-h-screen bg-slate-50 dark:bg-slate-900 transition-colors duration-200 flex flex-row overflow-hidden">
    <!-- Sidebar Component -->
    <Sidebar 
      v-model:is-open="isSidebarOpen"
      v-model:active-tab="activeTab"
      :is-logged-in="isLoggedIn"
      :user="user"
      :viewing-user-id="viewingUserId"
      :auth-loading="authLoading"
      @login="isLoginModalOpen = true"
      @logout="logout"
      @edit-profile="isProfileModalOpen = true"
      @open-admin="isAdminModalOpen = true"
      @upload="resetData"
    />

    <!-- Modals -->
    <LoginModal :is-open="isLoginModalOpen" @close="isLoginModalOpen = false" />
    <ProfileEditModal :is-open="isProfileModalOpen" @close="isProfileModalOpen = false" />
    <UploadResultModal 
      :is-open="isDiffModalOpen" 
      :diff-data="diffResult" 
      @close="isDiffModalOpen = false" 
    />
    <AdminUserListModal
      :is-open="isAdminModalOpen"
      @close="isAdminModalOpen = false"
      @select="handleSelectUser"
    />

    <div class="flex-1 flex flex-col h-screen overflow-x-hidden overflow-y-auto relative custom-scrollbar lg:ml-72">
      <!-- Header -->
      <header class="bg-white dark:bg-slate-800 border-b border-slate-200 dark:border-slate-700 sticky top-0 z-30 shadow-sm transition-colors duration-200 h-16 shrink-0">
        <div class="max-w-7xl lg:max-w-none mx-auto lg:mx-0 px-4 sm:px-6 lg:px-8 h-full flex items-center justify-between">
          <div class="flex items-center gap-4">
            <!-- Hamburger Button -->
            <button 
              @click="isSidebarOpen = true" 
              class="p-2 -ml-2 text-slate-500 hover:text-slate-700 dark:text-slate-400 dark:hover:text-slate-200 transition-colors rounded-lg hover:bg-slate-100 dark:hover:bg-slate-700 focus:outline-none lg:hidden"
            >
              <svg xmlns="http://www.w3.org/2000/svg" class="h-6 w-6" fill="none" viewBox="0 0 24 24" stroke="currentColor">
                <path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M4 6h16M4 12h16M4 18h16" />
              </svg>
            </button>

            <div class="flex lg:hidden items-center gap-2 cursor-pointer group" @click="activeTab = 'dashboard'">
              <div class="relative w-8 h-8 bg-blue-600 rounded-lg flex items-center justify-center text-white font-bold text-xl shadow-sm group-hover:bg-blue-700 transition-colors overflow-hidden">
                B
                <div 
                  class="absolute bg-red-500 text-white text-[9px] font-black py-[2px] w-[46px] text-center transform -rotate-45 shadow-sm leading-none tracking-wider"
                  style="bottom: 3px; right: -14px;"
                >
                  BETA
                </div>
              </div>
            </div>
            
            <div class="hidden lg:flex items-center gap-4 overflow-x-auto no-scrollbar ml-4 h-full flex-1">
              <button 
                @click="activeTab = 'dashboard'"
                class="flex items-center h-full px-3 border-b-2 transition-all font-bold text-sm tracking-wide shrink-0 whitespace-nowrap"
                :class="activeTab === 'dashboard' ? 'border-blue-600 text-blue-600' : 'border-transparent text-slate-500 hover:text-slate-800 dark:hover:text-slate-200'"
              >
                ダッシュボード
              </button>
              <button 
                @click="activeTab = 'table'"
                class="flex items-center h-full px-3 border-b-2 transition-all font-bold text-sm tracking-wide shrink-0 whitespace-nowrap"
                :class="activeTab === 'table' ? 'border-blue-600 text-blue-600' : 'border-transparent text-slate-500 hover:text-slate-800 dark:hover:text-slate-200'"
              >
                スコア一覧
              </button>
              <button 
                @click="activeTab = 'ranking'"
                class="flex items-center h-full px-3 border-b-2 transition-all font-bold text-sm tracking-wide shrink-0 whitespace-nowrap"
                :class="activeTab === 'ranking' ? 'border-blue-600 text-blue-600' : 'border-transparent text-slate-500 hover:text-slate-800 dark:hover:text-slate-200'"
              >
                ランキング
              </button>
              <button 
                v-if="isLoggedIn && !viewingUserId"
                @click="activeTab = 'history'"
                class="flex items-center h-full px-3 border-b-2 transition-all font-bold text-sm tracking-wide shrink-0 whitespace-nowrap"
                :class="activeTab === 'history' ? 'border-blue-600 text-blue-600' : 'border-transparent text-slate-500 hover:text-slate-800 dark:hover:text-slate-200'"
              >
                成長記録
              </button>
              <button 
                v-if="isLoggedIn && !viewingUserId"
                @click="activeTab = 'profile'"
                class="flex items-center h-full px-3 border-b-2 transition-all font-bold text-sm tracking-wide shrink-0 whitespace-nowrap"
                :class="activeTab === 'profile' ? 'border-blue-600 text-blue-600' : 'border-transparent text-slate-500 hover:text-slate-800 dark:hover:text-slate-200'"
              >
                プロフィール
              </button>
              
              <!-- Special Titles for non-tab pages -->
              <span v-if="['changelog', 'terms', 'about'].includes(activeTab)" class="ml-4 px-3 py-1 bg-slate-100 dark:bg-slate-700 rounded text-xs font-bold text-slate-600 dark:text-slate-300 shrink-0 capitalize">
                {{ activeTab }}
              </span>
            </div>
          </div>
          
          <div class="flex items-center gap-4">
            <!-- Dark Mode Toggle -->
            <button @click="toggleDarkMode" class="p-2 text-slate-500 hover:text-slate-700 dark:text-slate-400 dark:hover:text-slate-200 transition-colors rounded-full hover:bg-slate-100 dark:hover:bg-slate-700 focus:outline-none">
              <svg v-if="isDarkMode" xmlns="http://www.w3.org/2000/svg" viewBox="0 0 24 24" fill="currentColor" class="h-5 w-5">
                <path fill-rule="evenodd" d="M9.528 1.718a.75.75 0 01.162.819A8.97 8.97 0 009 6a9 9 0 009 9 8.97 8.97 0 003.463-.69.75.75 0 01.981.98 10.503 10.503 0 01-9.694 6.46c-5.799 0-10.5-4.701-10.5-10.5 0-4.368 2.667-8.112 6.46-9.694a.75.75 0 01.818.162z" clip-rule="evenodd" />
              </svg>
              <svg v-else xmlns="http://www.w3.org/2000/svg" viewBox="0 0 24 24" fill="currentColor" class="h-5 w-5">
                <path d="M12 2.25a.75.75 0 01.75.75v2.25a.75.75 0 01-1.5 0V3a.75.75 0 01.75-.75zM7.5 12a4.5 4.5 0 119 0 4.5 4.5 0 01-9 0zM18.894 6.166a.75.75 0 00-1.06-1.06l-1.591 1.59a.75.75 0 101.06 1.061l1.591-1.59zM21.75 12a.75.75 0 01-.75.75h-2.25a.75.75 0 010-1.5H21a.75.75 0 01.75.75zM17.834 18.894a.75.75 0 001.06-1.06l-1.59-1.591a.75.75 0 10-1.061 1.06l1.59 1.591zM12 18a.75.75 0 01.75.75V21a.75.75 0 01-1.5 0v-2.25A.75.75 0 0112 18zM7.758 17.303a.75.75 0 00-1.061-1.06l-1.591 1.59a.75.75 0 001.06 1.061l1.591-1.59zM6 12a.75.75 0 01-.75.75H3a.75.75 0 010-1.5h2.25A.75.75 0 016 12zM6.697 7.757a.75.75 0 001.06-1.06l-1.59-1.591a.75.75 0 00-1.061 1.06l1.59 1.591z" />
              </svg>
            </button>
            
            <!-- Notification Bell -->
            <div v-if="isLoggedIn" class="relative">
              <button 
                @click="isNotificationOpen = !isNotificationOpen"
                class="p-2 text-slate-500 hover:text-slate-700 dark:text-slate-400 dark:hover:text-slate-200 transition-colors rounded-full hover:bg-slate-100 dark:hover:bg-slate-700 focus:outline-none relative"
              >
                <svg xmlns="http://www.w3.org/2000/svg" class="h-6 w-6" fill="none" viewBox="0 0 24 24" stroke="currentColor">
                  <path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M15 17h5l-1.405-1.405A2.032 2.032 0 0118 14.158V11a6.002 6.002 0 00-4-5.659V5a2 2 0 10-4 0v.341C7.67 6.165 6 8.388 6 11v3.159c0 .538-.214 1.055-.595 1.436L4 17h5m6 0v1a3 3 0 11-6 0v-1m6 0H9" />
                </svg>
                <span v-if="pendingRequests.length > 0" class="absolute top-1.5 right-1.5 w-4 h-4 bg-red-500 text-white text-[10px] font-bold rounded-full flex items-center justify-center border-2 border-white dark:border-slate-800">
                  {{ pendingRequests.length }}
                </span>
              </button>
              <NotificationBox :is-open="isNotificationOpen" @close="isNotificationOpen = false" />
            </div>
            
            <template v-if="!isLoggedIn && !authLoading">
              <button class="text-sm font-medium text-slate-600 hover:text-slate-900 dark:text-slate-300 dark:hover:text-white transition-colors" @click="isLoginModalOpen = true">ログイン</button>
            </template>
            <template v-if="isLoggedIn">
              <div @click="isSidebarOpen = true" class="w-8 h-8 bg-gradient-to-br from-blue-500 to-indigo-600 rounded-full flex items-center justify-center text-white text-xs font-bold shadow-sm cursor-pointer hover:shadow-md transition-all lg:hidden">
                {{ user?.displayName?.charAt(0) || user?.iidxId?.charAt(0) || 'U' }}
              </div>
            </template>
          </div>
        </div>
      </header>

      <!-- Main Content -->
      <main class="flex-1 w-full mx-auto px-4 sm:px-6 lg:px-8 py-8 sm:py-12">
        <!-- Mobile Navigation Tabs (Body Portion) -->
        <nav class="lg:hidden sticky top-16 z-20 bg-slate-50/95 dark:bg-slate-900/95 backdrop-blur-sm border-b border-slate-200 dark:border-slate-700 -mx-4 px-4 mb-8 flex items-center gap-1 overflow-x-auto no-scrollbar">
          <button 
            @click="activeTab = 'dashboard'"
            class="py-3 px-3 border-b-2 transition-all font-bold text-sm whitespace-nowrap"
            :class="activeTab === 'dashboard' ? 'border-blue-600 text-blue-600' : 'border-transparent text-slate-500'"
          >
            ダッシュボード
          </button>
          <button 
            @click="activeTab = 'table'"
            class="py-3 px-3 border-b-2 transition-all font-bold text-sm whitespace-nowrap"
            :class="activeTab === 'table' ? 'border-blue-600 text-blue-600' : 'border-transparent text-slate-500'"
          >
            スコア一覧
          </button>
          <button 
            @click="activeTab = 'ranking'"
            class="py-3 px-3 border-b-2 transition-all font-bold text-sm whitespace-nowrap"
            :class="activeTab === 'ranking' ? 'border-blue-600 text-blue-600' : 'border-transparent text-slate-500'"
          >
            ランキング
          </button>
          <button 
            v-if="isLoggedIn && !viewingUserId"
            @click="activeTab = 'history'"
            class="py-3 px-3 border-b-2 transition-all font-bold text-sm whitespace-nowrap"
            :class="activeTab === 'history' ? 'border-blue-600 text-blue-600' : 'border-transparent text-slate-500'"
          >
            成長記録
          </button>
          <button 
            v-if="isLoggedIn && !viewingUserId"
            @click="activeTab = 'profile'"
            class="py-3 px-3 border-b-2 transition-all font-bold text-sm whitespace-nowrap"
            :class="activeTab === 'profile' ? 'border-blue-600 text-blue-600' : 'border-transparent text-slate-500'"
          >
            プロフィール
          </button>
        </nav>
        <!-- Admin Viewing Banner -->
        <div v-if="viewingUserId" class="w-full max-w-6xl mb-6 flex flex-col sm:flex-row items-center justify-between gap-4 bg-gradient-to-r from-indigo-500 to-purple-600 p-4 rounded-xl shadow-md text-white border border-indigo-400 dark:border-indigo-700 animate-fade-in relative overflow-hidden shrink-0">
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
          <Changelog class="w-full max-w-5xl mx-auto animate-fade-in" />
        </template>

        <template v-else-if="activeTab === 'ranking'">
          <RankingList class="w-full max-w-5xl mx-auto animate-fade-in" />
        </template>
        
        <template v-else-if="activeTab === 'terms'">
          <Terms class="w-full max-w-5xl mx-auto animate-fade-in" />
        </template>
        
        <template v-else-if="activeTab === 'about'">
          <About class="w-full max-w-5xl mx-auto animate-fade-in" />
        </template>
        
        <template v-else>
          <!-- Hero Section (Visible only when no data) -->
          <div v-if="!scoreData.length" class="text-center mb-12 max-w-2xl mx-auto animate-fade-in">
            <h1 class="text-4xl font-extrabold text-slate-900 dark:text-white tracking-tight sm:text-5xl mb-4">
              スコアデータを<span class="text-blue-600 dark:text-blue-400">可視化</span>しよう
            </h1>
            <p class="text-lg text-slate-600 dark:text-slate-400 leading-relaxed">
              最新のCSVデータをドロップするだけで、あなたの実力値を自動でグラフ化・分析します。
            </p>
            
            <!-- PWA Install Banner -->
            <div v-if="showInstallBanner" class="mt-8 p-6 bg-blue-600 rounded-2xl shadow-xl text-white flex flex-col sm:flex-row items-center gap-4 animate-in zoom-in duration-300">
              <div class="w-12 h-12 bg-white/20 rounded-xl flex items-center justify-center shrink-0">
                <svg xmlns="http://www.w3.org/2000/svg" class="h-8 w-8 text-white" fill="none" viewBox="0 0 24 24" stroke="currentColor">
                  <path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M12 18h.01M8 21h8a2 2 0 002-2V5a2 2 0 00-2-2H8a2 2 0 00-2 2v14a2 2 0 002 2z" />
                </svg>
              </div>
              <div class="text-center sm:text-left flex-1">
                <h3 class="font-bold text-lg">アプリとして追加</h3>
                <p class="text-blue-100 text-sm">ホーム画面に追加して、もっと快適にスコア管理しましょう。</p>
              </div>
              <div class="flex gap-2">
                <button @click="showInstallBanner = false" class="px-4 py-2 bg-white/10 hover:bg-white/20 rounded-lg text-sm font-bold transition-all">後で</button>
                <button @click="installApp" class="px-4 py-2 bg-white text-blue-600 hover:bg-blue-50 rounded-lg text-sm font-bold transition-all shadow-md">インストール</button>
              </div>
            </div>
          </div>

          <!-- Empty State or Explicit Upload State -->
          <div v-if="!scoreData.length || showUploadArea" class="w-full max-w-3xl mx-auto animate-fade-in flex flex-col items-center">
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

            <!-- History Tab -->
            <UploadHistory 
              v-if="activeTab === 'history'"
              class="w-full max-w-6xl animate-fade-in" 
            />

            <!-- Friends Tab -->
            <Friends 
              v-if="activeTab === 'friends'"
              class="w-full max-w-6xl animate-fade-in"
            />
          </div>
        </template>
      </main>

      <!-- Footer -->
      <footer class="bg-white dark:bg-slate-800 border-t border-slate-200 dark:border-slate-700 py-8 transition-colors duration-200 shrink-0">
        <div class="max-w-7xl mx-auto px-4 sm:px-6 lg:px-8 flex flex-col sm:flex-row items-center justify-between gap-4">
          <p class="text-sm text-slate-500 dark:text-slate-400">
            © 2026 beat-seeker.
          </p>
          <div class="flex items-center gap-4 flex-wrap justify-center">
            <button @click="activeTab = 'about'" class="text-sm font-medium text-slate-500 dark:text-slate-400 hover:text-slate-800 dark:hover:text-slate-200 transition-colors">beat-seekerとは？</button>
            <button @click="activeTab = 'terms'" class="text-sm font-medium text-slate-500 dark:text-slate-400 hover:text-slate-800 dark:hover:text-slate-200 transition-colors">利用規約</button>
            <button @click="activeTab = 'ranking'" class="text-sm font-medium text-slate-500 dark:text-slate-400 hover:text-slate-800 dark:hover:text-slate-200 transition-colors">ランキング</button>
          </div>
        </div>
      </footer>
    </div>
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
