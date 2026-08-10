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
import { ref, computed, watch, onMounted, onUnmounted, nextTick } from 'vue';
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
import { useSe } from '../composables/useSe';
import { KIND_LABEL_JA, LEVELS_FOR_KIND } from '../composables/competitionMatchKinds';

const { songDataBody, fetchGameData } = useGameData();
const { competitions, fetchCompetitions, fetchRevealData } = useCompetitionAdmin();
const toast = useToast();
const se = useSe();

onMounted(async () => {
  if (songDataBody.value.length === 0) await fetchGameData();
  // リサイズで半面の幅が変わるため測り直す。
  window.addEventListener('resize', refitAll);
  // Web フォント適用前に測ると幅がずれるので、ロード完了後にもう一度。
  // (FontFaceSet 非対応環境でも以降の初期化を止めないよう握り潰す)
  document.fonts?.ready.then(() => refitAll()).catch(() => { /* noop */ });
  se.preload();
  // URL パラメータからの自動取り込み (CompetitionAdminView から新規タブで開かれた場合の経路)
  await autoLoadFromUrlParams();
});

onUnmounted(() => {
  window.removeEventListener('resize', refitAll);
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
 * StrategyCard 抽選プール (strategy_card_songs.json)。
 * ここでの用途はスピンアニメのルーレットに流すダミー行の生成のみで、
 * 「実際に当たる曲」の抽選には使わない ({@link applyStrategyIfNeeded} 参照)。
 */
type StrategyPoolSong = { id: number; version: string; title: string; diff: 'A' | 'L'; level: number };
const strategyPool = strategySongs as Record<CompetitionSongGenre, Record<string, StrategyPoolSong[]>>;

/**
 * 「相手の pick が strategy で置き換わるべきか」を判定して、置換後の pick を返す (or 元の pick)。
 *
 * 抽選そのものはサーバ側 (reveal データ生成時の遅延抽選) で 1 度だけ確定され、
 * {@code playerAStrategyResult} / {@code playerBStrategyResult} として降ってくる。
 * ここでクライアント抽選をやり直すと、
 *  - REVEAL で発表した曲と管理画面のスコア記録 (= サーバの抽選結果) が食い違う
 *  - リロードや再取り込みのたびに当選曲が変わる
 * ため、必ずサーバが確定した値を使う。
 *
 * 「相手が発動 → 自分の曲がランダム化」なので、side='a' の置換曲は B 側の StrategyUse に
 * 保存された {@code playerBStrategyResult}、side='b' は {@code playerAStrategyResult}。
 */
const applyStrategyIfNeeded = (
  match: CompetitionRevealMatch,
  side: 'a' | 'b',
): CompetitionRevealPick | null => {
  const myPick = side === 'a' ? match.playerAPick : match.playerBPick;
  const opponentUsedStrategy = side === 'a' ? match.playerBStrategyUsed : match.playerAStrategyUsed;
  const drawn = side === 'a' ? match.playerBStrategyResult : match.playerAStrategyResult;
  if (!myPick) return null;
  if (!opponentUsedStrategy) return myPick;
  if (!drawn) {
    // サーバが抽選を保留した状態 (相手の自選曲未提出 / プール空)。
    // ここで独自抽選すると記録と食い違うので、置換せず自選曲のまま扱う。
    console.warn(`[SongReveal] match ${match.matchId} (${side}): strategy 発動だが抽選結果が未確定のため自選曲を表示します`);
    return myPick;
  }
  return {
    songGenre: drawn.songGenre,
    songLevel: drawn.songLevel,
    songStrategyId: drawn.songStrategyId,
    songTitle: drawn.songTitle,
    songDiff: drawn.songDiff,
  };
};

/**
 * 試合を選択 → 左右にプレイヤー名と曲を流し込む。
 * playerA → left, playerB → right にマッピング。
 * strategy が使われた側は相手 pick がランダム抽選結果に差し替わる。
 */
const handleApplyMatchToReveal = (match: CompetitionRevealMatch) => {
  // エラーフィードバックは「手動でモーダルから取り込んだ場合」(= 主催だけが見ている画面) のみトースト表示。
  // URL パラメータ自動読込時 (= 全画面 OBS で映ってる可能性) はトーストを出さず console.warn のみ。
  const notify = (msg: string) => {
    if (isImportModalOpen.value) toast.error(msg);
    else console.warn('[SongReveal]', msg);
  };

  if (!match.playerAName || !match.playerBName) {
    notify('両サイドにプレイヤーがアサインされていない試合は取り込めません');
    return;
  }
  if (!match.playerAPick || !match.playerBPick) {
    notify('両サイドの自選曲が揃っていない試合は取り込めません');
    return;
  }

  // 元の自選曲 (original)
  const songOriginalA = resolveSongData(match.playerAPick);
  const songOriginalB = resolveSongData(match.playerBPick);
  // ストラテジー発動で置き換わる曲 (effective)。発動無しなら original と同じ。
  const effectiveA = applyStrategyIfNeeded(match, 'a');
  const effectiveB = applyStrategyIfNeeded(match, 'b');
  if (!effectiveA || !effectiveB) {
    notify('自選曲の解決に失敗しました');
    return;
  }
  const songEffectiveA = resolveSongData(effectiveA);
  const songEffectiveB = resolveSongData(effectiveB);

  if (!songOriginalA || !songOriginalB || !songEffectiveA || !songEffectiveB) {
    notify('SongData に該当曲が見つかりません');
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

  // スピン用プールの絞り込みコンテキスト (ジャンル × matchKind の Lv 帯) を保存
  const levels = LEVELS_FOR_KIND[match.matchKind];
  spinContextLeft.value  = { genre: match.playerAPick.songGenre, levels };
  spinContextRight.value = { genre: match.playerBPick.songGenre, levels };

  // ネタバレ防止のため、Strategy 発動の有無や曲名などの告知トーストは出さない。
  // (OBS キャプチャや会場映像に映ったら台無しになる)
  closeImportModal();
};

const KIND_LABEL = KIND_LABEL_JA;

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
      // ネタバレを避けるためトーストは出さず、コンソールにだけ記録する。
      // (会場画面に「match が見つかりません」と出ると Strategy 発動が読まれる恐れもあるため)
      console.warn(`[SongReveal] 大会 ${competitionId} に match ${matchId} が見つかりません`);
      return;
    }
    handleApplyMatchToReveal(match);
    // handleApplyMatchToReveal は失敗時に selectedLeft/Right を設定しないので、それを判定して遷移
    if (selectedLeft.value && selectedRight.value) {
      phase.value = 'reveal';
      revealStep.value = 0;
    }
  } catch (e) {
    console.warn('[SongReveal] 大会データの読込に失敗:', e);
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
 *    抽選はサーバ側で確定済みの値 (playerXStrategyResult) をそのまま使うため、
 *    リロードしても当選曲は変わらず、管理画面のスコア記録とも一致する。
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

/**
 * スピン用プール構築コンテキスト。各 affected 側ごとに「自分の pick のジャンル」と
 * 「matchKind の Lv 帯」を覚えておき、ルーレットで通り過ぎる候補もこの範囲内に限定する。
 */
type SpinContext = { genre: CompetitionSongGenre; levels: number[] };
const spinContextLeft = ref<SpinContext | null>(null);
const spinContextRight = ref<SpinContext | null>(null);

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
  // 0ms: 直前にホワーシュで「飛んでくる」気配を作る (短尺 whoosh4)
  se.play('whoosh4');
  stageTimers.push(window.setTimeout(() => { ref.value.burst     = true; se.play('impact'); }, 50));
  stageTimers.push(window.setTimeout(() => { ref.value.title     = true; }, 300));
  stageTimers.push(window.setTimeout(() => { ref.value.artist    = true; se.play('ui2'); }, 1500));
  stageTimers.push(window.setTimeout(() => { ref.value.diffBadge = true; se.play('ui'); }, 2300));
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
  // 初回 REVEAL 押下時に autoplay 制限を解除
  se.unlock();
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
    // step 2 から先 (Strategy 演出 / スピン) は自動進行せず、毎回クリックで前進する。
    return;
  }
  if (revealStep.value === 2) {
    // クリック → Strategy 申告があれば演出オーバーレイ表示 (step 3 へ)。無ければ何もしない。
    if (strategyDeclaredByLeft.value || strategyDeclaredByRight.value) {
      revealStep.value = 3;
      strategyOverlayActive.value = true;
      // 長尺ホワーシュでオーバーレイ突入を盛り上げる
      se.play('whoosh');
    }
    return;
  }
  if (revealStep.value === 3) {
    // クリック → オーバーレイ表示中ならオーバーレイを消してスピン開始。
    // スピン進行中は誤操作防止のため何もしない (どちらかが回ってる間は無視)。
    if (strategyOverlayActive.value) {
      strategyOverlayActive.value = false;
      // 低域ドゥンでオーバーレイから抽選へバトンタッチ
      se.play('whoosh3');
      startStrategySpins();
    }
    return;
  }
};

/**
 * Strategy Card 抽選スピンを起動する。affected 側 (相手が strategy 申告) ごとに
 * スロットマシン式スピンを開始し、着地で {@code selectedLeft / selectedRight} を effective に置換。
 * step 3 のオーバーレイをクリックで消した直後に呼ばれる。
 */
const startStrategySpins = () => {
  if (affectingLeft.value) startSpinForSide('left');
  if (affectingRight.value) startSpinForSide('right');
};

const startSpinForSide = async (side: 'left' | 'right') => {
  const original = side === 'left' ? selectedLeft.value : selectedRight.value;
  const effective = side === 'left' ? effectiveLeftSong.value : effectiveRightSong.value;
  const ctx = side === 'left' ? spinContextLeft.value : spinContextRight.value;
  if (!original || !effective || !ctx) return;

  // プール構築: 自分の pick のジャンル × matchKind の Lv 帯 (strategy_card_songs から)。
  // ルーレットで通り過ぎる曲もこの範囲内に絞り、ジャンル外の曲が映らないようにする。
  const pool: StrategyPoolSong2[] = [];
  for (const lv of ctx.levels) {
    const arr = strategyPool[ctx.genre]?.[String(lv)];
    if (arr) pool.push(...arr);
  }
  if (pool.length === 0) return;
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
  // 単一 easeOut で 6.8 秒着地。最後のスロー区間を長めに取るため、
  // 終端に重みのある cubic-bezier プロファイルを使用する。
  spinTimers.push(window.setTimeout(() => {
    strip.style.transition = 'transform 6800ms cubic-bezier(0.05, 0.78, 0.12, 1)';
    strip.style.transform = `translateY(${targetY}px)`;
    // スピン開始直後にホワーシュで「回り始め」を強調
    se.play('whoosh4');
  }, 50));
  // 着地直前 (6.0s 付近) に長尺ホワーシュで減速をブースト
  spinTimers.push(window.setTimeout(() => { se.play('whoosh2'); }, 5400));

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
    se.play('impact');
  }, 7000));
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
  spinContextLeft.value = null;
  spinContextRight.value = null;
  strategyOverlayActive.value = false;
};

/**
 * REVEAL フェーズ中の「画面どこでもクリック」ハンドラ。
 * Reset ボタンは @click.stop で伝搬を止めているのでここまで来ない。
 */
const onStageClick = () => {
  if (phase.value !== 'reveal') return;
  onReveal();
};

/**
 * タイトルを「英語の語 (連続するラテン文字)」と「CJK 1 文字」「空白」「その他記号」に分割。
 * cascade アニメ用に文字ごとの spans を返すが、英語語内では改行を許さない (`white-space: nowrap`)。
 * これで `ALL MY TURN-...` のような英字語が途中で折り返さなくなる。
 *
 * 各文字には元タイトル内のグローバル index を持たせ、cascade アニメの delay 計算に使う。
 */
type TitleToken = { chars: { text: string; index: number }[] };
const titleTokensOf = (s: SongDataEntry | null): TitleToken[] => {
  if (!s) return [];
  // U+3000-U+9FFF (CJK 全般 + ひらがな/カタカナ + CJK 統合漢字) と
  // U+FF00-U+FFEF (全角英数 + 半角カナ) を「ここで折り返してよい文字」と扱う
  const isCJKOrPunct = (c: string) => /[　-鿿＀-￯]/.test(c);
  const isSpace = (c: string) => /\s/.test(c);
  const arr = Array.from(s.title);
  const tokens: TitleToken[] = [];
  let buffer: { text: string; index: number }[] = [];
  arr.forEach((c, i) => {
    if (isCJKOrPunct(c) || isSpace(c)) {
      if (buffer.length > 0) {
        tokens.push({ chars: buffer });
        buffer = [];
      }
      tokens.push({ chars: [{ text: c, index: i }] });
    } else {
      buffer.push({ text: c, index: i });
    }
  });
  if (buffer.length > 0) tokens.push({ chars: buffer });
  return tokens;
};

// ── タイトル / アーティストの実測フィット ────────────────────
/**
 * 以前は「文字数」でフォントサイズのバケットを切り替えていたが、全角 (CJK) と
 * 半角 (ラテン) では 1 文字あたりの描画幅が約 2 倍違うため、英字タイトル側だけが
 * 不当に小さくなっていた。
 *   例: "Rainbow after snow" (18 文字) → lg:text-7xl = 72px
 *       "センチメンタル・サマー" (11 文字) → lg:text-8xl = 96px
 *   実際の描画幅は前者 ≒ 650px / 後者 ≒ 1050px で、判定が逆転していた。
 *
 * ここでは実際の描画幅と行数を測り、半面に収まる最大サイズを二分探索で求める。
 * 左右で同じアルゴリズム・同じ箱を使うので、文字種によらず見た目の大きさが揃う。
 */
const TITLE_LINE_HEIGHT = 1.25;
const ARTIST_LINE_HEIGHT = 1.3;
/** 難易度バッジは従来 text-8xl (line-height: 1) だったのでそれに合わせる。 */
const DIFF_LINE_HEIGHT = 1;
/** 2 行を許す場合の上限行数。まずは 1 行に収めるのを優先する (下記 fitPreferOneLine 参照)。 */
const TITLE_MAX_LINES = 2;
const ARTIST_MAX_LINES = 2;
const TITLE_MIN_PX = 24;
const ARTIST_MIN_PX = 12;
const DIFF_MIN_PX = 24;
/**
 * 1 行フィットの結果が「上限 × この比率」を下回ったときだけ 2 行を許す。
 * CJK は 1 文字ずつが折り返し可能トークンなので、2 行を無条件に許すと
 * 「センチメンタル・サ / マー」のような不格好な位置で切れてしまう。
 */
const ONE_LINE_MIN_RATIO = 0.5;
/** 半面の端に文字が触れないようにする安全率。この幅で折り返し / はみ出しを判定する。 */
const WIDTH_SAFETY = 0.94;
/** .artist-fade は letter-spacing: 0.1em で着地するので、測定もその値で行う。 */
const ARTIST_REST_LETTER_SPACING = '0.1em';

const titleFontPxLeft = ref(64);
const titleFontPxRight = ref(64);
const artistFontPxLeft = ref(24);
const artistFontPxRight = ref(24);
const diffFontPxLeft = ref(64);
const diffFontPxRight = ref(64);

const titleElLeft = ref<HTMLElement | null>(null);
const titleElRight = ref<HTMLElement | null>(null);
const artistElLeft = ref<HTMLElement | null>(null);
const artistElRight = ref<HTMLElement | null>(null);
const diffElLeft = ref<HTMLElement | null>(null);
const diffElRight = ref<HTMLElement | null>(null);

/** ビューポート幅ごとの上限 px。従来の「最大バケット」と同じ値を踏襲する。 */
const breakpointMax = (base: number, sm: number, md: number, lg: number): number => {
  const w = window.innerWidth;
  if (w >= 1024) return lg;
  if (w >= 768) return md;
  if (w >= 640) return sm;
  return base;
};

/**
 * el が「幅 <= 親幅」かつ「行数 <= maxLines」に収まる最大フォントサイズ (px) を二分探索する。
 *
 * 測定は el のクローン (不可視 + アニメーション停止) に対して行う。
 * cascade / fade アニメーション中の transform・opacity・letter-spacing が
 * 測定値を汚すのを避けるため、着地後の静止状態を再現したプローブで測る。
 * 測れない場合 (未マウント / v-show で非表示) は fallback をそのまま返す。
 */
const fitFontSize = (
  el: HTMLElement | null,
  opts: {
    lineHeight: number; maxLines: number; minPx: number; maxPx: number;
    letterSpacing?: string;
    /**
     * 幅の基準 + プローブの置き場所。省略時は el 自身の幅 / el の親。
     * 難易度バッジのように shrink-to-fit な inline-block の中にいる要素は、
     * el.clientWidth が「折り返し済みの幅」になってしまうため、外側の
     * カラム要素を明示的に渡して本来使える幅で測る。
     */
    boxEl?: HTMLElement | null;
  },
  fallback: number,
): number => {
  const host = opts.boxEl ?? el?.parentElement;
  if (!el || !host) return fallback;
  const rawWidth = (opts.boxEl ?? el).clientWidth;
  if (rawWidth === 0) return fallback; // スピン中は v-show で display:none
  // 実際の描画幅より内側で判定することで、左右の端に文字が張り付くのを防ぐ。
  // (italic + skew-x の張り出しぶんの逃げも兼ねる)
  const width = Math.floor(rawWidth * WIDTH_SAFETY);

  const probe = el.cloneNode(true) as HTMLElement;
  probe.style.position = 'absolute';
  probe.style.visibility = 'hidden';
  probe.style.pointerEvents = 'none';
  probe.style.left = '0';
  probe.style.top = '0';
  probe.style.width = `${width}px`;
  probe.style.maxWidth = 'none';
  probe.style.lineHeight = String(opts.lineHeight);
  probe.style.animation = 'none';
  probe.style.transform = 'none'; // skew / scale を外して純粋なレイアウト幅で測る
  if (opts.letterSpacing) probe.style.letterSpacing = opts.letterSpacing;
  for (const c of Array.from(probe.querySelectorAll<HTMLElement>('.cascade-char'))) {
    c.style.animation = 'none';
    c.style.opacity = '1';
    c.style.transform = 'none';
  }
  host.appendChild(probe);
  try {
    // scrollWidth: nowrap の英単語トークンがはみ出していないか
    // rect.height : 折り返して maxLines を超えていないか
    const fits = (px: number): boolean => {
      probe.style.fontSize = `${px}px`;
      const h = probe.getBoundingClientRect().height;
      return probe.scrollWidth <= width + 1 && h <= px * opts.lineHeight * opts.maxLines + 2;
    };
    if (fits(opts.maxPx)) return opts.maxPx;
    let lo = opts.minPx;
    let hi = opts.maxPx;
    while (hi - lo > 1) {
      const mid = Math.floor((lo + hi) / 2);
      if (fits(mid)) lo = mid;
      else hi = mid;
    }
    return lo;
  } finally {
    probe.remove();
  }
};

/**
 * タイトルの上限 px。幅だけでなく半面の高さでも抑えて、
 * アーティスト名 / 難易度バッジやプレイヤー名バナーの領域を潰さないようにする。
 */
const titleMaxPx = (el: HTMLElement | null): number => {
  const half = el?.closest('.reveal-half') as HTMLElement | null;
  const h = half?.clientHeight || window.innerHeight;
  // 60/72/96/128 = 従来の text-6xl / 7xl / 8xl / 9xl。
  // 0.14 は TITLE_MAX_LINES 行ぶんで半面の約 35% に収まる比率。1080p / 一般的な
  // デスクトップ (高さ 900px 以上) では上限側が効き、縦の狭い窓でだけ効いてくる。
  return Math.min(breakpointMax(60, 72, 96, 128), Math.round(h * 0.14));
};
/** アーティスト名の上限 px。20/24/30/36 = 従来の text-xl / 2xl / 3xl / 4xl。 */
const artistMaxPx = (): number => breakpointMax(20, 24, 30, 36);
/** 難易度バッジの上限 px。36/60/72/96 = 従来の text-4xl / 6xl / 7xl / 8xl。 */
const diffMaxPx = (): number => breakpointMax(36, 60, 72, 96);

/**
 * 「1 行に収まる最大サイズ」を最優先で採用する。
 * それが上限の ONE_LINE_MIN_RATIO を下回る (= 長すぎて極端に小さくなる) ときだけ、
 * 2 行を許してサイズを取り戻す。
 *
 * ラテン文字は 1 文字あたりの幅が全角の約半分なので、1 行に詰めようとすると
 * 自然と全角タイトルより大きいフォントサイズが選ばれる。ラテンは字面の高さ
 * (cap height) が em box の約 7 割、CJK はほぼ 10 割なので、この差がちょうど
 * 打ち消し合って左右の「見た目の大きさ」が揃う。
 */
const fitPreferOneLine = (
  el: HTMLElement | null,
  base: { lineHeight: number; minPx: number; maxPx: number; letterSpacing?: string },
  maxLines: number,
  fallback: number,
): number => {
  const onePx = fitFontSize(el, { ...base, maxLines: 1 }, fallback);
  if (onePx >= base.maxPx * ONE_LINE_MIN_RATIO) return onePx;
  return fitFontSize(el, { ...base, maxLines }, fallback);
};

/** 左右 4 要素をまとめて測り直す。DOM 反映後 (flush: 'post') に呼ぶこと。 */
const refitAll = () => {
  const titleBase = (el: HTMLElement | null) => ({
    lineHeight: TITLE_LINE_HEIGHT,
    minPx: TITLE_MIN_PX,
    maxPx: titleMaxPx(el),
  });
  const artistBase = {
    lineHeight: ARTIST_LINE_HEIGHT,
    minPx: ARTIST_MIN_PX,
    maxPx: artistMaxPx(),
    letterSpacing: ARTIST_REST_LETTER_SPACING,
  };
  // 難易度バッジ ("LEGGENDARIA 12" など) は常に 1 行。inline-block の中にいるので
  // 幅の基準は外側の曲情報カラム (.diff-slam の親) を使う。
  const diffOpts = (el: HTMLElement | null) => ({
    lineHeight: DIFF_LINE_HEIGHT,
    maxLines: 1,
    minPx: DIFF_MIN_PX,
    maxPx: diffMaxPx(),
    boxEl: el?.parentElement?.parentElement ?? null,
  });
  titleFontPxLeft.value = fitPreferOneLine(titleElLeft.value, titleBase(titleElLeft.value), TITLE_MAX_LINES, titleFontPxLeft.value);
  titleFontPxRight.value = fitPreferOneLine(titleElRight.value, titleBase(titleElRight.value), TITLE_MAX_LINES, titleFontPxRight.value);
  artistFontPxLeft.value = fitPreferOneLine(artistElLeft.value, artistBase, ARTIST_MAX_LINES, artistFontPxLeft.value);
  artistFontPxRight.value = fitPreferOneLine(artistElRight.value, artistBase, ARTIST_MAX_LINES, artistFontPxRight.value);
  diffFontPxLeft.value = fitFontSize(diffElLeft.value, diffOpts(diffElLeft.value), diffFontPxLeft.value);
  diffFontPxRight.value = fitFontSize(diffElRight.value, diffOpts(diffElRight.value), diffFontPxRight.value);
};

// 曲の差し替え (Strategy 着地含む) / 各段階の出現 / スピン終了のたびに測り直す。
watch(
  [
    selectedLeft, selectedRight,
    spinningLeft, spinningRight,
    () => leftStage.value.title, () => rightStage.value.title,
    () => leftStage.value.artist, () => rightStage.value.artist,
    () => leftStage.value.diffBadge, () => rightStage.value.diffBadge,
  ],
  () => refitAll(),
  { flush: 'post' },
);

const canReveal = computed(() => !!selectedLeft.value && !!selectedRight.value);

</script>

<template>
  <div class="song-reveal-view min-h-screen w-full bg-slate-950 text-white relative overflow-hidden">
    <!-- 共通の背景 -->
    <div class="absolute inset-0 pointer-events-none">
      <div class="absolute inset-0 bg-gradient-to-br from-slate-950 via-slate-900 to-cyan-950"></div>
      <div class="absolute inset-0 opacity-20 bg-grid"></div>
      <div class="absolute inset-0 neon-streaks opacity-30"></div>
    </div>

    <!-- SE ミュート切替 (REVEAL フェーズ中のクリックには反応させない) -->
    <div class="absolute top-4 right-4 z-50 flex items-center gap-2">
      <div
        @click.stop
        class="flex items-center px-3 py-2 rounded-xl bg-slate-800/70 border border-white/10 backdrop-blur shadow-lg"
      >
        <button
          type="button"
          @click.stop="se.toggleMuted()"
          class="text-slate-300 hover:text-white transition-colors"
          :aria-label="se.muted.value ? 'SE ミュート解除' : 'SE ミュート'"
          :title="se.muted.value ? 'SE ミュート解除' : 'SE ミュート'"
        >
          <svg v-if="!se.muted.value" xmlns="http://www.w3.org/2000/svg" class="h-5 w-5" fill="none" viewBox="0 0 24 24" stroke="currentColor" stroke-width="2">
            <path stroke-linecap="round" stroke-linejoin="round" d="M15.536 8.464a5 5 0 010 7.072M17.95 6.05a8 8 0 010 11.9M9 9H5a1 1 0 00-1 1v4a1 1 0 001 1h4l4 4V5L9 9z" />
          </svg>
          <svg v-else xmlns="http://www.w3.org/2000/svg" class="h-5 w-5" fill="none" viewBox="0 0 24 24" stroke="currentColor" stroke-width="2">
            <path stroke-linecap="round" stroke-linejoin="round" d="M9 9H5a1 1 0 00-1 1v4a1 1 0 001 1h4l4 4V5L9 9zM17 9l4 4m0-4l-4 4" />
          </svg>
        </button>
      </div>
    </div>

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
         内部の Reset ボタンは @click.stop で伝搬を止めている。 -->
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
            class="relative overflow-hidden rounded-2xl border-2 border-amber-300/40 bg-slate-950/95"
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
            ref="titleElLeft"
            class="title-cascade font-black tracking-tight"
            :style="{ fontSize: `${titleFontPxLeft}px`, lineHeight: `${TITLE_LINE_HEIGHT}` }"
          >
            <span
              v-for="(tok, ti) in titleTokensOf(selectedLeft)"
              :key="`l-tok-${ti}`"
              class="title-token"
            >
              <span
                v-for="ch in tok.chars"
                :key="ch.index"
                class="cascade-char inline-block"
                :style="{ animationDelay: `${ch.index * 70}ms` }"
              >{{ ch.text === ' ' ? ' ' : ch.text }}</span>
            </span>
          </p>
          <p
            v-if="leftStage.artist"
            ref="artistElLeft"
            class="artist-fade font-bold tracking-wider text-cyan-100"
            :style="{ fontSize: `${artistFontPxLeft}px`, lineHeight: `${ARTIST_LINE_HEIGHT}` }"
          >
            {{ selectedLeft?.artist }}
          </p>
          <div v-if="leftStage.diffBadge" class="diff-slam inline-block">
            <p
              ref="diffElLeft"
              class="diff-text font-black tracking-widest italic skew-x-[-8deg]"
              :class="selectedLeft?.difficulty === '10' ? 'diff-leggendaria' : 'diff-another'"
              :style="{ fontSize: `${diffFontPxLeft}px`, lineHeight: `${DIFF_LINE_HEIGHT}` }"
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
            class="relative overflow-hidden rounded-2xl border-2 border-amber-300/40 bg-slate-950/95"
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
            ref="titleElRight"
            class="title-cascade font-black tracking-tight"
            :style="{ fontSize: `${titleFontPxRight}px`, lineHeight: `${TITLE_LINE_HEIGHT}` }"
          >
            <span
              v-for="(tok, ti) in titleTokensOf(selectedRight)"
              :key="`r-tok-${ti}`"
              class="title-token"
            >
              <span
                v-for="ch in tok.chars"
                :key="ch.index"
                class="cascade-char inline-block"
                :style="{ animationDelay: `${ch.index * 70}ms` }"
              >{{ ch.text === ' ' ? ' ' : ch.text }}</span>
            </span>
          </p>
          <p
            v-if="rightStage.artist"
            ref="artistElRight"
            class="artist-fade font-bold tracking-wider text-cyan-100"
            :style="{ fontSize: `${artistFontPxRight}px`, lineHeight: `${ARTIST_LINE_HEIGHT}` }"
          >
            {{ selectedRight?.artist }}
          </p>
          <div v-if="rightStage.diffBadge" class="diff-slam inline-block">
            <p
              ref="diffElRight"
              class="diff-text font-black tracking-widest italic skew-x-[-8deg]"
              :class="selectedRight?.difficulty === '10' ? 'diff-leggendaria' : 'diff-another'"
              :style="{ fontSize: `${diffFontPxRight}px`, lineHeight: `${DIFF_LINE_HEIGHT}` }"
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
        <div class="absolute inset-0 bg-black/80"></div>
        <div class="relative text-center px-8 py-10 space-y-6 max-w-3xl">
          <p class="text-xs sm:text-sm font-mono uppercase tracking-[0.6em] text-amber-300 strategy-flicker">
            ⚡ Strategy Card Declared ⚡
          </p>
          <h2 class="text-5xl sm:text-7xl font-black tracking-tight bg-clip-text text-transparent bg-gradient-to-r from-fuchsia-300 via-amber-200 to-fuchsia-300 drop-shadow-[0_0_12px_rgba(252,211,77,0.5)] strategy-pop">
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

      <!--
        フッター: Reset のみ (進行ガイドは Strategy 発動の有無が文言で漏れるためネタバレ防止で撤去)。
        Reset ボタンは主催だけが押せる極小ボタンとして残す。
      -->
      <div class="absolute bottom-4 left-1/2 -translate-x-1/2 z-50">
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
/* NOTE: 動画エンコード時にブラーが潰れて見えるため、filter: blur(...) を使わず
   opacity + transform + letter-spacing だけで「ふわっと出現」を表現する。 */
@keyframes playerBannerKf {
  0%   { opacity: 0; transform: translateY(-20px) scale(0.94); letter-spacing: 0.4em; }
  100% { opacity: 1; transform: translateY(0)     scale(1);    letter-spacing: 0.1em; }
}
.player-banner p {
  animation: playerBannerKf 0.8s cubic-bezier(0.16, 0.74, 0.22, 1) forwards;
  border-bottom: 3px solid currentColor;
  text-shadow: 0 0 7px rgba(255, 255, 255, 0.3);
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
  /* filter: blur を外したぶん、コニックグラデーションを薄めにして
     回転時のラスタライズざらつきを目立たなくする (動画エンコード対策)。 */
  opacity: 0.7;
  animation: rayRotateKf 18s linear infinite;
}

@keyframes cascadeCharKf {
  0%   { opacity: 0; transform: translateY(-60px) scale(1.5) rotateX(-90deg); }
  60%  { opacity: 1; transform: translateY(8px)   scale(1.05) rotateX(0deg);  }
  100% { opacity: 1; transform: translateY(0)     scale(1)    rotateX(0deg);  }
}
/* グローは「白の近接 1 枚 + シアンの中距離 1 枚」まで。大半径 (旧 60px) の層は
   配信のビットレートでは輪郭を溶かすだけで効果が乗らないので落とす。 */
.title-cascade {
  color: #fff;
  text-shadow:
    0 0 8px rgba(255, 255, 255, 0.4),
    0 0 20px rgba(56, 189, 248, 0.3);
}
/* 英字語 ("ALL", "TURN-") は token 単位でまとめ、内部での折り返しを禁止する。
   CJK / 空白は 1 文字ずつ別 token になるので、トークン境界で自然に改行できる。 */
.title-token {
  display: inline-block;
  white-space: nowrap;
  vertical-align: top;
}
.cascade-char {
  display: inline-block;
  opacity: 0;
  transform: translateY(-60px) scale(1.5);
  animation: cascadeCharKf 0.6s cubic-bezier(0.18, 0.89, 0.32, 1.28) forwards;
}

@keyframes artistFadeKf {
  0%   { opacity: 0; transform: translateY(10px); letter-spacing: 0.5em; }
  100% { opacity: 1; transform: translateY(0);    letter-spacing: 0.1em; }
}
.artist-fade {
  animation: artistFadeKf 0.9s cubic-bezier(0.16, 0.74, 0.22, 1) forwards;
  text-shadow: 0 0 6px rgba(125, 211, 252, 0.35);
}

@keyframes diffSlamKf {
  0%   { opacity: 0; transform: translateY(120%) scale(2.4) skewX(-8deg); }
  55%  { opacity: 1; transform: translateY(-8%)   scale(1.18) skewX(-8deg); }
  75%  { transform: translateY(2%) scale(0.95) skewX(-8deg); }
  100% { opacity: 1; transform: translateY(0)    scale(1)    skewX(-8deg); }
}
.diff-slam {
  animation: diffSlamKf 0.9s cubic-bezier(0.22, 0.95, 0.28, 1.15) forwards;
}

/* 配信のビットレートだと大半径のグローは輪郭を溶かしてしまうため、
   半径・重ね枚数とも控えめにする (旧: 12+30px ⇔ 24+60px の 2 枚重ね)。
   脈動は残しつつ、文字のエッジが常に立つ範囲に収める。 */
@keyframes diffGlowKf {
  0%, 100% { filter: drop-shadow(0 0 5px currentColor); }
  50%      { filter: drop-shadow(0 0 10px currentColor); }
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
  text-shadow: 0 0 10px rgba(251, 146, 60, 0.35);
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
  0%   { opacity: 0; transform: scale(0.4) rotate(-8deg); }
  40%  { opacity: 1; transform: scale(1.12) rotate(2deg); }
  60%  { transform: scale(0.96) rotate(-1deg); }
  100% { opacity: 1; transform: scale(1) rotate(0deg); }
}
.strategy-pop {
  animation: strategyPopKf 0.9s cubic-bezier(0.16, 1, 0.3, 1.2) forwards;
}

/* 小さめの等幅テキストに大半径グローを乗せると配信では真っ先に潰れるので、
   点滅の明暗差 (opacity) で「明滅感」を出し、グロー半径自体は小さく保つ。 */
@keyframes strategyFlickerKf {
  0%, 100% { opacity: 1; text-shadow: 0 0 8px rgba(252, 211, 77, 0.5), 0 0 16px rgba(252, 211, 77, 0.3); }
  45%      { opacity: 0.6; text-shadow: 0 0 4px rgba(252, 211, 77, 0.25); }
  50%      { opacity: 1;   text-shadow: 0 0 12px rgba(252, 211, 77, 0.6), 0 0 22px rgba(252, 211, 77, 0.4); }
  55%      { opacity: 0.6; text-shadow: 0 0 4px rgba(252, 211, 77, 0.25); }
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
