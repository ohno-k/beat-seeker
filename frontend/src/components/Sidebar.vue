<script setup lang="ts">
import { computed } from 'vue';
import { useI18n } from '../composables/useI18n';

const { t, currentLang, setLanguage, availableLanguages } = useI18n();

const props = defineProps<{
  isOpen: boolean;
  activeTab: string;
  isLoggedIn: boolean;
  user: any;
  viewingUserId: number | null;
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
}>();

const closeSidebar = () => {
  emit('update:isOpen', false);
};

const selectTab = (tab: string) => {
  emit('update:activeTab', tab);
  closeSidebar();
};

const handleUploadClick = () => {
  emit('upload');
  closeSidebar();
};

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
  { id: 'tier-voting', label: t('nav.tierVoting'), icon: 'M9 5H7a2 2 0 00-2 2v12a2 2 0 002 2h10a2 2 0 002-2V7a2 2 0 00-2-2h-2M9 5a2 2 0 002 2h2a2 2 0 002-2M9 5a2 2 0 012-2h2a2 2 0 012 2m-6 7l2 2 4-4' },
]);

const secondaryItems = computed(() => [
  { id: 'changelog', label: t('nav.changelog'), icon: 'M12 8v4l3 3m6-3a9 9 0 11-18 0 9 9 0 0118 0z' },
  { id: 'about', label: t('nav.about'), icon: 'M13 16h-1v-4h-1m1-4h.01M21 12a9 9 0 11-18 0 9 9 0 0118 0z' },
  { id: 'terms', label: t('nav.terms'), icon: 'M9 12h6m-6 4h6m2 5H7a2 2 0 01-2-2V5a2 2 0 012-2h5.586a1 1 0 01.707.293l5.414 5.414a1 1 0 01.293.707V19a2 2 0 01-2 2z' },
]);

const isAdmin = computed(() => {
  return props.user?.id == 18 || props.user?.iidxId === '5787-1145';
});

const filteredNavItems = computed(() => {
  return navigationItems.value.filter(item => {
    if (item.requiresAuth && !props.isLoggedIn) return false;
    if (item.hideOnViewing && props.viewingUserId) return false;
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

        <!-- Sidebar Footer (Version/Socials maybe?) -->
        <div class="p-6 text-center">
          <p class="text-[10px] text-slate-400 dark:text-slate-500 font-mono">
            v1.3.1 | beat-seeker
          </p>
        </div>
      </div>
    </aside>
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
