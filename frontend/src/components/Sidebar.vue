<script setup lang="ts">
/**
 * 【コンポーネントの役割】 モバイル/PC 共通のサイドバーナビゲーション。
 * - 主要タブ（ダッシュボード、スコア一覧、プロフィール、ランキング、フレンド 等）を v-model:activeTab で制御
 * - 権限ベースで各メニューを出し分け（ログイン必須、特定ユーザー ID 限定、サポーター限定 等）
 * - 閲覧モード（admin / friend）時は一部メニューを非表示（hideOnViewing）
 * - 多言語切替、ログイン/ログアウト/プロフィール編集ボタンを内包
 * - サポーター（Ko-fi）用の Sプリフト 付きランチャー（トークンをクリップボードコピー付きで ko-fi.com を開く）
 *
 * @emits update:isOpen サイドバー開閉状態を親と同期。
 * @emits update:activeTab タブ選択通知。
 * @emits login/logout/editProfile/openAdmin/upload 対応するアクションを親に通知。
 */
import { computed, ref } from 'vue';
import { useI18n } from '../composables/useI18n';
import { useAdmin } from '../composables/useAdmin';

const { t, currentLang, setLanguage, availableLanguages } = useI18n();

/** Ko-fi 支援トークンをコピー済みの一時フラグ（5 秒で戻る）。 */
const kofiCopied = ref(false);
/** Ko-fi 確認モーダルの表示フラグ（サポーターのみ使用）。 */
const showKofiModal = ref(false);

/**
 * 【関数の役割】 Ko-fi ボタン押下時のハンドラ。
 * supporterToken を持つ既存サポーターは確認モーダルを先に表示し、未サポーターはそのまま外部リンクを開く。
 */
const handleKofiClick = () => {
  const token = props.user?.supporterToken;
  if (token) {
    showKofiModal.value = true;
  } else {
    window.open('https://ko-fi.com/beat_seeker', '_blank');
  }
};

/**
 * 【関数の役割】 Ko-fi 確認モーダルで「開く」を押したときの処理。
 * サポーターのトークンをクリップボードへコピーしつつ、新規タブで ko-fi.com を開く。
 * コピー失敗はサイレントに無視（機能は支障なく継続する）。
 */
const confirmKofiOpen = () => {
  const token = props.user?.supporterToken;
  if (token) {
    navigator.clipboard.writeText(token).then(() => {
      kofiCopied.value = true;
      setTimeout(() => { kofiCopied.value = false; }, 5000);
    }).catch(() => {});
  }
  showKofiModal.value = false;
  window.open('https://ko-fi.com/beat_seeker', '_blank');
};

const props = defineProps<{
  isOpen: boolean;
  activeTab: string;
  isLoggedIn: boolean;
  user: any;
  viewingUserId: number | null;
  viewingMode: 'admin' | 'friend' | null;
  authLoading: boolean;
}>();

const emit = defineEmits<{
  (e: 'update:isOpen', value: boolean): void;
  (e: 'update:activeTab', value: any): void;
  (e: 'login'): void;
  (e: 'logout'): void;
  (e: 'editProfile'): void;
  (e: 'openAdmin'): void;
  (e: 'upload'): void;
  (e: 'openOcrSearch'): void;
}>();

/** 【関数の役割】 サイドバーを閉じる（v-model:isOpen → false）。 */
const closeSidebar = () => {
  emit('update:isOpen', false);
};

/** 【関数の役割】 タブを選択して即座にサイドバーを閉じる（モバイル想定）。 */
const selectTab = (tab: string) => {
  emit('update:activeTab', tab);
  closeSidebar();
};

/** 【関数の役割】 アップロードボタン押下時、親にスコア取り込みを通知して閉じる。 */
const handleUploadClick = () => {
  emit('upload');
  closeSidebar();
};

/** 【関数の役割】 カメラ OCR 曲検索ボタン押下時、親に OCR モーダル起動を通知してサイドバーを閉じる。 */
const handleOcrSearchClick = () => {
  emit('openOcrSearch');
  closeSidebar();
};

/**
 * 【関数の役割】 ログイン/ログアウト等のアクション用共通ハンドラ。
 * 型システムをバイパスして emit イベント名を動的に発火するため (emit as any) を使用している。
 */
const handleAction = (event: 'login' | 'logout' | 'editProfile' | 'openAdmin') => {
  (emit as any)(event);
  closeSidebar();
};

const navigationItems = computed(() => [
  { id: 'dashboard', label: t('nav.dashboard'), icon: 'M3 12l2-2m0 0l7-7 7 7M5 10v10a1 1 0 001 1h3m10-11l2 2m-2-2v10a1 1 0 01-1 1h-3m-6 0a1 1 0 001-1v-4a1 1 0 011-1h2a1 1 0 011 1v4a1 1 0 001 1m-6 0h6' },
  { id: 'table', label: t('nav.scoreList'), icon: 'M9 5H7a2 2 0 00-2 2v12a2 2 0 002 2h10a2 2 0 002-2V7a2 2 0 00-2-2h-2M9 5a2 2 0 002 2h2a2 2 0 002-2M9 5a2 2 0 012-2h2a2 2 0 012 2m-3 7h3m-3 4h3m-6-4h.01M9 16h.01' },
  { id: 'profile', label: t('nav.profile'), icon: 'M16 7a4 4 0 11-8 0 4 4 0 018 0zM12 14a7 7 0 00-7 7h14a7 7 0 00-7-7z', requiresAuth: true },
  { id: 'ranking', label: t('nav.ranking'), icon: 'M9 19v-6a2 2 0 00-2-2H5a2 2 0 00-2 2v6a2 2 0 002 2h2a2 2 0 002-2zm0 0V9a2 2 0 012-2h2a2 2 0 012 2v10m-6 0a2 2 0 002 2h2a2 2 0 002-2m0 0V5a2 2 0 012-2h2a2 2 0 012 2v14a2 2 0 01-2 2h-2a2 2 0 01-2-2z' },
  { id: 'friends', label: t('nav.friends'), icon: 'M12 4.354a4 4 0 110 5.292M15 21H3v-1a6 6 0 0112 0v1zm0 0h6v-1a6 6 0 00-9-5.197M13 7a4 4 0 11-8 0 4 4 0 018 0z', requiresAuth: true, hideOnViewing: true },
  { id: 'history', label: t('nav.history'), icon: 'M13 7h8m0 0v8m0-8l-8 8-4-4-6 6', requiresAuth: true, hideOnViewing: true },
  { id: 'arena', label: t('nav.arena'), icon: 'M9 12l2 2 4-4M7.835 4.697a3.42 3.42 0 001.946-.806 3.42 3.42 0 014.438 0 3.42 3.42 0 001.946.806 3.42 3.42 0 013.138 3.138 3.42 3.42 0 00.806 1.946 3.42 3.42 0 010 4.438 3.42 3.42 0 00-.806 1.946 3.42 3.42 0 01-3.138 3.138 3.42 3.42 0 00-1.946.806 3.42 3.42 0 01-4.438 0 3.42 3.42 0 00-1.946-.806 3.42 3.42 0 01-3.138-3.138z', requiresAuth: true, hideOnViewing: true },
  { id: 'arcade-assist', label: t('nav.arcadeAssist'), icon: 'M9 20l-5.447-2.724A1 1 0 013 16.382V5.618a1 1 0 011.447-.894L9 7m0 13l6-3m-6 3V7m6 10l4.553 2.276A1 1 0 0021 18.382V7.618a1 1 0 00-.553-.894L15 4m0 13V4m0 0L9 7', requiresAuth: true, hideOnViewing: true },
  { id: 'tier-voting', label: t('nav.tierVoting'), icon: 'M9 5H7a2 2 0 00-2 2v12a2 2 0 002 2h10a2 2 0 002-2V7a2 2 0 00-2-2h-2M9 5a2 2 0 002 2h2a2 2 0 002-2M9 5a2 2 0 012-2h2a2 2 0 012 2m-6 7l2 2 4-4' },
  { id: 'song-avg', label: t('nav.songAvg'), icon: 'M9 19v-6a2 2 0 00-2-2H5a2 2 0 00-2 2v6a2 2 0 002 2h2a2 2 0 002-2zm0 0V9a2 2 0 012-2h2a2 2 0 012 2v10m-6 0a2 2 0 002 2h2a2 2 0 002-2m0 0V5a2 2 0 012-2h2a2 2 0 012 2v14a2 2 0 01-2 2h-2a2 2 0 01-2-2z' },
  { id: 'diff-table', label: t('nav.diffTable'), icon: 'M4 6h16M4 10h16M4 14h16M4 18h16' },
  { id: 'rank-comparison', label: t('nav.rankComparison'), icon: 'M3 6l3 1m0 0l-3 9a5.002 5.002 0 006.001 0M6 7l3 9M6 7l6-2m6 2l3-1m-3 1l-3 9a5.002 5.002 0 006.001 0M18 7l3 9m-3-9l-6-2m0-2v2m0 16V5m0 16H9m3 0h3', allowedUserIds: [18, 23, 24] },
  { id: 'score-prediction', label: t('nav.scorePrediction'), icon: 'M9 19v-6a2 2 0 00-2-2H5a2 2 0 00-2 2v6a2 2 0 002 2h2a2 2 0 002-2zm0 0V9a2 2 0 012-2h2a2 2 0 012 2v10m-6 0a2 2 0 002 2h2a2 2 0 002-2m0 0V5a2 2 0 012-2h2a2 2 0 012 2v14a2 2 0 01-2 2h-2a2 2 0 01-2-2z', requiresAuth: true, hideOnViewing: true, supporterOnly: true },
]);

/**
 * 【computed の役割】 主要ナビゲーションの下に置く副次メニュー（更新履歴・About・利用規約）。
 * 権限による出し分けは無く全ユーザーに見せる。
 */
const secondaryItems = computed(() => [
  { id: 'changelog', label: t('nav.changelog'), icon: 'M12 8v4l3 3m6-3a9 9 0 11-18 0 9 9 0 0118 0z' },
  { id: 'about', label: t('nav.about'), icon: 'M13 16h-1v-4h-1m1-4h.01M21 12a9 9 0 11-18 0 9 9 0 0118 0z' },
  { id: 'terms', label: t('nav.terms'), icon: 'M9 12h6m-6 4h6m2 5H7a2 2 0 01-2-2V5a2 2 0 012-2h5.586a1 1 0 01.707.293l5.414 5.414a1 1 0 01.293.707V19a2 2 0 01-2 2z' },
]);

/**
 * 【computed の役割】 管理者判定。
 * 判定ロジックは {@link useAdmin} に集約しており、
 * 設定値（`VITE_ADMIN_USER_ID` / `VITE_ADMIN_IIDX_ID`）で上書き可能。
 */
const { isAdmin } = useAdmin();

/**
 * 【computed の役割】 現在のユーザー状態に基づいて navigationItems を絞り込む。
 * - requiresAuth: 未ログインなら除外
 * - hideOnViewing: 他ユーザー閲覧モード中は除外（ただし score-prediction は admin モードでは許可）
 * - allowedUserIds: 指定 ID のユーザーのみ表示
 */
const filteredNavItems = computed(() => {
  return navigationItems.value.filter(item => {
    if (item.requiresAuth && !props.isLoggedIn) return false;
    // admin モード閲覧中は score-prediction だけ例外的に許可（管理者がユーザー挙動確認のため）
    if (item.hideOnViewing && props.viewingUserId) {
      if (item.id === 'score-prediction' && props.viewingMode === 'admin') return true;
      return false;
    }
    if (item.allowedUserIds && (!props.user || !item.allowedUserIds.includes(props.user.id))) return false;
    return true;
  });
});
</script>

<template>
  <div>
    <!-- Backdrop Overlay -->
    <Transition
      enter-active-class="transition-opacity duration-300 ease-out"
      enter-from-class="opacity-0"
      enter-to-class="opacity-100"
      leave-active-class="transition-opacity duration-200 ease-in"
      leave-from-class="opacity-100"
      leave-to-class="opacity-0"
    >
      <div 
        v-if="isOpen" 
        @click="closeSidebar"
        class="fixed inset-0 bg-slate-900/40 backdrop-blur-[2px] z-40 transition-opacity xl:hidden"
      ></div>
    </Transition>

    <!-- Sidebar Panel -->
    <aside 
      class="fixed inset-y-0 left-0 bg-white dark:bg-slate-800 border-r border-slate-200 dark:border-slate-700 shadow-xl transition-all duration-300 ease-in-out flex flex-col z-50 w-72 lg:translate-x-0 lg:shadow-none lg:h-screen lg:z-40"
      :class="[
        isOpen ? 'translate-x-0' : '-translate-x-full lg:translate-x-0'
      ]"
    >
      <div class="h-full flex flex-col">
        <!-- Sidebar Header (Logo) -->
        <div class="p-6 border-b border-slate-100 dark:border-slate-700 flex items-center justify-between">
          <div class="flex items-center gap-2 cursor-pointer group" @click="selectTab('dashboard')">
            <div class="relative w-8 h-8 bg-blue-600 rounded-lg flex items-center justify-center text-white font-bold text-xl shadow-sm group-hover:bg-blue-700 transition-colors overflow-hidden">
              B
              <div 
                class="absolute bg-red-500 text-white text-[9px] font-black py-[2px] w-[46px] text-center transform -rotate-45 shadow-sm leading-none tracking-wider"
                style="bottom: 3px; right: -14px;"
              >
                BETA
              </div>
            </div>
            <span class="text-xl font-extrabold bg-clip-text text-transparent bg-gradient-to-r from-blue-700 to-blue-500 tracking-tight">
              beat-seeker
            </span>
          </div>
          <button @click="closeSidebar" class="p-2 text-slate-400 hover:text-slate-600 dark:hover:text-slate-200 transition-colors lg:hidden">
            <svg xmlns="http://www.w3.org/2000/svg" class="h-6 w-6" fill="none" viewBox="0 0 24 24" stroke="currentColor">
              <path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M6 18L18 6M6 6l12 12" />
            </svg>
          </button>
        </div>

        <!-- Scrollable Content Area -->
        <div class="flex-1 overflow-y-auto py-6 px-4 space-y-8 custom-scrollbar">
          
          <!-- User Profile Section -->
          <div class="px-2">
            <div v-if="authLoading" class="animate-pulse flex items-center gap-3">
              <div class="w-10 h-10 bg-slate-200 dark:bg-slate-700 rounded-full"></div>
              <div class="h-4 w-24 bg-slate-200 dark:bg-slate-700 rounded"></div>
            </div>
            
            <template v-else-if="isLoggedIn">
              <div class="flex flex-col gap-4">
                <div 
                  @click="handleAction('editProfile')"
                  class="flex items-center gap-3 p-2 rounded-xl hover:bg-slate-50 dark:hover:bg-slate-700/50 cursor-pointer transition-all group border border-transparent hover:border-slate-100 dark:hover:border-slate-600"
                >
                  <div class="w-10 h-10 bg-gradient-to-br from-blue-500 to-indigo-600 rounded-full flex items-center justify-center text-white font-bold shadow-md">
                    {{ (user?.displayName || user?.iidxId || 'U').charAt(0) }}
                  </div>
                  <div class="flex-1 min-w-0">
                    <p class="text-sm font-bold text-slate-900 dark:text-white truncate">
                      {{ user?.displayName || user?.iidxId }}
                    </p>
                  </div>
                  <svg xmlns="http://www.w3.org/2000/svg" class="h-4 w-4 text-slate-400 group-hover:text-slate-600 transition-colors" fill="none" viewBox="0 0 24 24" stroke="currentColor">
                    <path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M9 5l7 7-7 7" />
                  </svg>
                </div>
                
                <div class="flex flex-col gap-1">
                  <button
                    v-if="isAdmin && !viewingUserId"
                    @click="selectTab('admin-song-ranks')"
                    class="flex items-center gap-3 px-4 py-2.5 text-sm font-bold text-amber-600 dark:text-amber-400 bg-amber-50 dark:bg-amber-900/30 rounded-xl hover:bg-amber-100 dark:hover:bg-amber-900/50 transition-all border border-amber-100 dark:border-amber-800"
                  >
                    <svg xmlns="http://www.w3.org/2000/svg" class="h-5 w-5" fill="none" viewBox="0 0 24 24" stroke="currentColor">
                      <path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M9 19v-6a2 2 0 00-2-2H5a2 2 0 00-2 2v6a2 2 0 002 2h2a2 2 0 002-2zm0 0V9a2 2 0 012-2h2a2 2 0 012 2v10m-6 0a2 2 0 002 2h2a2 2 0 002-2m0 0V5a2 2 0 012-2h2a2 2 0 012 2v14a2 2 0 01-2 2h-2a2 2 0 01-2-2z" />
                    </svg>
                    {{ t('nav.songRankings') }}
                  </button>
                  <button
                    v-if="isAdmin && !viewingUserId"
                    @click="handleAction('openAdmin')"
                    class="flex items-center gap-3 px-4 py-2.5 text-sm font-bold text-indigo-600 dark:text-indigo-400 bg-indigo-50 dark:bg-indigo-900/30 rounded-xl hover:bg-indigo-100 dark:hover:bg-indigo-900/50 transition-all border border-indigo-100 dark:border-indigo-800"
                  >
                    <svg xmlns="http://www.w3.org/2000/svg" class="h-5 w-5" fill="none" viewBox="0 0 24 24" stroke="currentColor">
                      <path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M15 12a3 3 0 11-6 0 3 3 0 016 0z" />
                      <path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M2.458 12C3.732 7.943 7.523 5 12 5c4.478 0 8.268 2.943 9.542 7-1.274 4.057-5.064 7-9.542 7-4.477 0-8.268-2.943-9.542-7z" />
                    </svg>
                    {{ t('nav.adminPanel') }}
                  </button>
                  <button 
                    @click="handleAction('logout')"
                    class="flex items-center gap-3 px-4 py-2 text-sm font-medium text-slate-500 dark:text-slate-400 hover:text-red-600 dark:hover:text-red-400 transition-colors rounded-xl hover:bg-red-50 dark:hover:bg-red-900/20"
                  >
                    <svg xmlns="http://www.w3.org/2000/svg" class="h-5 w-5" fill="none" viewBox="0 0 24 24" stroke="currentColor">
                      <path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M17 16l4-4m0 0l-4-4m4 4H7m6 4v1a3 3 0 01-3 3H6a3 3 0 01-3-3V7a3 3 0 013-3h4a3 3 0 013 3v1" />
                    </svg>
                    {{ t('nav.logout') }}
                  </button>
                </div>
              </div>
            </template>
            
            <template v-else>
              <button 
                @click="handleAction('login')"
                class="w-full flex items-center justify-center gap-2 px-6 py-3 bg-gradient-to-r from-blue-600 to-indigo-600 text-white font-bold rounded-xl shadow-lg shadow-blue-500/20 hover:shadow-blue-500/40 hover:-translate-y-0.5 transition-all"
              >
                <svg xmlns="http://www.w3.org/2000/svg" class="h-5 w-5" fill="none" viewBox="0 0 24 24" stroke="currentColor">
                  <path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M11 16l-4-4m0 0l4-4m-4 4h14m-5 4v1a3 3 0 01-3 3H6a3 3 0 01-3-3V7a3 3 0 013-3h4a3 3 0 013 3v1" />
                </svg>
                {{ t('nav.loginRegister') }}
              </button>
            </template>
            
            <!-- Upload CSV Button in Sidebar -->
            <div v-if="!viewingUserId" class="flex flex-col gap-1 mt-2">
              <button
                @click="handleUploadClick"
                class="w-full flex items-center justify-center gap-2 px-6 py-3 bg-white dark:bg-slate-700 text-slate-700 dark:text-slate-200 font-bold rounded-xl border border-slate-200 dark:border-slate-600 hover:bg-slate-50 dark:hover:bg-slate-600 transition-all shadow-sm"
              >
                <svg xmlns="http://www.w3.org/2000/svg" class="h-5 w-5" fill="none" viewBox="0 0 24 24" stroke="currentColor">
                  <path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M7 16a4 4 0 01-.88-7.903A5 5 0 1115.9 6L16 6a5 5 0 011 9.9M15 13l-3-3m0 0l-3 3m3-3v12" />
                </svg>
                {{ t('nav.uploadCsv') }}
              </button>
            </div>

            <!--
              カメラ OCR 曲検索: ARENA で降ってきた曲の候補を即特定するためのクイックアクション。
              現在は開発中のため、userID=18 のみに公開（PoC 段階）。
            -->
            <div v-if="user?.id === 18" class="flex flex-col gap-1 mt-2">
              <button
                @click="handleOcrSearchClick"
                class="w-full flex items-center justify-center gap-2 px-6 py-3 bg-gradient-to-r from-fuchsia-500 to-purple-600 text-white font-bold rounded-xl shadow-lg shadow-fuchsia-500/20 hover:shadow-fuchsia-500/40 hover:-translate-y-0.5 transition-all"
              >
                <svg xmlns="http://www.w3.org/2000/svg" class="h-5 w-5" fill="none" viewBox="0 0 24 24" stroke="currentColor">
                  <path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M3 9a2 2 0 012-2h.93a2 2 0 001.664-.89l.812-1.22A2 2 0 0110.07 4h3.86a2 2 0 011.664.89l.812 1.22A2 2 0 0018.07 7H19a2 2 0 012 2v9a2 2 0 01-2 2H5a2 2 0 01-2-2V9z" />
                  <path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M15 13a3 3 0 11-6 0 3 3 0 016 0z" />
                </svg>
                {{ t('nav.ocrSearch') }}
              </button>
            </div>
          </div>

          <!-- Primary Navigation -->
          <nav class="space-y-1">
            <template v-for="item in filteredNavItems" :key="item.id">
              <button
                @click="selectTab(item.id)"
                class="w-full flex items-center gap-3 px-4 py-3 text-sm font-medium rounded-xl transition-all group"
                :class="activeTab === item.id
                  ? 'bg-blue-600 text-white shadow-lg shadow-blue-500/30'
                  : 'text-slate-600 dark:text-slate-400 hover:bg-slate-50 dark:hover:bg-slate-700/50 hover:text-slate-900 dark:hover:text-white'"
              >
                <svg xmlns="http://www.w3.org/2000/svg" class="h-5 w-5 shrink-0" fill="none" viewBox="0 0 24 24" stroke="currentColor">
                  <path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" :d="item.icon" />
                </svg>
                {{ item.label }}
                <span v-if="item.supporterOnly" class="ml-auto text-[8px] font-black uppercase tracking-wider px-1.5 py-0.5 rounded-full bg-amber-100 dark:bg-amber-900/40 text-amber-600 dark:text-amber-400 border border-amber-200 dark:border-amber-700">
                  Supporter
                </span>
              </button>
            </template>
          </nav>

          <!-- Divider -->
          <div class="h-px bg-slate-100 dark:bg-slate-700 mx-2"></div>

          <!-- Secondary Navigation -->
          <div class="space-y-4">
            <h3 class="px-4 text-[10px] font-black text-slate-400 dark:text-slate-500 uppercase tracking-[0.2em]">
              {{ t('app.sidebar.support') }}
            </h3>
            <nav class="space-y-1">
              <template v-for="item in secondaryItems" :key="item.id">
                <button
                  @click="selectTab(item.id)"
                  class="w-full flex items-center gap-3 px-4 py-2.5 text-sm font-medium rounded-xl transition-all"
                  :class="activeTab === item.id 
                    ? 'bg-slate-100 dark:bg-slate-700 text-slate-900 dark:text-white' 
                    : 'text-slate-500 dark:text-slate-400 hover:bg-slate-50 dark:hover:bg-slate-700/50 hover:text-slate-700 dark:hover:text-white'"
                >
                  <svg xmlns="http://www.w3.org/2000/svg" class="h-4 w-4 shrink-0" fill="none" viewBox="0 0 24 24" stroke="currentColor">
                    <path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" :d="item.icon" />
                  </svg>
                  {{ item.label }}
                </button>
              </template>
            </nav>
          </div>

          <!-- Language Switcher -->
          <div class="space-y-4">
            <h3 class="px-4 text-[10px] font-black text-slate-400 dark:text-slate-500 uppercase tracking-[0.2em]">
              {{ t('app.sidebar.language') }}
            </h3>
            <div class="px-2">
              <div class="flex flex-wrap gap-2 p-2 bg-slate-50 dark:bg-slate-900/50 rounded-xl border border-slate-100 dark:border-slate-700">
                <button 
                  v-for="lang in availableLanguages" 
                  :key="lang"
                  @click="setLanguage(lang)"
                  class="flex-1 py-1 px-2 text-[10px] font-bold rounded-lg transition-all"
                  :class="currentLang === lang 
                    ? 'bg-blue-600 text-white shadow-sm' 
                    : 'text-slate-500 hover:text-slate-700 dark:text-slate-400 dark:hover:text-slate-200 hover:bg-white dark:hover:bg-slate-700'"
                >
                  {{ t(`lang.${lang}`) }}
                </button>
              </div>
            </div>
          </div>
        </div>

        <!-- Ko-fi Support -->
        <div class="px-4 pb-3 space-y-2">
          <button
            @click="handleKofiClick"
            class="group flex items-center gap-3 w-full px-4 py-3 bg-gradient-to-r from-amber-50 to-yellow-50 dark:from-amber-900/20 dark:to-yellow-900/20 border border-amber-200 dark:border-amber-800/50 rounded-xl hover:shadow-md hover:shadow-amber-500/10 hover:-translate-y-0.5 transition-all text-left"
          >
            <div class="w-8 h-8 bg-amber-400 rounded-lg flex items-center justify-center shrink-0 group-hover:scale-110 transition-transform">
              <svg xmlns="http://www.w3.org/2000/svg" class="h-4 w-4 text-white" viewBox="0 0 24 24" fill="currentColor">
                <path d="M18.5 3H6C4.9 3 4 3.9 4 5v11c0 1.1.9 2 2 2h1v3l3-3h8.5c1.38 0 2.5-1.12 2.5-2.5v-10C21 4.12 19.88 3 18.5 3zm-3 10.5c-1.93 0-3.5-1.57-3.5-3.5 0-.44.09-.86.23-1.25L11 8h-1V6h2.5l2.11 1.77c.32-.12.66-.27 1.39-.27 1.93 0 3.5 1.57 3.5 3.5s-1.57 3.5-3.5 3.5z"/>
              </svg>
            </div>
            <div class="flex-1 min-w-0">
              <p class="text-xs font-bold text-amber-700 dark:text-amber-400">{{ t('supporter.kofiButton') }}</p>
              <p class="text-[10px] text-amber-500 dark:text-amber-500/70 font-medium">{{ t('supporter.kofiSidebarHint') }}</p>
            </div>
            <svg xmlns="http://www.w3.org/2000/svg" class="h-4 w-4 text-amber-400 shrink-0 group-hover:translate-x-0.5 transition-transform" fill="none" viewBox="0 0 24 24" stroke="currentColor">
              <path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M10 6H6a2 2 0 00-2 2v10a2 2 0 002 2h10a2 2 0 002-2v-4M14 4h6m0 0v6m0-6L10 14" />
            </svg>
          </button>
          <!-- Token hint for logged-in users -->
          <div v-if="isLoggedIn && user?.supporterToken" class="px-1">
            <p v-if="kofiCopied" class="text-[10px] font-bold text-emerald-600 dark:text-emerald-400 text-center">
              {{ t('supporter.tokenCopied') }}
            </p>
            <p v-else class="text-[10px] text-slate-400 dark:text-slate-500 text-center leading-relaxed">
              {{ t('supporter.tokenHint') }}
              <span class="font-mono font-bold text-amber-600 dark:text-amber-400 select-all">{{ user.supporterToken }}</span>
            </p>
          </div>
        </div>

        <!-- Sidebar Footer -->
        <div class="p-6 pt-0 text-center">
          <p class="text-[10px] text-slate-400 dark:text-slate-500 font-mono">
            v1.5.0 | beat-seeker
          </p>
        </div>
      </div>
    </aside>

    <!-- Ko-fi Confirmation Modal -->
    <Teleport to="body">
      <Transition
        enter-active-class="transition-opacity duration-200"
        enter-from-class="opacity-0"
        enter-to-class="opacity-100"
        leave-active-class="transition-opacity duration-150"
        leave-from-class="opacity-100"
        leave-to-class="opacity-0"
      >
        <div v-if="showKofiModal" class="fixed inset-0 z-[100] flex items-center justify-center p-4">
          <div class="absolute inset-0 bg-slate-900/50 backdrop-blur-sm" @click="showKofiModal = false"></div>
          <div class="relative bg-white dark:bg-slate-800 rounded-2xl border border-slate-200 dark:border-slate-700 shadow-2xl max-w-sm w-full p-6 space-y-4">
            <!-- Header -->
            <div class="flex items-center gap-3">
              <div class="w-10 h-10 bg-amber-100 dark:bg-amber-900/30 rounded-xl flex items-center justify-center">
                <svg xmlns="http://www.w3.org/2000/svg" class="h-5 w-5 text-amber-500" fill="none" viewBox="0 0 24 24" stroke="currentColor">
                  <path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M13 16h-1v-4h-1m1-4h.01M21 12a9 9 0 11-18 0 9 9 0 0118 0z" />
                </svg>
              </div>
              <h3 class="text-lg font-bold text-slate-900 dark:text-white">{{ t('supporter.modalTitle') }}</h3>
            </div>

            <!-- Body -->
            <p class="text-sm text-slate-600 dark:text-slate-300 leading-relaxed">
              {{ t('supporter.modalDesc') }}
            </p>

            <!-- Token display -->
            <div class="bg-amber-50 dark:bg-amber-900/20 border border-amber-200 dark:border-amber-800/50 rounded-xl p-3 text-center">
              <p class="text-[10px] font-bold text-amber-600 dark:text-amber-400 uppercase tracking-wider mb-1">{{ t('supporter.modalTokenLabel') }}</p>
              <p class="text-lg font-mono font-black text-amber-700 dark:text-amber-300 select-all">{{ user?.supporterToken }}</p>
            </div>

            <!-- Buttons -->
            <div class="flex gap-2">
              <button
                @click="showKofiModal = false"
                class="flex-1 px-4 py-2.5 text-sm font-bold text-slate-600 dark:text-slate-400 bg-slate-100 dark:bg-slate-700 rounded-xl hover:bg-slate-200 dark:hover:bg-slate-600 transition-colors"
              >
                {{ t('supporter.modalCancel') }}
              </button>
              <button
                @click="confirmKofiOpen"
                class="flex-1 px-4 py-2.5 text-sm font-bold text-white bg-gradient-to-r from-amber-500 to-yellow-500 rounded-xl hover:shadow-lg hover:shadow-amber-500/20 transition-all"
              >
                {{ t('supporter.modalConfirm') }}
              </button>
            </div>
          </div>
        </div>
      </Transition>
    </Teleport>
  </div>
</template>

<style scoped>
.custom-scrollbar::-webkit-scrollbar {
  width: 4px;
}
.custom-scrollbar::-webkit-scrollbar-track {
  background: transparent;
}
.custom-scrollbar::-webkit-scrollbar-thumb {
  background: rgba(148, 163, 184, 0.2);
  border-radius: 20px;
}
.custom-scrollbar:hover::-webkit-scrollbar-thumb {
  background: rgba(148, 163, 184, 0.4);
}
</style>
