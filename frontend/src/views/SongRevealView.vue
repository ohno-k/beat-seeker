<script setup lang="ts">
/**
 * 【Viewの役割】 IIDX 非公式大会向け「選曲発表」演出画面 (2 曲対応・左右分割版)。
 *
 * BEMANI PRO LEAGUE の選曲発表シーンを参考にした派手なリビールアニメ。
 * 画面を縦半分にし、左右それぞれに 1 曲ずつセットして発表する。
 * OBS では半分ずつクロップして使う想定。
 *
 * 利用フロー:
 *  1. アクティブ側を Left / Right で切り替えながらそれぞれ検索 → 選曲
 *  2. 両方セットしたら REVEAL ボタンで演出開始 (左側がアニメ)
 *  3. 演出中は **画面のどこでもクリック** すると次の 1 曲ぶんのアニメが進む:
 *     - 左公開済み + クリック → 右公開
 *     - 両方公開済み + クリック → 何もしない (誤爆防止)
 *  4. OBS は「左半分のみ」「右半分のみ」をそれぞれクロップして 2 つのソースにできる
 *
 * 主催 (ID=19) と運営担当 (ID=18) のみがサイドバーから到達。URL は `/song-reveal`。
 *
 * データソース: `song_data.json` を ANOTHER (4) / LEGGENDARIA (10) のみフィルタ。
 */
import { ref, computed, onMounted, onUnmounted, nextTick } from 'vue';
import { useGameData, type SongDataEntry } from '../composables/useGameData';

const { songDataBody, fetchGameData } = useGameData();

onMounted(() => {
  if (songDataBody.value.length === 0) fetchGameData();
  document.addEventListener('fullscreenchange', onFullscreenChange);
});

onUnmounted(() => {
  document.removeEventListener('fullscreenchange', onFullscreenChange);
  for (const id of stageTimers) clearTimeout(id);
});

const playableCharts = computed<SongDataEntry[]>(() => {
  return songDataBody.value.filter(s => s.difficulty === '4' || s.difficulty === '10');
});

const diffName = (code: string): string => (code === '10' ? 'LEGGENDARIA' : 'ANOTHER');

// ── 検索状態 ─────────────────────────────────────────────
const searchQuery = ref('');
const activeSide = ref<'left' | 'right'>('left');

const searchResults = computed<SongDataEntry[]>(() => {
  const q = searchQuery.value.trim().toLowerCase();
  if (q.length === 0) return [];
  const matched = playableCharts.value.filter(s => {
    return s.title.toLowerCase().includes(q) || (s.artist && s.artist.toLowerCase().includes(q));
  });
  matched.sort((a, b) => {
    if (a.title !== b.title) return a.title.localeCompare(b.title);
    return Number(a.difficulty) - Number(b.difficulty);
  });
  return matched.slice(0, 100);
});

// ── 選曲スロット ────────────────────────────────────────
const selectedLeft = ref<SongDataEntry | null>(null);
const selectedRight = ref<SongDataEntry | null>(null);

const selectChart = (chart: SongDataEntry) => {
  if (phase.value !== 'select') return;
  if (activeSide.value === 'left') selectedLeft.value = chart;
  else selectedRight.value = chart;
};

const clearSlot = (side: 'left' | 'right') => {
  if (side === 'left') selectedLeft.value = null;
  else selectedRight.value = null;
};

// ── フェーズ / 段階アニメ ──────────────────────────────
/**
 * - 'select': 選曲画面 (検索)
 * - 'reveal': 演出再生中。`revealStep` で進行段階を管理。
 *   revealStep == 0 → 両側非表示 (初期)
 *   revealStep == 1 → 左側公開済み (右はまだ)
 *   revealStep == 2 → 両側公開済み
 */
const phase = ref<'select' | 'reveal'>('select');
const revealStep = ref<0 | 1 | 2>(0);

const leftStage  = ref({ burst: false, title: false, artist: false, diffBadge: false });
const rightStage = ref({ burst: false, title: false, artist: false, diffBadge: false });

let stageTimers: number[] = [];

/**
 * 片側のアニメ段階を順に true にしていく (CSS animation がそれぞれ発火)。
 * 既存タイマーは別側のもこの配列に積まれるが、別側用なので干渉しない。
 */
const triggerSide = (side: 'left' | 'right') => {
  const ref = side === 'left' ? leftStage : rightStage;
  ref.value = { burst: false, title: false, artist: false, diffBadge: false };
  stageTimers.push(window.setTimeout(() => { ref.value.burst     = true; }, 50));
  stageTimers.push(window.setTimeout(() => { ref.value.title     = true; }, 300));
  stageTimers.push(window.setTimeout(() => { ref.value.artist    = true; }, 1500));
  stageTimers.push(window.setTimeout(() => { ref.value.diffBadge = true; }, 2300));
};

/**
 * REVEAL ボタン / Space キー共通のハンドラ。
 * step 0 → 左側演出開始 → step 1
 * step 1 → 右側演出開始 → step 2
 * step 2 → ループ的に Reset (選択画面に戻る)
 */
const onReveal = () => {
  if (phase.value === 'select') {
    if (!selectedLeft.value || !selectedRight.value) return;
    phase.value = 'reveal';
    revealStep.value = 1;
    triggerSide('left');
    return;
  }
  if (revealStep.value === 1) {
    revealStep.value = 2;
    triggerSide('right');
    return;
  }
  // 2 回目以降の Space は何もしない (誤爆防止)。Reset は専用ボタン側で。
};

const reset = () => {
  for (const id of stageTimers) clearTimeout(id);
  stageTimers = [];
  phase.value = 'select';
  revealStep.value = 0;
  leftStage.value  = { burst: false, title: false, artist: false, diffBadge: false };
  rightStage.value = { burst: false, title: false, artist: false, diffBadge: false };
};

/**
 * REVEAL フェーズ中の「画面どこでもクリック」ハンドラ。
 * Reset / Fullscreen ボタンは @click.stop で伝搬を止めているのでここまで来ない。
 */
const onStageClick = () => {
  if (phase.value !== 'reveal') return;
  onReveal();
};

/** タイトルを文字単位で配列化 (絵文字/サロゲートペア安全) */
const titleCharsOf = (s: SongDataEntry | null): string[] => (s ? Array.from(s.title) : []);

const canReveal = computed(() => !!selectedLeft.value && !!selectedRight.value);

// ── フルスクリーン (Strategy Card と同パターン) ──────────────
const containerEl = ref<HTMLElement | null>(null);
const isFullscreen = ref(false);
const onFullscreenChange = () => { isFullscreen.value = !!document.fullscreenElement; };
const toggleFullscreen = async () => {
  try {
    if (document.fullscreenElement) await document.exitFullscreen();
    else if (containerEl.value) await containerEl.value.requestFullscreen();
  } catch { /* noop */ }
};
</script>

<template>
  <div
    ref="containerEl"
    class="song-reveal-view min-h-screen w-full bg-slate-950 text-white relative overflow-hidden"
    :class="{ 'is-fullscreen': isFullscreen }"
  >
    <!-- 共通の背景 -->
    <div class="absolute inset-0 pointer-events-none">
      <div class="absolute inset-0 bg-gradient-to-br from-slate-950 via-slate-900 to-cyan-950"></div>
      <div class="absolute inset-0 opacity-20 bg-grid"></div>
      <div class="absolute inset-0 neon-streaks opacity-30"></div>
    </div>

    <!-- フルスクリーン切替 (REVEAL フェーズ中のクリックには反応させない) -->
    <button
      type="button"
      @click.stop="toggleFullscreen"
      class="absolute top-4 right-4 z-50 p-2.5 rounded-xl bg-slate-800/70 hover:bg-slate-700 border border-white/10 hover:border-white/30 text-slate-300 hover:text-white backdrop-blur transition-all shadow-lg"
      :aria-label="isFullscreen ? 'フルスクリーン解除' : 'フルスクリーン表示'"
    >
      <svg v-if="!isFullscreen" xmlns="http://www.w3.org/2000/svg" class="h-5 w-5" fill="none" viewBox="0 0 24 24" stroke="currentColor" stroke-width="2">
        <path stroke-linecap="round" stroke-linejoin="round" d="M4 8V4h4M20 8V4h-4M4 16v4h4M20 16v4h-4" />
      </svg>
      <svg v-else xmlns="http://www.w3.org/2000/svg" class="h-5 w-5" fill="none" viewBox="0 0 24 24" stroke="currentColor" stroke-width="2">
        <path stroke-linecap="round" stroke-linejoin="round" d="M9 4v4H5M15 4v4h4M9 20v-4H5M15 20v-4h4" />
      </svg>
    </button>

    <!-- ========== Phase: SELECT ========== -->
    <div v-if="phase === 'select'" class="relative z-10 max-w-6xl mx-auto p-4 sm:p-8 space-y-6">
      <div>
        <h1 class="text-3xl sm:text-5xl font-black tracking-tight bg-clip-text text-transparent bg-gradient-to-r from-cyan-300 via-sky-300 to-amber-300 drop-shadow-[0_0_25px_rgba(56,189,248,0.4)]">
          SONG REVEAL
        </h1>
        <p class="text-slate-400 mt-2 text-sm tracking-widest uppercase">選曲発表演出 (2 曲対応 / 左右分割)</p>
      </div>

      <!-- スロット 2 つ (Left / Right) と Active 切替 -->
      <div class="grid grid-cols-2 gap-3">
        <button
          type="button"
          @click="activeSide = 'left'"
          class="text-left rounded-2xl border-2 p-4 transition-all"
          :class="activeSide === 'left'
            ? 'border-cyan-400 bg-gradient-to-br from-slate-900 to-cyan-950/40 shadow-lg shadow-cyan-500/20'
            : 'border-white/10 bg-slate-900/50 hover:border-white/20'"
        >
          <div class="flex items-center justify-between mb-2">
            <p class="text-[10px] font-mono text-cyan-300 tracking-[0.3em]">LEFT SIDE</p>
            <span v-if="activeSide === 'left'" class="text-[9px] font-black tracking-widest uppercase px-2 py-0.5 rounded bg-cyan-500/30 text-cyan-200 border border-cyan-400/50">ACTIVE</span>
          </div>
          <div v-if="selectedLeft" class="space-y-1">
            <p class="text-lg font-black truncate">{{ selectedLeft.title }}</p>
            <p class="text-xs text-slate-400 truncate">{{ selectedLeft.artist }}</p>
            <div class="flex items-center gap-2 mt-1">
              <span class="px-2 py-0.5 rounded text-[9px] font-black tracking-widest uppercase"
                :class="selectedLeft.difficulty === '10'
                  ? 'bg-amber-500/20 text-amber-300 border border-amber-500/40'
                  : 'bg-red-500/20 text-red-300 border border-red-500/40'">
                {{ diffName(selectedLeft.difficulty) }}
              </span>
              <span class="px-2 py-0.5 rounded text-[9px] font-black tracking-widest uppercase bg-white/10 border border-white/20">Lv{{ selectedLeft.level }}</span>
              <span @click.stop="clearSlot('left')" class="ml-auto text-[10px] text-slate-500 hover:text-rose-400 cursor-pointer">クリア</span>
            </div>
          </div>
          <p v-else class="text-slate-500 text-sm font-mono">未選択</p>
        </button>

        <button
          type="button"
          @click="activeSide = 'right'"
          class="text-left rounded-2xl border-2 p-4 transition-all"
          :class="activeSide === 'right'
            ? 'border-amber-400 bg-gradient-to-br from-slate-900 to-amber-950/40 shadow-lg shadow-amber-500/20'
            : 'border-white/10 bg-slate-900/50 hover:border-white/20'"
        >
          <div class="flex items-center justify-between mb-2">
            <p class="text-[10px] font-mono text-amber-300 tracking-[0.3em]">RIGHT SIDE</p>
            <span v-if="activeSide === 'right'" class="text-[9px] font-black tracking-widest uppercase px-2 py-0.5 rounded bg-amber-500/30 text-amber-200 border border-amber-400/50">ACTIVE</span>
          </div>
          <div v-if="selectedRight" class="space-y-1">
            <p class="text-lg font-black truncate">{{ selectedRight.title }}</p>
            <p class="text-xs text-slate-400 truncate">{{ selectedRight.artist }}</p>
            <div class="flex items-center gap-2 mt-1">
              <span class="px-2 py-0.5 rounded text-[9px] font-black tracking-widest uppercase"
                :class="selectedRight.difficulty === '10'
                  ? 'bg-amber-500/20 text-amber-300 border border-amber-500/40'
                  : 'bg-red-500/20 text-red-300 border border-red-500/40'">
                {{ diffName(selectedRight.difficulty) }}
              </span>
              <span class="px-2 py-0.5 rounded text-[9px] font-black tracking-widest uppercase bg-white/10 border border-white/20">Lv{{ selectedRight.level }}</span>
              <span @click.stop="clearSlot('right')" class="ml-auto text-[10px] text-slate-500 hover:text-rose-400 cursor-pointer">クリア</span>
            </div>
          </div>
          <p v-else class="text-slate-500 text-sm font-mono">未選択</p>
        </button>
      </div>

      <!-- 検索 -->
      <div class="relative">
        <svg class="absolute left-4 top-1/2 -translate-y-1/2 h-5 w-5 text-slate-400" fill="none" viewBox="0 0 24 24" stroke="currentColor">
          <path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M21 21l-6-6m2-5a7 7 0 11-14 0 7 7 0 0114 0z" />
        </svg>
        <input
          v-model="searchQuery"
          type="text"
          :placeholder="`${activeSide === 'left' ? 'LEFT' : 'RIGHT'} 側の曲を検索 (タイトル / アーティスト)`"
          class="w-full pl-12 pr-4 py-4 text-lg bg-slate-900/70 border-2 border-white/10 focus:border-cyan-400 rounded-2xl text-white placeholder-slate-500 outline-none transition-colors"
        />
      </div>

      <!-- 検索結果リスト -->
      <div v-if="searchResults.length > 0" class="bg-slate-900/50 border border-white/10 rounded-2xl overflow-hidden max-h-[45vh] overflow-y-auto custom-scrollbar">
        <button
          v-for="(chart, i) in searchResults"
          :key="`${chart.title}-${chart.difficulty}-${i}`"
          type="button"
          @click="selectChart(chart)"
          class="w-full text-left flex items-center gap-3 px-4 py-3 hover:bg-cyan-500/10 transition-colors border-b border-white/5 last:border-b-0"
        >
          <div class="flex-1 min-w-0">
            <p class="text-sm font-bold truncate">{{ chart.title }}</p>
            <p class="text-[11px] text-slate-400 truncate">{{ chart.artist }}</p>
          </div>
          <span
            class="shrink-0 px-2.5 py-1 rounded text-[9px] font-black tracking-widest uppercase"
            :class="chart.difficulty === '10'
              ? 'bg-amber-500/20 text-amber-300 border border-amber-500/40'
              : 'bg-red-500/20 text-red-300 border border-red-500/40'"
          >
            {{ diffName(chart.difficulty) }}
          </span>
          <span class="shrink-0 px-2.5 py-1 rounded text-[9px] font-black tracking-widest uppercase bg-white/10 border border-white/20">
            Lv{{ chart.level }}
          </span>
        </button>
      </div>
      <p v-else-if="searchQuery.trim().length > 0" class="text-slate-500 text-sm font-mono text-center py-6">該当する曲が見つかりません</p>

      <!-- REVEAL ボタン -->
      <div class="flex flex-col sm:flex-row gap-3 items-center justify-center pt-2">
        <button
          @click="onReveal"
          :disabled="!canReveal"
          class="px-10 py-5 rounded-2xl text-lg font-black tracking-[0.3em] uppercase transition-all relative overflow-hidden"
          :class="canReveal
            ? 'bg-gradient-to-r from-cyan-500 via-sky-500 to-amber-500 text-white shadow-xl shadow-cyan-500/40 hover:scale-105 hover:-translate-y-0.5 active:scale-95'
            : 'bg-slate-800 text-slate-500 cursor-not-allowed'"
        >
          <span class="relative z-10">▶ REVEAL</span>
          <span v-if="canReveal" class="absolute inset-0 button-shine pointer-events-none"></span>
        </button>
        <p class="text-[11px] font-mono text-slate-500 tracking-widest">REVEAL 後は画面のどこでもクリックで次の 1 曲を発表</p>
      </div>
    </div>

    <!-- ========== Phase: REVEAL (左右分割) ==========
         画面のどこでもクリックで次の 1 曲ぶんのアニメを進行させる。
         内部の Reset / Fullscreen ボタンは @click.stop で伝搬を止めている。 -->
    <div v-else class="relative z-10 min-h-screen flex cursor-pointer select-none" @click="onStageClick">
      <!-- 中央仕切り -->
      <div class="absolute top-0 bottom-0 left-1/2 -translate-x-1/2 w-px bg-gradient-to-b from-transparent via-cyan-400/50 to-transparent pointer-events-none z-30"></div>

      <!-- LEFT HALF -->
      <div class="reveal-half relative w-1/2 flex items-center justify-center overflow-hidden p-4">
        <!-- 半面パネル背景 -->
        <div class="absolute inset-0 reveal-half-bg-left"></div>
        <div class="absolute inset-0 reveal-rays pointer-events-none"></div>

        <!-- 待機表示 (まだ公開されていない) -->
        <div v-if="!leftStage.title" class="relative text-center text-slate-700 font-mono text-xs tracking-[0.4em] z-10">
          <p>LEFT SIDE</p>
          <p class="text-slate-800 mt-1">...</p>
        </div>

        <!-- バースト -->
        <div v-if="leftStage.burst" class="absolute inset-0 pointer-events-none">
          <div class="burst-radial"></div>
          <div class="burst-ring burst-ring-1"></div>
          <div class="burst-ring burst-ring-2"></div>
          <div class="burst-ring burst-ring-3"></div>
        </div>

        <!-- 曲情報 -->
        <div class="relative z-10 text-center w-full max-w-2xl space-y-5 sm:space-y-8 px-2">
          <p
            v-if="leftStage.title"
            class="title-cascade text-3xl sm:text-5xl md:text-6xl font-black tracking-tight leading-tight"
          >
            <span
              v-for="(ch, i) in titleCharsOf(selectedLeft)"
              :key="i"
              class="cascade-char inline-block"
              :style="{ animationDelay: `${i * 70}ms` }"
            >{{ ch === ' ' ? ' ' : ch }}</span>
          </p>
          <p
            v-if="leftStage.artist"
            class="artist-fade text-xl sm:text-2xl md:text-3xl font-bold tracking-wider text-cyan-100"
          >
            {{ selectedLeft?.artist }}
          </p>
          <div v-if="leftStage.diffBadge" class="diff-slam inline-block">
            <p
              class="diff-text text-3xl sm:text-5xl md:text-6xl font-black tracking-widest italic skew-x-[-8deg]"
              :class="selectedLeft?.difficulty === '10' ? 'diff-leggendaria' : 'diff-another'"
            >
              {{ diffName(selectedLeft?.difficulty || '') }} {{ selectedLeft?.level }}
            </p>
          </div>
        </div>
      </div>

      <!-- RIGHT HALF -->
      <div class="reveal-half relative w-1/2 flex items-center justify-center overflow-hidden p-4">
        <div class="absolute inset-0 reveal-half-bg-right"></div>
        <div class="absolute inset-0 reveal-rays pointer-events-none"></div>

        <div v-if="!rightStage.title" class="relative text-center text-slate-700 font-mono text-xs tracking-[0.4em] z-10">
          <p>RIGHT SIDE</p>
          <p class="text-slate-800 mt-1">...</p>
        </div>

        <div v-if="rightStage.burst" class="absolute inset-0 pointer-events-none">
          <div class="burst-radial"></div>
          <div class="burst-ring burst-ring-1"></div>
          <div class="burst-ring burst-ring-2"></div>
          <div class="burst-ring burst-ring-3"></div>
        </div>

        <div class="relative z-10 text-center w-full max-w-2xl space-y-5 sm:space-y-8 px-2">
          <p
            v-if="rightStage.title"
            class="title-cascade text-3xl sm:text-5xl md:text-6xl font-black tracking-tight leading-tight"
          >
            <span
              v-for="(ch, i) in titleCharsOf(selectedRight)"
              :key="i"
              class="cascade-char inline-block"
              :style="{ animationDelay: `${i * 70}ms` }"
            >{{ ch === ' ' ? ' ' : ch }}</span>
          </p>
          <p
            v-if="rightStage.artist"
            class="artist-fade text-xl sm:text-2xl md:text-3xl font-bold tracking-wider text-cyan-100"
          >
            {{ selectedRight?.artist }}
          </p>
          <div v-if="rightStage.diffBadge" class="diff-slam inline-block">
            <p
              class="diff-text text-3xl sm:text-5xl md:text-6xl font-black tracking-widest italic skew-x-[-8deg]"
              :class="selectedRight?.difficulty === '10' ? 'diff-leggendaria' : 'diff-another'"
            >
              {{ diffName(selectedRight?.difficulty || '') }} {{ selectedRight?.level }}
            </p>
          </div>
        </div>
      </div>

      <!-- フッター: 進行ガイド + Reset (画面中央下、OBS の左右クロップに被らない極小領域) -->
      <div class="absolute bottom-4 left-1/2 -translate-x-1/2 z-40 flex items-center gap-3">
        <div class="px-4 py-2 rounded-full bg-slate-900/80 border border-white/10 text-[10px] font-mono tracking-widest text-slate-300 backdrop-blur">
          <span v-if="revealStep === 1">CLICK → RIGHT</span>
          <span v-else-if="revealStep === 2">REVEAL COMPLETE</span>
        </div>
        <button
          @click.stop="reset"
          class="px-4 py-2 rounded-full text-[10px] font-bold tracking-widest uppercase bg-slate-800/80 hover:bg-slate-700 border border-white/10 text-slate-300 hover:text-white backdrop-blur transition-all"
        >
          ◀ Reset
        </button>
      </div>
    </div>
  </div>
</template>

<style scoped>
.bg-grid {
  background-image:
    linear-gradient(rgba(56, 189, 248, 0.4) 1px, transparent 1px),
    linear-gradient(90deg, rgba(56, 189, 248, 0.4) 1px, transparent 1px);
  background-size: 50px 50px;
}

@keyframes neonSweep {
  0%   { transform: translate(-30%, 0) rotate(15deg); }
  100% { transform: translate(30%, 0) rotate(15deg); }
}
.neon-streaks {
  background:
    repeating-linear-gradient(115deg,
      transparent 0px, transparent 80px,
      rgba(56, 189, 248, 0.15) 80px, rgba(56, 189, 248, 0.15) 82px,
      transparent 82px, transparent 200px);
  animation: neonSweep 12s linear infinite;
}

.song-reveal-view.is-fullscreen,
.song-reveal-view:fullscreen {
  width: 100vw;
  min-height: 100vh;
  overflow-y: auto;
}

.custom-scrollbar::-webkit-scrollbar { width: 8px; }
.custom-scrollbar::-webkit-scrollbar-track { background: transparent; }
.custom-scrollbar::-webkit-scrollbar-thumb {
  background: rgba(148, 163, 184, 0.3);
  border-radius: 4px;
}

@keyframes buttonShine {
  0%   { transform: translateX(-100%); }
  100% { transform: translateX(100%); }
}
.button-shine {
  background: linear-gradient(120deg, transparent 30%, rgba(255, 255, 255, 0.35) 50%, transparent 70%);
  animation: buttonShine 2.5s linear infinite;
}

/* ============ REVEAL ステージ ============ */

/* 左右で背景の重心をずらして「2 つの陣営」感を出す */
.reveal-half-bg-left {
  background: radial-gradient(ellipse at 30% 50%,
    rgba(56, 189, 248, 0.20) 0%,
    rgba(2, 6, 23, 0.95) 60%,
    rgba(2, 6, 23, 1) 100%
  );
}
.reveal-half-bg-right {
  background: radial-gradient(ellipse at 70% 50%,
    rgba(251, 191, 36, 0.18) 0%,
    rgba(2, 6, 23, 0.95) 60%,
    rgba(2, 6, 23, 1) 100%
  );
}

@keyframes burstRadialKf {
  0%   { transform: translate(-50%, -50%) scale(0.2); opacity: 0; }
  20%  { transform: translate(-50%, -50%) scale(0.5); opacity: 1; }
  100% { transform: translate(-50%, -50%) scale(2.5); opacity: 0; }
}
.burst-radial {
  position: absolute;
  top: 50%; left: 50%;
  width: 80vmax; height: 80vmax;
  background: radial-gradient(circle,
    rgba(255, 255, 255, 0.4) 0%,
    rgba(56, 189, 248, 0.25) 30%,
    transparent 60%);
  mix-blend-mode: screen;
  animation: burstRadialKf 1.2s cubic-bezier(0.1, 0.7, 0.3, 1) forwards;
}

@keyframes ringExpandKf {
  0%   { transform: translate(-50%, -50%) scale(0.1); opacity: 0; }
  10%  { opacity: 1; }
  100% { transform: translate(-50%, -50%) scale(5); opacity: 0; }
}
.burst-ring {
  position: absolute;
  top: 50%; left: 50%;
  width: 220px; height: 220px;
  border-radius: 9999px;
  border: 4px solid rgba(125, 211, 252, 0.9);
  box-shadow: 0 0 40px rgba(56, 189, 248, 0.7);
  mix-blend-mode: screen;
  animation: ringExpandKf 1.4s cubic-bezier(0.18, 0.85, 0.30, 1) forwards;
}
.burst-ring-1 { animation-delay: 0s; }
.burst-ring-2 { animation-delay: 0.15s; border-color: rgba(255, 255, 255, 0.9); }
.burst-ring-3 { animation-delay: 0.30s; border-color: rgba(252, 211, 77, 0.9); }

@keyframes rayRotateKf {
  0%   { transform: rotate(0deg); }
  100% { transform: rotate(360deg); }
}
.reveal-rays {
  background: conic-gradient(
    from 0deg,
    rgba(56, 189, 248, 0.18) 0deg,
    transparent 25deg,
    rgba(56, 189, 248, 0.18) 60deg,
    transparent 85deg,
    rgba(56, 189, 248, 0.18) 120deg,
    transparent 145deg,
    rgba(125, 211, 252, 0.22) 180deg,
    transparent 205deg,
    rgba(56, 189, 248, 0.18) 240deg,
    transparent 265deg,
    rgba(56, 189, 248, 0.18) 300deg,
    transparent 325deg
  );
  filter: blur(10px);
  animation: rayRotateKf 18s linear infinite;
}

@keyframes cascadeCharKf {
  0%   { opacity: 0; transform: translateY(-60px) scale(1.5) rotateX(-90deg); filter: blur(8px); }
  60%  { opacity: 1; transform: translateY(8px)   scale(1.05) rotateX(0deg);   filter: blur(0);    }
  100% { opacity: 1; transform: translateY(0)     scale(1)    rotateX(0deg);   filter: blur(0);    }
}
.title-cascade {
  color: #fff;
  text-shadow:
    0 0 18px rgba(255, 255, 255, 0.8),
    0 0 36px rgba(56, 189, 248, 0.7),
    0 0 60px rgba(56, 189, 248, 0.5);
}
.cascade-char {
  display: inline-block;
  opacity: 0;
  transform: translateY(-60px) scale(1.5);
  animation: cascadeCharKf 0.6s cubic-bezier(0.18, 0.89, 0.32, 1.28) forwards;
}

@keyframes artistFadeKf {
  0%   { opacity: 0; transform: translateY(10px); letter-spacing: 0.5em; filter: blur(6px); }
  100% { opacity: 1; transform: translateY(0);    letter-spacing: 0.1em; filter: blur(0);   }
}
.artist-fade {
  animation: artistFadeKf 0.9s cubic-bezier(0.16, 0.74, 0.22, 1) forwards;
  text-shadow: 0 0 12px rgba(125, 211, 252, 0.6);
}

@keyframes diffSlamKf {
  0%   { opacity: 0; transform: translateY(120%) scale(2.4) skewX(-8deg); filter: blur(10px); }
  55%  { opacity: 1; transform: translateY(-8%)   scale(1.18) skewX(-8deg); filter: blur(0);   }
  75%  { transform: translateY(2%) scale(0.95) skewX(-8deg); }
  100% { opacity: 1; transform: translateY(0)    scale(1)    skewX(-8deg); filter: blur(0);   }
}
.diff-slam {
  animation: diffSlamKf 0.9s cubic-bezier(0.22, 0.95, 0.28, 1.15) forwards;
}

@keyframes diffGlowKf {
  0%, 100% { filter: drop-shadow(0 0 12px currentColor) drop-shadow(0 0 30px currentColor); }
  50%      { filter: drop-shadow(0 0 24px currentColor) drop-shadow(0 0 60px currentColor); }
}
.diff-text {
  animation: diffGlowKf 2.2s ease-in-out infinite;
}
.diff-another {
  color: #fb923c;
  background: linear-gradient(180deg, #fffbeb 0%, #fb923c 50%, #b91c1c 100%);
  -webkit-background-clip: text;
  background-clip: text;
  -webkit-text-fill-color: transparent;
  text-shadow: 0 0 30px rgba(251, 146, 60, 0.7);
}
.diff-leggendaria {
  color: #fbbf24;
  background: linear-gradient(180deg, #fef3c7 0%, #fbbf24 50%, #92400e 100%);
  -webkit-background-clip: text;
  background-clip: text;
  -webkit-text-fill-color: transparent;
  text-shadow: 0 0 30px rgba(251, 191, 36, 0.7);
}
</style>
