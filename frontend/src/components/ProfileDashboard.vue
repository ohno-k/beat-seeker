<template>
  <div class="w-full space-y-6 animate-fade-in">
    <div class="bg-white dark:bg-slate-800 p-6 rounded-2xl shadow-sm border border-slate-200 dark:border-slate-700 transition-colors duration-200">
      <h2 class="text-2xl font-bold text-slate-800 dark:text-slate-100 mb-2">プロフィール・成長軌跡</h2>
      <p class="text-slate-500 dark:text-slate-400 mb-6">過去のアップロード記録から、あなたの成長の軌跡を可視化します。</p>

      <div v-if="isLoading" class="flex flex-col items-center justify-center py-12">
        <div class="w-10 h-10 border-4 border-blue-200 dark:border-blue-900 border-t-blue-600 dark:border-t-blue-500 rounded-full animate-spin mb-4"></div>
        <p class="text-slate-500 dark:text-slate-400 font-medium">履歴データを読み込み中...</p>
      </div>
      
      <div v-else-if="historyData.length === 0" class="py-12 text-center border-2 border-dashed border-slate-200 dark:border-slate-700 rounded-xl">
        <p class="text-slate-500 dark:text-slate-400 font-medium">履歴データがありません。<br/>スコアを複数回アップロードすると、ここに成長グラフが表示されます。</p>
      </div>

      <div v-else class="space-y-8">
        <!-- Stats Summary -->
        <div class="grid grid-cols-2 md:grid-cols-4 gap-4">
          <div class="bg-blue-50/50 dark:bg-slate-700/50 border-blue-100 dark:border-slate-600 p-4 rounded-xl border flex flex-col items-center transition-colors duration-200">
            <span class="text-[10px] font-bold text-blue-500 dark:text-blue-400 uppercase tracking-widest mb-1">スナップショット数</span>
            <span class="text-2xl font-black text-slate-700 dark:text-slate-200">{{ historyData.length }}</span>
          </div>
          <div class="bg-amber-50/50 dark:bg-slate-700/50 border-amber-100 dark:border-slate-600 p-4 rounded-xl border flex flex-col items-center transition-colors duration-200">
            <span class="text-[10px] font-bold text-amber-500 dark:text-amber-400 uppercase tracking-widest mb-1">最新の累計EXスコア</span>
            <span class="text-2xl font-black text-slate-700 dark:text-slate-200">{{ latestTotalScore.toLocaleString() }}</span>
          </div>
          <div class="bg-emerald-50/50 dark:bg-slate-700/50 border-emerald-100 dark:border-slate-600 p-4 rounded-xl border flex flex-col items-center transition-colors duration-200">
            <span class="text-[10px] font-bold text-emerald-500 dark:text-emerald-400 uppercase tracking-widest mb-1">最新のAAA取得数</span>
            <span class="text-2xl font-black text-slate-700 dark:text-slate-200">{{ latestAaaCount }}</span>
          </div>
          <div class="bg-purple-50/50 dark:bg-slate-700/50 border-purple-100 dark:border-slate-600 p-4 rounded-xl border flex flex-col items-center transition-colors duration-200">
            <span class="text-[10px] font-bold text-purple-500 dark:text-purple-400 uppercase tracking-widest mb-1">最新のFC数</span>
            <span class="text-2xl font-black text-slate-700 dark:text-slate-200">{{ latestFcCount }}</span>
          </div>
        </div>

        <!-- Charts -->
        <div class="grid grid-cols-1 lg:grid-cols-2 gap-6 mt-6">
          <div class="bg-white dark:bg-slate-800 p-4 rounded-2xl border border-slate-200 dark:border-slate-700 shadow-sm transition-colors duration-200">
            <h3 class="font-bold text-slate-800 dark:text-slate-100 mb-4">累計EXスコアの推移</h3>
            <div class="h-64">
              <LineChart v-if="scoreChartData" :data="scoreChartData" :options="lineOptionsObj" />
            </div>
          </div>
          
          <div class="bg-white dark:bg-slate-800 p-4 rounded-2xl border border-slate-200 dark:border-slate-700 shadow-sm transition-colors duration-200">
            <h3 class="font-bold text-slate-800 dark:text-slate-100 mb-4">DJレベル取得数の推移</h3>
            <div class="h-64">
              <LineChart v-if="djLevelChartData" :data="djLevelChartData" :options="lineOptionsObj" />
            </div>
          </div>

          <div class="bg-white dark:bg-slate-800 p-4 rounded-2xl border border-slate-200 dark:border-slate-700 shadow-sm lg:col-span-2 transition-colors duration-200">
            <h3 class="font-bold text-slate-800 dark:text-slate-100 mb-4">上位クリアタイプの推移</h3>
            <div class="h-64">
              <LineChart v-if="clearChartData" :data="clearChartData" :options="lineOptionsObj" />
            </div>
          </div>
        </div>
      </div>
    </div>
  </div>
</template>

<script setup lang="ts">
import { ref, onMounted, computed } from 'vue';
import { Chart as ChartJS, CategoryScale, LinearScale, PointElement, LineElement, Title, Tooltip, Legend } from 'chart.js';
import { Line as LineChart } from 'vue-chartjs';
import { useDarkMode } from '../composables/useDarkMode';
import { useAuth } from '../composables/useAuth';

const { isDarkMode } = useDarkMode();
const { authHeaders } = useAuth();

ChartJS.register(CategoryScale, LinearScale, PointElement, LineElement, Title, Tooltip, Legend);

interface HistoryRecord {
  snapshotId: string;
  date: string;
  totalScore: number;
  fcCount: number;
  exhCount: number;
  hCount: number;
  clearCount: number;
  easyCount: number;
  aaaCount: number;
  aaCount: number;
  aCount: number;
}

const API_BASE = import.meta.env.VITE_API_BASE ?? 'http://localhost:8080';

const isLoading = ref(true);
const historyData = ref<HistoryRecord[]>([]);

onMounted(async () => {
    try {
        const res = await fetch(`${API_BASE}/api/scores/history`, {
            headers: authHeaders()
        });
        if (res.ok) {
            historyData.value = await res.json();
            // Ensure chronological order
            historyData.value.sort((a, b) => new Date(a.date).getTime() - new Date(b.date).getTime());
        }
    } catch (e) {
        console.error("Failed to load history", e);
    } finally {
        isLoading.value = false;
    }
});

const latestRecord = computed(() => {
    if (historyData.value.length === 0) return null;
    return historyData.value[historyData.value.length - 1];
});

const latestTotalScore = computed(() => latestRecord.value?.totalScore || 0);
const latestAaaCount = computed(() => latestRecord.value?.aaaCount || 0);
const latestFcCount = computed(() => latestRecord.value?.fcCount || 0);

const labels = computed(() => {
    return historyData.value.map(record => {
        const d = new Date(record.date);
        return `${d.getMonth() + 1}/${d.getDate()}`;
    });
});

const scoreChartData = computed(() => {
    if (historyData.value.length === 0) return null;
    return {
        labels: labels.value,
        datasets: [
            {
                label: '累計EXスコア',
                data: historyData.value.map(r => r.totalScore),
                borderColor: '#3b82f6',
                backgroundColor: 'rgba(59, 130, 246, 0.1)',
                fill: true,
                tension: 0.3,
                pointRadius: 4,
                pointBackgroundColor: '#3b82f6'
            }
        ]
    };
});

const djLevelChartData = computed(() => {
    if (historyData.value.length === 0) return null;
    return {
        labels: labels.value,
        datasets: [
            {
                label: 'AAA',
                data: historyData.value.map(r => r.aaaCount),
                borderColor: '#fbbf24',
                backgroundColor: '#fbbf24',
                tension: 0.3,
            },
            {
                label: 'AA',
                data: historyData.value.map(r => r.aaCount),
                borderColor: '#94a3b8',
                backgroundColor: '#94a3b8',
                tension: 0.3,
            },
            {
                label: 'A',
                data: historyData.value.map(r => r.aCount),
                borderColor: '#22c55e',
                backgroundColor: '#22c55e',
                tension: 0.3,
            }
        ]
    };
});

const clearChartData = computed(() => {
    if (historyData.value.length === 0) return null;
    return {
        labels: labels.value,
        datasets: [
            {
                label: 'FULLCOMBO CLEAR',
                data: historyData.value.map(r => r.fcCount),
                borderColor: '#10b981',
                backgroundColor: '#10b981',
                tension: 0.3,
            },
            {
                label: 'EX HARD CLEAR',
                data: historyData.value.map(r => r.exhCount),
                borderColor: '#f59e0b',
                backgroundColor: '#f59e0b',
                tension: 0.3,
            },
            {
                label: 'HARD CLEAR',
                data: historyData.value.map(r => r.hCount),
                borderColor: '#ef4444',
                backgroundColor: '#ef4444',
                tension: 0.3,
            }
        ]
    };
});

const lineOptionsObj = computed(() => {
  return {
    responsive: true,
    maintainAspectRatio: false,
    plugins: {
      legend: {
        position: 'top' as const,
        labels: {
            usePointStyle: true,
            font: { family: "'Inter', sans-serif" },
            color: isDarkMode.value ? '#cbd5e1' : '#475569'
        }
      },
      tooltip: {
          mode: 'index' as const,
          intersect: false,
          backgroundColor: isDarkMode.value ? 'rgba(15, 23, 42, 0.9)' : 'rgba(255, 255, 255, 0.9)',
          titleColor: isDarkMode.value ? '#f8fafc' : '#0f172a',
          bodyColor: isDarkMode.value ? '#cbd5e1' : '#334155',
          borderColor: isDarkMode.value ? '#334155' : '#e2e8f0',
          borderWidth: 1
      }
    },
    interaction: {
        mode: 'nearest' as const,
        axis: 'x' as const,
        intersect: false
    },
    scales: {
      x: {
        ticks: { color: isDarkMode.value ? '#94a3b8' : '#64748b' },
        grid: { color: isDarkMode.value ? '#334155' : '#f1f5f9' }
      },
      y: {
        ticks: { color: isDarkMode.value ? '#94a3b8' : '#64748b' },
        grid: { color: isDarkMode.value ? '#334155' : '#f1f5f9' }
      }
    }
  };
});
</script>

<style scoped>
.animate-fade-in {
  animation: fadeIn 0.4s ease-out forwards;
}

@keyframes fadeIn {
  from { opacity: 0; transform: translateY(10px); }
  to { opacity: 1; transform: translateY(0); }
}
</style>
