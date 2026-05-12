<script setup lang="ts">
/**
 * 【Viewの役割】 IIDX 非公式大会向け「ストラテジーカード」抽選画面。
 *
 * 大会主催 / 当該ユーザーのみがサイドバーから到達できる隠し機能。
 * ジャンル (NOTES/PEAK/CHORD/CHARGE/SCRATCH/SOF-LAN/INSANE) と
 * 戦 (先鋒/中堅/大将) を選んで「抽選」を押すと、ルーレットがぐるぐる回ったのちに
 * 着地した課題曲を結果として大写しにする。
 *
 * 戦→レベル対応:
 *  - 先鋒 (vanguard): Lv8-10
 *  - 中堅 (middle):   Lv11
 *  - 大将 (captain):  Lv12
 *
 * INSANE はカテゴリ性質上 Lv12 しか存在しないため大将のみ選択可能 (UI で他をロック)。
 *
 * データソース: `frontend/src/data/strategy_card_songs.json`
 *   (プロジェクトルート直下の {genre}.txt を `scripts/build_strategy_cards.cjs` で変換)
 */
import { ref, computed, onUnmounted } from 'vue';
import strategySongs from '../data/strategy_card_songs.json';

type Genre = 'NOTES' | 'PEAK' | 'CHORD' | 'CHARGE' | 'SCRATCH' | 'SOF-LAN' | 'INSANE';
type MatchKind = 'vanguard' | 'middle' | 'captain';

interface Song {
  id: number;
  version: string;
  title: string;
  diff: string;
  level: number;
}

// ジャンル定義 (表示メタ)
const GENRES: { key: Genre; label: string; gradient: string; glow: string; icon: string }[] = [
  { key: 'NOTES',   label: 'NOTES',   gradient: 'from-sky-500 via-blue-500 to-indigo-600',       glow: 'shadow-blue-500/50',    icon: 'M9 19V5l12-2v14M9 9l12-2M5 21a2 2 0 100-4 2 2 0 000 4zm12-2a2 2 0 100-4 2 2 0 000 4z' },
  { key: 'PEAK',    label: 'PEAK',    gradient: 'from-red-500 via-rose-500 to-pink-600',         glow: 'shadow-rose-500/50',    icon: 'M13 7h8m0 0v8m0-8l-8 8-4-4-6 6' },
  { key: 'CHORD',   label: 'CHORD',   gradient: 'from-emerald-500 via-green-500 to-teal-600',    glow: 'shadow-emerald-500/50', icon: 'M4 6h16M4 12h16M4 18h16' },
  { key: 'CHARGE',  label: 'CHARGE',  gradient: 'from-amber-400 via-yellow-500 to-orange-500',   glow: 'shadow-amber-500/50',   icon: 'M13 10V3L4 14h7v7l9-11h-7z' },
  { key: 'SCRATCH', label: 'SCRATCH', gradient: 'from-violet-500 via-purple-500 to-fuchsia-600', glow: 'shadow-purple-500/50',  icon: 'M12 4.354a4 4 0 110 5.292M15 21H3v-1a6 6 0 0112 0v1zm0 0h6v-1a6 6 0 00-9-5.197M13 7a4 4 0 11-8 0 4 4 0 018 0z' },
  { key: 'SOF-LAN', label: 'SOF-LAN', gradient: 'from-pink-500 via-fuchsia-500 to-purple-600',   glow: 'shadow-fuchsia-500/50', icon: 'M3 12h3l3-9 4 18 3-9h5' },
  { key: 'INSANE',  label: 'INSANE',  gradient: 'from-slate-800 via-red-700 to-black',           glow: 'shadow-red-600/60',     icon: 'M12 9v2m0 4h.01m-6.938 4h13.856c1.54 0 2.502-1.667 1.732-3L13.732 4c-.77-1.333-2.694-1.333-3.464 0L3.34 16c-.77 1.333.192 3 1.732 3z' },
];

const MATCHES: { key: MatchKind; label: string; sub: string; levels: number[]; gradient: string }[] = [
  { key: 'vanguard', label: '先鋒戦', sub: 'Lv8 - 10', levels: [8, 9, 10], gradient: 'from-emerald-400 to-teal-500' },
  { key: 'middle',   label: '中堅戦', sub: 'Lv11',      levels: [11],       gradient: 'from-amber-400 to-orange-500' },
  { key: 'captain',  label: '大将戦', sub: 'Lv12',      levels: [12],       gradient: 'from-rose-500 to-red-600' },
];

const songs = strategySongs as Record<Genre, Record<string, Song[]>>;

const selectedGenre = ref<Genre | null>(null);
const selectedMatch = ref<MatchKind | null>(null);

// 抽選状態
const isSpinning = ref(false);
const currentDisplay = ref<Song | null>(null);
const resultSong = ref<Song | null>(null);
const showResultFlash = ref(false);

// アニメーション制御用 timer ID
let spinTimer: number | null = null;
let stopTimer: number | null = null;

/** 候補曲リスト (現在選択中のジャンル × 戦) */
const candidates = computed<Song[]>(() => {
  if (!selectedGenre.value || !selectedMatch.value) return [];
  const match = MATCHES.find(m => m.key === selectedMatch.value)!;
  const pool: Song[] = [];
  for (const lv of match.levels) {
    const arr = songs[selectedGenre.value][String(lv)];
    if (arr) pool.push(...arr);
  }
  return pool;
});

/** INSANE は Lv12 のみ → 先鋒/中堅は無効化 */
const matchDisabled = (m: MatchKind): boolean => {
  if (selectedGenre.value === 'INSANE' && m !== 'captain') return true;
  return false;
};

const canSpin = computed(() => {
  return selectedGenre.value && selectedMatch.value && !isSpinning.value && candidates.value.length > 0;
});

/**
 * 【関数の役割】 抽選を開始する。ルーレットを高速回転させ、徐々に減速して停止する。
 *
 * フロー:
 *  1. 候補プールからランダム値を生成し、最終結果を先に決定 (見た目の遅さに関係なく結果は等確率)
 *  2. setInterval で表示曲を切り替え続ける
 *  3. 一定時間ごとに切替速度を遅くしていく (60ms → 110 → 180 → ...) → 物理ルーレット風の減速
 *  4. 最後に最終結果を確定表示し、結果フラッシュ演出を出す
 */
const spin = () => {
  if (!canSpin.value) return;
  const pool = candidates.value;
  if (pool.length === 0) return;

  isSpinning.value = true;
  resultSong.value = null;
  showResultFlash.value = false;

  const finalIndex = Math.floor(Math.random() * pool.length);
  const finalSong = pool[finalIndex];

  // 減速スケジュール: 各段階での切替間隔(ms) と それを維持する回数
  const phases: { interval: number; ticks: number }[] = [
    { interval: 50,  ticks: 25 },
    { interval: 80,  ticks: 12 },
    { interval: 130, ticks: 8 },
    { interval: 200, ticks: 5 },
    { interval: 320, ticks: 3 },
    { interval: 500, ticks: 2 },
  ];

  let phaseIdx = 0;
  let ticksInPhase = 0;
  let lastShownIdx = -1;

  const showRandom = () => {
    let idx = Math.floor(Math.random() * pool.length);
    // 同じ曲が連続しないように軽く除外
    if (idx === lastShownIdx && pool.length > 1) {
      idx = (idx + 1) % pool.length;
    }
    lastShownIdx = idx;
    currentDisplay.value = pool[idx];
  };

  const scheduleNext = () => {
    const phase = phases[phaseIdx];
    spinTimer = window.setTimeout(() => {
      showRandom();
      ticksInPhase++;
      if (ticksInPhase >= phase.ticks) {
        phaseIdx++;
        ticksInPhase = 0;
      }
      if (phaseIdx >= phases.length) {
        // 最終演出: ゆっくり最後の一回を見せたあと結果に着地
        stopTimer = window.setTimeout(() => {
          currentDisplay.value = finalSong;
          resultSong.value = finalSong;
          isSpinning.value = false;
          showResultFlash.value = true;
          // フラッシュは少し経ったら静まる
          window.setTimeout(() => { showResultFlash.value = false; }, 1500);
        }, 600);
        return;
      }
      scheduleNext();
    }, phase.interval);
  };

  showRandom();
  scheduleNext();
};

const reset = () => {
  if (spinTimer !== null) { clearTimeout(spinTimer); spinTimer = null; }
  if (stopTimer !== null) { clearTimeout(stopTimer); stopTimer = null; }
  isSpinning.value = false;
  currentDisplay.value = null;
  resultSong.value = null;
  showResultFlash.value = false;
};

const selectGenre = (g: Genre) => {
  if (isSpinning.value) return;
  selectedGenre.value = g;
  // INSANE 選択時に先鋒/中堅が選ばれていたらリセット
  if (g === 'INSANE' && selectedMatch.value && selectedMatch.value !== 'captain') {
    selectedMatch.value = null;
  }
  resultSong.value = null;
  currentDisplay.value = null;
};

const selectMatch = (m: MatchKind) => {
  if (isSpinning.value) return;
  if (matchDisabled(m)) return;
  selectedMatch.value = m;
  resultSong.value = null;
  currentDisplay.value = null;
};

onUnmounted(() => {
  if (spinTimer !== null) clearTimeout(spinTimer);
  if (stopTimer !== null) clearTimeout(stopTimer);
});

const activeGenreMeta = computed(() => GENRES.find(g => g.key === selectedGenre.value));
const activeMatchMeta = computed(() => MATCHES.find(m => m.key === selectedMatch.value));
</script>

<template>
  <div class="strategy-card-view min-h-[calc(100vh-4rem)] bg-gradient-to-br from-slate-950 via-slate-900 to-indigo-950 text-white p-4 sm:p-8 relative overflow-hidden">
    <!-- 背景の装飾的グリッド -->
    <div class="absolute inset-0 opacity-10 pointer-events-none bg-grid"></div>

    <!-- ヘッダ -->
    <div class="relative max-w-6xl mx-auto mb-8">
      <h1 class="text-3xl sm:text-5xl font-black tracking-tight bg-clip-text text-transparent bg-gradient-to-r from-cyan-300 via-fuchsia-300 to-amber-300 drop-shadow-[0_0_25px_rgba(186,85,255,0.4)]">
        STRATEGY CARD
      </h1>
      <p class="text-slate-400 mt-2 text-sm tracking-widest uppercase">課題曲ランダム抽選</p>
    </div>

    <!-- メインカード -->
    <div class="relative max-w-6xl mx-auto bg-slate-900/60 backdrop-blur-xl border border-white/10 rounded-3xl shadow-2xl p-6 sm:p-10 space-y-10">

      <!-- Step 1: ジャンル選択 -->
      <section>
        <div class="flex items-center gap-3 mb-4">
          <div class="w-8 h-8 bg-gradient-to-br from-cyan-400 to-blue-600 rounded-full flex items-center justify-center font-black text-sm shadow-lg shadow-cyan-500/40">1</div>
          <h2 class="text-xl font-bold tracking-wide">ジャンル選択</h2>
        </div>
        <div class="grid grid-cols-2 sm:grid-cols-4 lg:grid-cols-7 gap-3">
          <button
            v-for="g in GENRES"
            :key="g.key"
            type="button"
            @click="selectGenre(g.key)"
            :disabled="isSpinning"
            class="genre-card relative group rounded-2xl p-4 text-left transition-all duration-300 overflow-hidden border-2"
            :class="[
              selectedGenre === g.key
                ? `bg-gradient-to-br ${g.gradient} border-white/40 shadow-xl ${g.glow} scale-105 -translate-y-1`
                : 'bg-slate-800/60 border-white/5 hover:border-white/20 hover:bg-slate-800',
              isSpinning ? 'opacity-50 cursor-not-allowed' : 'cursor-pointer'
            ]"
          >
            <div class="flex items-center gap-2 mb-2">
              <svg xmlns="http://www.w3.org/2000/svg" class="h-5 w-5" :class="selectedGenre === g.key ? 'text-white' : 'text-slate-400'" fill="none" viewBox="0 0 24 24" stroke="currentColor">
                <path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" :d="g.icon" />
              </svg>
              <span class="text-[10px] font-mono opacity-70" :class="selectedGenre === g.key ? 'text-white' : 'text-slate-500'">GENRE</span>
            </div>
            <p class="text-lg font-black tracking-wide" :class="selectedGenre === g.key ? 'text-white' : 'text-slate-200'">
              {{ g.label }}
            </p>
            <!-- 光るリング -->
            <div v-if="selectedGenre === g.key" class="absolute inset-0 rounded-2xl ring-2 ring-white/40 animate-pulse pointer-events-none"></div>
          </button>
        </div>
      </section>

      <!-- Step 2: 戦選択 -->
      <section>
        <div class="flex items-center gap-3 mb-4">
          <div class="w-8 h-8 bg-gradient-to-br from-fuchsia-400 to-purple-600 rounded-full flex items-center justify-center font-black text-sm shadow-lg shadow-fuchsia-500/40">2</div>
          <h2 class="text-xl font-bold tracking-wide">戦を選択</h2>
        </div>
        <div class="grid grid-cols-1 sm:grid-cols-3 gap-3">
          <button
            v-for="m in MATCHES"
            :key="m.key"
            type="button"
            @click="selectMatch(m.key)"
            :disabled="isSpinning || matchDisabled(m.key)"
            class="match-card relative rounded-2xl p-5 text-left transition-all duration-300 overflow-hidden border-2"
            :class="[
              selectedMatch === m.key
                ? `bg-gradient-to-br ${m.gradient} border-white/40 shadow-xl scale-[1.02]`
                : matchDisabled(m.key)
                  ? 'bg-slate-900/40 border-white/5 opacity-40 cursor-not-allowed'
                  : 'bg-slate-800/60 border-white/5 hover:border-white/20 hover:bg-slate-800',
              isSpinning ? 'opacity-50 cursor-not-allowed' : ''
            ]"
          >
            <p class="text-2xl font-black tracking-wide mb-1" :class="selectedMatch === m.key ? 'text-white' : 'text-slate-200'">{{ m.label }}</p>
            <p class="text-xs font-mono tracking-widest" :class="selectedMatch === m.key ? 'text-white/80' : 'text-slate-500'">{{ m.sub }}</p>
            <span v-if="matchDisabled(m.key)" class="absolute top-2 right-2 text-[9px] font-bold px-2 py-0.5 rounded bg-slate-700 text-slate-400 uppercase tracking-wider">N/A</span>
            <div v-if="selectedMatch === m.key" class="absolute inset-0 rounded-2xl ring-2 ring-white/40 animate-pulse pointer-events-none"></div>
          </button>
        </div>
        <p v-if="selectedGenre === 'INSANE'" class="mt-3 text-[11px] text-rose-300/80 font-mono">※ INSANE は大将戦 (Lv12) のみ抽選可能</p>
      </section>

      <!-- Step 3: 抽選 -->
      <section>
        <div class="flex items-center gap-3 mb-4">
          <div class="w-8 h-8 bg-gradient-to-br from-amber-400 to-orange-600 rounded-full flex items-center justify-center font-black text-sm shadow-lg shadow-amber-500/40">3</div>
          <h2 class="text-xl font-bold tracking-wide">抽選</h2>
          <span v-if="candidates.length > 0" class="ml-auto text-xs font-mono text-slate-400">候補: <span class="text-cyan-300 font-bold">{{ candidates.length }}</span> 曲</span>
        </div>

        <!-- ルーレット表示エリア -->
        <div class="relative rounded-3xl bg-gradient-to-br from-slate-950 to-slate-900 border-2 border-white/10 p-8 sm:p-12 min-h-[260px] flex items-center justify-center overflow-hidden">
          <!-- 背景パルス -->
          <div v-if="isSpinning" class="absolute inset-0 pointer-events-none">
            <div class="absolute inset-0 bg-gradient-to-r from-cyan-500/20 via-fuchsia-500/20 to-amber-500/20 animate-pulse"></div>
          </div>

          <!-- 結果フラッシュ -->
          <Transition
            enter-active-class="transition-opacity duration-200"
            enter-from-class="opacity-0"
            enter-to-class="opacity-100"
            leave-active-class="transition-opacity duration-700"
            leave-from-class="opacity-100"
            leave-to-class="opacity-0"
          >
            <div v-if="showResultFlash" class="absolute inset-0 bg-white pointer-events-none"></div>
          </Transition>

          <!-- 表示中 / 結果 -->
          <div v-if="currentDisplay" class="relative z-10 text-center w-full">
            <!-- スピン中 -->
            <div v-if="isSpinning" class="space-y-2">
              <p class="text-xs font-mono text-cyan-300 tracking-[0.4em] animate-pulse">DRAWING...</p>
              <p
                :key="currentDisplay.id"
                class="text-2xl sm:text-4xl font-black tracking-tight text-white drop-shadow-[0_0_15px_rgba(96,165,250,0.6)] song-flicker"
              >
                {{ currentDisplay.title }}
              </p>
              <p class="text-xs sm:text-sm font-mono text-slate-400">{{ currentDisplay.version }} / Lv{{ currentDisplay.level }} {{ currentDisplay.diff === 'L' ? 'LEGGENDARIA' : 'ANOTHER' }}</p>
            </div>

            <!-- 結果確定 -->
            <div v-else-if="resultSong" class="result-pop space-y-3">
              <p class="text-xs font-mono tracking-[0.4em]" :class="activeGenreMeta ? 'text-amber-300' : 'text-slate-300'">DECIDED</p>
              <div class="flex items-center justify-center gap-2 flex-wrap">
                <span v-if="activeGenreMeta" class="px-3 py-1 rounded-full text-[10px] font-black tracking-widest uppercase bg-gradient-to-r" :class="activeGenreMeta.gradient">{{ activeGenreMeta.label }}</span>
                <span v-if="activeMatchMeta" class="px-3 py-1 rounded-full text-[10px] font-black tracking-widest uppercase bg-gradient-to-r" :class="activeMatchMeta.gradient">{{ activeMatchMeta.label }}</span>
                <span class="px-3 py-1 rounded-full text-[10px] font-black tracking-widest uppercase bg-white/10 border border-white/20">Lv{{ resultSong.level }}</span>
                <span class="px-3 py-1 rounded-full text-[10px] font-black tracking-widest uppercase" :class="resultSong.diff === 'L' ? 'bg-amber-500/20 text-amber-300 border border-amber-500/40' : 'bg-red-500/20 text-red-300 border border-red-500/40'">
                  {{ resultSong.diff === 'L' ? 'LEGGENDARIA' : 'ANOTHER' }}
                </span>
              </div>
              <p class="text-3xl sm:text-5xl font-black tracking-tight bg-clip-text text-transparent bg-gradient-to-r from-amber-200 via-white to-amber-200 drop-shadow-[0_0_30px_rgba(252,211,77,0.5)] result-title">
                {{ resultSong.title }}
              </p>
              <p class="text-sm font-mono text-slate-300">{{ resultSong.version }}</p>
            </div>
          </div>

          <div v-else class="text-center text-slate-500 font-mono text-sm tracking-wider relative z-10">
            <p>ジャンルと戦を選択してください</p>
          </div>
        </div>

        <!-- アクションボタン -->
        <div class="flex flex-col sm:flex-row gap-3 mt-6">
          <button
            type="button"
            @click="spin"
            :disabled="!canSpin"
            class="flex-1 py-4 px-8 rounded-2xl text-lg font-black tracking-widest uppercase transition-all relative overflow-hidden"
            :class="canSpin
              ? 'bg-gradient-to-r from-cyan-500 via-fuchsia-500 to-amber-500 text-white shadow-xl shadow-fuchsia-500/40 hover:scale-[1.02] hover:-translate-y-0.5 active:scale-95'
              : 'bg-slate-800 text-slate-500 cursor-not-allowed'"
          >
            <span v-if="isSpinning" class="flex items-center justify-center gap-2">
              <svg class="animate-spin h-5 w-5" xmlns="http://www.w3.org/2000/svg" fill="none" viewBox="0 0 24 24">
                <circle class="opacity-25" cx="12" cy="12" r="10" stroke="currentColor" stroke-width="4"></circle>
                <path class="opacity-75" fill="currentColor" d="M4 12a8 8 0 018-8V0C5.373 0 0 5.373 0 12h4z"></path>
              </svg>
              DRAWING...
            </span>
            <span v-else>{{ resultSong ? 'もう一度抽選' : '抽選する' }}</span>
            <!-- ボタンの内部光沢アニメーション -->
            <span v-if="canSpin" class="absolute inset-0 button-shine pointer-events-none"></span>
          </button>
          <button
            v-if="resultSong || currentDisplay"
            type="button"
            @click="reset"
            :disabled="isSpinning"
            class="py-4 px-6 rounded-2xl text-sm font-bold tracking-wider uppercase bg-slate-800/60 border border-white/10 text-slate-300 hover:bg-slate-700 hover:text-white transition-all"
          >
            リセット
          </button>
        </div>
      </section>
    </div>
  </div>
</template>

<style scoped>
.bg-grid {
  background-image:
    linear-gradient(rgba(139, 92, 246, 0.4) 1px, transparent 1px),
    linear-gradient(90deg, rgba(139, 92, 246, 0.4) 1px, transparent 1px);
  background-size: 40px 40px;
}

/* スピン中のタイトル文字をチカチカ */
@keyframes songFlicker {
  0%, 100% { opacity: 1; transform: translateY(0) scale(1); }
  50% { opacity: 0.85; transform: translateY(-2px) scale(1.02); }
}
.song-flicker {
  animation: songFlicker 0.18s ease-in-out infinite;
}

/* 結果確定時のポップ */
@keyframes resultPop {
  0%   { opacity: 0; transform: scale(0.85); filter: blur(8px); }
  60%  { opacity: 1; transform: scale(1.08); filter: blur(0); }
  100% { opacity: 1; transform: scale(1);   filter: blur(0); }
}
.result-pop {
  animation: resultPop 0.7s cubic-bezier(0.16, 1, 0.3, 1);
}

@keyframes titleGlow {
  0%, 100% { text-shadow: 0 0 30px rgba(252, 211, 77, 0.5); }
  50%      { text-shadow: 0 0 50px rgba(252, 211, 77, 0.9), 0 0 80px rgba(255, 255, 255, 0.4); }
}
.result-title {
  animation: titleGlow 2.4s ease-in-out infinite;
}

/* 抽選ボタンの内部光沢 */
@keyframes buttonShine {
  0%   { transform: translateX(-100%); }
  100% { transform: translateX(100%); }
}
.button-shine {
  background: linear-gradient(120deg, transparent 30%, rgba(255, 255, 255, 0.35) 50%, transparent 70%);
  animation: buttonShine 2.5s linear infinite;
}
</style>
