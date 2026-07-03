<script setup lang="ts">
/**
 * 【Viewの役割】 スキルツリー可視化ページ。
 *
 * 機能:
 *  - API `/api/analysis/skill-tree` から譜面チェーン（類似度で繋げた段位ラダー）を取得する。
 *  - チェーンを階段状に描画し、ユーザーのクリア状況（bestClear）で点の色を変える。
 *  - チェーンに属さない独立譜面は折りたたみセクションで別途表示。
 *  - ノードクリックで詳細（BPM／ノーツ／皿・同時押し率など）を展開表示。
 *  - ユーザーの現在地を「NEXT」バッジで強調（クリアした最後のノードの次が推奨譜面）。
 *
 * 依存:
 *  - `useAuth` — API 認証ヘッダ。
 *  - `useI18n` — 多言語。
 */
import { ref, computed, onMounted } from 'vue';
import { useAuth, API_BASE } from '../composables/useAuth';
import { useI18n } from '../composables/useI18n';

const { t } = useI18n();
const { isLoggedIn, authHeaders } = useAuth();

/** ツリー上のノード（譜面1つぶん）。BPM／ノーツ／特性指標などを含む */
interface ChartNode {
  textage: string; title: string; artist: string;
  level: number; difficulty: string; notes: number;
  bpmMain: number; bpmRaw: string; isSoflan: boolean;
  scratchPct: number; chordPct: number; cnNotes: number | null;
  dominantEff16: number;
  similarityToPrev: number; connectionReason: string;
}
/** 譜面チェーン（類似譜面を繋げた連鎖 or 独立1譜面）のデータ構造 */
interface SkillChain {
  categoryId: string; categoryLabel: string; categoryDescription: string;
  totalInCategory: number; isIndependent: boolean; nodes: ChartNode[];
}
/** ノード単位のユーザー進捗情報 */
interface UserProgress { bestClear: string; clearRank: number; missCount: number | null; }
/** API 全体のレスポンス型 */
interface TreeData { chains: SkillChain[]; userProgress: Record<string, UserProgress>; }

/** API fetch 中フラグ */
const loading = ref(true);
/** API エラー表示用 */
const error = ref('');
/** サーバーから取得したツリーデータ */
const data = ref<TreeData | null>(null);
/** 詳細展開対象のノード textage。null なら何も展開しない */
const selectedNode = ref<string | null>(null);
/** 独立譜面セクションの折りたたみ状態 */
const showIndependent = ref(false);

/** 複数ノードからなるチェーン（本体の階段表示対象） */
const multiChains = computed(() => data.value?.chains.filter(c => !c.isIndependent) || []);
/** 単独ノードのチェーン（グリッド表示対象） */
const independentCharts = computed(() => data.value?.chains.filter(c => c.isIndependent) || []);

/**
 * 【関数の役割】 チェーン末端ノードのレベルに応じた色セットを返す。
 * 末端が最終目標なので、チェーン全体のカラーリングに採用。
 */
function chainColor(chain: SkillChain) {
  const last = chain.nodes[chain.nodes.length - 1];
  return levelColor(last?.level || 0);
}

/**
 * 【関数の役割】 レベル値から Tailwind カラークラスを返す。
 * バッジ・接続線・テキストの3要素を一組にして返す。
 */
function levelColor(lv: number) {
  if (lv <= 0) return { badge: 'bg-slate-600', line: 'bg-slate-400', text: 'text-slate-400' };
  if (lv <= 3) return { badge: 'bg-emerald-600', line: 'bg-emerald-400', text: 'text-emerald-400' };
  if (lv <= 6) return { badge: 'bg-sky-600', line: 'bg-sky-400', text: 'text-sky-400' };
  if (lv <= 9) return { badge: 'bg-amber-600', line: 'bg-amber-400', text: 'text-amber-400' };
  return { badge: 'bg-rose-600', line: 'bg-rose-400', text: 'text-rose-400' };
}

/** クリアタイプごとの文字色（FULLCOMBO は金、EXHARDは黄、HARDは赤…） */
const clearColors: Record<string, string> = {
  'FULLCOMBO CLEAR': 'text-amber-400', 'EX HARD CLEAR': 'text-yellow-300',
  'HARD CLEAR': 'text-red-400', 'CLEAR': 'text-blue-400',
  'EASY CLEAR': 'text-green-400', 'ASSIST CLEAR': 'text-purple-400', 'FAILED': 'text-slate-500',
};
/** クリアタイプごとのカード左ボーダー色（カードの装飾用） */
const clearBorders: Record<string, string> = {
  'FULLCOMBO CLEAR': 'border-amber-400/60', 'EX HARD CLEAR': 'border-yellow-300/50',
  'HARD CLEAR': 'border-red-400/40', 'CLEAR': 'border-blue-400/30',
  'EASY CLEAR': 'border-green-400/30', 'ASSIST CLEAR': 'border-purple-400/20', 'FAILED': 'border-slate-500/20',
};
/** 難易度コード→短縮記号（A=ANOTHER, L=LEGGENDARIA, H=HYPER, N=NORMAL, B=BEGINNER） */
const diffLabel = (d: string) => ({ '4': 'A', '10': 'L', '3': 'H', '2': 'N', '1': 'B' }[d] || d);
/** "FULLCOMBO CLEAR" → "FC" のようにクリア種別を短縮表示する */
function clearLabel(c: string) { return c.replace(' CLEAR', '').replace('FULLCOMBO', 'FC'); }
/** textage ID からユーザー進捗を引く。未プレイなら null */
function prog(t: string): UserProgress | null { return data.value?.userProgress?.[t] || null; }

/**
 * 【関数の役割】 API からスキルツリーデータを取得する。
 * 処理の流れ: ローディング開始 → GET → 成功時 data に格納 → finally でローディング解除。
 */
async function fetchData() {
  loading.value = true; error.value = '';
  try {
    const res = await fetch(`${API_BASE}/api/analysis/skill-tree`, { headers: authHeaders() });
    if (!res.ok) throw new Error(`HTTP ${res.status}`);
    data.value = await res.json();
  } catch (e: any) { error.value = e.message; } finally { loading.value = false; }
}
onMounted(fetchData);

/**
 * 【関数の役割】 チェーン内で「ユーザーがクリアした最後のノードのインデックス」を返す。
 * clearRank >= 2 を「クリア済み」と判定する。NEXT バッジの基準として使う。
 * @returns クリア済みノードのインデックス（未クリアのみなら -1）
 */
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
      <div class="w-10 h-10 bg-indigo-600 rounded-md flex items-center justify-center">
        <svg xmlns="http://www.w3.org/2000/svg" class="h-6 w-6 text-white" fill="none" viewBox="0 0 24 24" stroke="currentColor" stroke-width="2">
          <path stroke-linecap="round" stroke-linejoin="round" d="M13 10V3L4 14h7v7l9-11h-7z" />
        </svg>
      </div>
      <div>
        <h1 class="text-2xl font-bold text-slate-900 dark:text-white tracking-tight">SKILL TREE</h1>
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

    <div v-else-if="error" class="bg-red-50 dark:bg-red-900/30 border border-red-200 dark:border-red-800 rounded-md p-6 text-center">
      <p class="text-red-600 dark:text-red-400 font-medium">{{ error }}</p>
      <button @click="fetchData" class="mt-3 px-4 py-2 bg-red-600 text-white rounded-lg text-sm font-bold">リトライ</button>
    </div>

    <div v-else-if="data" class="space-y-8">

      <!-- ===== チェーン一覧: 類似譜面チェーンを階段状に描画 ===== -->
      <section v-for="chain in multiChains" :key="chain.categoryId">
        <!-- セクションヘッダー: 到達先の譜面名 -->
        <div class="flex items-center gap-3 mb-3">
          <div class="w-8 h-8 rounded-lg flex items-center justify-center text-white text-[10px] font-bold leading-none"
               :class="chainColor(chain).badge">
            {{ chain.categoryDescription }}
          </div>
          <div class="flex-1 min-w-0">
            <h2 class="text-base font-bold text-slate-900 dark:text-white truncate">{{ chain.categoryLabel }}</h2>
            <p class="text-[11px] text-slate-400 dark:text-slate-500">{{ chain.totalInCategory }} ステップ</p>
          </div>
        </div>

        <!-- チェーン本体: 縦のガイド線とノードカードを並べる。左端の円がプレイ状況を示す -->
        <div class="relative pl-6">
          <div class="absolute left-[15px] top-0 bottom-0 w-0.5 rounded-full" :class="chainColor(chain).line" style="opacity:0.25"></div>

          <div v-for="(node, idx) in chain.nodes" :key="node.textage" class="relative flex items-start gap-3 mb-1">
            <!-- ノードドット: クリア済みなら色付き、NEXT なら点滅、未プレイなら中空白 -->
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

            <!-- ノードカード: 難易度・曲名・ノーツ数・クリアランク・NEXTバッジ。クリックで詳細展開 -->
            <div
              class="flex-1 flex items-center gap-2 px-3 py-1.5 rounded-lg border transition-all cursor-pointer hover:border-slate-300 dark:hover:border-slate-500 text-sm"
              :class="[
                prog(node.textage)?.clearRank >= 2
                  ? `bg-white dark:bg-slate-800/80 ${clearBorders[prog(node.textage)!.bestClear] || 'border-slate-200 dark:border-slate-700'} border-l-2`
                  : idx === currentPos(chain) + 1
                    ? 'bg-indigo-50 dark:bg-indigo-950/30 border-indigo-300 dark:border-indigo-700 border-l-2'
                    : 'bg-white/60 dark:bg-slate-800/40 border-slate-200/60 dark:border-slate-700/40'
              ]"
              @click="selectedNode = selectedNode === node.textage ? null : node.textage"
            >
              <span class="text-[11px] font-bold text-slate-400 dark:text-slate-500 w-7 flex-shrink-0">
                {{ diffLabel(node.difficulty) }}{{ node.level || '?' }}
              </span>
              <span class="font-medium text-slate-800 dark:text-slate-200 truncate flex-1 text-xs">{{ node.title }}</span>
              <span class="text-[10px] text-slate-400 dark:text-slate-500 flex-shrink-0">{{ node.notes }}n</span>
              <span v-if="prog(node.textage)" class="text-[10px] font-bold flex-shrink-0 w-12 text-right" :class="clearColors[prog(node.textage)!.bestClear]">
                {{ clearLabel(prog(node.textage)!.bestClear) }}
              </span>
              <span v-else class="text-[10px] text-slate-400 dark:text-slate-600 w-12 text-right flex-shrink-0">—</span>
              <span v-if="idx === currentPos(chain) + 1 && isLoggedIn" class="px-1.5 py-0.5 bg-indigo-500 text-white text-[9px] font-bold rounded flex-shrink-0">
                NEXT
              </span>
            </div>
          </div>
        </div>

        <!-- 展開された詳細: BPM・ノーツ・皿割合など追加情報を表示 -->
        <template v-for="node in chain.nodes" :key="'detail-' + node.textage">
          <div v-if="selectedNode === node.textage" class="ml-6 mt-1 mb-3 p-3 bg-slate-50 dark:bg-slate-700/40 rounded-md border border-slate-200 dark:border-slate-700 text-xs">
            <div class="flex items-center gap-2 mb-2">
              <span class="font-bold text-slate-800 dark:text-white text-sm">{{ node.title }}</span>
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

      <!-- ===== 独立譜面セクション: どのチェーンにも属さない譜面を2カラムグリッドで表示 ===== -->
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
            class="flex items-center gap-2 px-3 py-1.5 rounded-lg border bg-white/60 dark:bg-slate-800/40 border-slate-200/60 dark:border-slate-700/40 text-sm cursor-pointer hover:border-slate-300 dark:hover:border-slate-500 transition-all"
            @click="selectedNode = selectedNode === chain.nodes[0].textage ? null : chain.nodes[0].textage"
          >
            <span class="text-[10px] font-bold px-1 py-0.5 rounded text-white" :class="levelColor(chain.nodes[0].level).badge">
              {{ diffLabel(chain.nodes[0].difficulty) }}{{ chain.nodes[0].level || '?' }}
            </span>
            <span class="font-medium text-slate-800 dark:text-slate-200 truncate flex-1 text-xs">{{ chain.nodes[0].title }}</span>
            <span class="text-[10px] text-slate-400 dark:text-slate-500 flex-shrink-0">{{ chain.nodes[0].notes }}n</span>
            <span v-if="prog(chain.nodes[0].textage)" class="text-[10px] font-bold flex-shrink-0" :class="clearColors[prog(chain.nodes[0].textage)!.bestClear]">
              {{ clearLabel(prog(chain.nodes[0].textage)!.bestClear) }}
            </span>
          </div>
        </div>

        <!-- 独立譜面クリック時の詳細展開 -->
        <template v-for="chain in independentCharts" :key="'ind-detail-' + chain.categoryId">
          <div v-if="selectedNode === chain.nodes[0].textage" class="mt-1 mb-3 p-3 bg-slate-50 dark:bg-slate-700/40 rounded-md border border-slate-200 dark:border-slate-700 text-xs">
            <div class="flex items-center gap-2 mb-2">
              <span class="font-bold text-slate-800 dark:text-white text-sm">{{ chain.nodes[0].title }}</span>
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
