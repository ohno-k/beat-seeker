<script setup lang="ts">
/**
 * 【Viewの役割】 IIDX 非公式大会向け「選曲発表」演出画面。
 *
 * BEMANI PRO LEAGUE の選曲発表シーンを参考にした派手なリビールアニメ。
 * 主催 (ID=19) と運営担当 (ID=18) のみがサイドバーから到達。
 *
 * 利用フロー:
 *  1. 検索ボックスに曲名を入れて候補を絞り込む
 *  2. ANOTHER / LEGGENDARIA を別行として表示 → 1 行クリックで選曲
 *  3. 選曲済みカードの REVEAL ボタンを押すと演出開始
 *  4. 演出: 背景バースト → タイトル文字を 1 字ずつカスケード →
 *           アーティスト名フェードイン → 難易度バッジが下からスラム着地
 *  5. 終了後は静止表示。リセットで選択画面に戻る。
 *
 * データソース: `song_data.json` (グローバル composable `useGameData`)
 *   - difficulty === '4'  → ANOTHER
 *   - difficulty === '10' → LEGGENDARIA
 *   それ以外は除外 (beat-seeker は ANOTHER/LEGGENDARIA のみ対応)
 *
 * URL: `/song-reveal` (OBS ブラウザソース用のスタンドアロン)。OBS で操作する場合は
 *      Interact パネルから検索 → REVEAL クリックする想定。
 */
import { ref, computed, onMounted, nextTick } from 'vue';
import { useGameData, type SongDataEntry } from '../composables/useGameData';

const { songDataBody, fetchGameData } = useGameData();

onMounted(() => {
  // スタンドアロン URL (`/song-reveal`) から開いた時、composable が未初期化のことがあるので明示的に取得
  if (songDataBody.value.length === 0) {
    fetchGameData();
  }
});

/** ANOTHER (4) / LEGGENDARIA (10) のみに絞った譜面リスト。 */
const playableCharts = computed<SongDataEntry[]>(() => {
  return songDataBody.value.filter(s => s.difficulty === '4' || s.difficulty === '10');
});

const searchQuery = ref('');

/** 難易度コード → 表示名 */
const diffName = (code: string): string => {
  if (code === '10') return 'LEGGENDARIA';
  return 'ANOTHER';
};

/**
 * 検索結果。
 *  - 空クエリ時は何も出さない (リストが膨大なため)
 *  - 部分一致 (大小文字無視)、最大 100 件
 *  - 並び順: タイトル昇順 → 同曲タイトル内では ANOTHER → LEGGENDARIA
 */
const searchResults = computed<SongDataEntry[]>(() => {
  const q = searchQuery.value.trim().toLowerCase();
  if (q.length === 0) return [];
  const matched = playableCharts.value.filter(s => {
    return s.title.toLowerCase().includes(q) || (s.artist && s.artist.toLowerCase().includes(q));
  });
  matched.sort((a, b) => {
    if (a.title !== b.title) return a.title.localeCompare(b.title);
    // 同曲内では ANOTHER (4) を先、LEGGENDARIA (10) を後
    return Number(a.difficulty) - Number(b.difficulty);
  });
  return matched.slice(0, 100);
});

/** 選択中の譜面。null = 未選択。 */
const selectedChart = ref<SongDataEntry | null>(null);

const selectChart = (chart: SongDataEntry) => {
  if (phase.value !== 'select') return;
  selectedChart.value = chart;
};

/**
 * Phase:
 *  - 'select'   : 検索 + 選曲画面
 *  - 'reveal'   : 演出アニメーション再生中
 *  - 'finished' : 演出停止、静止表示
 */
const phase = ref<'select' | 'reveal' | 'finished'>('select');

/** 段階的アニメ用フラグ (個別に opacity / transform を当てる) */
const stage = ref({ burst: false, title: false, artist: false, diffBadge: false });

let stageTimers: number[] = [];

const startReveal = async () => {
  if (!selectedChart.value) return;
  phase.value = 'reveal';
  stage.value = { burst: false, title: false, artist: false, diffBadge: false };

  // 段階的に true にすることで CSS transition / animation が連鎖発火する
  await nextTick();
  stageTimers.push(window.setTimeout(() => { stage.value.burst     = true; }, 50));
  stageTimers.push(window.setTimeout(() => { stage.value.title     = true; }, 300));
  stageTimers.push(window.setTimeout(() => { stage.value.artist    = true; }, 1500));
  stageTimers.push(window.setTimeout(() => { stage.value.diffBadge = true; }, 2300));
  // 完了 (静止状態へ)
  stageTimers.push(window.setTimeout(() => { phase.value = 'finished'; }, 3500));
};

const reset = () => {
  for (const id of stageTimers) clearTimeout(id);
  stageTimers = [];
  phase.value = 'select';
  stage.value = { burst: false, title: false, artist: false, diffBadge: false };
};

/** タイトル文字を 1 字ずつカスケード表示するための配列。Array.from で絵文字/サロゲートペアにも安全。 */
const titleChars = computed<string[]>(() => {
  return selectedChart.value ? Array.from(selectedChart.value.title) : [];
});

// フルスクリーン (Strategy Card と同パターン)
const containerEl = ref<HTMLElement | null>(null);
const isFullscreen = ref(false);
const onFullscreenChange = () => { isFullscreen.value = !!document.fullscreenElement; };
const toggleFullscreen = async () => {
  try {
    if (document.fullscreenElement) await document.exitFullscreen();
    else if (containerEl.value) await containerEl.value.requestFullscreen();
  } catch { /* noop */ }
};
onMounted(() => {
  document.addEventListener('fullscreenchange', onFullscreenChange);
});
</script>

<template>
  <div
    ref="containerEl"
    class="song-reveal-view min-h-screen w-full bg-slate-950 text-white relative overflow-hidden"
    :class="{ 'is-fullscreen': isFullscreen }"
  >
    <!-- 背景: 動く格子 + ネオン光線 (常時) -->
    <div class="absolute inset-0 pointer-events-none">
      <div class="absolute inset-0 bg-gradient-to-br from-slate-950 via-slate-900 to-cyan-950"></div>
      <div class="absolute inset-0 opacity-20 bg-grid"></div>
      <div class="absolute inset-0 neon-streaks opacity-30"></div>
    </div>

    <!-- フルスクリーン切替 -->
    <button
      type="button"
      @click="toggleFullscreen"
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
    <div v-if="phase === 'select'" class="relative z-10 max-w-4xl mx-auto p-4 sm:p-8 space-y-6">
      <div>
        <h1 class="text-3xl sm:text-5xl font-black tracking-tight bg-clip-text text-transparent bg-gradient-to-r from-cyan-300 via-sky-300 to-amber-300 drop-shadow-[0_0_25px_rgba(56,189,248,0.4)]">
          SONG REVEAL
        </h1>
        <p class="text-slate-400 mt-2 text-sm tracking-widest uppercase">選曲発表演出</p>
      </div>

      <!-- 検索 -->
      <div class="relative">
        <svg class="absolute left-4 top-1/2 -translate-y-1/2 h-5 w-5 text-slate-400" fill="none" viewBox="0 0 24 24" stroke="currentColor">
          <path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M21 21l-6-6m2-5a7 7 0 11-14 0 7 7 0 0114 0z" />
        </svg>
        <input
          v-model="searchQuery"
          type="text"
          placeholder="曲名 / アーティストで検索…"
          class="w-full pl-12 pr-4 py-4 text-lg bg-slate-900/70 border-2 border-white/10 focus:border-cyan-400 rounded-2xl text-white placeholder-slate-500 outline-none transition-colors"
        />
      </div>

      <!-- 選択中プレビュー -->
      <div
        v-if="selectedChart"
        class="bg-gradient-to-br from-slate-900 to-slate-800 border border-cyan-500/30 rounded-2xl p-5 flex items-center gap-4 shadow-lg shadow-cyan-500/10"
      >
        <div class="flex-1 min-w-0">
          <p class="text-[10px] font-mono text-cyan-300 tracking-[0.3em]">SELECTED</p>
          <p class="text-xl sm:text-2xl font-black truncate">{{ selectedChart.title }}</p>
          <p class="text-sm text-slate-400 truncate">{{ selectedChart.artist }}</p>
          <div class="flex items-center gap-2 mt-2">
            <span
              class="px-3 py-1 rounded-full text-[10px] font-black tracking-widest uppercase"
              :class="selectedChart.difficulty === '10'
                ? 'bg-amber-500/20 text-amber-300 border border-amber-500/40'
                : 'bg-red-500/20 text-red-300 border border-red-500/40'"
            >
              {{ diffName(selectedChart.difficulty) }}
            </span>
            <span class="px-3 py-1 rounded-full text-[10px] font-black tracking-widest uppercase bg-white/10 border border-white/20">
              Lv{{ selectedChart.level }}
            </span>
          </div>
        </div>
        <button
          @click="startReveal"
          class="shrink-0 px-6 py-4 rounded-2xl text-base sm:text-lg font-black tracking-widest uppercase bg-gradient-to-r from-cyan-500 via-sky-500 to-amber-500 text-white shadow-xl shadow-cyan-500/40 hover:scale-105 hover:-translate-y-0.5 active:scale-95 transition-all relative overflow-hidden"
        >
          <span class="relative z-10">▶ REVEAL</span>
          <span class="absolute inset-0 button-shine pointer-events-none"></span>
        </button>
      </div>

      <!-- 検索結果リスト -->
      <div v-if="searchResults.length > 0" class="bg-slate-900/50 border border-white/10 rounded-2xl overflow-hidden max-h-[55vh] overflow-y-auto custom-scrollbar">
        <button
          v-for="(chart, i) in searchResults"
          :key="`${chart.title}-${chart.difficulty}-${i}`"
          type="button"
          @click="selectChart(chart)"
          class="w-full text-left flex items-center gap-3 px-4 py-3 hover:bg-cyan-500/10 transition-colors border-b border-white/5 last:border-b-0"
          :class="{ 'bg-cyan-500/20': selectedChart === chart }"
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
      <p v-else-if="searchQuery.trim().length > 0" class="text-slate-500 text-sm font-mono text-center py-8">
        該当する曲が見つかりません
      </p>
      <p v-else class="text-slate-500 text-sm font-mono text-center py-8">
        曲名またはアーティスト名で検索してください
      </p>
    </div>

    <!-- ========== Phase: REVEAL / FINISHED ========== -->
    <div
      v-else
      class="relative z-10 min-h-screen flex flex-col items-center justify-center p-8 reveal-stage"
    >
      <!-- バースト演出 (背景の光膜) -->
      <div v-if="stage.burst || phase === 'finished'" class="absolute inset-0 pointer-events-none">
        <div class="burst-radial"></div>
        <div class="burst-ring burst-ring-1"></div>
        <div class="burst-ring burst-ring-2"></div>
        <div class="burst-ring burst-ring-3"></div>
      </div>
      <!-- ネオン光線 (常時) -->
      <div class="absolute inset-0 reveal-rays pointer-events-none"></div>

      <!-- メインカード -->
      <div class="relative z-10 text-center w-full max-w-5xl space-y-6 sm:space-y-10">
        <!-- タイトル: 文字ごとカスケード -->
        <div class="relative">
          <p
            v-if="stage.title || phase === 'finished'"
            class="title-cascade text-5xl sm:text-7xl md:text-8xl font-black tracking-tight break-keep leading-tight"
          >
            <span
              v-for="(ch, i) in titleChars"
              :key="i"
              class="cascade-char inline-block"
              :style="{ animationDelay: `${i * 70}ms` }"
            >{{ ch === ' ' ? ' ' : ch }}</span>
          </p>
        </div>

        <!-- アーティスト -->
        <p
          v-if="stage.artist || phase === 'finished'"
          class="artist-fade text-2xl sm:text-3xl md:text-4xl font-bold tracking-wider text-cyan-100"
        >
          {{ selectedChart?.artist }}
        </p>

        <!-- 難易度バッジ -->
        <div
          v-if="stage.diffBadge || phase === 'finished'"
          class="diff-slam inline-block"
        >
          <p
            class="diff-text text-4xl sm:text-6xl md:text-7xl font-black tracking-widest italic skew-x-[-8deg]"
            :class="selectedChart?.difficulty === '10' ? 'diff-leggendaria' : 'diff-another'"
          >
            {{ diffName(selectedChart?.difficulty || '') }} {{ selectedChart?.level }}
          </p>
        </div>
      </div>

      <!-- リセットボタン (演出終了後のみ表示) -->
      <button
        v-if="phase === 'finished'"
        @click="reset"
        class="absolute bottom-6 right-6 z-30 px-5 py-3 rounded-xl text-xs font-bold tracking-widest uppercase bg-slate-800/70 hover:bg-slate-700 border border-white/10 text-slate-300 hover:text-white backdrop-blur transition-all"
      >
        ◀ Reset
      </button>
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

/* 常時走る斜め光線 */
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

/* REVEAL ボタンの内部光沢 */
@keyframes buttonShine {
  0%   { transform: translateX(-100%); }
  100% { transform: translateX(100%); }
}
.button-shine {
  background: linear-gradient(120deg, transparent 30%, rgba(255, 255, 255, 0.35) 50%, transparent 70%);
  animation: buttonShine 2.5s linear infinite;
}

/* ============ REVEAL ステージ ============ */

.reveal-stage {
  background: radial-gradient(ellipse at center,
    rgba(14, 165, 233, 0.12) 0%,
    rgba(2, 6, 23, 0.95) 60%,
    rgba(2, 6, 23, 1) 100%
  );
}

/* バースト: 中央から外向きにグラデーションが広がる */
@keyframes burstRadialKf {
  0%   { transform: scale(0.2); opacity: 0; }
  20%  { transform: scale(0.5); opacity: 1; }
  100% { transform: scale(2.5); opacity: 0; }
}
.burst-radial {
  position: absolute;
  top: 50%; left: 50%;
  width: 80vmax; height: 80vmax;
  transform: translate(-50%, -50%);
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

/* 背景の回転光線 */
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

/* タイトル: 文字ごとに 1 字ずつ降ってきて着地 */
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

/* アーティスト: ふわっとフェードイン */
@keyframes artistFadeKf {
  0%   { opacity: 0; transform: translateY(10px); letter-spacing: 0.5em; filter: blur(6px); }
  100% { opacity: 1; transform: translateY(0);    letter-spacing: 0.1em; filter: blur(0);   }
}
.artist-fade {
  animation: artistFadeKf 0.9s cubic-bezier(0.16, 0.74, 0.22, 1) forwards;
  text-shadow: 0 0 12px rgba(125, 211, 252, 0.6);
}

/* 難易度バッジ: 下から大きく → ZOOM SLAM 着地 */
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
