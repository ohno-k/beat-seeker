<template>
  <Teleport to="body">
    <div class="fixed inset-0 z-[110] bg-slate-50 dark:bg-slate-900 flex flex-col animate-fade-in transition-colors duration-200">
      <!-- ヘッダー（タイトル + ×閉じる） -->
      <div class="px-8 py-6 border-b border-slate-100 dark:border-slate-700/50 flex justify-between items-center bg-white dark:bg-slate-800 sticky top-0 z-10 transition-colors duration-200">
        <div>
          <h3 class="text-2xl font-bold text-slate-800 dark:text-slate-100">{{ t('beatTierInfo.title') }}</h3>
          <p class="text-[10px] font-bold text-slate-400 dark:text-slate-500 mt-0.5">{{ t('beatTierInfo.subtitle') }}</p>
        </div>
        <button @click="$emit('close')" class="p-2 text-slate-400 dark:text-slate-500 hover:text-slate-600 dark:hover:text-slate-300 hover:bg-slate-100 dark:hover:bg-slate-700 rounded-full transition-all">
          <svg class="w-6 h-6" fill="none" viewBox="0 0 24 24" stroke="currentColor">
            <path stroke-linecap="round" stroke-linejoin="round" stroke-width="2.5" d="M6 18L18 6M6 6l12 12" />
          </svg>
        </button>
      </div>

      <!-- 本体スクロール領域 -->
      <div class="flex-1 overflow-y-auto custom-scrollbar bg-slate-50/50 dark:bg-slate-900/50 transition-colors duration-200">
        <!-- タブ切替（解説 / 対象曲一覧） -->
        <div class="px-8 pt-6 sticky top-0 bg-white/80 dark:bg-slate-800/80 backdrop-blur-md z-10 transition-colors duration-200">
          <div class="flex border-b border-slate-200 dark:border-slate-700 gap-8">
            <button 
              @click="activeTab = 'about'" 
              :class="['pb-4 text-sm font-bold transition-all relative', activeTab === 'about' ? 'text-blue-600 dark:text-blue-400' : 'text-slate-400 dark:text-slate-500 hover:text-slate-600 dark:hover:text-slate-300']"
            >
              {{ t('beatTierInfo.tabAbout') }}
              <div v-if="activeTab === 'about'" class="absolute bottom-0 left-0 right-0 h-1 bg-blue-600 dark:bg-blue-500 rounded-full"></div>
            </button>
            <button 
              @click="activeTab = 'songs'" 
              :class="['pb-4 text-sm font-bold transition-all relative', activeTab === 'songs' ? 'text-blue-600 dark:text-blue-400' : 'text-slate-400 dark:text-slate-500 hover:text-slate-600 dark:hover:text-slate-300']"
            >
              {{ t('beatTierInfo.tabSongs') }}
              <div v-if="activeTab === 'songs'" class="absolute bottom-0 left-0 right-0 h-1 bg-blue-600 dark:bg-blue-500 rounded-full"></div>
            </button>
          </div>
        </div>

        <div class="p-4 sm:p-8">
          <!-- 解説タブ（Beat-PT の仕組み・計算式・階段） -->
          <div v-if="activeTab === 'about'" class="space-y-8 animate-fade-in">
            <section>
              <h4 class="text-lg font-bold text-slate-800 dark:text-slate-100 mb-3 flex items-center gap-2">
                <span class="w-1.5 h-6 bg-blue-600 dark:bg-blue-500 rounded-full"></span>
                {{ t('beatTierInfo.whatIsTitle') }}
              </h4>
              <p class="text-slate-600 dark:text-slate-300 leading-relaxed text-sm font-medium mb-5" v-html="t('beatTierInfo.whatIsDesc')"></p>
              <TierInfoFlow accent="blue" :steps="flowSteps" />
            </section>

            <!-- 計算式（式を項ごとに分解 + 2 つのグラフ） -->
            <section class="bg-slate-900 dark:bg-slate-950 rounded-md text-white border border-slate-700 dark:border-slate-800 formula-card">
              <h4 class="text-[10px] font-bold mb-4 text-slate-400 dark:text-slate-500">Calculation Formula</h4>

              <div class="formula-terms">
                <span class="formula-lhs text-blue-300">Beat-PT <span class="formula-op">=</span></span>
                <div class="term">
                  <p class="term-main text-blue-300">Rate%<sup>1.3</sup></p>
                  <p class="term-sub">{{ t('beatTierInfo.termRate') }}</p>
                </div>
                <span class="formula-op">×</span>
                <div class="term">
                  <p class="term-main text-blue-300">Weight</p>
                  <p class="term-sub">{{ t('beatTierInfo.termWeight') }}</p>
                </div>
                <span class="formula-op">+</span>
                <div class="term">
                  <p class="term-main text-amber-300">Bonus</p>
                  <p class="term-sub">{{ t('beatTierInfo.termBonus') }}</p>
                </div>
              </div>

              <p class="text-xs font-bold text-slate-400 leading-relaxed mt-5" v-html="t('beatTierInfo.formulaDesc')"></p>

              <div class="charts">
                <!-- グラフ1: スコアレート → Beat-PT（例の難易度で 1 本） -->
                <figure class="chart-box">
                  <figcaption class="chart-title">{{ t('beatTierInfo.curveTitle', { rank: CURVE_RANK }) }}</figcaption>
                  <svg :viewBox="`0 0 ${CW} ${CH}`" class="chart-svg" role="img" :aria-label="t('beatTierInfo.curveTitle', { rank: CURVE_RANK })">
                    <defs>
                      <linearGradient id="beatCurveFill" x1="0" y1="0" x2="0" y2="1">
                        <stop offset="0%" stop-color="#60a5fa" stop-opacity="0.35" />
                        <stop offset="100%" stop-color="#60a5fa" stop-opacity="0" />
                      </linearGradient>
                    </defs>
                    <g v-for="v in curveYTicks" :key="'y' + v">
                      <line :x1="PL" :x2="CW - PR" :y1="cy(v)" :y2="cy(v)" stroke="#1e293b" />
                      <text :x="PL - 5" :y="cy(v) + 3" text-anchor="end" font-size="9" fill="#64748b">{{ v }}</text>
                    </g>
                    <g v-for="r in [70, 80, 90, 100]" :key="'x' + r">
                      <text :x="cx(r)" :y="CH - 6" text-anchor="middle" font-size="9" fill="#64748b">{{ r }}%</text>
                    </g>
                    <g v-for="b in bonusLines" :key="b.label">
                      <line :x1="cx(b.rate)" :x2="cx(b.rate)" :y1="PT" :y2="CH - PB" stroke="#fbbf24" stroke-opacity="0.55" stroke-dasharray="3 3" />
                      <text :x="cx(b.rate) - 3" :y="PT + 9" text-anchor="end" font-size="8.5" font-weight="700" fill="#fcd34d">{{ b.label }}</text>
                      <text :x="cx(b.rate) - 3" :y="PT + 19" text-anchor="end" font-size="8" fill="#fcd34d" fill-opacity="0.75">+1%</text>
                    </g>
                    <path :d="curveArea" fill="url(#beatCurveFill)" />
                    <path :d="curvePath" fill="none" stroke="#60a5fa" stroke-width="2.25" stroke-linejoin="round" />
                    <circle :cx="cx(100)" :cy="cy(curveMax)" r="3" fill="#60a5fa" />
                    <text :x="cx(100) - 5" :y="cy(curveMax) + 14" text-anchor="end" font-size="9" font-weight="700" fill="#bfdbfe">{{ curveMax.toFixed(1) }} pt</text>
                  </svg>
                </figure>

                <!-- グラフ2: 非公式難易度ごとの重み -->
                <figure class="chart-box">
                  <figcaption class="chart-title">{{ t('beatTierInfo.weightChartTitle') }}</figcaption>
                  <svg :viewBox="`0 0 ${CW} ${CH}`" class="chart-svg" role="img" :aria-label="t('beatTierInfo.weightChartTitle')">
                    <g v-for="v in [0, 100, 200]" :key="'wy' + v">
                      <line :x1="PL" :x2="CW - PR" :y1="wy(v)" :y2="wy(v)" stroke="#1e293b" />
                      <text :x="PL - 5" :y="wy(v) + 3" text-anchor="end" font-size="9" fill="#64748b">{{ v }}</text>
                    </g>
                    <g v-for="(w, i) in weightBars" :key="w.rank">
                      <rect :x="wx(i)" :y="wy(w.weight)" :width="wBarW" :height="wy(0) - wy(w.weight)" rx="1.5"
                        :fill="w.rank === CURVE_RANK ? '#93c5fd' : '#3b82f6'" :fill-opacity="0.45 + 0.5 * (i / (weightBars.length - 1))" />
                      <text v-if="w.rank.endsWith('.0') || w.rank.endsWith('.5')" :x="wx(i) + wBarW / 2" :y="CH - 6" text-anchor="middle" font-size="9" fill="#64748b">{{ w.rank }}</text>
                      <text v-if="i === 0 || i === weightBars.length - 1" :x="wx(i) + wBarW / 2" :y="wy(w.weight) - 4" text-anchor="middle" font-size="9" font-weight="700" fill="#bfdbfe">{{ w.weight }}</text>
                    </g>
                  </svg>
                </figure>
              </div>

              <div class="mt-5 pt-4 border-t border-slate-700/70 text-sm font-bold text-slate-300 leading-relaxed">
                <p>{{ t('beatTierInfo.weightDesc') }}</p>
                <p class="text-blue-400/80 dark:text-blue-300/80 mt-1">{{ t('beatTierInfo.finalPointsDesc') }}</p>
              </div>
            </section>

            <!-- ランク一覧ボード（階段を可視化） -->
            <section>
              <div class="flex items-center justify-between mb-4">
                <h4 class="text-lg font-bold text-slate-800 dark:text-slate-100 flex items-center gap-2">
                  <span class="w-1.5 h-6 bg-purple-600 dark:bg-purple-500 rounded-full"></span>
                  {{ t('beatTierInfo.rankBoardTitle') }}
                </h4>
                <div class="text-[10px] font-bold text-slate-400 dark:text-slate-500 bg-slate-100 dark:bg-slate-800 px-3 py-1 rounded transition-colors duration-200">Hierarchy</div>
              </div>
              <div class="bg-white dark:bg-slate-950 rounded-md p-2 sm:p-4 border border-slate-200 dark:border-slate-800">
                <TierRankLadder :grouped-ranks="groupedRanks" :rank-names="rankNames" scale="linear" />
              </div>
            </section>
          </div>

          <!-- 対象曲一覧タブ（難易度ごとに weight と曲リスト） -->
          <div v-else class="space-y-6 animate-fade-in h-full flex flex-col">
            <div class="flex flex-col sm:flex-row gap-4">
              <div class="relative flex-1">
                <input 
                  v-model="songSearch" 
                  type="text" 
                  :placeholder="t('beatTierInfo.songSearchPlaceholder')"
                  class="w-full pl-10 pr-4 py-2.5 bg-white dark:bg-slate-800 border border-slate-200 dark:border-slate-700 rounded-md text-sm font-medium focus:ring-2 focus:ring-blue-500 dark:focus:ring-blue-400 focus:border-transparent outline-none transition-all text-slate-800 dark:text-slate-100 placeholder-slate-400 dark:placeholder-slate-500"
                />
                <svg class="w-5 h-5 absolute left-3 top-1/2 -translate-y-1/2 text-slate-400 dark:text-slate-500" fill="none" viewBox="0 0 24 24" stroke="currentColor">
                  <path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M21 21l-6-6m2-5a7 7 0 11-14 0 7 7 0 0114 0z" />
                </svg>
              </div>
            </div>

            <div class="space-y-4">
              <div v-for="group in filteredSongGroups" :key="group.rank" class="bg-white dark:bg-slate-800 rounded-md border border-slate-200 dark:border-slate-700 overflow-hidden transition-colors duration-200">
                <div class="px-5 py-3 bg-slate-50 dark:bg-slate-700/50 border-b border-slate-200 dark:border-slate-700 flex items-center justify-between transition-colors duration-200">
                  <div class="flex items-center gap-2">
                    <span class="text-xs font-bold text-slate-500 dark:text-slate-400">{{ t('beatTierInfo.unofficialDifficulty') }}</span>
                    <span class="text-lg font-bold text-slate-800 dark:text-slate-100">{{ group.rank }}</span>
                  </div>
                  <div class="flex items-center gap-2">
                    <span class="text-xs font-bold text-slate-400 dark:text-slate-500">{{ t('beatTierInfo.weight') }}</span>
                    <span class="text-sm font-bold text-blue-600 dark:text-blue-400">{{ group.weight }} pt</span>
                  </div>
                </div>
                <div class="p-4 grid grid-cols-1 sm:grid-cols-2 lg:grid-cols-3 gap-x-6 gap-y-3 lg:gap-y-4">
                  <div 
                    v-for="song in group.songs" 
                    :key="song"
                    class="text-sm font-bold text-slate-700 dark:text-slate-300 flex items-center gap-2 truncate py-1"
                    :title="song"
                  >
                    <div class="w-1.5 h-1.5 shrink-0 rounded-full bg-slate-300 dark:bg-slate-600"></div>
                    {{ song }}
                  </div>
                </div>
              </div>
              <div v-if="filteredSongGroups.length === 0" class="py-20 text-center text-slate-400 dark:text-slate-500 font-bold">
                {{ t('beatTierInfo.noSongsFound') }}
              </div>
            </div>
          </div>
        </div>
      </div>
      
      <!-- フッター（更新日表示） -->
      <div class="px-8 py-5 bg-slate-50 dark:bg-slate-800/80 border-t border-slate-100 dark:border-slate-700/50 text-center transition-colors duration-200">
        <p class="text-[10px] font-bold text-slate-400 dark:text-slate-500">
          {{ t('beatTierInfo.footerDesc') }} • {{ todayLabel }}
        </p>
      </div>
    </div>
  </Teleport>
</template>

<script setup lang="ts">
/**
 * 【コンポーネントの役割】 BeatTier の仕組み解説を全画面モーダルで表示する。
 *
 * タブ構成:
 *  - about: Beat-PT の計算式、ランク階段の可視化
 *  - songs: 非公式難易度表ごとの weight と楽曲一覧（検索可）
 *
 * emits:
 *  - close: ×ボタンで閉じる
 */
import { ref, computed } from 'vue';
import { useI18n } from '../composables/useI18n';
import { WEIGHTS, getGroupedRanks, calculatePoints, getMaxPoints, SCORE_RATE_TIER_C_MIN } from '../utils/beatTier';
import { diffTable as diffTableRanksRef } from '../composables/useGameData';
import { formatJstDate } from '../utils/jstTime';
import TierRankLadder from './TierRankLadder.vue';
import TierInfoFlow from './TierInfoFlow.vue';

const { t } = useI18n();
defineEmits(['close']);

/** フッターに出す「本日」の日付。端末 TZ に依らず JST で表示する。 */
const todayLabel = computed(() => formatJstDate(new Date()));

/** アクティブなタブ。`about`（解説） / `songs`（対象曲一覧）。 */
const activeTab = ref<'about' | 'songs'>('about');
/** 曲名検索キーワード。大文字小文字を無視して部分一致。 */
const songSearch = ref('');

/** ランク名ごとに tier でまとめた配列。階段表示用。 */
const groupedRanks = computed(() => getGroupedRanks());
/** Beginner と Legend 以外のランク名（中間層）。表示順を一覧で固定。 */
const rankNames = ['Mythic', 'Ancient', 'Master', 'Elite', 'Commander', 'Veteran', 'Expert', 'Advanced', 'Intermediate', 'Novice'];

/** 「合計ポイントの決まり方」3 ステップ図の文言。 */
const flowSteps = computed(() => [1, 2, 3].map(i => ({
  title: t(`beatTierInfo.flow${i}Title`),
  desc: t(`beatTierInfo.flow${i}Desc`),
})));

// ── 計算式カードのグラフ（SVG 座標系。カードは常に暗色なので色は固定値） ──
const CW = 320;
const CH = 170;
const PL = 30;
const PR = 10;
const PT = 10;
const PB = 22;
/** スコアレート曲線の例に使う非公式難易度。 */
const CURVE_RANK = '12.0';
const CURVE_Y_MAX = 180;
const curveYTicks = [0, 60, 120, 180];
const RATE_MIN = 2 / 3 * 100;

const cx = (rate: number) => PL + (rate - RATE_MIN) / (100 - RATE_MIN) * (CW - PL - PR);
const cy = (pt: number) => PT + (1 - pt / CURVE_Y_MAX) * (CH - PT - PB);

/** ボーナスが付く境界（calculatePoints と同じ閾値）。 */
const bonusLines = [
  { label: 'AA', rate: 77.77 },
  { label: 'AAA', rate: 88.88 },
  { label: 'MAX-', rate: 94.44 },
];

/** 100% 時の Beat-PT（= weight × 1.03）。 */
const curveMax = getMaxPoints(CURVE_RANK);

/** 実際の calculatePoints を 0.05% 刻みで描く。ボーナス境界で段差になるので線を切る。 */
const curveSamples = (() => {
  const pts: { r: number; p: number; jump: boolean }[] = [];
  let prev = -1;
  for (let i = 0; ; i++) {
    const r = Math.min(100, SCORE_RATE_TIER_C_MIN + 0.001 + i * 0.05);
    const p = calculatePoints(r, CURVE_RANK);
    const bonusCount = bonusLines.filter(b => r > b.rate).length;
    pts.push({ r, p, jump: prev >= 0 && bonusCount !== prev });
    prev = bonusCount;
    if (r >= 100) break;
  }
  return pts;
})();
const curvePath = curveSamples
  .map((s, i) => `${i === 0 || s.jump ? 'M' : 'L'}${cx(s.r).toFixed(1)},${cy(s.p).toFixed(1)}`)
  .join(' ');
const curveArea = `M${cx(curveSamples[0].r).toFixed(1)},${cy(0)} `
  + curveSamples.map(s => `L${cx(s.r).toFixed(1)},${cy(s.p).toFixed(1)}`).join(' ')
  + ` L${cx(100).toFixed(1)},${cy(0)} Z`;

/** 重みの棒グラフ（WEIGHTS をそのまま並べる）。 */
const weightBars = Object.entries(WEIGHTS)
  .map(([rank, weight]) => ({ rank, weight }))
  .sort((a, b) => parseFloat(a.rank) - parseFloat(b.rank));
const W_Y_MAX = 200;
const wSlot = (CW - PL - PR) / weightBars.length;
const wBarW = wSlot * 0.72;
const wx = (i: number) => PL + i * wSlot + (wSlot - wBarW) / 2;
const wy = (v: number) => PT + (1 - v / W_Y_MAX) * (CH - PT - PB);

/**
 * 【computed の役割】 非公式難易度表を「weight > 0 のランクだけ」に整形した配列。
 * weight 0 の層は Beat-PT に寄与しないので解説対象から除外。
 */
const songGroups = computed(() => {
  return (diffTableRanksRef.value || []).map((r: any) => ({
    rank: r.rank,
    weight: WEIGHTS[r.rank] || 0,
    songs: r.songs
  })).filter((g: any) => g.weight > 0);
});

/**
 * 【computed の役割】 検索キーワードに応じて楽曲を絞り込む。
 * 空文字のときは全件を返す。マッチ 0 件のランク層は結果から除外。
 */
const filteredSongGroups = computed(() => {
  if (!songSearch.value) return songGroups.value;

  return songGroups.value.map(group => {
    const matchedSongs = group.songs.filter((s: string) =>
      s.toLowerCase().includes(songSearch.value.toLowerCase())
    );
    return { ...group, songs: matchedSongs };
  }).filter(group => group.songs.length > 0);
});
</script>

<style scoped>
.custom-scrollbar::-webkit-scrollbar {
  width: 6px;
}
.custom-scrollbar::-webkit-scrollbar-track {
  background: transparent;
}
.custom-scrollbar::-webkit-scrollbar-thumb {
  background: #e2e8f0;
  border-radius: 10px;
}
.custom-scrollbar::-webkit-scrollbar-thumb:hover {
  background: #cbd5e1;
}

.animate-fade-in {
  animation: fadeIn 0.2s ease-out;
}

@keyframes fadeIn {
  from { opacity: 0; transform: translateY(10px); }
  to { opacity: 1; transform: translateY(0); }
}

.transition-hover {
  transition: all 0.2s cubic-bezier(0.4, 0, 0.2, 1);
}

/* ── 計算式カード（output.css の sm: 上書き負けを避けるため scoped CSS でレイアウト） ── */
.formula-card {
  container-type: inline-size;
  padding: 20px;
}
.formula-terms {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  justify-content: center;
  gap: 8px 10px;
}
.formula-lhs {
  font-size: 22px;
  font-weight: 800;
  width: 100%;
  text-align: center;
}
.formula-op {
  font-size: 20px;
  font-weight: 700;
  color: #64748b;
}
.term {
  flex: 1 1 0;
  min-width: 0;
  max-width: 180px;
  padding: 10px 8px;
  border-radius: 8px;
  background: rgb(30 41 59 / 0.7);
  border: 1px solid rgb(51 65 85);
  text-align: center;
}
.term-main {
  font-size: 16px;
  font-weight: 800;
  line-height: 1.2;
}
.term-sub {
  margin-top: 4px;
  font-size: 10px;
  font-weight: 700;
  color: #94a3b8;
  line-height: 1.35;
}
.charts {
  display: grid;
  grid-template-columns: minmax(0, 1fr);
  gap: 12px;
  margin-top: 16px;
}
.chart-box {
  margin: 0;
  padding: 10px 10px 4px;
  border-radius: 8px;
  background: rgb(2 6 23 / 0.5);
  border: 1px solid rgb(30 41 59);
}
.chart-title {
  font-size: 11px;
  font-weight: 700;
  color: #cbd5e1;
  margin-bottom: 4px;
}
.chart-svg {
  display: block;
  width: 100%;
  max-width: 480px;
  margin: 0 auto;
  height: auto;
}
@container (min-width: 560px) {
  .formula-card {
    padding: 28px;
  }
  .formula-lhs {
    width: auto;
    font-size: 26px;
  }
  .term-main {
    font-size: 22px;
  }
  .charts {
    grid-template-columns: repeat(2, minmax(0, 1fr));
  }
}
</style>
