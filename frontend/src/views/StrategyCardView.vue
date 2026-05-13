<script setup lang="ts">
/**
 * 【Viewの役割】 IIDX 非公式大会向け「ストラテジーカード」抽選画面。
 *
 * 大会主催 / 当該ユーザーのみがサイドバーから到達できる隠し機能。
 * ジャンル (NOTES/PEAK/CHORD/CHARGE/SCRATCH/SOF-LAN/INSANE/12ALL) と
 * 戦 (先鋒/中堅/大将) を選んで「抽選」を押すと、ルーレットがぐるぐる回ったのちに
 * 着地した課題曲を結果として大写しにする。
 *
 * 戦→レベル対応:
 *  - 先鋒 (vanguard): Lv8-10
 *  - 中堅 (middle):   Lv11
 *  - 大将 (captain):  Lv12
 *
 * INSANE / 12ALL はカテゴリ性質上 Lv12 しか存在しないため大将のみ選択可能 (UI で他をロック)。
 *
 * データソース: `frontend/src/data/strategy_card_songs.json`
 *   (プロジェクトルート直下の {genre}.txt を `scripts/build_strategy_cards.cjs` で変換)
 */
import { ref, computed, onUnmounted, onMounted, nextTick } from 'vue';
import strategySongs from '../data/strategy_card_songs.json';

type Genre = 'NOTES' | 'PEAK' | 'CHORD' | 'CHARGE' | 'SCRATCH' | 'SOF-LAN' | 'INSANE' | '12ALL';
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
  { key: '12ALL',   label: '12ALL',   gradient: 'from-yellow-300 via-fuchsia-400 to-cyan-300',   glow: 'shadow-fuchsia-400/50', icon: 'M11.049 2.927c.3-.921 1.603-.921 1.902 0l1.519 4.674a1 1 0 00.95.69h4.915c.969 0 1.371 1.24.588 1.81l-3.976 2.888a1 1 0 00-.363 1.118l1.518 4.674c.3.922-.755 1.688-1.538 1.118l-3.976-2.888a1 1 0 00-1.176 0l-3.976 2.888c-.783.57-1.838-.197-1.538-1.118l1.518-4.674a1 1 0 00-.363-1.118l-3.976-2.888c-.783-.57-.38-1.81.588-1.81h4.914a1 1 0 00.951-.69l1.519-4.674z' },
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
const resultSong = ref<Song | null>(null);
// 当選時の演出フラグ。リング波紋 + 画面端ゴールドフラッシュをトリガする。
// 旧実装の「全画面ホワイトアウト」はタイトルが読めなくなるため廃止。
const showResultBurst = ref(false);

// スロットマシン風スクロール用の状態
// 1行の高さ (px) — CSS の .row { height } と同期。
const ROW_HEIGHT = 96;
// ルーレットウィンドウに同時表示する行数。中央行が当選位置。
const VISIBLE_ROWS = 3;
// スクロールのストリップに並べる曲リスト。spin 開始時に毎回生成。
const scrollItems = ref<Song[]>([]);
// ストリップ要素 (CSS transform を JS から直接いじる)
const stripEl = ref<HTMLElement | null>(null);

// アニメーション制御用 timer ID
let stopTimer: number | null = null;
// 抽選を多段階で進めるためのチェーン用タイマー (途中で reset された場合に全部キャンセル)
const stageTimers: number[] = [];

// フルスクリーン状態
const containerEl = ref<HTMLElement | null>(null);
const isFullscreen = ref(false);

const onFullscreenChange = () => {
  isFullscreen.value = !!document.fullscreenElement;
};

const toggleFullscreen = async () => {
  try {
    if (document.fullscreenElement) {
      await document.exitFullscreen();
    } else if (containerEl.value) {
      await containerEl.value.requestFullscreen();
    }
  } catch {
    // フルスクリーン拒否時はサイレントに無視
  }
};

onMounted(() => {
  document.addEventListener('fullscreenchange', onFullscreenChange);
});

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

/** INSANE / 12ALL は Lv12 のみ → 先鋒/中堅は無効化 */
const captainOnlyGenres: Genre[] = ['INSANE', '12ALL'];
const matchDisabled = (m: MatchKind): boolean => {
  if (selectedGenre.value && captainOnlyGenres.includes(selectedGenre.value) && m !== 'captain') return true;
  return false;
};

const canSpin = computed(() => {
  return selectedGenre.value && selectedMatch.value && !isSpinning.value && candidates.value.length > 0;
});

/**
 * 1 ステージぶんの transition 仕様 (張り替えのための情報)。
 * target: そのステージで到達したい translateY (px)
 */
type SpinStage = { target: number; duration: number; easing: string };

/**
 * ランダムに選んだ「抽選パターン」に応じて減速プロファイル (段階配列) を組み立てる。
 *
 * 大会で毎回同じ尺・同じ緩急で止まると相手に傾向を読まれてしまうため、
 * 抽選ごとに以下から 1 つランダム選択し、さらに各段階の所要時間にも揺らぎを入れる。
 *
 *  - 'smooth' (素直)   : 単一の easeOut でそのまま着地。トリックなし。
 *  - 'quick'  (短め)   : 2段階。当選行2行手前で少し減速し、サッと着地。短時間。
 *  - 'tension'(緊張感) : 3段階。当選行 1 行手前でほぼ停止 → 這うように着地 (見せ場)。
 *
 * 同じ確率 (1/3) で選ぶことで、観客視点から「いつ止まりかけるか」が予測しにくくなる。
 */
const buildSpinStages = (finalIndex: number, offsetForRow: (rowIdx: number) => number): SpinStage[] => {
  const r = Math.random();
  const jitter = (base: number, range: number) => base + Math.floor(Math.random() * range);

  if (r < 1 / 3) {
    // smooth: 単発 easeOut で 4.5-5.3 秒
    return [{
      target: offsetForRow(finalIndex),
      duration: jitter(4500, 800),
      easing: 'cubic-bezier(0.12, 0.72, 0.20, 1)',
    }];
  }

  if (r < 2 / 3) {
    // quick: 2段階、合計 約 4.0-4.7 秒
    return [
      {
        target: offsetForRow(finalIndex - 2),
        duration: jitter(2400, 600),
        easing: 'cubic-bezier(0.10, 0.65, 0.30, 1)',
      },
      {
        target: offsetForRow(finalIndex),
        duration: jitter(1500, 400),
        easing: 'cubic-bezier(0.30, 0.50, 0.40, 1)',
      },
    ];
  }

  // tension: 3段階、合計 約 7.0-8.7 秒
  return [
    {
      target: offsetForRow(finalIndex - 4),
      duration: jitter(2800, 700),
      easing: 'cubic-bezier(0.10, 0.70, 0.30, 1)',
    },
    {
      target: offsetForRow(finalIndex - 1),
      duration: jitter(2400, 600),
      easing: 'cubic-bezier(0.15, 0.65, 0.45, 1)',
    },
    {
      target: offsetForRow(finalIndex),
      duration: jitter(1800, 500),
      easing: 'cubic-bezier(0.55, 0.0, 0.45, 1)',
    },
  ];
};

/**
 * 【関数の役割】 抽選を開始する。スロットマシン風に曲名リストが上方向にスクロールし、
 * `buildSpinStages` が選んだランダムプロファイルに従って減速・着地する。
 *
 * フロー:
 *  1. 候補プールからランダムに最終曲を決定
 *  2. ダミー多数 + 当選曲 + 末尾バッファでストリップを生成
 *  3. ランダム選択された減速パターンに従い、transition と translateY を段階ごとに張り替える
 *  4. 全段階終了で結果フラッシュ + 結果セクション表示
 */
const spin = async () => {
  if (!canSpin.value) return;
  const pool = candidates.value;
  if (pool.length === 0) return;

  isSpinning.value = true;
  resultSong.value = null;
  showResultBurst.value = false;

  const finalSong = pool[Math.floor(Math.random() * pool.length)];

  // ストリップ構築: ダミー 60 行 → 当選行 → 末尾バッファ 2 行
  const TOTAL_DUMMY = 60;
  const items: Song[] = [];
  let lastIdx = -1;
  for (let i = 0; i < TOTAL_DUMMY; i++) {
    let idx = Math.floor(Math.random() * pool.length);
    if (pool.length > 1 && idx === lastIdx) idx = (idx + 1) % pool.length;
    lastIdx = idx;
    items.push(pool[idx]);
  }
  const finalIndex = items.length; // 当選行の位置
  items.push(finalSong);
  for (let i = 0; i < 2; i++) {
    items.push(pool[Math.floor(Math.random() * pool.length)]);
  }
  scrollItems.value = items;

  await nextTick();
  const strip = stripEl.value;
  if (!strip) return;

  // 位置リセット
  strip.style.transition = 'none';
  strip.style.transform = 'translateY(0)';
  void strip.offsetHeight; // 強制 reflow

  const centerRowOffset = Math.floor(VISIBLE_ROWS / 2);
  const offsetForRow = (rowIdx: number) => -(rowIdx - centerRowOffset) * ROW_HEIGHT;

  // ランダム選択された段階リストを取得して順番に適用
  const stages = buildSpinStages(finalIndex, offsetForRow);

  let cumulative = 0;
  stages.forEach((stage, i) => {
    const applyStage = () => {
      strip.style.transition = `transform ${stage.duration}ms ${stage.easing}`;
      strip.style.transform = `translateY(${stage.target}px)`;
    };
    if (i === 0) {
      applyStage();
    } else {
      stageTimers.push(window.setTimeout(applyStage, cumulative));
    }
    cumulative += stage.duration;
  });

  // 全段階終了で結果確定
  stopTimer = window.setTimeout(() => {
    resultSong.value = finalSong;
    isSpinning.value = false;
    showResultBurst.value = true;
    // バースト演出 (リング波紋 + エッジフラッシュ) は約 1.6 秒で自然消滅
    window.setTimeout(() => { showResultBurst.value = false; }, 1600);
  }, cumulative);
};

const reset = () => {
  if (stopTimer !== null) { clearTimeout(stopTimer); stopTimer = null; }
  for (const id of stageTimers) clearTimeout(id);
  stageTimers.length = 0;
  isSpinning.value = false;
  resultSong.value = null;
  scrollItems.value = [];
  showResultBurst.value = false;
  if (stripEl.value) {
    stripEl.value.style.transition = 'none';
    stripEl.value.style.transform = 'translateY(0)';
  }
};

const selectGenre = (g: Genre) => {
  if (isSpinning.value) return;
  selectedGenre.value = g;
  // INSANE / 12ALL 選択時に先鋒/中堅が選ばれていたらリセット
  if (captainOnlyGenres.includes(g) && selectedMatch.value && selectedMatch.value !== 'captain') {
    selectedMatch.value = null;
  }
  reset();
};

const selectMatch = (m: MatchKind) => {
  if (isSpinning.value) return;
  if (matchDisabled(m)) return;
  selectedMatch.value = m;
  reset();
};

onUnmounted(() => {
  if (stopTimer !== null) clearTimeout(stopTimer);
  for (const id of stageTimers) clearTimeout(id);
  document.removeEventListener('fullscreenchange', onFullscreenChange);
});

const activeGenreMeta = computed(() => GENRES.find(g => g.key === selectedGenre.value));
const activeMatchMeta = computed(() => MATCHES.find(m => m.key === selectedMatch.value));
</script>

<template>
  <div
    ref="containerEl"
    class="strategy-card-view min-h-[calc(100vh-4rem)] bg-gradient-to-br from-slate-950 via-slate-900 to-indigo-950 text-white p-4 sm:p-8 relative overflow-hidden"
    :class="{ 'is-fullscreen': isFullscreen }"
  >
    <!-- 背景の装飾的グリッド -->
    <div class="absolute inset-0 opacity-10 pointer-events-none bg-grid"></div>

    <!-- フルスクリーン切替ボタン (右上に固定表示) -->
    <button
      type="button"
      @click="toggleFullscreen"
      class="absolute top-4 right-4 z-30 p-2.5 rounded-xl bg-slate-800/70 hover:bg-slate-700 border border-white/10 hover:border-white/30 text-slate-300 hover:text-white backdrop-blur transition-all shadow-lg"
      :title="isFullscreen ? 'フルスクリーン解除' : 'フルスクリーン表示'"
      :aria-label="isFullscreen ? 'フルスクリーン解除' : 'フルスクリーン表示'"
    >
      <!-- 全画面化アイコン (4つの角矢印) / 解除アイコン (内向き矢印) -->
      <svg v-if="!isFullscreen" xmlns="http://www.w3.org/2000/svg" class="h-5 w-5" fill="none" viewBox="0 0 24 24" stroke="currentColor" stroke-width="2">
        <path stroke-linecap="round" stroke-linejoin="round" d="M4 8V4h4M20 8V4h-4M4 16v4h4M20 16v4h-4" />
      </svg>
      <svg v-else xmlns="http://www.w3.org/2000/svg" class="h-5 w-5" fill="none" viewBox="0 0 24 24" stroke="currentColor" stroke-width="2">
        <path stroke-linecap="round" stroke-linejoin="round" d="M9 4v4H5M15 4v4h4M9 20v-4H5M15 20v-4h4" />
      </svg>
    </button>

    <!-- ヘッダ -->
    <div class="relative max-w-6xl mx-auto mb-8 pr-14">
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
        <div class="grid grid-cols-2 sm:grid-cols-4 lg:grid-cols-8 gap-3">
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
        <p v-if="selectedGenre && captainOnlyGenres.includes(selectedGenre)" class="mt-3 text-[11px] text-rose-300/80 font-mono">※ {{ selectedGenre }} は大将戦 (Lv12) のみ抽選可能</p>
      </section>

      <!-- Step 3: 抽選 -->
      <section>
        <div class="flex items-center gap-3 mb-4">
          <div class="w-8 h-8 bg-gradient-to-br from-amber-400 to-orange-600 rounded-full flex items-center justify-center font-black text-sm shadow-lg shadow-amber-500/40">3</div>
          <h2 class="text-xl font-bold tracking-wide">抽選</h2>
          <span v-if="candidates.length > 0" class="ml-auto text-xs font-mono text-slate-400">候補: <span class="text-cyan-300 font-bold">{{ candidates.length }}</span> 曲</span>
        </div>

        <!-- ルーレット表示エリア -->
        <div class="relative rounded-3xl bg-gradient-to-br from-slate-950 to-slate-900 border-2 border-white/10 overflow-hidden">
          <!-- 背景パルス -->
          <div v-if="isSpinning" class="absolute inset-0 pointer-events-none z-0">
            <div class="absolute inset-0 bg-gradient-to-r from-cyan-500/20 via-fuchsia-500/20 to-amber-500/20 animate-pulse"></div>
          </div>

          <!-- 結果確定セクション (抽選後) -->
          <div v-if="resultSong && !isSpinning" class="relative z-10 p-8 sm:p-12 text-center result-pop space-y-3 overflow-hidden">
            <!-- 背景: ゆっくり回転する光線 (常時) -->
            <div class="absolute inset-0 -z-10 result-rays pointer-events-none"></div>

            <!-- 当選バースト: 金リングが中央から3連波紋 + エッジフラッシュ (1.6 秒で自然消滅) -->
            <div v-if="showResultBurst" class="absolute inset-0 z-20 pointer-events-none">
              <span class="ring-burst ring-burst-1"></span>
              <span class="ring-burst ring-burst-2"></span>
              <span class="ring-burst ring-burst-3"></span>
              <!-- 四辺だけが光るボーダーフラッシュ。中央の文字には被らない。 -->
              <div class="absolute inset-0 edge-flash"></div>
            </div>

            <p class="relative text-xs font-mono tracking-[0.4em] text-amber-300">DECIDED</p>
            <div class="relative flex items-center justify-center gap-2 flex-wrap">
              <span v-if="activeGenreMeta" class="px-3 py-1 rounded-full text-[10px] font-black tracking-widest uppercase bg-gradient-to-r" :class="activeGenreMeta.gradient">{{ activeGenreMeta.label }}</span>
              <span v-if="activeMatchMeta" class="px-3 py-1 rounded-full text-[10px] font-black tracking-widest uppercase bg-gradient-to-r" :class="activeMatchMeta.gradient">{{ activeMatchMeta.label }}</span>
              <span class="px-3 py-1 rounded-full text-[10px] font-black tracking-widest uppercase bg-white/10 border border-white/20">Lv{{ resultSong.level }}</span>
              <span class="px-3 py-1 rounded-full text-[10px] font-black tracking-widest uppercase" :class="resultSong.diff === 'L' ? 'bg-amber-500/20 text-amber-300 border border-amber-500/40' : 'bg-red-500/20 text-red-300 border border-red-500/40'">
                {{ resultSong.diff === 'L' ? 'LEGGENDARIA' : 'ANOTHER' }}
              </span>
            </div>
            <p class="relative text-3xl sm:text-5xl font-black tracking-tight bg-clip-text text-transparent bg-gradient-to-r from-amber-200 via-white to-amber-200 drop-shadow-[0_0_30px_rgba(252,211,77,0.5)] result-title">
              {{ resultSong.title }}
            </p>
            <p class="relative text-sm font-mono text-slate-300">{{ resultSong.version }}</p>
          </div>

          <!-- ルーレット (スクロール) - スピン中は常時表示、停止後も結果が出るまでは見せる -->
          <div
            v-else
            class="roulette-window relative z-10"
            :style="{ height: `${ROW_HEIGHT * VISIBLE_ROWS}px` }"
          >
            <!-- 中央の当選ラインインジケータ -->
            <div
              class="absolute left-0 right-0 pointer-events-none z-20 border-y-2 border-amber-300 bg-amber-300/5"
              :style="{ top: `${ROW_HEIGHT * Math.floor(VISIBLE_ROWS / 2)}px`, height: `${ROW_HEIGHT}px` }"
            >
              <!-- 左右の矢印マーカー -->
              <div class="absolute left-2 top-1/2 -translate-y-1/2 text-amber-300 text-xl font-black drop-shadow-[0_0_8px_rgba(252,211,77,0.8)] animate-pulse">▶</div>
              <div class="absolute right-2 top-1/2 -translate-y-1/2 text-amber-300 text-xl font-black drop-shadow-[0_0_8px_rgba(252,211,77,0.8)] animate-pulse">◀</div>
            </div>

            <!-- 上下のフェードマスク -->
            <div class="absolute inset-x-0 top-0 z-20 pointer-events-none bg-gradient-to-b from-slate-950 to-transparent" :style="{ height: `${ROW_HEIGHT}px` }"></div>
            <div class="absolute inset-x-0 bottom-0 z-20 pointer-events-none bg-gradient-to-t from-slate-950 to-transparent" :style="{ height: `${ROW_HEIGHT}px` }"></div>

            <!-- スクロールするストリップ -->
            <div ref="stripEl" class="strip will-change-transform">
              <template v-if="scrollItems.length > 0">
                <div
                  v-for="(s, i) in scrollItems"
                  :key="i"
                  class="row flex flex-col items-center justify-center px-4"
                  :style="{ height: `${ROW_HEIGHT}px` }"
                >
                  <p class="text-xl sm:text-2xl font-black tracking-tight text-white truncate max-w-full">{{ s.title }}</p>
                  <p class="text-[10px] sm:text-xs font-mono text-slate-400 mt-1 truncate max-w-full">
                    {{ s.version }} <span class="mx-1 text-slate-600">·</span> Lv{{ s.level }} <span class="mx-1 text-slate-600">·</span> {{ s.diff === 'L' ? 'LEGGENDARIA' : 'ANOTHER' }}
                  </p>
                </div>
              </template>
              <!-- アイドル時のプレースホルダ (中央行に1行ぶん) -->
              <template v-else>
                <div class="row" :style="{ height: `${ROW_HEIGHT}px` }"></div>
                <div class="row flex items-center justify-center text-center text-slate-500 font-mono text-sm tracking-wider" :style="{ height: `${ROW_HEIGHT}px` }">
                  <p v-if="!selectedGenre || !selectedMatch">ジャンルと戦を選択してください</p>
                  <p v-else>抽選ボタンを押してください</p>
                </div>
                <div class="row" :style="{ height: `${ROW_HEIGHT}px` }"></div>
              </template>
            </div>
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

/* フルスクリーン時はビューポートを 100vh 占有し、スクロール可能に保つ */
.strategy-card-view.is-fullscreen,
.strategy-card-view:fullscreen {
  min-height: 100vh;
  width: 100vw;
  overflow-y: auto;
}

/* ルーレット (スロットマシン) のスクロールストリップ。
   transition は JS から動的に付け外しする (初回リセット時は無効、抽選開始時に有効化)。 */
.strip {
  display: flex;
  flex-direction: column;
  transform: translateY(0);
}
.row {
  width: 100%;
  display: flex;
  flex-direction: column;
  justify-content: center;
  align-items: center;
  text-align: center;
  border-bottom: 1px solid rgba(148, 163, 184, 0.08);
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

/* === 当選時の演出 (ホワイトアウトの代わり) === */

/* 金色のリングが中央から広がっていく波紋。3連で重ねて派手に。
   mix-blend-mode: screen で背景を持ち上げつつ文字には被らない (リング自体が透過)。 */
@keyframes ringBurst {
  0%   { transform: translate(-50%, -50%) scale(0.2); opacity: 0.0; }
  10%  { opacity: 0.9; }
  100% { transform: translate(-50%, -50%) scale(6.0); opacity: 0; }
}
.ring-burst {
  position: absolute;
  top: 50%;
  left: 50%;
  width: 180px;
  height: 180px;
  border-radius: 9999px;
  border: 4px solid rgba(252, 211, 77, 0.85);
  box-shadow: 0 0 30px rgba(252, 211, 77, 0.6), inset 0 0 30px rgba(252, 211, 77, 0.4);
  mix-blend-mode: screen;
  animation: ringBurst 1.4s cubic-bezier(0.18, 0.85, 0.30, 1) forwards;
}
.ring-burst-1 { animation-delay: 0s; }
.ring-burst-2 { animation-delay: 0.18s; border-color: rgba(255, 255, 255, 0.85); }
.ring-burst-3 { animation-delay: 0.36s; border-color: rgba(252, 165, 100, 0.85); }

/* 画面四辺だけが金色に光ってフェードアウトする。中央の文字は隠さない。 */
@keyframes edgeFlash {
  0%   { box-shadow: inset 0 0 0 0 rgba(252, 211, 77, 0); }
  20%  { box-shadow: inset 0 0 80px 8px rgba(252, 211, 77, 0.9); }
  100% { box-shadow: inset 0 0 120px 0 rgba(252, 211, 77, 0); }
}
.edge-flash {
  animation: edgeFlash 1.4s ease-out forwards;
  border-radius: inherit;
}

/* 結果セクション背後の回転する光線 (常時): 注目を集めつつ視認性は維持。 */
@keyframes raySpin {
  0%   { transform: rotate(0deg); }
  100% { transform: rotate(360deg); }
}
.result-rays {
  background: conic-gradient(
    from 0deg,
    rgba(252, 211, 77, 0.18) 0deg,
    transparent 25deg,
    rgba(252, 211, 77, 0.18) 60deg,
    transparent 85deg,
    rgba(252, 211, 77, 0.18) 120deg,
    transparent 145deg,
    rgba(252, 211, 77, 0.18) 180deg,
    transparent 205deg,
    rgba(252, 211, 77, 0.18) 240deg,
    transparent 265deg,
    rgba(252, 211, 77, 0.18) 300deg,
    transparent 325deg
  );
  filter: blur(8px);
  animation: raySpin 14s linear infinite;
}
</style>
