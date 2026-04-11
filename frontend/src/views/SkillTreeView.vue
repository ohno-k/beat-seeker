<script setup lang="ts">
import { ref, computed, onMounted } from 'vue';
import { useAuth, API_BASE } from '../composables/useAuth';
import { useI18n } from '../composables/useI18n';

const { t } = useI18n();
const { isLoggedIn, authHeaders } = useAuth();

interface ChartNode {
  textage: string; title: string; artist: string;
  level: number; difficulty: string; notes: number;
  bpmMain: number; bpmRaw: string; isSoflan: boolean;
  scratchPct: number; chordPct: number; cnNotes: number | null;
  dominantEff16: number;
  similarityToPrev: number; connectionReason: string;
}
interface SkillChain {
  categoryId: string; categoryLabel: string; categoryDescription: string;
  totalInCategory: number; isIndependent: boolean; nodes: ChartNode[];
}
interface UserProgress { bestClear: string; clearRank: number; missCount: number | null; }
interface TreeData { chains: SkillChain[]; userProgress: Record<string, UserProgress>; }

const loading = ref(true);
const error = ref('');
const data = ref<TreeData | null>(null);
const selectedNode = ref<string | null>(null);
const showIndependent = ref(false);

const multiChains = computed(() => data.value?.chains.filter(c => !c.isIndependent) || []);
const independentCharts = computed(() => data.value?.chains.filter(c => c.isIndependent) || []);

/** チェーン末端レベルに応じた色 */
function chainColor(chain: SkillChain) {
  const last = chain.nodes[chain.nodes.length - 1];
  return levelColor(last?.level || 0);
}
function levelColor(lv: number) {
  if (lv <= 0) return { badge: 'bg-slate-600', line: 'bg-slate-400', text: 'text-slate-400' };
  if (lv <= 3) return { badge: 'bg-emerald-600', line: 'bg-emerald-400', text: 'text-emerald-400' };
  if (lv <= 6) return { badge: 'bg-sky-600', line: 'bg-sky-400', text: 'text-sky-400' };
  if (lv <= 9) return { badge: 'bg-amber-600', line: 'bg-amber-400', text: 'text-amber-400' };
  return { badge: 'bg-rose-600', line: 'bg-rose-400', text: 'text-rose-400' };
}

const clearColors: Record<string, string> = {
  'FULLCOMBO CLEAR': 'text-amber-400', 'EX HARD CLEAR': 'text-yellow-300',
  'HARD CLEAR': 'text-red-400', 'CLEAR': 'text-blue-400',
  'EASY CLEAR': 'text-green-400', 'ASSIST CLEAR': 'text-purple-400', 'FAILED': 'text-slate-500',
};
const clearBorders: Record<string, string> = {
  'FULLCOMBO CLEAR': 'border-amber-400/60', 'EX HARD CLEAR': 'border-yellow-300/50',
  'HARD CLEAR': 'border-red-400/40', 'CLEAR': 'border-blue-400/30',
  'EASY CLEAR': 'border-green-400/30', 'ASSIST CLEAR': 'border-purple-400/20', 'FAILED': 'border-slate-500/20',
};
const diffLabel = (d: string) => ({ '4': 'A', '10': 'L', '3': 'H', '2': 'N', '1': 'B' }[d] || d);
function clearLabel(c: string) { return c.replace(' CLEAR', '').replace('FULLCOMBO', 'FC'); }
function prog(t: string): UserProgress | null { return data.value?.userProgress?.[t] || null; }

async function fetchData() {
  loading.value = true; error.value = '';
  try {
    const res = await fetch(`${API_BASE}/api/analysis/skill-tree`, { headers: authHeaders() });
    if (!res.ok) throw new Error(`HTTP ${res.status}`);
    data.value = await res.json();
  } catch (e: any) { error.value = e.message; } finally { loading.value = false; }
}
onMounted(fetchData);

function currentPos(chain: SkillChain): number {
  let last = -1;
  for (let i = 0; i < chain.nodes.length; i++) {
    const p = prog(chain.nodes[i].textage);
    if (p && p.clearRank >= 2) last = i;
  }
  return last;
}
</script>

<template>
  <div class="w-full max-w-5xl mx-auto">
    <!-- ヘッダー -->
    <div class="mb-6 flex items-center gap-3">
      <div class="w-10 h-10 bg-gradient-to-br from-indigo-500 to-purple-600 rounded-xl flex items-center justify-center shadow-lg shadow-indigo-500/30">
        <svg xmlns="http://www.w3.org/2000/svg" class="h-6 w-6 text-white" fill="none" viewBox="0 0 24 24" stroke="currentColor" stroke-width="2">
          <path stroke-linecap="round" stroke-linejoin="round" d="M13 10V3L4 14h7v7l9-11h-7z" />
        </svg>
      </div>
      <div>
        <h1 class="text-2xl font-black text-slate-900 dark:text-white tracking-tight">SKILL TREE</h1>
        <p class="text-sm text-slate-500 dark:text-slate-400">{{ t('skillTree.subtitle') }}</p>
      </div>
      <!-- 統計 -->
      <div v-if="data" class="ml-auto flex gap-3 text-xs text-slate-400 dark:text-slate-500">
        <span>{{ multiChains.length }} チェーン</span>
        <span>{{ independentCharts.length }} 独立</span>
      </div>
    </div>

    <div v-if="loading" class="flex flex-col items-center py-20">
      <div class="w-12 h-12 border-4 border-indigo-200 dark:border-indigo-900 border-t-indigo-600 rounded-full animate-spin mb-4"></div>
      <p class="text-slate-500 dark:text-slate-400 font-medium">スキルツリーを生成中...</p>
    </div>

    <div v-else-if="error" class="bg-red-50 dark:bg-red-900/30 border border-red-200 dark:border-red-800 rounded-xl p-6 text-center">
      <p class="text-red-600 dark:text-red-400 font-medium">{{ error }}</p>
      <button @click="fetchData" class="mt-3 px-4 py-2 bg-red-600 text-white rounded-lg text-sm font-bold">リトライ</button>
    </div>

    <div v-else-if="data" class="space-y-8">

      <!-- ===== チェーン一覧 ===== -->
      <section v-for="chain in multiChains" :key="chain.categoryId">
        <!-- セクションヘッダー: 到達先の譜面名 -->
        <div class="flex items-center gap-3 mb-3">
          <div class="w-8 h-8 rounded-lg flex items-center justify-center text-white text-[10px] font-black leading-none"
               :class="chainColor(chain).badge">
            {{ chain.categoryDescription }}
          </div>
          <div class="flex-1 min-w-0">
            <h2 class="text-base font-black text-slate-900 dark:text-white truncate">{{ chain.categoryLabel }}</h2>
            <p class="text-[11px] text-slate-400 dark:text-slate-500">{{ chain.totalInCategory }} ステップ</p>
          </div>
        </div>

        <!-- チェーン本体 -->
        <div class="relative pl-6">
          <div class="absolute left-[15px] top-0 bottom-0 w-0.5 rounded-full" :class="chainColor(chain).line" style="opacity:0.25"></div>

          <div v-for="(node, idx) in chain.nodes" :key="node.textage" class="relative flex items-start gap-3 mb-1">
            <!-- ドット -->
            <div class="absolute -left-6 top-2.5 z-10">
              <div
                class="w-3 h-3 rounded-full border-2"
                :class="prog(node.textage)?.clearRank >= 2
                  ? `${chainColor(chain).badge} border-transparent`
                  : idx === currentPos(chain) + 1
                    ? 'bg-indigo-500 border-transparent animate-pulse'
                    : 'bg-white dark:bg-slate-800 border-slate-300 dark:border-slate-600'"
              ></div>
            </div>

            <!-- カード -->
            <div
              class="flex-1 flex items-center gap-2 px-3 py-1.5 rounded-lg border transition-all cursor-pointer hover:shadow-sm text-sm"
              :class="[
                prog(node.textage)?.clearRank >= 2
                  ? `bg-white dark:bg-slate-800/80 ${clearBorders[prog(node.textage)!.bestClear] || 'border-slate-200 dark:border-slate-700'} border-l-2`
                  : idx === currentPos(chain) + 1
                    ? 'bg-indigo-50 dark:bg-indigo-950/30 border-indigo-300 dark:border-indigo-700 border-l-2'
                    : 'bg-white/60 dark:bg-slate-800/40 border-slate-200/60 dark:border-slate-700/40'
              ]"
              @click="selectedNode = selectedNode === node.textage ? null : node.textage"
            >
              <span class="text-[11px] font-black text-slate-400 dark:text-slate-500 w-7 flex-shrink-0">
                {{ diffLabel(node.difficulty) }}{{ node.level || '?' }}
              </span>
              <span class="font-medium text-slate-800 dark:text-slate-200 truncate flex-1 text-xs">{{ node.title }}</span>
              <span class="text-[10px] text-slate-400 dark:text-slate-500 flex-shrink-0">{{ node.notes }}n</span>
              <span v-if="prog(node.textage)" class="text-[10px] font-black flex-shrink-0 w-12 text-right" :class="clearColors[prog(node.textage)!.bestClear]">
                {{ clearLabel(prog(node.textage)!.bestClear) }}
              </span>
              <span v-else class="text-[10px] text-slate-400 dark:text-slate-600 w-12 text-right flex-shrink-0">—</span>
              <span v-if="idx === currentPos(chain) + 1 && isLoggedIn" class="px-1.5 py-0.5 bg-indigo-500 text-white text-[9px] font-black rounded uppercase flex-shrink-0">
                NEXT
              </span>
            </div>
          </div>
        </div>

        <!-- 展開された詳細 -->
        <template v-for="node in chain.nodes" :key="'detail-' + node.textage">
          <div v-if="selectedNode === node.textage" class="ml-6 mt-1 mb-3 p-3 bg-slate-50 dark:bg-slate-700/40 rounded-xl border border-slate-200 dark:border-slate-700 text-xs">
            <div class="flex items-center gap-2 mb-2">
              <span class="font-black text-slate-800 dark:text-white text-sm">{{ node.title }}</span>
              <span class="text-slate-400">{{ node.artist }}</span>
            </div>
            <div class="flex flex-wrap gap-2 mb-2">
              <span class="px-2 py-0.5 bg-slate-200 dark:bg-slate-600 rounded">BPM {{ node.bpmRaw || node.bpmMain }}</span>
              <span class="px-2 py-0.5 bg-slate-200 dark:bg-slate-600 rounded">{{ node.notes }} notes</span>
              <span v-if="node.scratchPct > 0" class="px-2 py-0.5 rounded" :class="node.scratchPct > 12 ? 'bg-red-100 dark:bg-red-900/40 text-red-600 dark:text-red-400' : 'bg-slate-200 dark:bg-slate-600'">
                皿 {{ node.scratchPct.toFixed(1) }}%
              </span>
              <span v-if="node.chordPct > 0" class="px-2 py-0.5 rounded" :class="node.chordPct > 55 ? 'bg-blue-100 dark:bg-blue-900/40 text-blue-600 dark:text-blue-400' : 'bg-slate-200 dark:bg-slate-600'">
                同時 {{ node.chordPct.toFixed(1) }}%
              </span>
              <span v-if="node.cnNotes" class="px-2 py-0.5 bg-yellow-100 dark:bg-yellow-900/40 rounded text-yellow-700 dark:text-yellow-400">CN {{ node.cnNotes }}</span>
              <span v-if="node.isSoflan" class="px-2 py-0.5 bg-orange-100 dark:bg-orange-900/40 rounded text-orange-700 dark:text-orange-400">ソフラン</span>
            </div>
            <div v-if="node.similarityToPrev > 0" class="text-[11px] italic" :class="chainColor(chain).text">
              前の譜面と {{ Math.round(node.similarityToPrev * 100) }}% 類似 — {{ node.connectionReason }}
            </div>
          </div>
        </template>
      </section>

      <!-- ===== 独立譜面セクション ===== -->
      <section v-if="independentCharts.length > 0">
        <button
          @click="showIndependent = !showIndependent"
          class="flex items-center gap-2 mb-3 text-sm font-bold text-slate-500 dark:text-slate-400 hover:text-slate-700 dark:hover:text-slate-300 transition-colors"
        >
          <svg xmlns="http://www.w3.org/2000/svg" class="h-4 w-4 transition-transform" :class="showIndependent ? 'rotate-90' : ''" fill="none" viewBox="0 0 24 24" stroke="currentColor" stroke-width="2">
            <path stroke-linecap="round" stroke-linejoin="round" d="M9 5l7 7-7 7" />
          </svg>
          独立譜面（{{ independentCharts.length }}）
        </button>

        <div v-if="showIndependent" class="grid grid-cols-1 sm:grid-cols-2 gap-1.5">
          <div
            v-for="chain in independentCharts"
            :key="chain.categoryId"
            class="flex items-center gap-2 px-3 py-1.5 rounded-lg border bg-white/60 dark:bg-slate-800/40 border-slate-200/60 dark:border-slate-700/40 text-sm cursor-pointer hover:shadow-sm transition-all"
            @click="selectedNode = selectedNode === chain.nodes[0].textage ? null : chain.nodes[0].textage"
          >
            <span class="text-[10px] font-black px-1 py-0.5 rounded text-white" :class="levelColor(chain.nodes[0].level).badge">
              {{ diffLabel(chain.nodes[0].difficulty) }}{{ chain.nodes[0].level || '?' }}
            </span>
            <span class="font-medium text-slate-800 dark:text-slate-200 truncate flex-1 text-xs">{{ chain.nodes[0].title }}</span>
            <span class="text-[10px] text-slate-400 dark:text-slate-500 flex-shrink-0">{{ chain.nodes[0].notes }}n</span>
            <span v-if="prog(chain.nodes[0].textage)" class="text-[10px] font-black flex-shrink-0" :class="clearColors[prog(chain.nodes[0].textage)!.bestClear]">
              {{ clearLabel(prog(chain.nodes[0].textage)!.bestClear) }}
            </span>
          </div>
        </div>

        <!-- 独立譜面の詳細展開 -->
        <template v-for="chain in independentCharts" :key="'ind-detail-' + chain.categoryId">
          <div v-if="selectedNode === chain.nodes[0].textage" class="mt-1 mb-3 p-3 bg-slate-50 dark:bg-slate-700/40 rounded-xl border border-slate-200 dark:border-slate-700 text-xs">
            <div class="flex items-center gap-2 mb-2">
              <span class="font-black text-slate-800 dark:text-white text-sm">{{ chain.nodes[0].title }}</span>
              <span class="text-slate-400">{{ chain.nodes[0].artist }}</span>
            </div>
            <div class="flex flex-wrap gap-2">
              <span class="px-2 py-0.5 bg-slate-200 dark:bg-slate-600 rounded">BPM {{ chain.nodes[0].bpmRaw || chain.nodes[0].bpmMain }}</span>
              <span class="px-2 py-0.5 bg-slate-200 dark:bg-slate-600 rounded">{{ chain.nodes[0].notes }} notes</span>
              <span v-if="chain.nodes[0].scratchPct > 0" class="px-2 py-0.5 bg-slate-200 dark:bg-slate-600 rounded">皿 {{ chain.nodes[0].scratchPct.toFixed(1) }}%</span>
              <span v-if="chain.nodes[0].chordPct > 0" class="px-2 py-0.5 bg-slate-200 dark:bg-slate-600 rounded">同時 {{ chain.nodes[0].chordPct.toFixed(1) }}%</span>
              <span v-if="chain.nodes[0].cnNotes" class="px-2 py-0.5 bg-yellow-100 dark:bg-yellow-900/40 rounded text-yellow-700 dark:text-yellow-400">CN {{ chain.nodes[0].cnNotes }}</span>
              <span v-if="chain.nodes[0].isSoflan" class="px-2 py-0.5 bg-orange-100 dark:bg-orange-900/40 rounded text-orange-700 dark:text-orange-400">ソフラン</span>
            </div>
          </div>
        </template>
      </section>

    </div>
  </div>
</template>
