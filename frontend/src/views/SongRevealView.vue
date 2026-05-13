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
 *  2. 両方セットしたら REVEAL ボタンで演出フェーズへ遷移
 *     (この時点では両側ともまだ表示されず、待機状態のまま)
 *  3. 演出中は **画面のどこでもクリック** すると次の 1 曲ぶんのアニメが進む:
 *     - 待機中 + クリック → 左公開 (1 曲目)
 *     - 左公開済み + クリック → 右公開 (2 曲目)
 *     - 両方公開済み + クリック → 何もしない (誤爆防止)
 *  4. OBS は「左半分のみ」「右半分のみ」をそれぞれクロップして 2 つのソースにできる
 *
 * 主催 (ID=19) / 運営担当 (ID=18) / ID=23 / ID=210 のみがサイドバーから到達。URL は `/song-reveal`。
 *
 * データソース: `song_data.json` を ANOTHER (4) / LEGGENDARIA (10) のみフィルタ。
 */
import { ref, computed, onMounted, onUnmounted, nextTick } from 'vue';
import { useGameData, type SongDataEntry } from '../composables/useGameData';
import {
  useCompetitionAdmin,
  type CompetitionRevealData,
  type CompetitionRevealMatch,
  type CompetitionRevealPick,
  type CompetitionSongGenre,
} from '../composables/useCompetitionAdmin';
import strategySongs from '../data/strategy_card_songs.json';
import { useToast } from '../composables/useToast';

const { songDataBody, fetchGameData } = useGameData();
const { competitions, fetchCompetitions, fetchRevealData } = useCompetitionAdmin();
const toast = useToast();

onMounted(async () => {
  if (songDataBody.value.length === 0) await fetchGameData();
  document.addEventListener('fullscreenchange', onFullscreenChange);
  // URL パラメータからの自動取り込み (CompetitionAdminView から新規タブで開かれた場合の経路)
  await autoLoadFromUrlParams();
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

// ── 大会から取り込み (CompetitionRevealData) ─────────────
/** 「大会から取り込み」モーダルを開いているか。 */
const isImportModalOpen = ref(false);
/** 選択中の大会 ID (モーダル内 step1)。 */
const selectedImportCompetitionId = ref<number | null>(null);
/** 選択中の大会の reveal データ (matches 一覧)。 */
const importRevealData = ref<CompetitionRevealData | null>(null);
const isImportLoading = ref(false);

/** モーダルを開き、大会一覧の取得が未済なら fetch する。 */
const openImportModal = async () => {
  isImportModalOpen.value = true;
  selectedImportCompetitionId.value = null;
  importRevealData.value = null;
  if (competitions.value.length === 0) {
    try {
      await fetchCompetitions();
    } catch (e) {
      toast.error((e as Error).message);
    }
  }
};
const closeImportModal = () => {
  isImportModalOpen.value = false;
  selectedImportCompetitionId.value = null;
  importRevealData.value = null;
};

/** 大会を選択 → その大会の試合一覧を取得。 */
const handleSelectImportCompetition = async (compId: number) => {
  selectedImportCompetitionId.value = compId;
  isImportLoading.value = true;
  try {
    importRevealData.value = await fetchRevealData(compId);
  } catch (e) {
    toast.error((e as Error).message);
    importRevealData.value = null;
  } finally {
    isImportLoading.value = false;
  }
};

/**
 * 競技 pick (タイトル + 難易度) を SongDataEntry に逆引きする。
 * difficulty: 'A' → '4' / 'L' → '10' でマッチング。見つからなければ null。
 */
const resolveSongData = (pick: CompetitionRevealPick): SongDataEntry | null => {
  const targetDiff = pick.songDiff === 'L' ? '10' : '4';
  return songDataBody.value.find(s => s.title === pick.songTitle && s.difficulty === targetDiff) ?? null;
};

/**
 * StrategyCard 発動時のランダムプール。matchKind の Lv 帯 × 相手 pick のジャンル。
 * frontend に持っている strategy_card_songs.json を使ってクライアント側で抽選する。
 */
type StrategyPoolSong = { id: number; version: string; title: string; diff: 'A' | 'L'; level: number };
const strategyPool = strategySongs as Record<CompetitionSongGenre, Record<string, StrategyPoolSong[]>>;
const LEVELS_FOR_KIND: Record<'vanguard' | 'middle' | 'captain', number[]> = {
  vanguard: [8, 9, 10],
  middle: [11],
  captain: [12],
};

/** matchKind と相手の pick から、ランダム化用プール (genre × Lv帯) を集める。 */
const buildSpinPool = (matchKind: 'vanguard' | 'middle' | 'captain', opponentPick: CompetitionRevealPick): StrategyPoolSong[] => {
  const pool: StrategyPoolSong[] = [];
  for (const lv of LEVELS_FOR_KIND[matchKind]) {
    const arr = strategyPool[opponentPick.songGenre]?.[String(lv)];
    if (arr) pool.push(...arr);
  }
  return pool;
};

/** 「相手の pick が strategy で置き換わるべきか」を判定して、置換後の pick を返す (or 元の pick)。 */
const applyStrategyIfNeeded = (
  match: CompetitionRevealMatch,
  side: 'a' | 'b',
): CompetitionRevealPick | null => {
  // side の自分の pick を返す関数。相手が strategy を使った場合、自分の pick がランダム化される。
  const myPick = side === 'a' ? match.playerAPick : match.playerBPick;
  const opponentUsedStrategy = side === 'a' ? match.playerBStrategyUsed : match.playerAStrategyUsed;
  if (!myPick) return null;
  if (!opponentUsedStrategy) return myPick;

  // 相手が strategy を使ったので、自分の pick (= myPick のジャンル) を Lv 帯内でランダム化する
  const pool = buildSpinPool(match.matchKind, myPick);
  if (pool.length === 0) return myPick;
  const random = pool[Math.floor(Math.random() * pool.length)];
  return {
    songGenre: myPick.songGenre,
    songLevel: random.level,
    songStrategyId: random.id,
    songTitle: random.title,
    songDiff: random.diff,
  };
};

/**
 * 試合を選択 → 左右にプレイヤー名と曲を流し込む。
 * playerA → left, playerB → right にマッピング。
 * strategy が使われた側は相手 pick がランダム抽選結果に差し替わる。
 */
const handleApplyMatchToReveal = (match: CompetitionRevealMatch) => {
  if (!match.playerAName || !match.playerBName) {
    toast.error('両サイドにプレイヤーがアサインされていない試合は取り込めません');
    return;
  }
  if (!match.playerAPick || !match.playerBPick) {
    toast.error('両サイドの自選曲が揃っていない試合は取り込めません');
    return;
  }

  // 元の自選曲 (original)
  const songOriginalA = resolveSongData(match.playerAPick);
  const songOriginalB = resolveSongData(match.playerBPick);
  // ストラテジー発動で置き換わる曲 (effective)。発動無しなら original と同じ。
  const effectiveA = applyStrategyIfNeeded(match, 'a');
  const effectiveB = applyStrategyIfNeeded(match, 'b');
  if (!effectiveA || !effectiveB) {
    toast.error('自選曲の解決に失敗しました');
    return;
  }
  const songEffectiveA = resolveSongData(effectiveA);
  const songEffectiveB = resolveSongData(effectiveB);

  if (!songOriginalA || !songOriginalB || !songEffectiveA || !songEffectiveB) {
    toast.error('SongData に該当曲が見つかりません');
    return;
  }

  leftPlayer.value = match.playerAName;
  rightPlayer.value = match.playerBName;
  // R-3: 初期表示は ORIGINAL。スピン着地後に effective に置換される。
  selectedLeft.value = songOriginalA;
  selectedRight.value = songOriginalB;
  effectiveLeftSong.value = songEffectiveA;
  effectiveRightSong.value = songEffectiveB;
  // Strategy 申告フラグを反映。左 = playerA、右 = playerB の申告。
  strategyDeclaredByLeft.value = match.playerAStrategyUsed;
  strategyDeclaredByRight.value = match.playerBStrategyUsed;

  const noteParts: string[] = [];
  if (match.playerAStrategyUsed) noteParts.push('A 側が StrategyCard 発動 → B の曲をランダム化');
  if (match.playerBStrategyUsed) noteParts.push('B 側が StrategyCard 発動 → A の曲をランダム化');
  if (noteParts.length > 0) toast.info(noteParts.join(' / '));
  toast.success(`取り込みました: ${match.teamAName} vs ${match.teamBName}`);
  closeImportModal();
};

const KIND_LABEL: Record<'vanguard' | 'middle' | 'captain', string> = {
  vanguard: '先鋒戦',
  middle: '中堅戦',
  captain: '大将戦',
};

/**
 * URL に {@code ?competitionId=X&matchId=Y} があれば、その試合を自動取り込み + REVEAL フェーズへ遷移。
 * CompetitionAdminView の対戦表から「▶ REVEAL を再生」で新規タブを開いた経路で使われる。
 * フェッチに失敗したり対象試合が見つからない場合はトーストでエラー通知し、SELECT フェーズに留まる。
 */
const autoLoadFromUrlParams = async (): Promise<void> => {
  const params = new URLSearchParams(window.location.search);
  const competitionId = Number(params.get('competitionId'));
  const matchId = Number(params.get('matchId'));
  if (!competitionId || !matchId) return;
  try {
    const data = await fetchRevealData(competitionId);
    const match = data.matches.find(m => m.matchId === matchId);
    if (!match) {
      toast.error(`大会 ${competitionId} に match ${matchId} が見つかりません`);
      return;
    }
    handleApplyMatchToReveal(match);
    // handleApplyMatchToReveal は失敗時に selectedLeft/Right を設定しないので、それを判定して遷移
    if (selectedLeft.value && selectedRight.value) {
      phase.value = 'reveal';
      revealStep.value = 0;
    }
  } catch (e) {
    toast.error('大会データの読込に失敗しました: ' + (e as Error).message);
  }
};

// ── プレイヤー名 (自由入力。REVEAL フェーズ中、各半面の上部に常時表示) ──
const leftPlayer = ref('');
const rightPlayer = ref('');

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
/**
 * R-3: Strategy 演出 step を追加。
 * 0 = 両側未公開 / 1 = 左公開済 / 2 = 両側公開済 / 3 = Strategy 演出 + スピン中。
 * step 3 中に affected 側で抽選スピン → 着地で曲が effective に入れ替わる。
 */
const revealStep = ref<0 | 1 | 2 | 3>(0);

const leftStage  = ref({ burst: false, title: false, artist: false, diffBadge: false });
const rightStage = ref({ burst: false, title: false, artist: false, diffBadge: false });

// R-3: 大会から取り込んだ試合で誰が Strategy Card を申告したか。
// left = playerA (申告すると B/right 側の選曲がランダム化)
// right = playerB (申告すると A/left 側の選曲がランダム化)
const strategyDeclaredByLeft = ref(false);
const strategyDeclaredByRight = ref(false);

/**
 * 「abandoned (original) pick」と「actual played (effective) pick」を別々に保持する。
 *  - selectedLeft / selectedRight: 現在画面に表示中の SongDataEntry。
 *    step 0〜2 は original (= プレイヤーの自選曲) を表示。
 *    step 3 スピン着地後に affected 側のみ effective に置換される。
 *  - effectiveLeft / effectiveRight: ストラテジー発動で抽選された曲 (発動無しなら original と同じ)。
 *    StrategyCardView と同じプール (strategy_card_songs) からクライアント抽選。
 */
const effectiveLeftSong = ref<SongDataEntry | null>(null);
const effectiveRightSong = ref<SongDataEntry | null>(null);

/**
 * 「affected 側」= 相手が Strategy Card を申告した側。スピンアニメの対象。
 * affectingLeft = 右 (B) が申告 → 左 (A) の曲がランダム化される
 * affectingRight = 左 (A) が申告 → 右 (B) の曲がランダム化される
 */
const affectingLeft = computed(() => strategyDeclaredByRight.value);
const affectingRight = computed(() => strategyDeclaredByLeft.value);

// スピンアニメ用ストリップ (StrategyCardView と同パターン)
type StrategyPoolSong2 = { id: number; version: string; title: string; diff: 'A' | 'L'; level: number };
const SPIN_ROW_HEIGHT = 64;        // ストリップ 1 行の高さ (px)
const SPIN_VISIBLE_ROWS = 3;
const spinItemsLeft = ref<StrategyPoolSong2[]>([]);
const spinItemsRight = ref<StrategyPoolSong2[]>([]);
const spinStripLeft = ref<HTMLElement | null>(null);
const spinStripRight = ref<HTMLElement | null>(null);
const spinningLeft = ref(false);
const spinningRight = ref(false);
const spinBurstLeft = ref(false);
const spinBurstRight = ref(false);
/** step 3 前半: 「⚡ STRATEGY CARD ⚡」全画面演出表示中 (約 1.8 秒)。 */
const strategyOverlayActive = ref(false);
let spinTimers: number[] = [];

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
 * REVEAL ボタン (SELECT → REVEAL 遷移) と、REVEAL 中のクリック進行の両方を担う。
 *
 *  - SELECT 中: 演出フェーズへ遷移するだけ。両側とも待機状態 (step 0)。
 *  - REVEAL 中 step 0 → 左公開 (step 1)
 *  - REVEAL 中 step 1 → 右公開 (step 2)
 *  - REVEAL 中 step 2 → 何もしない (誤爆防止)。Reset は専用ボタン。
 */
const onReveal = () => {
  if (phase.value === 'select') {
    if (!selectedLeft.value || !selectedRight.value) return;
    phase.value = 'reveal';
    revealStep.value = 0; // 両側とも待機。次のクリックで左が動き出す。
    return;
  }
  if (revealStep.value === 0) {
    revealStep.value = 1;
    triggerSide('left');
    return;
  }
  if (revealStep.value === 1) {
    revealStep.value = 2;
    triggerSide('right');
    // 両側公開後、Strategy 申告があれば自動的に step 3 (演出 + スピン) へ進める。
    // 右側 reveal アニメ完了後の余韻を待ってから遷移。
    if (strategyDeclaredByLeft.value || strategyDeclaredByRight.value) {
      stageTimers.push(window.setTimeout(() => {
        if (revealStep.value === 2) {
          revealStep.value = 3;
          startStrategySpins();
        }
      }, 3000));
    }
    return;
  }
  if (revealStep.value === 2) {
    // 手動で次へ進むときも Strategy 申告があれば 3 へ
    if (strategyDeclaredByLeft.value || strategyDeclaredByRight.value) {
      revealStep.value = 3;
      startStrategySpins();
    }
    return;
  }
  // revealStep === 3 は誤爆防止で何もしない。Reset 専用。
};

/**
 * Step 3 に入ったとき、まず Strategy Card 演出オーバーレイを表示し、
 * 1.8 秒後にオーバーレイを消してスピンを起動する。
 * affected 側 (相手が strategy 申告) ごとにスロットマシン式スピン → 着地で曲が effective に置換。
 */
const startStrategySpins = () => {
  strategyOverlayActive.value = true;
  spinTimers.push(window.setTimeout(() => {
    strategyOverlayActive.value = false;
    if (affectingLeft.value) startSpinForSide('left');
    if (affectingRight.value) startSpinForSide('right');
  }, 1800));
};

const startSpinForSide = async (side: 'left' | 'right') => {
  const original = side === 'left' ? selectedLeft.value : selectedRight.value;
  const effective = side === 'left' ? effectiveLeftSong.value : effectiveRightSong.value;
  if (!original || !effective) return;

  // プール構築: 元の自選曲のジャンル × 戦の Lv 帯 (strategy_card_songs から)
  // 元の自選曲のジャンル/Lv 帯は match から直接わからないので、effectiveLeftSong/RightSong の
  // genre/level を逆引き or プレイヤー pick の情報をスピン用 ref に保存する方式が必要だが、
  // ここでは「effective が存在する = strategy 発動済」のシンプル前提で
  // 「effective の Lv ± 周辺と同 genre」っぽい曲をランダムプールとする。
  // 簡易実装: 全 strategy_card_songs を flat にしてランダムに 50 件選ぶ (派手な見た目目的)。
  const pool: StrategyPoolSong2[] = [];
  for (const genre of Object.keys(strategyPool) as (keyof typeof strategyPool)[]) {
    for (const lv of Object.keys(strategyPool[genre])) {
      pool.push(...strategyPool[genre][lv]);
    }
  }
  // 着地行を effective の SongDataEntry に最も近いプール曲に統一しておく。
  // タイトル + diff (A/L → 4/10) でマッチさせる。
  const targetDiff = effective.difficulty === '10' ? 'L' : 'A';
  const finalSong: StrategyPoolSong2 = pool.find(s => s.title === effective.title && s.diff === targetDiff)
    ?? { id: 0, version: '', title: effective.title, diff: targetDiff, level: Number(effective.level) };

  // ストリップ構築: ダミー 50 行 → 当選行 → 末尾バッファ 2 行
  const TOTAL_DUMMY = 50;
  const items: StrategyPoolSong2[] = [];
  for (let i = 0; i < TOTAL_DUMMY; i++) {
    items.push(pool[Math.floor(Math.random() * pool.length)]);
  }
  const finalIndex = items.length;
  items.push(finalSong);
  for (let i = 0; i < 2; i++) {
    items.push(pool[Math.floor(Math.random() * pool.length)]);
  }

  // 反応 ref に流し込み + スピン開始
  if (side === 'left') {
    spinItemsLeft.value = items;
    spinningLeft.value = true;
  } else {
    spinItemsRight.value = items;
    spinningRight.value = true;
  }

  await nextTick();
  const strip = side === 'left' ? spinStripLeft.value : spinStripRight.value;
  if (!strip) return;

  // 位置リセット
  strip.style.transition = 'none';
  strip.style.transform = 'translateY(0)';
  void strip.offsetHeight; // 強制 reflow

  const centerRowOffset = Math.floor(SPIN_VISIBLE_ROWS / 2);
  const targetY = -(finalIndex - centerRowOffset) * SPIN_ROW_HEIGHT;
  // 単一 easeOut で 4.5 秒着地 (StrategyCardView の smooth プロファイル相当)
  spinTimers.push(window.setTimeout(() => {
    strip.style.transition = 'transform 4500ms cubic-bezier(0.12, 0.72, 0.20, 1)';
    strip.style.transform = `translateY(${targetY}px)`;
  }, 50));

  // 着地完了 → effective に置換 + burst
  spinTimers.push(window.setTimeout(() => {
    if (side === 'left') {
      selectedLeft.value = effective;
      spinningLeft.value = false;
      spinBurstLeft.value = true;
      window.setTimeout(() => { spinBurstLeft.value = false; }, 1600);
    } else {
      selectedRight.value = effective;
      spinningRight.value = false;
      spinBurstRight.value = true;
      window.setTimeout(() => { spinBurstRight.value = false; }, 1600);
    }
  }, 4700));
};

const reset = () => {
  for (const id of stageTimers) clearTimeout(id);
  stageTimers = [];
  for (const id of spinTimers) clearTimeout(id);
  spinTimers = [];
  phase.value = 'select';
  revealStep.value = 0;
  leftStage.value  = { burst: false, title: false, artist: false, diffBadge: false };
  rightStage.value = { burst: false, title: false, artist: false, diffBadge: false };
  strategyDeclaredByLeft.value = false;
  strategyDeclaredByRight.value = false;
  effectiveLeftSong.value = null;
  effectiveRightSong.value = null;
  spinningLeft.value = false;
  spinningRight.value = false;
  spinItemsLeft.value = [];
  spinItemsRight.value = [];
  spinBurstLeft.value = false;
  spinBurstRight.value = false;
  strategyOverlayActive.value = false;
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
      <div class="flex items-start justify-between gap-3 flex-wrap">
        <div>
          <h1 class="text-3xl sm:text-5xl font-black tracking-tight bg-clip-text text-transparent bg-gradient-to-r from-cyan-300 via-sky-300 to-amber-300 drop-shadow-[0_0_25px_rgba(56,189,248,0.4)]">
            SONG REVEAL
          </h1>
          <p class="text-slate-400 mt-2 text-sm tracking-widest uppercase">選曲発表演出 (2 曲対応 / 左右分割)</p>
        </div>
        <button
          type="button"
          @click="openImportModal"
          class="px-4 py-2 rounded-xl text-xs font-black tracking-widest uppercase bg-gradient-to-r from-violet-500 via-fuchsia-500 to-amber-500 text-white hover:shadow-lg transition-all"
          title="主催権限でログイン中の場合のみ動作"
        >
          📥 大会から取り込み
        </button>
      </div>

      <!-- プレイヤー名 (自由入力。REVEAL 時に各半面の上部に表示) -->
      <div class="grid grid-cols-2 gap-3">
        <div>
          <label class="block text-[10px] font-mono text-cyan-300 tracking-[0.3em] mb-1">LEFT PLAYER</label>
          <input
            v-model="leftPlayer"
            type="text"
            maxlength="40"
            placeholder="プレイヤー名を入力"
            class="w-full px-4 py-3 bg-slate-900/70 border-2 border-white/10 focus:border-cyan-400 rounded-xl text-white placeholder-slate-500 outline-none transition-colors"
          />
        </div>
        <div>
          <label class="block text-[10px] font-mono text-amber-300 tracking-[0.3em] mb-1">RIGHT PLAYER</label>
          <input
            v-model="rightPlayer"
            type="text"
            maxlength="40"
            placeholder="プレイヤー名を入力"
            class="w-full px-4 py-3 bg-slate-900/70 border-2 border-white/10 focus:border-amber-400 rounded-xl text-white placeholder-slate-500 outline-none transition-colors"
          />
        </div>
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

    <!-- ========== 大会から取り込みモーダル ==========
         主催 (4 ID) ログイン状態で開く想定。SELECT フェーズ中のみ起動可能。
         競技側で組まれた matchups から 1 試合を選ぶと、左右のプレイヤー名 + 自選曲が
         自動的にスロットへ流し込まれる。StrategyCard が使われていた側は
         相手の pick が strategy_card_songs プール内でランダム化される。 -->
    <div
      v-if="isImportModalOpen"
      class="fixed inset-0 z-50 bg-black/70 backdrop-blur-sm flex items-center justify-center p-4"
      @click.self="closeImportModal"
    >
      <div class="bg-slate-900 border border-slate-700 rounded-2xl max-w-3xl w-full max-h-[85vh] overflow-hidden flex flex-col">
        <!-- ヘッダ -->
        <div class="px-5 py-3 border-b border-slate-700 flex items-center justify-between">
          <p class="text-sm font-black tracking-widest uppercase text-cyan-300">大会から取り込み</p>
          <button type="button" @click="closeImportModal" class="text-slate-400 hover:text-white text-xl leading-none">×</button>
        </div>

        <!-- Step 1: 大会選択 -->
        <div v-if="!selectedImportCompetitionId" class="flex-1 overflow-y-auto">
          <p class="px-5 py-2 text-[10px] font-mono uppercase tracking-widest text-slate-500">Step 1: 大会を選ぶ</p>
          <ul v-if="competitions.length > 0" class="divide-y divide-slate-800">
            <li
              v-for="c in competitions"
              :key="c.id"
              class="px-5 py-3 hover:bg-slate-800 cursor-pointer transition-colors"
              @click="handleSelectImportCompetition(c.id)"
            >
              <p class="font-bold">{{ c.name }}</p>
              <p class="text-[11px] text-slate-400 font-mono">ID #{{ c.id }} · status: {{ c.status }} · 作成 {{ new Date(c.createdAt).toLocaleString() }}</p>
            </li>
          </ul>
          <p v-else class="px-5 py-8 text-center text-slate-500 text-sm">取り込める大会がありません (主催権限でログインしていることを確認してください)</p>
        </div>

        <!-- Step 2: 試合選択 -->
        <div v-else class="flex-1 flex flex-col min-h-0">
          <div class="px-5 py-2 flex items-center justify-between border-b border-slate-800">
            <p class="text-[10px] font-mono uppercase tracking-widest text-slate-500">
              Step 2: 試合を選ぶ - <span class="text-cyan-300">{{ importRevealData?.competitionName ?? '...' }}</span>
            </p>
            <button
              type="button"
              @click="selectedImportCompetitionId = null; importRevealData = null"
              class="text-[10px] text-slate-400 hover:text-white"
            >← 大会を選び直す</button>
          </div>
          <div v-if="isImportLoading" class="px-5 py-8 text-center text-slate-500 text-sm">読み込み中…</div>
          <ul v-else-if="importRevealData && importRevealData.matches.length > 0" class="flex-1 overflow-y-auto divide-y divide-slate-800">
            <li
              v-for="m in importRevealData.matches"
              :key="m.matchId"
              class="px-5 py-3 hover:bg-slate-800 cursor-pointer transition-colors"
              :class="(!m.playerAPick || !m.playerBPick) ? 'opacity-50' : ''"
              @click="handleApplyMatchToReveal(m)"
            >
              <div class="flex items-center justify-between gap-2">
                <p class="font-bold text-sm">
                  <span class="text-cyan-300">{{ m.playerAName ?? '?' }}</span>
                  <span class="text-slate-500 mx-1">({{ m.teamAName }})</span>
                  <span class="text-slate-500 mx-2">vs</span>
                  <span class="text-amber-300">{{ m.playerBName ?? '?' }}</span>
                  <span class="text-slate-500 mx-1">({{ m.teamBName }})</span>
                </p>
                <p class="text-[10px] font-mono text-slate-400 uppercase tracking-widest shrink-0">
                  M#{{ m.matchupOrder }} · {{ KIND_LABEL[m.matchKind] }}
                  <span v-if="m.requiredGenre" class="ml-1 text-emerald-300">[{{ m.requiredGenre }}]</span>
                </p>
              </div>
              <div class="mt-1 text-[11px] font-mono text-slate-400 flex items-center gap-3 flex-wrap">
                <span v-if="m.playerAPick">A: {{ m.playerAPick.songTitle }}</span>
                <span v-else class="text-rose-400">A: 未提出</span>
                <span v-if="m.playerBPick">B: {{ m.playerBPick.songTitle }}</span>
                <span v-else class="text-rose-400">B: 未提出</span>
                <span v-if="m.playerAStrategyUsed" class="text-fuchsia-300">⚡ A 発動</span>
                <span v-if="m.playerBStrategyUsed" class="text-fuchsia-300">⚡ B 発動</span>
              </div>
            </li>
          </ul>
          <p v-else class="px-5 py-8 text-center text-slate-500 text-sm">この大会には試合がありません</p>
        </div>
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

        <!-- プレイヤー名バナー (REVEAL 突入時から常時表示) -->
        <div v-if="leftPlayer" class="player-banner player-banner-left absolute top-8 left-0 right-0 z-20 text-center pointer-events-none">
          <p class="inline-block px-6 py-2 text-3xl sm:text-5xl md:text-6xl lg:text-7xl font-black tracking-wider text-white">
            {{ leftPlayer }}
          </p>
        </div>

        <!-- 待機表示 (まだ公開されていない) -->
        <div v-if="!leftStage.title" class="relative text-center text-slate-700 font-mono text-xs tracking-[0.4em] z-10">
          <p>LEFT SIDE</p>
          <p class="text-slate-800 mt-1">...</p>
        </div>

        <!-- バースト (通常 reveal + Strategy 着地で共通) -->
        <div v-if="leftStage.burst || spinBurstLeft" class="absolute inset-0 pointer-events-none">
          <div class="burst-radial"></div>
          <div class="burst-ring burst-ring-1"></div>
          <div class="burst-ring burst-ring-2"></div>
          <div class="burst-ring burst-ring-3"></div>
        </div>

        <!--
          R-3: Strategy 抽選スピンストリップ (左)。
          spinningLeft = true の間表示し、着地で selectedLeft が effective に置換されると同時に消える。
        -->
        <div v-if="spinningLeft" class="relative z-10 w-full max-w-2xl px-2">
          <div
            class="relative overflow-hidden rounded-2xl border-2 border-amber-300/40 bg-slate-950/80 backdrop-blur"
            :style="{ height: `${SPIN_ROW_HEIGHT * SPIN_VISIBLE_ROWS}px` }"
          >
            <div
              class="absolute left-0 right-0 pointer-events-none z-20 border-y-2 border-amber-300 bg-amber-300/10"
              :style="{ top: `${SPIN_ROW_HEIGHT * Math.floor(SPIN_VISIBLE_ROWS / 2)}px`, height: `${SPIN_ROW_HEIGHT}px` }"
            ></div>
            <div class="absolute inset-x-0 top-0 z-20 pointer-events-none bg-gradient-to-b from-slate-950 to-transparent" :style="{ height: `${SPIN_ROW_HEIGHT}px` }"></div>
            <div class="absolute inset-x-0 bottom-0 z-20 pointer-events-none bg-gradient-to-t from-slate-950 to-transparent" :style="{ height: `${SPIN_ROW_HEIGHT}px` }"></div>
            <div ref="spinStripLeft" class="strategy-spin-strip will-change-transform">
              <div
                v-for="(s, i) in spinItemsLeft"
                :key="i"
                class="flex flex-col items-center justify-center px-3 text-center"
                :style="{ height: `${SPIN_ROW_HEIGHT}px` }"
              >
                <p class="text-base sm:text-xl font-black text-white truncate max-w-full">{{ s.title }}</p>
                <p class="text-[10px] font-mono text-slate-400 mt-0.5 truncate max-w-full">
                  {{ s.version }} · Lv{{ s.level }} · {{ s.diff === 'L' ? 'LEGGENDARIA' : 'ANOTHER' }}
                </p>
              </div>
            </div>
          </div>
          <p class="mt-4 text-[10px] font-mono uppercase tracking-[0.4em] text-amber-300 text-center strategy-flicker">
            ⚡ Strategy Card Spinning ⚡
          </p>
        </div>

        <!-- 曲情報 (左) -->
        <div v-show="!spinningLeft" class="relative z-10 text-center w-full max-w-4xl space-y-6 sm:space-y-10 md:space-y-12 px-2">
          <p
            v-if="leftStage.title"
            class="title-cascade text-6xl sm:text-7xl md:text-8xl lg:text-9xl font-black tracking-tight leading-tight"
          >
            <span
              v-for="(ch, i) in titleCharsOf(selectedLeft)"
              :key="`${selectedLeft?.title}-${i}`"
              class="cascade-char inline-block"
              :style="{ animationDelay: `${i * 70}ms` }"
            >{{ ch === ' ' ? ' ' : ch }}</span>
          </p>
          <p
            v-if="leftStage.artist"
            class="artist-fade text-xl sm:text-2xl md:text-3xl lg:text-4xl font-bold tracking-wider text-cyan-100"
          >
            {{ selectedLeft?.artist }}
          </p>
          <div v-if="leftStage.diffBadge" class="diff-slam inline-block">
            <p
              class="diff-text text-4xl sm:text-6xl md:text-7xl lg:text-8xl font-black tracking-widest italic skew-x-[-8deg]"
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

        <!-- プレイヤー名バナー -->
        <div v-if="rightPlayer" class="player-banner player-banner-right absolute top-8 left-0 right-0 z-20 text-center pointer-events-none">
          <p class="inline-block px-6 py-2 text-3xl sm:text-5xl md:text-6xl lg:text-7xl font-black tracking-wider text-white">
            {{ rightPlayer }}
          </p>
        </div>

        <div v-if="!rightStage.title" class="relative text-center text-slate-700 font-mono text-xs tracking-[0.4em] z-10">
          <p>RIGHT SIDE</p>
          <p class="text-slate-800 mt-1">...</p>
        </div>

        <div v-if="rightStage.burst || spinBurstRight" class="absolute inset-0 pointer-events-none">
          <div class="burst-radial"></div>
          <div class="burst-ring burst-ring-1"></div>
          <div class="burst-ring burst-ring-2"></div>
          <div class="burst-ring burst-ring-3"></div>
        </div>

        <!-- R-3: Strategy 抽選スピンストリップ (右) -->
        <div v-if="spinningRight" class="relative z-10 w-full max-w-2xl px-2">
          <div
            class="relative overflow-hidden rounded-2xl border-2 border-amber-300/40 bg-slate-950/80 backdrop-blur"
            :style="{ height: `${SPIN_ROW_HEIGHT * SPIN_VISIBLE_ROWS}px` }"
          >
            <div
              class="absolute left-0 right-0 pointer-events-none z-20 border-y-2 border-amber-300 bg-amber-300/10"
              :style="{ top: `${SPIN_ROW_HEIGHT * Math.floor(SPIN_VISIBLE_ROWS / 2)}px`, height: `${SPIN_ROW_HEIGHT}px` }"
            ></div>
            <div class="absolute inset-x-0 top-0 z-20 pointer-events-none bg-gradient-to-b from-slate-950 to-transparent" :style="{ height: `${SPIN_ROW_HEIGHT}px` }"></div>
            <div class="absolute inset-x-0 bottom-0 z-20 pointer-events-none bg-gradient-to-t from-slate-950 to-transparent" :style="{ height: `${SPIN_ROW_HEIGHT}px` }"></div>
            <div ref="spinStripRight" class="strategy-spin-strip will-change-transform">
              <div
                v-for="(s, i) in spinItemsRight"
                :key="i"
                class="flex flex-col items-center justify-center px-3 text-center"
                :style="{ height: `${SPIN_ROW_HEIGHT}px` }"
              >
                <p class="text-base sm:text-xl font-black text-white truncate max-w-full">{{ s.title }}</p>
                <p class="text-[10px] font-mono text-slate-400 mt-0.5 truncate max-w-full">
                  {{ s.version }} · Lv{{ s.level }} · {{ s.diff === 'L' ? 'LEGGENDARIA' : 'ANOTHER' }}
                </p>
              </div>
            </div>
          </div>
          <p class="mt-4 text-[10px] font-mono uppercase tracking-[0.4em] text-amber-300 text-center strategy-flicker">
            ⚡ Strategy Card Spinning ⚡
          </p>
        </div>

        <div v-show="!spinningRight" class="relative z-10 text-center w-full max-w-4xl space-y-6 sm:space-y-10 md:space-y-12 px-2">
          <p
            v-if="rightStage.title"
            class="title-cascade text-6xl sm:text-7xl md:text-8xl lg:text-9xl font-black tracking-tight leading-tight"
          >
            <span
              v-for="(ch, i) in titleCharsOf(selectedRight)"
              :key="`${selectedRight?.title}-${i}`"
              class="cascade-char inline-block"
              :style="{ animationDelay: `${i * 70}ms` }"
            >{{ ch === ' ' ? ' ' : ch }}</span>
          </p>
          <p
            v-if="rightStage.artist"
            class="artist-fade text-xl sm:text-2xl md:text-3xl lg:text-4xl font-bold tracking-wider text-cyan-100"
          >
            {{ selectedRight?.artist }}
          </p>
          <div v-if="rightStage.diffBadge" class="diff-slam inline-block">
            <p
              class="diff-text text-4xl sm:text-6xl md:text-7xl lg:text-8xl font-black tracking-widest italic skew-x-[-8deg]"
              :class="selectedRight?.difficulty === '10' ? 'diff-leggendaria' : 'diff-another'"
            >
              {{ diffName(selectedRight?.difficulty || '') }} {{ selectedRight?.level }}
            </p>
          </div>
        </div>
      </div>

      <!--
        R-3: Strategy 演出オーバーレイ。step 3 進入直後の約 1.8 秒だけ表示し、
        その後消えてスピンアニメに切り替わる。
      -->
      <div
        v-if="strategyOverlayActive"
        class="absolute inset-0 z-50 flex items-center justify-center pointer-events-none strategy-overlay-fade"
      >
        <div class="absolute inset-0 bg-black/60 backdrop-blur-sm"></div>
        <div class="relative text-center px-8 py-10 space-y-6 max-w-3xl">
          <p class="text-xs sm:text-sm font-mono uppercase tracking-[0.6em] text-amber-300 strategy-flicker">
            ⚡ Strategy Card Declared ⚡
          </p>
          <h2 class="text-5xl sm:text-7xl font-black tracking-tight bg-clip-text text-transparent bg-gradient-to-r from-fuchsia-300 via-amber-200 to-fuchsia-300 drop-shadow-[0_0_30px_rgba(252,211,77,0.8)] strategy-pop">
            STRATEGY CARD
          </h2>
          <div class="space-y-2 strategy-fade-in" style="animation-delay: 0.6s">
            <p v-if="strategyDeclaredByLeft" class="text-2xl sm:text-3xl font-black">
              <span class="text-cyan-300">{{ leftPlayer }}</span>
              <span class="text-slate-300 mx-2">の申告</span>
              <span class="text-rose-300 text-base">→ {{ rightPlayer }} の選曲がランダム化</span>
            </p>
            <p v-if="strategyDeclaredByRight" class="text-2xl sm:text-3xl font-black">
              <span class="text-amber-300">{{ rightPlayer }}</span>
              <span class="text-slate-300 mx-2">の申告</span>
              <span class="text-rose-300 text-base">→ {{ leftPlayer }} の選曲がランダム化</span>
            </p>
          </div>
        </div>
      </div>

      <!-- フッター: 進行ガイド + Reset (画面中央下、OBS の左右クロップに被らない極小領域) -->
      <div class="absolute bottom-4 left-1/2 -translate-x-1/2 z-50 flex items-center gap-3">
        <div class="px-4 py-2 rounded-full bg-slate-900/80 border border-white/10 text-[10px] font-mono tracking-widest text-slate-300 backdrop-blur">
          <span v-if="revealStep === 0">CLICK → LEFT</span>
          <span v-else-if="revealStep === 1">CLICK → RIGHT</span>
          <span v-else-if="revealStep === 2">{{ strategyDeclaredByLeft || strategyDeclaredByRight ? 'STRATEGY を表示中…' : 'REVEAL COMPLETE' }}</span>
          <span v-else-if="revealStep === 3">⚡ STRATEGY DECLARED ⚡</span>
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

/* プレイヤー名バナー: REVEAL 突入時にスーッと出現してそのまま居続ける。
   左右で下線アクセントの色を変えて陣営を分かりやすく。 */
@keyframes playerBannerKf {
  0%   { opacity: 0; transform: translateY(-20px); letter-spacing: 0.4em; filter: blur(6px); }
  100% { opacity: 1; transform: translateY(0);     letter-spacing: 0.1em; filter: blur(0);   }
}
.player-banner p {
  animation: playerBannerKf 0.8s cubic-bezier(0.16, 0.74, 0.22, 1) forwards;
  border-bottom: 3px solid currentColor;
  text-shadow: 0 0 14px rgba(255, 255, 255, 0.55);
}
.player-banner-left p  { color: #67e8f9; } /* cyan-300 */
.player-banner-right p { color: #fcd34d; } /* amber-300 */

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
/* LEGGENDARIA 公式ロゴ準拠: 明ピンク → ホットピンク → 濃マゼンタの縦グラデ + 細めの黒アウトライン。
   text-shadow を 4 方向で重ねる方式は文字内部のピンクが見えなくなりがちなので、
   ネイティブの -webkit-text-stroke を使って細い黒輪郭だけを描く。
   グローは .diff-text の filter: drop-shadow アニメーションが担う。 */
.diff-leggendaria {
  color: #ec4899; /* fallback (stroke を持たないブラウザ用) */
  background: linear-gradient(180deg,
    #fdf2f8 0%,    /* ハイライト */
    #fbcfe8 15%,
    #f472b6 45%,   /* メインのホットピンク */
    #db2777 75%,
    #9d174d 100%   /* 下部の影 */
  );
  -webkit-background-clip: text;
  background-clip: text;
  -webkit-text-fill-color: transparent;
  -webkit-text-stroke: 1.5px #0a0a0a;
  paint-order: stroke fill; /* 先に stroke を描いてから fill を上に → 縁が潰れない */
}

/* ===== R-3: Strategy 演出オーバーレイ ===== */
@keyframes strategyOverlayFadeKf {
  0%   { opacity: 0; }
  10%  { opacity: 1; }
  100% { opacity: 1; }
}
.strategy-overlay-fade {
  animation: strategyOverlayFadeKf 0.5s ease-out forwards;
}

@keyframes strategyPopKf {
  0%   { opacity: 0; transform: scale(0.4) rotate(-8deg); filter: blur(20px); }
  40%  { opacity: 1; transform: scale(1.12) rotate(2deg); filter: blur(0); }
  60%  { transform: scale(0.96) rotate(-1deg); }
  100% { opacity: 1; transform: scale(1) rotate(0deg); filter: blur(0); }
}
.strategy-pop {
  animation: strategyPopKf 0.9s cubic-bezier(0.16, 1, 0.3, 1.2) forwards;
}

@keyframes strategyFlickerKf {
  0%, 100% { opacity: 1; text-shadow: 0 0 20px rgba(252, 211, 77, 0.9), 0 0 40px rgba(252, 211, 77, 0.5); }
  45%      { opacity: 0.6; text-shadow: 0 0 8px rgba(252, 211, 77, 0.4); }
  50%      { opacity: 1;   text-shadow: 0 0 32px rgba(252, 211, 77, 1.0), 0 0 60px rgba(252, 211, 77, 0.7); }
  55%      { opacity: 0.6; text-shadow: 0 0 8px rgba(252, 211, 77, 0.4); }
}
.strategy-flicker {
  animation: strategyFlickerKf 1.6s ease-in-out infinite;
}

@keyframes strategyFadeInKf {
  0%   { opacity: 0; transform: translateY(20px); }
  100% { opacity: 1; transform: translateY(0); }
}
.strategy-fade-in {
  opacity: 0;
  animation: strategyFadeInKf 0.6s ease-out forwards;
}

/* Strategy 抽選スピンのストリップ。JS から transform を直接いじって動かす。 */
.strategy-spin-strip {
  display: flex;
  flex-direction: column;
  transform: translateY(0);
}
</style>
