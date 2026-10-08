<template>
  <Teleport to="body">
    <div class="fixed inset-0 z-[110] bg-slate-50 dark:bg-slate-900 flex flex-col animate-fade-in transition-colors duration-200">
      <!-- ヘッダー（タイトル + 閉じるボタン） -->
      <div class="px-8 py-6 border-b border-slate-100 dark:border-slate-700/50 flex justify-between items-center bg-white dark:bg-slate-800 sticky top-0 z-10 transition-colors duration-200">
        <div>
          <h3 class="text-2xl font-bold text-slate-800 dark:text-slate-100">{{ t('rateTierInfo.title') }}</h3>
          <p class="text-[10px] font-bold text-slate-400 dark:text-slate-500 mt-0.5">{{ t('rateTierInfo.subtitle') }}</p>
        </div>
        <button @click="$emit('close')" class="p-2 text-slate-400 dark:text-slate-500 hover:text-slate-600 dark:hover:text-slate-300 hover:bg-slate-100 dark:hover:bg-slate-700 rounded-full transition-all">
          <svg class="w-6 h-6" fill="none" viewBox="0 0 24 24" stroke="currentColor">
            <path stroke-linecap="round" stroke-linejoin="round" stroke-width="2.5" d="M6 18L18 6M6 6l12 12" />
          </svg>
        </button>
      </div>

      <!-- スクロール可能な本文領域 -->
      <div class="flex-1 overflow-y-auto custom-scrollbar bg-slate-50/50 dark:bg-slate-900/50 transition-colors duration-200">
        <!-- タブ切替（解説 / 閾値表） -->
        <div class="px-8 pt-6 sticky top-0 bg-white/80 dark:bg-slate-800/80 backdrop-blur-md z-10 transition-colors duration-200">
          <div class="flex border-b border-slate-200 dark:border-slate-700 gap-8">
            <button
              @click="activeTab = 'about'"
              :class="['pb-4 text-sm font-bold transition-all relative', activeTab === 'about' ? 'text-emerald-600 dark:text-emerald-400' : 'text-slate-400 dark:text-slate-500 hover:text-slate-600 dark:hover:text-slate-300']"
            >
              {{ t('beatTierInfo.tabAbout') }}
              <div v-if="activeTab === 'about'" class="absolute bottom-0 left-0 right-0 h-1 bg-emerald-600 dark:bg-emerald-500 rounded-full"></div>
            </button>
            <button
              @click="activeTab = 'table'"
              :class="['pb-4 text-sm font-bold transition-all relative', activeTab === 'table' ? 'text-emerald-600 dark:text-emerald-400' : 'text-slate-400 dark:text-slate-500 hover:text-slate-600 dark:hover:text-slate-300']"
            >
              {{ t('rateTierInfo.tabTable') }}
              <div v-if="activeTab === 'table'" class="absolute bottom-0 left-0 right-0 h-1 bg-emerald-600 dark:bg-emerald-500 rounded-full"></div>
            </button>
          </div>
        </div>

        <div class="p-4 sm:p-8">
          <!-- 解説タブ（Rate-PT の仕組みと計算式） -->
          <div v-if="activeTab === 'about'" class="space-y-8 animate-fade-in">
            <section>
              <h4 class="text-lg font-bold text-slate-800 dark:text-slate-100 mb-3 flex items-center gap-2">
                <span class="w-1.5 h-6 bg-emerald-600 dark:bg-emerald-500 rounded-full"></span>
                {{ t('rateTierInfo.whatIsTitle') }}
              </h4>
              <p class="text-slate-600 dark:text-slate-300 leading-relaxed text-sm font-medium mb-5" v-html="t('rateTierInfo.whatIsDesc')"></p>
              <TierInfoFlow accent="emerald" :steps="flowSteps" />
            </section>

            <!-- 計算式（分数で表示 + ポイント倍増の棒グラフ） -->
            <section class="bg-slate-900 dark:bg-slate-950 rounded-md text-white border border-slate-700 dark:border-slate-800 formula-card">
              <h4 class="text-[10px] font-bold mb-4 text-slate-400 dark:text-slate-500">Calculation Formula</h4>

              <div class="fraction-row" :aria-label="t('rateTierInfo.formulaTitle')">
                <span class="fraction-lhs text-emerald-300">Score Rate <span class="formula-op">=</span></span>
                <div class="fraction">
                  <span class="fraction-num">{{ t('rateTierInfo.termScore') }}</span>
                  <span class="fraction-bar"></span>
                  <span class="fraction-den">{{ t('rateTierInfo.termMax') }}</span>
                </div>
                <span class="formula-op">× 100%</span>
              </div>

              <p class="text-xs font-bold text-slate-400 leading-relaxed mt-5" v-html="t('rateTierInfo.formulaDesc')"></p>

              <figure class="chart-box">
                <figcaption class="chart-title">{{ t('rateTierInfo.pointsChartTitle') }}</figcaption>
                <svg :viewBox="`0 0 ${CW} ${CH}`" class="chart-svg" role="img" :aria-label="t('rateTierInfo.pointsChartTitle')">
                  <line :x1="PL" :x2="CW - PR" :y1="by(0)" :y2="by(0)" stroke="#334155" />
                  <g v-for="(th, i) in SCORE_RATE_THRESHOLDS" :key="th.rate">
                    <rect :x="bx(i)" :y="by(barLevel(th.points))" :width="barW" :height="by(0) - by(barLevel(th.points))" rx="2"
                      :fill="th.rate === 100 ? '#fbbf24' : '#34d399'" :fill-opacity="0.35 + 0.6 * (i / (SCORE_RATE_THRESHOLDS.length - 1))" />
                    <text :x="bx(i) + barW / 2" :y="by(barLevel(th.points)) - 4" text-anchor="middle" font-size="9" font-weight="700"
                      :fill="th.rate === 100 ? '#fcd34d' : '#a7f3d0'">{{ th.points }}</text>
                    <text :x="bx(i) + barW / 2" :y="CH - 10" text-anchor="middle" font-size="8" fill="#94a3b8">{{ rateLabel(th.rate) }}</text>
                  </g>
                </svg>
                <p class="text-[10px] font-bold text-slate-400 text-center pb-1">{{ t('rateTierInfo.pointsChartNote') }}</p>
              </figure>

              <div class="mt-5 pt-4 border-t border-slate-700/70 text-sm font-bold text-slate-300 leading-relaxed">
                <p>{{ t('rateTierInfo.weightDesc') }}</p>
                <p class="text-emerald-400/80 dark:text-emerald-300/80 mt-1">{{ t('rateTierInfo.finalPointsDesc') }}</p>
              </div>
            </section>

            <!-- ランク階段表示 -->
            <section>
              <div class="flex items-center justify-between mb-4">
                <h4 class="text-lg font-bold text-slate-800 dark:text-slate-100 flex items-center gap-2">
                  <span class="w-1.5 h-6 bg-purple-600 dark:bg-purple-500 rounded-full"></span>
                  {{ t('beatTierInfo.rankBoardTitle') }}
                </h4>
                <div class="text-[10px] font-bold text-slate-400 dark:text-slate-500 bg-slate-100 dark:bg-slate-800 px-3 py-1 rounded transition-colors duration-200">Hierarchy</div>
              </div>
              <div class="bg-white dark:bg-slate-950 rounded-md p-2 sm:p-4 border border-slate-200 dark:border-slate-800">
                <TierRankLadder :grouped-ranks="groupedRanks" :rank-names="rankNames" scale="log" :format="formatTierPt" />
              </div>
            </section>
          </div>

          <!-- スコアレート閾値テーブルタブ -->
          <div v-else class="space-y-6 animate-fade-in">
            <section>
              <h4 class="text-lg font-bold text-slate-800 dark:text-slate-100 mb-3 flex items-center gap-2">
                <span class="w-1.5 h-6 bg-emerald-600 dark:bg-emerald-500 rounded-full"></span>
                {{ t('rateTierInfo.tableTitle') }}
              </h4>
              <p class="text-slate-500 dark:text-slate-400 text-sm font-medium mb-6">
                {{ t('rateTierInfo.tableDesc') }}
              </p>
            </section>

            <div class="bg-white dark:bg-slate-800 rounded-md border border-slate-200 dark:border-slate-700 overflow-hidden">
              <div class="px-5 py-3 bg-slate-50 dark:bg-slate-700/50 border-b border-slate-200 dark:border-slate-700 grid grid-cols-3 gap-4">
                <span class="text-xs font-bold text-slate-500 dark:text-slate-400">{{ t('table.colRate') }}</span>
                <span class="text-xs font-bold text-slate-500 dark:text-slate-400 text-right">{{ t('table.colPoints') }}</span>
                <span class="text-xs font-bold text-slate-500 dark:text-slate-400 text-right">{{ t('rateTierInfo.scoreExampleTitle') }}</span>
              </div>
              <div class="divide-y divide-slate-100 dark:divide-slate-700">
                <div
                  v-for="(threshold, i) in SCORE_RATE_THRESHOLDS"
                  :key="i"
                  class="relative px-5 py-4 grid grid-cols-3 gap-4 hover:bg-slate-50 dark:hover:bg-slate-700/30 transition-colors"
                >
                  <!-- ポイントの大きさ（2 倍ごとに 1 目盛り）を行の背景バーで示す -->
                  <div class="absolute inset-y-0 left-0 pointer-events-none" :class="thresholdColor(threshold.points)" :style="{ width: `${barLevel(threshold.points) / MAX_LEVEL * 100}%`, opacity: 0.12 }"></div>
                  <div class="relative flex items-center gap-2">
                    <div class="w-2 h-2 rounded-full shrink-0" :class="thresholdColor(threshold.points)"></div>
                    <span class="text-sm font-bold text-slate-800 dark:text-slate-100 tabular-nums">{{ threshold.rate.toFixed(2) }}%</span>
                    <span v-if="threshold.rate === 100" class="text-[9px] font-bold px-1.5 py-0.5 rounded bg-amber-500 text-white">PERFECT</span>
                  </div>
                  <div class="relative text-right">
                    <span class="text-sm font-bold tabular-nums" :class="thresholdTextColor(threshold.points)">{{ threshold.points }} pt</span>
                  </div>
                  <div class="relative text-right">
                    <span class="text-xs font-bold text-slate-500 dark:text-slate-400 tabular-nums">{{ Math.round(threshold.rate / 100 * 3000).toLocaleString() }} / 3000</span>
                  </div>
                </div>
              </div>
            </div>

            <p class="text-[11px] font-bold text-slate-400 dark:text-slate-500 text-center">
              {{ t('rateTierInfo.notes1500') }}
            </p>
          </div>
        </div>
      </div>

      <!-- フッター（更新日表示） -->
      <div class="px-8 py-5 bg-slate-50 dark:bg-slate-800/80 border-t border-slate-100 dark:border-slate-700/50 text-center transition-colors duration-200">
        <p class="text-[10px] font-bold text-slate-400 dark:text-slate-500">
          {{ t('rateTierInfo.footerDesc') }} • {{ todayLabel }}
        </p>
      </div>
    </div>
  </Teleport>
</template>

<script setup lang="ts">
/**
 * 【コンポーネントの役割】 RateTier（スコアレート式称号）の仕組み解説モーダル。
 *
 * タブ構成:
 *  - about: 合計ポイントの決まり方（3 ステップ図）、Score Rate の式とポイント倍増グラフ、ランク階段
 *  - table: スコアレート閾値ごとの付与ポイント表 + 例（3000 満点換算）
 *
 * emits:
 *  - close: 閉じる
 */
import { ref, computed } from 'vue';
import { useI18n } from '../composables/useI18n';
import { SCORE_RATE_THRESHOLDS, getGroupedRateTierRanks } from '../utils/beatTier';
import { formatJstDate } from '../utils/jstTime';
import TierRankLadder from './TierRankLadder.vue';
import TierInfoFlow from './TierInfoFlow.vue';

const { t } = useI18n();
defineEmits(['close']);

/** フッターに出す「本日」の日付。端末 TZ に依らず JST で表示する。 */
const todayLabel = computed(() => formatJstDate(new Date()));

/** 現在のタブ（解説 / 閾値テーブル）。 */
const activeTab = ref<'about' | 'table'>('about');

/** RateTier のランク一覧を tier でグルーピングしたもの。 */
const groupedRanks = computed(() => getGroupedRateTierRanks());
/** 中間ランク名を表示順に固定。 */
const rankNames = ['Mythic', 'Ancient', 'Master', 'Elite', 'Commander', 'Veteran', 'Expert', 'Advanced', 'Intermediate', 'Novice'];

/** 「合計ポイントの決まり方」3 ステップ図の文言（1・3 番目は Beat-Tier と同じ）。 */
const flowSteps = computed(() => [
  { title: t('beatTierInfo.flow1Title'), desc: t('rateTierInfo.flow1Desc') },
  { title: t('rateTierInfo.flow2Title'), desc: t('rateTierInfo.flow2Desc') },
  { title: t('beatTierInfo.flow3Title'), desc: t('beatTierInfo.flow3Desc') },
]);

// ── ポイント倍増グラフ（SVG 座標系。カードは常に暗色なので色は固定値） ──
const CW = 320;
const CH = 170;
const PL = 6;
const PR = 6;
const PT = 16;
const PB = 26;
/** 1pt=1 段、512pt=10 段（2 倍ごとに 1 段）。 */
const barLevel = (points: number) => Math.log2(points) + 1;
const MAX_LEVEL = barLevel(SCORE_RATE_THRESHOLDS[SCORE_RATE_THRESHOLDS.length - 1].points);
const slot = (CW - PL - PR) / SCORE_RATE_THRESHOLDS.length;
const barW = slot * 0.7;
const bx = (i: number) => PL + i * slot + (slot - barW) / 2;
const by = (level: number) => PT + (1 - level / MAX_LEVEL) * (CH - PT - PB);
const rateLabel = (rate: number) => (rate === 100 ? '100%' : `${rate.toFixed(2)}%`);

/**
 * 【関数の役割】 サブティア閾値をアイコン下の小ラベル用に短く整形する。
 *
 * Rate-Tier の閾値は 25 pt 台から 22,000 pt 台まで 3 桁ぶん開きがあり、
 * 等比分割なので小数（例 3675.8）も出る。1,000 pt 以上は "3.7k" と丸め、
 * 未満はそのまま（小数第 1 位まで）出して桁幅を揃える。
 */
function formatTierPt(points: number | undefined): string {
  const v = points ?? 0;
  return v >= 1000 ? `${Math.round(v / 100) / 10}k` : String(Math.round(v * 10) / 10);
}

/** 【関数の役割】 閾値表の左端カラーマーカーを points 規模で決定する。 */
function thresholdColor(points: number): string {
  if (points >= 256) return 'bg-amber-400';
  if (points >= 64) return 'bg-emerald-500';
  if (points >= 8) return 'bg-teal-500';
  if (points >= 2) return 'bg-cyan-500';
  return 'bg-slate-300';
}

/** 【関数の役割】 閾値表の「points pt」テキスト色を points 規模で決定する。 */
function thresholdTextColor(points: number): string {
  if (points >= 256) return 'text-amber-500 dark:text-amber-400';
  if (points >= 64) return 'text-emerald-600 dark:text-emerald-400';
  if (points >= 8) return 'text-teal-600 dark:text-teal-400';
  if (points >= 2) return 'text-cyan-600 dark:text-cyan-400';
  return 'text-slate-500 dark:text-slate-400';
}
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

/* ── 計算式カード（output.css の sm: 上書き負けを避けるため scoped CSS でレイアウト） ── */
.formula-card {
  container-type: inline-size;
  padding: 20px;
}
.fraction-row {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  justify-content: center;
  gap: 8px 12px;
}
.fraction-lhs {
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
.fraction {
  display: inline-flex;
  flex-direction: column;
  align-items: center;
  padding: 10px 14px;
  border-radius: 8px;
  background: rgb(30 41 59 / 0.7);
  border: 1px solid rgb(51 65 85);
}
.fraction-num,
.fraction-den {
  font-size: 15px;
  font-weight: 800;
  color: #6ee7b7;
  line-height: 1.3;
}
.fraction-den {
  font-size: 13px;
}
.fraction-bar {
  align-self: stretch;
  height: 2px;
  margin: 6px 0;
  background: #6ee7b7;
  border-radius: 9999px;
}
.chart-box {
  margin: 16px 0 0;
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
  .fraction-lhs {
    width: auto;
    font-size: 26px;
  }
}
</style>
