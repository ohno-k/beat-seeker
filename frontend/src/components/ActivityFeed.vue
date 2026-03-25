<script setup lang="ts">
import { ref, onMounted } from 'vue';
import { useI18n } from '../composables/useI18n';
import { API_BASE } from '../composables/useAuth';

interface ActivityItem {
  id: number;
  type: string;
  displayName: string;
  oldValue: string;
  newValue: string;
  createdAt: string;
}

const { t, currentLang } = useI18n();
const activities = ref<ActivityItem[]>([]);
const isLoading = ref(false);

const fetchFeed = async () => {
  isLoading.value = true;
  try {
    const res = await fetch(`${API_BASE}/api/activity/feed`);
    if (res.ok) {
      activities.value = await res.json();
    }
  } catch (e) {
    console.warn('Failed to fetch activity feed:', e);
  } finally {
    isLoading.value = false;
  }
};

const formatDate = (isoStr: string) => {
  const d = new Date(isoStr);
  const now = new Date();
  const diffMs = now.getTime() - d.getTime();
  const diffMin = Math.floor(diffMs / 60000);
  if (diffMin < 1) return t('activity.justNow');
  if (diffMin < 60) return t('activity.minutesAgo', { n: diffMin });
  const diffH = Math.floor(diffMin / 60);
  if (diffH < 24) return t('activity.hoursAgo', { n: diffH });
  const diffD = Math.floor(diffH / 24);
  if (diffD < 7) return t('activity.daysAgo', { n: diffD });
  return d.toLocaleDateString(currentLang.value === 'ko' ? 'ko-KR' : currentLang.value === 'en' ? 'en-US' : 'ja-JP', { month: 'short', day: 'numeric' });
};

onMounted(fetchFeed);
</script>

<template>
  <div class="bg-white dark:bg-slate-800 rounded-2xl shadow-sm border border-slate-200 dark:border-slate-700 overflow-hidden transition-colors duration-200">
    <div class="px-5 py-4 border-b border-slate-100 dark:border-slate-700 flex items-center justify-between">
      <div class="flex items-center gap-2">
        <svg xmlns="http://www.w3.org/2000/svg" class="h-4 w-4 text-blue-500" fill="none" viewBox="0 0 24 24" stroke="currentColor" stroke-width="2">
          <path stroke-linecap="round" stroke-linejoin="round" d="M13 10V3L4 14h7v7l9-11h-7z" />
        </svg>
        <h3 class="text-sm font-black text-slate-700 dark:text-slate-200 uppercase tracking-widest">{{ t('activity.title') }}</h3>
      </div>
      <button @click="fetchFeed" class="p-1 text-slate-400 hover:text-slate-600 dark:hover:text-slate-200 transition-colors rounded">
        <svg xmlns="http://www.w3.org/2000/svg" class="h-4 w-4" :class="{ 'animate-spin': isLoading }" fill="none" viewBox="0 0 24 24" stroke="currentColor" stroke-width="2">
          <path stroke-linecap="round" stroke-linejoin="round" d="M4 4v5h.582m15.356 2A8.001 8.001 0 004.582 9m0 0H9m11 11v-5h-.581m0 0a8.003 8.003 0 01-15.357-2m15.357 2H15" />
        </svg>
      </button>
    </div>

    <div class="divide-y divide-slate-50 dark:divide-slate-700/50">
      <div v-if="isLoading" class="px-5 py-6 text-center">
        <div class="w-5 h-5 border-2 border-blue-200 border-t-blue-500 rounded-full animate-spin mx-auto"></div>
      </div>

      <div v-else-if="activities.length === 0" class="px-5 py-8 text-center">
        <p class="text-xs text-slate-400 dark:text-slate-500 font-bold">{{ t('activity.noNews') }}</p>
      </div>

      <div v-else v-for="item in activities" :key="item.id" class="px-5 py-3.5 flex items-start gap-3 hover:bg-slate-50/50 dark:hover:bg-slate-700/20 transition-colors">
        <!-- Icon -->
        <div class="shrink-0 w-7 h-7 rounded-full flex items-center justify-center mt-0.5"
             :class="item.type === 'RANK_UP' ? 'bg-amber-100 dark:bg-amber-900/30' : 'bg-blue-100 dark:bg-blue-900/30'">
          <svg v-if="item.type === 'RANK_UP'" xmlns="http://www.w3.org/2000/svg" class="h-3.5 w-3.5 text-amber-500 dark:text-amber-400" viewBox="0 0 20 20" fill="currentColor">
            <path fill-rule="evenodd" d="M5.293 7.707a1 1 0 010-1.414l4-4a1 1 0 011.414 0l4 4a1 1 0 01-1.414 1.414L11 5.414V17a1 1 0 11-2 0V5.414L6.707 7.707a1 1 0 01-1.414 0z" clip-rule="evenodd" />
          </svg>
        </div>

        <!-- Content -->
        <div class="flex-1 min-w-0">
          <p class="text-xs text-slate-700 dark:text-slate-300 leading-relaxed">
            <template v-if="item.type === 'RANK_UP'">
              {{ t('activity.rankUpMsg', { user: item.displayName, old: item.oldValue, new: item.newValue }) }}
            </template>
            <template v-else>
               <span class="font-bold text-slate-900 dark:text-white">{{ item.displayName }}</span>: {{ item.newValue }}
            </template>
          </p>
          <p class="text-[10px] text-slate-400 dark:text-slate-500 mt-0.5 font-medium">{{ formatDate(item.createdAt) }}</p>
        </div>
      </div>
    </div>
  </div>
</template>
