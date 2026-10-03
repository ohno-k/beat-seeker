<script setup lang="ts">
/**
 * ScorePredictionView.vue
 *
 * 【Viewの役割】
 * 譜面分析ページ。ANOTHER / LEGGENDARIA 譜面（全レベル）を 1 つ選び、傾向プロファイル
 * （実効BPM、皿率、同時押し率、配置パターン、同時押し構成、打鍵間隔、小節ごとノーツ密度）と
 * 譜面再生、ログイン時は類似譜面と自分のスコアを並べて表示する。
 *
 * 【レイアウト】
 * - PC: 左に曲選択（検索・レベル絞り込み・ランダム）、右に分析結果
 * - モバイル: 曲選択が上。曲を選んだら一覧は畳み、「一覧から選ぶ」で開き直す
 * - 分析結果は「譜面ヘッダ → 数値 4 枚 → ノーツ密度 → 配置パターン/同時押し構成 → 打鍵間隔 → 譜面再生 → 類似譜面」の順
 * - レスポンシブ切替は scoped CSS で書く（src/output.css が sm:/lg: 系の Tailwind クラスを後勝ちで潰すため）
 *
 * 【主な機能】
 * - 曲選択（ANOTHER/LEGGENDARIA の全レベル、textage 有りのみ。☆10 以下の絞り込みあり）。選択は /chart/... の URL に同期
 * - 傾向プロファイル取得と可視化
 * - 譜面再生（ChartPlayer。ノーツを上から降らせて再生）
 * - 類似譜面（予測 API の similarSongs）。曲名で該当譜面の分析へ移動
 * - 管理者は類似譜面の「内訳」で類似度計算の過程を表示
 */
import { ref, computed, watch, onMounted } from 'vue';
import { useRoute, useRouter } from 'vue-router';
import { useAuth, API_BASE } from '../composables/useAuth';
import { useAdmin } from '../composables/useAdmin';
import { useI18n } from '../composables/useI18n';
import { useGameData, type SongDataEntry } from '../composables/useGameData';
import ChartPlayer from '../components/ChartPlayer.vue';
import RandomRanking from '../components/RandomRanking.vue';

// props: 他人のIDを指定された場合の閲覧モード（admin=管理者、friend=フレンド閲覧）
const props = defineProps<{
  viewingUserId?: number | null;
  viewingMode?: 'admin' | 'friend' | null;
}>();

const { t } = useI18n();
const { isLoggedIn, authHeaders } = useAuth();
const { isAdmin } = useAdmin();
const { songDataBody } = useGameData();
const route = useRoute();
const router = useRouter();

/**
 * textage 識別子（例: `30/_cmflg.html?1AC00`）を URL パスに変換する。
 * パスは 3 セグメントに分解して `/chart/<version>/<slug>/<diffCode>` の形にする。
 * @returns 変換できない textage の場合は null
 */
function textageToPath(textage: string): string | null {
  const m = textage.match(/^([^/]+)\/(.+?)\.html\?(.+)$/);
  if (!m) return null;
  return `/chart/${m[1]}/${m[2]}/${m[3]}`;
}

/**
 * 現在のルートが `chart-analysis` ルートなら textage 文字列を組み立てて返す。
 * 3 セグメントを `<version>/<slug>.html?<diffCode>` の形式に再構成する。
 */
function textageFromRoute(): string | null {
  if (route.name !== 'chart-analysis') return null;
  const { version, slug, diff } = route.params;
  if (typeof version !== 'string' || typeof slug !== 'string' || typeof diff !== 'string') return null;
  return `${version}/${slug}.html?${diff}`;
}

// URL から指定された textage が songData に存在しなかった場合の表示用
const unknownTextageFromUrl = ref<string | null>(null);

// adminモードで別ユーザーを閲覧中かどうか
const isAdminViewing = computed(() =>
  props.viewingMode === 'admin' && props.viewingUserId != null
);

// ── 曲選択 ──────────────────────────────────────────────────
const searchQuery = ref('');                          // 検索文字列（曲名/アーティスト）
const levelFilter = ref<'all' | 12 | 11 | 'low'>('all'); // レベル絞り込み（low = ☆10 以下）
const selectedEntry = ref<SongDataEntry | null>(null); // 現在選択中の曲
const pickerOpen = ref(false);                        // モバイルで曲を選んだ後に一覧を開き直しているか
const pageTab = ref<'analysis' | 'ranking'>('analysis'); // ページ内のタブ（譜面分析 / 当たり配置ランキング）
const LIST_PAGE = 150;
const listLimit = ref(LIST_PAGE);                     // 一覧に描画する件数（「さらに表示」で増やす）

/**
 * 分析対象として使える曲の集合。
 * ANOTHER(difficulty=4) / LEGGENDARIA(10) の全レベルのうち、textage（譜面コード）を持つもの。
 * 並びはレベルの高い順 → 曲名順 → 難易度順。
 */
const targetEntries = computed((): SongDataEntry[] => {
  return songDataBody.value
    .filter(s => !!DIFF_META[s.difficulty] && !!s.textage)
    .sort((a, b) => b.level - a.level || a.title.localeCompare(b.title, 'ja')
      || DIFF_META[a.difficulty].order - DIFF_META[b.difficulty].order);
});

/** 検索文字列とレベルで絞った一覧（描画は listLimit 件まで）。 */
const matchedEntries = computed((): SongDataEntry[] => {
  const q = searchQuery.value.trim().toLowerCase();
  const lv = levelFilter.value;
  return targetEntries.value.filter(s =>
    (lv === 'all' || (lv === 'low' ? s.level <= 10 : s.level === lv))
    && (!q || s.title.toLowerCase().includes(q) || s.artist.toLowerCase().includes(q))
  );
});
const shownEntries = computed(() => matchedEntries.value.slice(0, listLimit.value));

watch([searchQuery, levelFilter], () => { listLimit.value = LIST_PAGE; });

// モバイル: 曲を選んだ後は一覧を畳む。検索中・開き直し中は出す
const listCollapsed = computed(() => !!selectedEntry.value && !pickerOpen.value && !searchQuery.value.trim());

/**
 * 曲を選択する。
 * 同じ曲の重複選択は（管理者閲覧モードでなければ）スキップし、それ以外なら予測APIを発火。
 * 選択時にブラウザ URL も `/chart/...` 形式に同期させ、外部共有可能なリンクにする。
 */
function selectEntry(entry: SongDataEntry) {
  pickerOpen.value = false;
  startPattern.value = null;
  if (selectedEntry.value?.textage === entry.textage && !isAdminViewing.value) return;
  selectedEntry.value = entry;
  unknownTextageFromUrl.value = null;
  predictionResult.value = null;
  predictionError.value = '';

  // URL を譜面ディープリンクに更新。同 URL なら無駄な history 追加を避ける。
  if (entry.textage) {
    const path = textageToPath(entry.textage);
    if (path && route.fullPath !== path) {
      router.push(path);
    }
  }

  if (entry.textage) {
    fetchPrediction(entry.textage);
  }
}

/** 当たり配置ランキングから開いた譜面と、譜面再生を始める並び（ほかの譜面を選んだら消す） */
const startPattern = ref<{ textage: string; pattern: string } | null>(null);
const playerStartPattern = computed(() =>
  startPattern.value && startPattern.value.textage === selectedEntry.value?.textage ? startPattern.value.pattern : '');

/** 当たり配置ランキングの行から、その譜面を譜面分析タブで開く。譜面再生はその行の並び（正規・MIRROR・R-RANDOM・自由入力）で始める。 */
function openChartFromRanking(textage: string, pattern: string) {
  pageTab.value = 'analysis';
  const entry = targetEntries.value.find(s => s.textage === textage);
  if (entry) {
    selectEntry(entry);
    startPattern.value = { textage, pattern };
  } else {
    startPattern.value = { textage, pattern };
    const path = textageToPath(textage);
    if (path) router.push(path);
  }
  window.scrollTo({ top: 0, behavior: 'smooth' });
}

/** 今の絞り込みの中からランダムに 1 譜面選ぶ（絞り込み結果が空なら全体から）。 */
function pickRandom() {
  const pool = matchedEntries.value.length ? matchedEntries.value : targetEntries.value;
  if (!pool.length) return;
  selectEntry(pool[Math.floor(Math.random() * pool.length)]);
}

/** 譜面再生（管理者の埋め作業）から次の譜面へ。画面の位置は譜面再生側が合わせるので、上へは戻さない。 */
function openFromPlayer(textage: string) {
  const entry = targetEntries.value.find(s => s.textage === textage);
  if (entry) {
    selectEntry(entry);
  } else {
    const path = textageToPath(textage);
    if (path) router.push(path);
  }
}

/** textage から譜面を探して選ぶ（類似譜面の曲名から移動するとき）。 */
function goToChart(textage: string) {
  const entry = targetEntries.value.find(s => s.textage === textage);
  if (entry) {
    selectEntry(entry);
    window.scrollTo({ top: 0, behavior: 'smooth' });
  }
}

/**
 * 現在のルートから対象 textage を読み取り、対応する曲を自動選択する。
 * songData がまだロードされていない場合は何もしない（ロード完了後の watch で再試行する）。
 */
function applyRouteToSelection() {
  const textage = textageFromRoute();
  if (!textage) return;
  if (songDataBody.value.length === 0) return;
  // 既に同じ曲を選択済みなら何もしない
  if (selectedEntry.value?.textage === textage) return;

  const entry = songDataBody.value.find(s => s.textage === textage);
  if (entry) {
    unknownTextageFromUrl.value = null;
    selectedEntry.value = entry;
    predictionResult.value = null;
    predictionError.value = '';
    fetchPrediction(textage);
  } else {
    // 該当譜面が見つからなかった場合のフォールバック表示用
    selectedEntry.value = null;
    unknownTextageFromUrl.value = textage;
  }
}

onMounted(() => {
  applyRouteToSelection();
});

// songData が遅延ロードされた直後にもう一度マッチを試行する
watch(() => songDataBody.value.length, (newLen, oldLen) => {
  if (oldLen === 0 && newLen > 0) {
    applyRouteToSelection();
  }
});

// ブラウザの戻る/進むやプログラム的な router.push に追従する
watch(() => route.fullPath, () => {
  applyRouteToSelection();
});

// 閲覧ユーザーが変わったら、現在の曲で再度予測を取り直す（管理者がユーザー切替時など）
watch(() => props.viewingUserId, () => {
  predictionResult.value = null;
  predictionError.value = '';
  if (selectedEntry.value?.textage) {
    fetchPrediction(selectedEntry.value.textage);
  }
});

// 難易度コード → 表示名・1 文字表記・色（一覧・ヘッダ・類似譜面で共通）。一覧に載せる難易度もこの表で決まる
const DIFF_META: Record<string, { order: number; label: string; short: string; cls: string; text: string; badge: string }> = {
  '4': { order: 0, label: 'ANOTHER', short: 'A', cls: 'is-ano', text: 'text-red-600 dark:text-red-400',
    badge: 'bg-red-100 text-red-700 dark:bg-red-900/40 dark:text-red-300' },
  '10': { order: 1, label: 'LEGGENDARIA', short: 'L', cls: 'is-leg', text: 'text-purple-600 dark:text-purple-400',
    badge: 'bg-purple-100 text-purple-700 dark:bg-purple-900/40 dark:text-purple-300' },
};
const DIFF_CODE_BY_NAME: Record<string, string> = Object.fromEntries(Object.entries(DIFF_META).map(([code, m]) => [m.label, code]));
const metaOf = (difficulty: string) => DIFF_META[difficulty] ?? DIFF_META['4'];

// difficulty コードから表示名（"ANOTHER" / "LEGGENDARIA"）に変換
function diffLabel(difficulty: string): string {
  return metaOf(difficulty).label;
}

// 難易度の 1 文字表記（一覧用）
function diffShort(difficulty: string): string {
  return metaOf(difficulty).short;
}

// 難易度バッジの Tailwind クラス
function diffBadgeClass(difficulty: string): string {
  return metaOf(difficulty).badge;
}

// ── 共有リンク ───────────────────────────────────────────────
const linkCopied = ref(false);
async function copyLink() {
  const path = selectedEntry.value?.textage ? textageToPath(selectedEntry.value.textage) : null;
  if (!path) return;
  try {
    await navigator.clipboard.writeText(`${window.location.origin}${path}`);
    linkCopied.value = true;
    setTimeout(() => { linkCopied.value = false; }, 1600);
  } catch {
    // クリップボードが使えない環境では何もしない
  }
}

// ── 予測API関連の型定義 ─────────────────────────────────────
// 類似曲1件分のデータ（類似度 + 参照ユーザーのスコア）
interface SimilarSong {
  title: string;
  difficultyName: string;
  textage: string;
  score?: number;
  scoreRate?: number;
  similarity: number;
  played: boolean;
}

// ── 類似度デバッグ（管理者専用） ─────────────────────────────
const debugResult = ref<Record<string, any> | null>(null); // 内訳レスポンス
const isDebugLoading = ref(false);                          // 取得中フラグ

/**
 * 選択中の曲と別曲 (textageB) の類似度内訳を管理者APIから取得する。
 * @param textageB 比較対象の曲のtextage
 */
async function fetchSimilarityDebug(textageB: string) {
  if (!selectedEntry.value?.textage) return;
  isDebugLoading.value = true;
  debugResult.value = null;
  try {
    const res = await fetch(
      `${API_BASE}/api/admin/similarity-debug?textageA=${encodeURIComponent(selectedEntry.value.textage)}&textageB=${encodeURIComponent(textageB)}`,
      { headers: authHeaders() }
    );
    debugResult.value = await res.json();
  } catch (e: any) {
    debugResult.value = { error: e.message };
  } finally {
    isDebugLoading.value = false;
  }
}

// 予測結果の型。類似曲の配列と、現在スコア（あれば）を使う
interface PredictionResult {
  textage: string;
  title: string;
  difficulty: string;
  level: number;
  notes: number;
  dominantEff16: number;       // 主要実効16分BPM
  predictedScore: number;      // 予測スコア（画面には出さない）
  predictedScoreRate: number;  // 予測達成率（画面には出さない）
  currentScore?: number;       // 現在のベストスコア（あれば）
  currentScoreRate?: number;
  similarSongs: SimilarSong[]; // 類似曲一覧
  message?: string;
  error?: string;
}

const predictionResult = ref<PredictionResult | null>(null);
const predictionError = ref('');
const isLoading = ref(false);

/**
 * 予測APIを呼び出す（類似譜面の一覧を得るため）。
 * 手順1: 未ログインなら何もしない
 * 手順2: 管理者閲覧なら /api/admin/score-prediction?userId=... を叩く
 * 手順3: 通常は /api/analysis/score-prediction を叩く
 * 手順4: error フィールドが入っていた場合もエラー扱い
 */
async function fetchPrediction(textage: string) {
  if (!isLoggedIn.value) return;
  isLoading.value = true;
  predictionError.value = '';
  predictionResult.value = null;

  try {
    // エンドポイントを閲覧モードによって切替
    const url = isAdminViewing.value
      ? `${API_BASE}/api/admin/score-prediction?textage=${encodeURIComponent(textage)}&userId=${props.viewingUserId}`
      : `${API_BASE}/api/analysis/score-prediction?textage=${encodeURIComponent(textage)}`;
    const res = await fetch(url, { headers: authHeaders() });
    const data = await res.json();
    // 返ってくる前に別の曲へ移っていたら捨てる
    if (selectedEntry.value?.textage !== textage) return;
    if (!res.ok || data.error) {
      predictionError.value = data.error ?? `エラー: ${res.status}`;
    } else {
      predictionResult.value = data as PredictionResult;
    }
  } catch (e: any) {
    predictionError.value = e.message ?? '通信エラー';
  } finally {
    isLoading.value = false;
  }
}

// ── 表示ヘルパー ─────────────────────────────────────────────
// DJ LEVEL 用の色クラス（MAX-=紫、AAA=黄、AA=青、A=緑、B=暗灰、それ以下=灰）。
function djLevelClass(rate: number): string {
  if (rate >= 94.45) return 'text-purple-600 dark:text-purple-400 font-bold';
  if (rate >= 88.89) return 'text-yellow-500 dark:text-yellow-400 font-bold';
  if (rate >= 77.78) return 'text-blue-500 dark:text-blue-400 font-bold';
  if (rate >= 66.67) return 'text-green-500 dark:text-green-400 font-bold';
  if (rate >= 55.56) return 'text-slate-700 dark:text-slate-300 font-semibold';
  return 'text-slate-500 dark:text-slate-400';
}

function parseJson<T>(raw: string | null | undefined, fallback: T): T {
  if (!raw) return fallback;
  try { return JSON.parse(raw) as T; } catch { return fallback; }
}

// ── 譜面傾向プロファイルの型定義 ────────────────────────────
/**
 * 譜面1つ分の「傾向」を示すデータ。
 * BPM、皿率、同時押し率、配置パターン（縦連/階段/トリル/二重階段）、
 * 小節ごとのノーツ分布（全体/鍵盤/皿）、同時押し数の分布、タグなどを保持する。
 */
interface TendencyProfile {
  title: string;
  difficulty: string;
  level: number;
  notes: number;
  bpmRaw: string;
  bpmMain: number;
  isSoflan: boolean;
  dominantEff16: number;
  weightedEff16: number;
  scratchPct: number;
  chordPct: number;
  singlePct: number;
  ranuchi: number;
  events: number | null;
  cnNotes: number | null;
  tagsJson: string | null;
  intervalDistJson: string | null;
  chordDistJson: string | null;
  measureNotesJson: string | null;
  measureNotesKbdJson: string | null;
  measureNotesScrJson: string | null;
  jackCount: number | null;
  jackNotes: number | null;
  jackPct: number | null;
  trillCount: number | null;
  trillNotes: number | null;
  trillPct: number | null;
  stairsCount: number | null;
  stairsNotes: number | null;
  stairsPct: number | null;
  dstairsCount: number | null;
  dstairsNotes: number | null;
  dstairsPct: number | null;
}

// 現在の曲の傾向プロファイル（選択変更時にAPIから取得して格納）
const tendencyProfile = ref<TendencyProfile | null>(null);
const profileState = ref<'idle' | 'loading' | 'ok' | 'missing'>('idle');

// 選択曲が変更されたら傾向プロファイルをAPIから取得する watch
watch(selectedEntry, async (entry) => {
  tendencyProfile.value = null;
  if (!entry?.textage) { profileState.value = 'idle'; return; }
  profileState.value = 'loading';
  try {
    const res = await fetch(
      `${API_BASE}/api/analysis/tendency-profile?textage=${encodeURIComponent(entry.textage)}`,
      { headers: authHeaders() }
    );
    if (selectedEntry.value?.textage !== entry.textage) return;
    if (res.ok) {
      tendencyProfile.value = await res.json();
      profileState.value = 'ok';
    } else {
      profileState.value = 'missing';
    }
  } catch {
    // 取得失敗は「データなし」表示に倒す（類似譜面・譜面再生は独立に動作）
    if (selectedEntry.value?.textage === entry.textage) profileState.value = 'missing';
  }
});

// ── タグバッジ（譜面属性: 皿多い/同時押し寄り/32分あり/高速 など） ─────
interface TagBadge { tag: string; label: string; colorClass: string; }

// サーバから返ってくるタグ文字列 → 表示ラベル + 色 の対応表
const TAG_DISPLAY: Record<string, { label: string; colorClass: string }> = {
  high_effective_bpm: { label: '高速',            colorClass: 'bg-red-100 text-red-700 dark:bg-red-900/40 dark:text-red-300' },
  mid_effective_bpm:  { label: '中速',            colorClass: 'bg-yellow-100 text-yellow-700 dark:bg-yellow-900/40 dark:text-yellow-300' },
  low_effective_bpm:  { label: '低速',            colorClass: 'bg-cyan-100 text-cyan-700 dark:bg-cyan-900/40 dark:text-cyan-300' },
  soflan:             { label: 'ソフラン',        colorClass: 'bg-amber-100 text-amber-700 dark:bg-amber-900/40 dark:text-amber-300' },
  scratch_very_heavy: { label: '皿: 非常に多い', colorClass: 'bg-orange-100 text-orange-700 dark:bg-orange-900/40 dark:text-orange-300' },
  scratch_heavy:      { label: '皿: 多い',       colorClass: 'bg-orange-100 text-orange-700 dark:bg-orange-900/40 dark:text-orange-300' },
  scratch_low:        { label: '皿: 少ない',     colorClass: 'bg-slate-100 text-slate-600 dark:bg-slate-700/60 dark:text-slate-300' },
  chord_heavy:        { label: '同時押し寄り',   colorClass: 'bg-blue-100 text-blue-700 dark:bg-blue-900/40 dark:text-blue-300' },
  single_heavy:       { label: '単鍵寄り',       colorClass: 'bg-teal-100 text-teal-700 dark:bg-teal-900/40 dark:text-teal-300' },
  has_32nd:           { label: '32分あり',        colorClass: 'bg-rose-100 text-rose-700 dark:bg-rose-900/40 dark:text-rose-300' },
  has_triplet:        { label: '3連あり',         colorClass: 'bg-amber-100 text-amber-700 dark:bg-amber-900/40 dark:text-amber-300' },
};
const TAG_ORDER = Object.keys(TAG_DISPLAY);

/**
 * tagsJson（配列が JSON 文字列）から表示用のバッジ配列を算出。
 * 配置パターン系（has_jack/trill/stairs/dstairs 等）は「配置パターン」欄で扱うので対応表に載せていない。
 */
const tagBadges = computed((): TagBadge[] => {
  const tags = parseJson<string[]>(tendencyProfile.value?.tagsJson, []);
  return tags
    .filter(tag => TAG_DISPLAY[tag])
    .sort((a, b) => TAG_ORDER.indexOf(a) - TAG_ORDER.indexOf(b))
    .map(tag => ({ tag, ...TAG_DISPLAY[tag] }));
});

// ── 配置パターン（トリル/階段/二重階段/縦連打）─────────────
// 各配置パターンの定義: ラベル・色・プロファイル上のフィールド名
const PATTERN_DEFS: { key: string; label: string; countKey: keyof TendencyProfile; notesKey: keyof TendencyProfile; pctKey: keyof TendencyProfile; bar: string }[] = [
  { key: 'trill',   label: 'トリル',   countKey: 'trillCount',   notesKey: 'trillNotes',   pctKey: 'trillPct',   bar: 'bg-sky-500' },
  { key: 'stairs',  label: '階段',     countKey: 'stairsCount',  notesKey: 'stairsNotes',  pctKey: 'stairsPct',  bar: 'bg-emerald-500' },
  { key: 'dstairs', label: '二重階段', countKey: 'dstairsCount', notesKey: 'dstairsNotes', pctKey: 'dstairsPct', bar: 'bg-violet-500' },
  { key: 'jack',    label: '縦連打',   countKey: 'jackCount',    notesKey: 'jackNotes',    pctKey: 'jackPct',    bar: 'bg-rose-500' },
];

interface PatternRow { key: string; label: string; count: number; notes: number; pct: number; bar: string; width: number; }

/** 4 パターンを常に並べる（0 回は「なし」）。バーの長さは 4 つのうち最大の割合を基準にする（最低 10%）。 */
const patternRows = computed((): PatternRow[] => {
  const p = tendencyProfile.value;
  if (!p || p.trillCount == null) return [];
  const rows = PATTERN_DEFS.map(d => ({
    key: d.key,
    label: d.label,
    count: (p[d.countKey] as number | null) ?? 0,
    notes: (p[d.notesKey] as number | null) ?? 0,
    pct: (p[d.pctKey] as number | null) ?? 0,
    bar: d.bar,
    width: 0,
  }));
  const scale = Math.max(10, ...rows.map(r => r.pct));
  rows.forEach(r => { r.width = (r.pct / scale) * 100; });
  return rows;
});

// ── 同時押し構成（1 打鍵タイミングで何個同時に押すか）────────
interface ChordRow { label: string; count: number; pct: number; }

/** chordDistJson（{"1":539,"2":524,...}）を「単押し / 2個 / 3個 / 4個 / 5個以上」に集計する。 */
const chordRows = computed((): ChordRow[] => {
  const dist = parseJson<Record<string, number>>(tendencyProfile.value?.chordDistJson, {});
  const buckets = [0, 0, 0, 0, 0];
  for (const [k, v] of Object.entries(dist)) {
    const n = Number(k);
    if (!Number.isFinite(n) || n < 1) continue;
    buckets[Math.min(n, 5) - 1] += v;
  }
  const total = buckets.reduce((a, b) => a + b, 0);
  if (total === 0) return [];
  const labels = ['単押し', '2個同時', '3個同時', '4個同時', '5個以上'];
  return buckets.map((count, i) => ({ label: labels[i], count, pct: (count / total) * 100 }));
});
const chordMaxPct = computed(() => Math.max(1, ...chordRows.value.map(r => r.pct)));

// ── 小節ごとノーツ数 ─────────────────────────────────────────
const measureNotes = computed(() => parseJson<number[]>(tendencyProfile.value?.measureNotesJson, []));
const measureNotesKbd = computed(() => parseJson<number[]>(tendencyProfile.value?.measureNotesKbdJson, []));
const measureNotesScr = computed(() => parseJson<number[]>(tendencyProfile.value?.measureNotesScrJson, []));
// 鍵盤/皿の分離データが存在するかどうか（存在するなら積み上げ表示）
const hasSplitMeasure = computed(() => measureNotesKbd.value.length > 0);

/** 密度グラフの描画用データ（SVG 座標。1 小節 = 幅 4、バー幅 3） */
const density = computed(() => {
  const all = measureNotes.value;
  const n = all.length;
  if (n === 0) return null;
  const max = Math.max(...all, 1);
  const H = 100;
  const scale = H / max;
  const bars = all.map((total, i) => {
    const scr = hasSplitMeasure.value ? (measureNotesScr.value[i] ?? 0) : 0;
    const kbd = hasSplitMeasure.value ? (measureNotesKbd.value[i] ?? 0) : total;
    return {
      x: i * 4 + 0.5,
      kbdY: H - kbd * scale,
      kbdH: kbd * scale,
      scrY: H - (kbd + scr) * scale,
      scrH: scr * scale,
      tip: `${i + 1}小節: ${total}ノーツ${hasSplitMeasure.value ? `（鍵盤 ${kbd} / 皿 ${scr}）` : ''}`,
    };
  });
  const played = all.filter(v => v > 0);
  const avg = played.length ? played.reduce((a, b) => a + b, 0) / played.length : 0;
  const peakIdx = all.indexOf(Math.max(...all));
  // 目盛り: 10 小節ごと（長い譜面は 20 / 50 小節ごと）
  const step = n > 250 ? 50 : n > 120 ? 20 : 10;
  const ticks: { label: number; left: number }[] = [];
  for (let m = step; m <= n; m += step) ticks.push({ label: m, left: ((m - 0.5) / n) * 100 });
  return { n, max, width: n * 4, H, bars, avg, avgY: H - avg * scale, peak: all[peakIdx], peakMeasure: peakIdx + 1, ticks };
});

// ── 打鍵間隔の割合 ───────────────────────────────────────────
// tick値 → 日本語音符名のマッピング（96 ticks/4分音符基準）
// 例: 96→4分、24→16分、12→32分。同じ tick 数の別表記（6分=64 等）も含む
const TICK_TO_NOTE: { tick: number; label: string }[] = [
  { tick: 384, label: '全音符' },
  { tick: 288, label: '付点2分' },
  { tick: 192, label: '2分' },
  { tick: 144, label: '付点4分' },
  { tick: 96,  label: '4分' },
  { tick: 72,  label: '付点8分' },
  { tick: 64,  label: '6分' },
  { tick: 48,  label: '8分' },
  { tick: 36,  label: '付点16分' },
  { tick: 32,  label: '12分' },
  { tick: 24,  label: '16分' },
  { tick: 18,  label: '付点32分' },
  { tick: 16,  label: '24分' },
  { tick: 12,  label: '32分' },
  { tick: 8,   label: '48分' },
  { tick: 6,   label: '64分' },
];

// 音符ごとに色を固定する（譜面を替えても 16分 は常に同じ色）。長い間隔=寒色 → 短い間隔=暖色
const NOTE_COLOR: Record<string, string> = {
  '全音符': 'bg-slate-300 dark:bg-slate-500',
  '付点2分': 'bg-slate-400',
  '2分': 'bg-slate-500 dark:bg-slate-400',
  '付点4分': 'bg-sky-300',
  '4分': 'bg-sky-500',
  '付点8分': 'bg-cyan-500',
  '6分': 'bg-teal-500',
  '8分': 'bg-emerald-500',
  '付点16分': 'bg-lime-500',
  '12分': 'bg-yellow-500',
  '16分': 'bg-orange-500',
  '付点32分': 'bg-rose-400',
  '24分': 'bg-rose-500',
  '32分': 'bg-red-600',
  '48分': 'bg-fuchsia-600',
  '64分': 'bg-purple-700',
  'その他': 'bg-slate-200 dark:bg-slate-600',
};

// ノーツ分布1エントリ分: 音符名・割合(%)・実数
interface NoteDistEntry { label: string; pct: number; count: number; color: string; }

/**
 * 打鍵間隔の分布。
 * 手順1: intervalDistJson を tick(文字列キー) → 詳細 の Map としてパース
 * 手順2: TICK_TO_NOTE で既知の tick のみラベル付きで entries に追加
 * 手順3: TICK_TO_NOTE の表記順（全音符→64分）にソート
 * 手順4: 合計が100未満なら「その他」で埋める
 */
const noteDistribution = computed((): NoteDistEntry[] => {
  const dist = parseJson<Record<string, { name: string; count: number; pct: number; eff16: number }> | null>(
    tendencyProfile.value?.intervalDistJson, null);
  if (!dist) return [];
  const tickMap = new Map(TICK_TO_NOTE.map(t => [String(t.tick), t.label]));
  const entries: NoteDistEntry[] = [];
  let knownPct = 0;
  for (const [tick, data] of Object.entries(dist)) {
    const label = tickMap.get(tick);
    if (label && data.pct > 0) {
      entries.push({ label, pct: data.pct, count: data.count, color: NOTE_COLOR[label] });
      knownPct += data.pct;
    }
  }
  const order = new Map(TICK_TO_NOTE.map((t, i) => [t.label, i]));
  entries.sort((a, b) => (order.get(a.label) ?? 99) - (order.get(b.label) ?? 99));
  // 未知の tick による残りを「その他」として追加
  const otherPct = Math.round((100 - knownPct) * 10) / 10;
  if (otherPct > 0) entries.push({ label: 'その他', pct: otherPct, count: 0, color: NOTE_COLOR['その他'] });
  return entries;
});

/** いちばん多い打鍵間隔（「主なリズム」として数値欄に出す） */
const mainInterval = computed(() => {
  const known = noteDistribution.value.filter(e => e.label !== 'その他');
  return known.length ? known.reduce((a, b) => (b.pct > a.pct ? b : a)) : null;
});

// ── 類似譜面 ────────────────────────────────────────────────
const similarSongs = computed(() => predictionResult.value?.similarSongs ?? []);
const similarPlayedCount = computed(() => similarSongs.value.filter(s => s.played).length);
const targetTextages = computed(() => new Set(targetEntries.value.map(s => s.textage)));
</script>

<template>
  <div class="chart-page px-4 py-6">
    <!-- ページ見出し -->
    <header class="mb-5">
      <h2 class="text-2xl font-bold text-slate-800 dark:text-white">{{ t('nav.scorePrediction') }}</h2>
      <p class="mt-1 text-xs text-slate-500 dark:text-slate-400">
        ANOTHER / LEGGENDARIA 譜面（全レベル）の傾向を、密度・配置・リズム・譜面再生で確認できます。
      </p>
    </header>

    <!-- ページ内のタブ: 譜面ごとの分析 / 全譜面の当たり配置ランキング -->
    <nav class="page-tabs mb-4" role="tablist">
      <button type="button" role="tab" :aria-selected="pageTab === 'analysis'" :class="{ on: pageTab === 'analysis' }"
        @click="pageTab = 'analysis'">譜面分析</button>
      <button type="button" role="tab" :aria-selected="pageTab === 'ranking'" :class="{ on: pageTab === 'ranking' }"
        @click="pageTab = 'ranking'">当たり配置ランキング</button>
    </nav>

    <template v-if="pageTab === 'analysis'">
    <!-- 管理者が他ユーザーを閲覧中の注意バナー -->
    <div v-if="isAdminViewing"
      class="mb-5 flex items-center gap-2 rounded-lg bg-indigo-50 dark:bg-indigo-900/30 border border-indigo-200 dark:border-indigo-700 px-4 py-2.5 text-xs text-indigo-700 dark:text-indigo-300 font-medium"
    >
      <svg xmlns="http://www.w3.org/2000/svg" class="h-4 w-4 shrink-0" fill="none" viewBox="0 0 24 24" stroke="currentColor" stroke-width="2">
        <path stroke-linecap="round" stroke-linejoin="round" d="M5.121 17.804A13.937 13.937 0 0112 16c2.5 0 4.847.655 6.879 1.804M15 10a3 3 0 11-6 0 3 3 0 016 0zM19 10a7 7 0 11-14 0 7 7 0 0114 0z" />
      </svg>
      閲覧中ユーザーのスコアで類似譜面を表示しています
    </div>

    <div class="chart-layout">
      <!-- ═══ 曲選択 ═══ -->
      <aside class="picker">
        <div class="picker-inner rounded-md border border-slate-200 dark:border-slate-700 bg-white dark:bg-slate-800">
          <div class="p-3 border-b border-slate-100 dark:border-slate-700">
            <div class="relative">
              <svg class="pointer-events-none absolute left-2.5 top-1/2 -translate-y-1/2 h-4 w-4 text-slate-400" fill="none" viewBox="0 0 24 24" stroke="currentColor" stroke-width="2">
                <path stroke-linecap="round" stroke-linejoin="round" d="M21 21l-4.35-4.35M17 10.5a6.5 6.5 0 11-13 0 6.5 6.5 0 0113 0z" />
              </svg>
              <input
                v-model="searchQuery"
                type="search"
                :placeholder="t('scorePrediction.searchPlaceholder')"
                class="search-input w-full pl-8 pr-3 py-2 rounded-md border border-slate-300 dark:border-slate-600
                       bg-white dark:bg-slate-900 text-slate-800 dark:text-white
                       focus:outline-none focus:ring-2 focus:ring-blue-500 text-xs"
              />
            </div>
            <div class="mt-2.5 flex items-center gap-2">
              <div class="seg">
                <button type="button" :class="{ on: levelFilter === 'all' }" @click="levelFilter = 'all'">すべて</button>
                <button type="button" :class="{ on: levelFilter === 12 }" @click="levelFilter = 12">☆12</button>
                <button type="button" :class="{ on: levelFilter === 11 }" @click="levelFilter = 11">☆11</button>
                <button type="button" :class="{ on: levelFilter === 'low' }" @click="levelFilter = 'low'">☆10以下</button>
              </div>
            </div>
            <!-- 件数とランダム（レベルの絞り込みが 4 つあるので、ランダムはこの行に置いて一覧の幅に収める） -->
            <div class="mt-2 flex items-center justify-between gap-2">
              <span class="text-[11px] text-slate-400 dark:text-slate-500 tabular-nums">{{ matchedEntries.length }} 譜面</span>
              <button type="button" class="random-btn" title="ランダムに選ぶ" @click="pickRandom">
                <svg class="h-3.5 w-3.5" fill="none" viewBox="0 0 24 24" stroke="currentColor" stroke-width="2">
                  <path stroke-linecap="round" stroke-linejoin="round" d="M16 3h5v5M4 20 21 3M21 16v5h-5M15 15l6 6M4 4l5 5" />
                </svg>
                ランダム
              </button>
            </div>
          </div>

          <!-- モバイルで曲を選んだ後は畳む -->
          <button v-if="listCollapsed" type="button" class="reopen-btn text-xs font-semibold text-blue-600 dark:text-blue-400"
            @click="pickerOpen = true">
            一覧から選ぶ
            <svg class="h-3.5 w-3.5" fill="none" viewBox="0 0 24 24" stroke="currentColor" stroke-width="2.5"><path stroke-linecap="round" stroke-linejoin="round" d="m6 9 6 6 6-6" /></svg>
          </button>

          <ul class="picker-list" :class="{ collapsed: listCollapsed }">
            <li v-for="entry in shownEntries" :key="entry.textage">
              <button type="button"
                class="song-item"
                :class="[metaOf(entry.difficulty).cls, { active: selectedEntry?.textage === entry.textage }]"
                @click="selectEntry(entry)">
                <span class="min-w-0 flex-1">
                  <span class="block truncate text-xs font-medium">{{ entry.title }}</span>
                  <span class="block truncate text-[10px] text-slate-400 dark:text-slate-500">{{ entry.artist }}</span>
                </span>
                <span class="shrink-0 text-[10px] font-bold tabular-nums"
                  :class="metaOf(entry.difficulty).text">
                  ☆{{ entry.level }} {{ diffShort(entry.difficulty) }}
                </span>
              </button>
            </li>
            <li v-if="matchedEntries.length > listLimit">
              <button type="button" class="more-btn text-xs text-blue-600 dark:text-blue-400" @click="listLimit += LIST_PAGE">
                さらに表示（残り {{ matchedEntries.length - listLimit }}）
              </button>
            </li>
            <li v-if="!matchedEntries.length" class="px-3 py-6 text-center text-xs text-slate-400 dark:text-slate-500">
              {{ songDataBody.length ? '該当する譜面がありません' : '楽曲データを読み込み中…' }}
            </li>
          </ul>
        </div>
      </aside>

      <!-- ═══ 分析結果 ═══ -->
      <div class="min-w-0">
        <!-- URL で指定された textage が songData に見つからなかった場合 -->
        <div v-if="unknownTextageFromUrl"
          class="rounded-md border border-amber-300 bg-amber-50 dark:bg-amber-900/20 dark:border-amber-700 p-6 text-center">
          <p class="text-amber-700 dark:text-amber-300 font-medium mb-1">{{ t('chartAnalysis.notFound') }}</p>
          <p class="text-xs text-amber-600/80 dark:text-amber-400/80 break-all">{{ unknownTextageFromUrl }}</p>
        </div>

        <!-- 未選択 -->
        <div v-else-if="!selectedEntry"
          class="empty-state rounded-md border border-dashed border-slate-300 dark:border-slate-600 text-center">
          <svg class="mx-auto h-10 w-10 text-slate-300 dark:text-slate-600" fill="none" viewBox="0 0 24 24" stroke="currentColor" stroke-width="1.5">
            <path stroke-linecap="round" stroke-linejoin="round" d="M3 3v18h18M7 15l3-4 3 2 5-7" />
          </svg>
          <p class="mt-3 text-sm font-semibold text-slate-600 dark:text-slate-300">譜面を選んでください</p>
          <p class="mt-1 text-xs text-slate-400 dark:text-slate-500">
            曲名・アーティストで検索するか、一覧から選びます。
          </p>
          <button type="button"
            class="mt-4 inline-flex items-center gap-1.5 rounded-md bg-blue-600 hover:bg-blue-500 text-white text-xs font-bold px-3.5 py-2"
            @click="pickRandom">
            ランダムに 1 譜面見る
          </button>
          <ul class="feature-list mt-6 text-left text-xs text-slate-500 dark:text-slate-400">
            <li><span class="dot bg-rose-500"></span>小節ごとのノーツ密度（鍵盤／皿）</li>
            <li><span class="dot bg-sky-500"></span>トリル・階段・縦連などの配置パターン</li>
            <li><span class="dot bg-orange-500"></span>同時押しの構成と打鍵間隔のリズム</li>
            <li><span class="dot bg-emerald-500"></span>譜面再生（RANDOM・ソフラン再現つき）</li>
          </ul>
        </div>

        <div v-else class="flex flex-col gap-4">
          <!-- ─── 譜面ヘッダ ─── -->
          <section class="hero rounded-md border border-slate-200 dark:border-slate-700 bg-white dark:bg-slate-800"
            :class="metaOf(selectedEntry.difficulty).cls">
            <div class="hero-top">
              <div class="min-w-0">
                <div class="flex flex-wrap items-center gap-1.5 text-[11px] font-bold">
                  <span class="px-1.5 py-0.5 rounded" :class="diffBadgeClass(selectedEntry.difficulty)">
                    {{ diffLabel(selectedEntry.difficulty) }}
                  </span>
                  <span class="px-1.5 py-0.5 rounded bg-slate-100 text-slate-600 dark:bg-slate-700 dark:text-slate-300">☆{{ selectedEntry.level }}</span>
                </div>
                <h3 class="hero-title mt-2 font-bold text-slate-800 dark:text-white break-words">{{ selectedEntry.title }}</h3>
                <p class="mt-0.5 text-xs text-slate-500 dark:text-slate-400 break-words">
                  {{ selectedEntry.artist }}<span v-if="selectedEntry.genre" class="text-slate-400 dark:text-slate-500"> ／ {{ selectedEntry.genre }}</span>
                </p>
              </div>
              <div class="hero-actions">
                <button type="button" class="action-btn" @click="copyLink">
                  <svg class="h-3.5 w-3.5" fill="none" viewBox="0 0 24 24" stroke="currentColor" stroke-width="2">
                    <path stroke-linecap="round" stroke-linejoin="round" d="M10 14a4 4 0 0 0 5.66 0l3-3a4 4 0 0 0-5.66-5.66l-1 1M14 10a4 4 0 0 0-5.66 0l-3 3a4 4 0 0 0 5.66 5.66l1-1" />
                  </svg>
                  {{ linkCopied ? 'コピーしました' : 'リンクをコピー' }}
                </button>
                <a v-if="selectedEntry.textage" class="action-btn" :href="`https://textage.cc/score/${selectedEntry.textage}`" target="_blank" rel="noopener noreferrer">
                  TexTage
                  <svg class="h-3 w-3" fill="none" viewBox="0 0 24 24" stroke="currentColor" stroke-width="2.5">
                    <path stroke-linecap="round" stroke-linejoin="round" d="M14 5h5v5M19 5l-8 8M18 14v5H5V6h5" />
                  </svg>
                </a>
              </div>
            </div>

            <dl class="hero-meta text-xs">
              <div><dt>NOTES</dt><dd>{{ selectedEntry.notes.toLocaleString() }}</dd></div>
              <div>
                <dt>BPM</dt>
                <dd>
                  {{ tendencyProfile?.bpmRaw ?? selectedEntry.bpm }}
                  <span v-if="tendencyProfile?.isSoflan" class="ml-1 text-[10px] font-bold text-amber-600 dark:text-amber-400">ソフラン</span>
                </dd>
              </div>
              <div v-if="tendencyProfile?.cnNotes"><dt>CN</dt><dd>{{ tendencyProfile.cnNotes }}</dd></div>
              <div v-if="density"><dt>小節数</dt><dd>{{ density.n }}</dd></div>
            </dl>

            <div v-if="tagBadges.length" class="mt-3 flex flex-wrap gap-1.5">
              <span v-for="tb in tagBadges" :key="tb.tag"
                class="inline-flex items-center px-2 py-0.5 rounded-full text-[11px] font-bold"
                :class="tb.colorClass">{{ tb.label }}</span>
            </div>
          </section>

          <!-- ─── プロファイル読み込み中／なし ─── -->
          <div v-if="profileState === 'loading'" class="skeleton-grid">
            <div v-for="i in 4" :key="i" class="h-20 rounded-md bg-slate-100 dark:bg-slate-800 animate-pulse"></div>
            <div class="skeleton-wide h-40 rounded-md bg-slate-100 dark:bg-slate-800 animate-pulse"></div>
          </div>
          <div v-else-if="profileState === 'missing'"
            class="rounded-md border border-slate-200 dark:border-slate-700 bg-white dark:bg-slate-800 p-5 text-center text-xs text-slate-400 dark:text-slate-500">
            この譜面の傾向データはまだありません。譜面再生は下から利用できます。
          </div>

          <template v-else-if="tendencyProfile">
            <!-- ─── 主要な数値 ─── -->
            <div class="stat-grid">
              <div class="stat rounded-md border border-slate-200 dark:border-slate-700 bg-white dark:bg-slate-800">
                <div class="stat-label">実効BPM</div>
                <div class="stat-value text-slate-800 dark:text-white">{{ tendencyProfile.dominantEff16.toFixed(0) }}</div>
                <div class="stat-sub">加重平均 {{ tendencyProfile.weightedEff16.toFixed(0) }}</div>
              </div>
              <div class="stat rounded-md border border-slate-200 dark:border-slate-700 bg-white dark:bg-slate-800">
                <div class="stat-label">皿の割合</div>
                <div class="stat-value text-rose-600 dark:text-rose-400">{{ tendencyProfile.scratchPct.toFixed(1) }}<small>%</small></div>
                <div class="stat-sub">約 {{ Math.round(tendencyProfile.notes * tendencyProfile.scratchPct / 100) }} ノーツ</div>
              </div>
              <div class="stat rounded-md border border-slate-200 dark:border-slate-700 bg-white dark:bg-slate-800">
                <div class="stat-label">同時押し率</div>
                <div class="stat-value text-blue-600 dark:text-blue-400">{{ tendencyProfile.chordPct.toFixed(1) }}<small>%</small></div>
                <div class="stat-sub">単押し {{ tendencyProfile.singlePct.toFixed(1) }}%</div>
              </div>
              <div class="stat rounded-md border border-slate-200 dark:border-slate-700 bg-white dark:bg-slate-800">
                <div class="stat-label">最大密度</div>
                <div class="stat-value text-amber-600 dark:text-amber-400">{{ density?.peak ?? '-' }}<small>/小節</small></div>
                <div class="stat-sub">
                  <template v-if="density">{{ density.peakMeasure }}小節目 ・ 平均 {{ density.avg.toFixed(1) }}</template>
                </div>
              </div>
            </div>

            <!-- ─── ノーツ密度 ─── -->
            <section v-if="density" class="card rounded-md border border-slate-200 dark:border-slate-700 bg-white dark:bg-slate-800">
              <div class="card-head">
                <h4 class="card-title">ノーツ密度</h4>
                <div class="legend">
                  <span v-if="hasSplitMeasure"><i class="bg-slate-400 dark:bg-slate-300"></i>鍵盤</span>
                  <span v-if="hasSplitMeasure"><i class="bg-rose-500"></i>皿</span>
                  <span><i class="legend-line"></i>平均</span>
                </div>
              </div>
              <div class="density-wrap">
                <span class="density-max tabular-nums">{{ density.max }}</span>
                <svg class="density-svg" :viewBox="`0 0 ${density.width} ${density.H}`" preserveAspectRatio="none" role="img"
                  :aria-label="`小節ごとのノーツ数（最大 ${density.max}）`">
                  <g v-for="(b, i) in density.bars" :key="i" class="density-col">
                    <title>{{ b.tip }}</title>
                    <rect :x="b.x - 0.5" y="0" width="4" :height="density.H" class="density-hover" />
                    <rect v-if="b.kbdH > 0" :x="b.x" :y="b.kbdY" width="3" :height="b.kbdH" class="fill-slate-400 dark:fill-slate-300" />
                    <rect v-if="b.scrH > 0" :x="b.x" :y="b.scrY" width="3" :height="b.scrH" class="fill-rose-500" />
                  </g>
                  <line x1="0" :x2="density.width" :y1="density.avgY" :y2="density.avgY" class="density-avg" vector-effect="non-scaling-stroke" />
                </svg>
                <div class="density-axis tabular-nums">
                  <span v-for="tk in density.ticks" :key="tk.label" :style="{ left: `${tk.left}%` }">{{ tk.label }}</span>
                </div>
              </div>
            </section>

            <!-- ─── 配置パターン / 同時押し構成 ─── -->
            <div class="two-col">
              <section v-if="patternRows.length" class="card rounded-md border border-slate-200 dark:border-slate-700 bg-white dark:bg-slate-800">
                <div class="card-head">
                  <h4 class="card-title">配置パターン</h4>
                  <span class="card-note">全ノーツに占める割合</span>
                </div>
                <ul class="bar-list">
                  <li v-for="r in patternRows" :key="r.key" :class="{ 'is-zero': r.count === 0 }"
                    :title="r.count ? `${r.count}回・${r.notes}ノーツ` : undefined">
                    <span class="bar-label">{{ r.label }}</span>
                    <span class="bar-track"><span class="bar-fill" :class="r.bar" :style="{ width: `${r.width}%` }"></span></span>
                    <span class="bar-value tabular-nums">
                      <template v-if="r.count">{{ r.pct }}%<small>{{ r.count }}回</small></template>
                      <template v-else>なし</template>
                    </span>
                  </li>
                </ul>
              </section>

              <section v-if="chordRows.length" class="card rounded-md border border-slate-200 dark:border-slate-700 bg-white dark:bg-slate-800">
                <div class="card-head">
                  <h4 class="card-title">同時押し構成</h4>
                  <span class="card-note">打鍵タイミングごとの個数</span>
                </div>
                <ul class="bar-list">
                  <li v-for="r in chordRows" :key="r.label" :class="{ 'is-zero': r.count === 0 }" :title="`${r.count}回`">
                    <span class="bar-label">{{ r.label }}</span>
                    <span class="bar-track"><span class="bar-fill bg-blue-500" :style="{ width: `${(r.pct / chordMaxPct) * 100}%` }"></span></span>
                    <span class="bar-value tabular-nums">{{ r.pct.toFixed(1) }}%</span>
                  </li>
                </ul>
              </section>
            </div>

            <!-- ─── 打鍵間隔 ─── -->
            <section v-if="noteDistribution.length" class="card rounded-md border border-slate-200 dark:border-slate-700 bg-white dark:bg-slate-800">
              <div class="card-head">
                <h4 class="card-title">打鍵間隔</h4>
                <span v-if="mainInterval" class="card-note">主なリズム: <b class="text-slate-600 dark:text-slate-300">{{ mainInterval.label }}</b></span>
              </div>
              <div class="rhythm-bar">
                <div v-for="nd in noteDistribution" :key="nd.label"
                  :class="nd.color" :style="{ width: `${nd.pct}%` }" :title="`${nd.label}: ${nd.pct}%`">
                  <span v-if="nd.pct >= 9">{{ nd.label }}</span>
                </div>
              </div>
              <div class="mt-2.5 flex flex-wrap gap-x-3.5 gap-y-1">
                <div v-for="nd in noteDistribution" :key="nd.label" class="flex items-center gap-1 text-[11px]">
                  <span class="inline-block w-2.5 h-2.5 rounded-sm shrink-0" :class="nd.color"></span>
                  <span class="font-semibold text-slate-600 dark:text-slate-300">{{ nd.label }}</span>
                  <span class="tabular-nums text-slate-400 dark:text-slate-500">{{ nd.pct }}%</span>
                </div>
              </div>
            </section>
          </template>

          <!-- ─── 譜面再生（データは「再生する」を押したときに取得。曲を替えたら作り直す）─── -->
          <ChartPlayer v-if="selectedEntry.textage" :key="selectedEntry.textage" :textage="selectedEntry.textage"
            :initial-pattern="playerStartPattern" @go="openFromPlayer" />

          <!-- ─── 類似譜面 ─── -->
          <section class="card rounded-md border border-slate-200 dark:border-slate-700 bg-white dark:bg-slate-800">
            <div class="card-head">
              <h4 class="card-title">{{ t('scorePrediction.similarSongs') }}</h4>
              <span v-if="similarSongs.length" class="card-note tabular-nums">
                {{ similarSongs.length }}譜面 ・ プレー済み {{ similarPlayedCount }}
              </span>
            </div>

            <p v-if="!isLoggedIn" class="py-3 text-center text-xs text-amber-700 dark:text-amber-300">
              {{ t('chartAnalysis.loginToSeeMore') }}
            </p>
            <div v-else-if="isLoading" class="py-6 flex items-center justify-center text-xs text-slate-400 dark:text-slate-500">
              <div class="w-4 h-4 mr-2 border-2 border-blue-200 border-t-blue-500 rounded-full animate-spin"></div>
              {{ t('scorePrediction.calculating') }}
            </div>
            <p v-else-if="predictionError" class="py-3 text-xs text-red-600 dark:text-red-400">{{ predictionError }}</p>
            <p v-else-if="predictionResult && !similarSongs.length" class="py-3 text-center text-xs text-slate-400 dark:text-slate-500">
              類似譜面が見つかりませんでした
            </p>

            <template v-else-if="similarSongs.length">
              <p v-if="predictionResult?.currentScore != null" class="mb-3 text-xs text-slate-500 dark:text-slate-400">
                この譜面の{{ isAdminViewing ? '' : 'あなたの' }}スコア:
                <b class="tabular-nums text-slate-700 dark:text-slate-200">{{ predictionResult.currentScore.toLocaleString() }}</b>
                <span v-if="predictionResult.currentScoreRate != null" class="ml-1 tabular-nums" :class="djLevelClass(predictionResult.currentScoreRate)">
                  {{ predictionResult.currentScoreRate }}%
                </span>
              </p>
              <ul class="similar-list">
                <li v-for="song in similarSongs" :key="`${song.title}_${song.difficultyName}`" class="similar-row">
                  <span class="sim-meter" :title="`類似度 ${(song.similarity * 100).toFixed(2)}%`">
                    <span class="sim-num tabular-nums">{{ (song.similarity * 100).toFixed(0) }}</span>
                    <span class="sim-track"><span class="sim-fill" :style="{ width: `${Math.max(0, Math.min(1, song.similarity)) * 100}%` }"></span></span>
                  </span>
                  <span class="min-w-0 flex items-center gap-1.5">
                    <span class="shrink-0 px-1 rounded text-[10px] font-bold"
                      :class="diffBadgeClass(DIFF_CODE_BY_NAME[song.difficultyName] ?? '4')">
                      {{ diffShort(DIFF_CODE_BY_NAME[song.difficultyName] ?? '4') }}
                    </span>
                    <button v-if="targetTextages.has(song.textage)" type="button"
                      class="truncate text-left text-xs text-slate-700 dark:text-slate-200 hover:text-blue-600 dark:hover:text-blue-400 hover:underline"
                      @click="goToChart(song.textage)">{{ song.title }}</button>
                    <span v-else class="truncate text-xs text-slate-700 dark:text-slate-200">{{ song.title }}</span>
                  </span>
                  <span class="text-right text-xs tabular-nums whitespace-nowrap">
                    <template v-if="song.played">
                      <span class="text-slate-500 dark:text-slate-400">{{ song.score!.toLocaleString() }}</span>
                      <span class="ml-1.5" :class="djLevelClass(song.scoreRate!)">{{ song.scoreRate }}%</span>
                    </template>
                    <span v-else class="text-slate-400 dark:text-slate-500">未プレー</span>
                  </span>
                  <button v-if="isAdmin" type="button" class="debug-btn" title="類似度の内訳（管理者）"
                    @click="fetchSimilarityDebug(song.textage)">内訳</button>
                </li>
              </ul>
            </template>
          </section>
        </div>
      </div>
    </div>
    </template>

    <!-- 当たり配置ランキング: 正規・MIRROR・R-RANDOM・自由入力の並びが各譜面で何位か（1P 基準・事前計算） -->
    <RandomRanking v-else @open="openChartFromRanking" />

    <!-- 類似度デバッグモーダル（管理者機能）: 計算過程を4グループに分けて表示 -->
    <Teleport to="body">
      <div v-if="debugResult || isDebugLoading"
        class="fixed inset-0 z-50 flex items-center justify-center bg-black/50 p-4"
        @click.self="debugResult = null">
        <div class="bg-white dark:bg-slate-800 rounded-md shadow-xl w-full max-w-2xl max-h-[90vh] overflow-y-auto p-6">
          <div class="flex justify-between items-center mb-4">
            <h3 class="text-base font-bold text-slate-800 dark:text-slate-100">類似度計算過程</h3>
            <button @click="debugResult = null" class="text-slate-400 hover:text-slate-600 dark:hover:text-slate-200">✕</button>
          </div>
          <div v-if="isDebugLoading" class="flex justify-center py-10">
            <div class="w-8 h-8 border-4 border-blue-200 border-t-blue-500 rounded-full animate-spin"></div>
          </div>
          <div v-else-if="debugResult">
            <!-- 上部: 比較対象2曲(A,B)の情報カード -->
            <div class="flex gap-3 mb-4 text-sm">
              <div class="flex-1 bg-slate-50 dark:bg-slate-900 rounded-md p-3">
                <div class="text-xs text-slate-400 mb-1">対象曲 (A)</div>
                <div class="font-bold text-slate-800 dark:text-slate-100">{{ debugResult.songA?.title }}</div>
                <div class="text-xs text-slate-500">難易度 {{ debugResult.songA?.informalRank }}</div>
              </div>
              <div class="flex-1 bg-slate-50 dark:bg-slate-900 rounded-md p-3">
                <div class="text-xs text-slate-400 mb-1">参照曲 (B)</div>
                <div class="font-bold text-slate-800 dark:text-slate-100">{{ debugResult.songB?.title }}</div>
                <div class="text-xs text-slate-500">難易度 {{ debugResult.songB?.informalRank }}</div>
              </div>
            </div>
            <!-- 生データ比較テーブル: ノーツ密度・BPM・スクラッチ割合などを並べて表示 -->
            <div class="mb-4">
              <div class="text-xs font-bold text-slate-500 mb-2">生データ比較</div>
              <table class="w-full text-xs">
                <thead><tr class="text-slate-400"><th class="text-left pb-1">指標</th><th class="text-right pb-1">A</th><th class="text-right pb-1">B</th></tr></thead>
                <tbody class="divide-y divide-slate-100 dark:divide-slate-700">
                  <tr v-for="[key, label] in [
                    ['nps', 'ノーツ密度 (nps)'],
                    ['dominantEff16', '主要インターバル実効BPM'],
                    ['weightedEff16', '加重平均実効BPM'],
                    ['scratchPct', 'スクラッチ割合 (%)'],
                    ['chordPct', '同時押し割合 (%)'],
                    ['cnRatio', 'CN割合'],
                  ]" :key="key">
                    <td class="py-1 text-slate-500">{{ label }}</td>
                    <td class="py-1 text-right tabular-nums text-slate-700 dark:text-slate-300">{{ debugResult.rawA?.[key] }}</td>
                    <td class="py-1 text-right tabular-nums text-slate-700 dark:text-slate-300">{{ debugResult.rawB?.[key] }}</td>
                  </tr>
                </tbody>
              </table>
            </div>
            <!-- グループ別の類似度内訳（密度/スクラッチ/鍵盤パターン/CN の4グループ） -->
            <div class="space-y-3">
              <div v-for="[label, key, color, paramLabels] in ([
                ['Group1: 密度', 'group1_density', 'blue', {
                  dNps_norm: 'ノーツ密度差（正規化）',
                  dEff16_norm: '主要BPM差（正規化）',
                  dWEff16_norm: '加重BPM差（正規化）',
                  dRank_norm: '非公式難易度差（正規化）',
                  dist2: '距離二乗和',
                  densitySim: '密度類似度',
                }],
                ['Group2: スクラッチ', 'group2_scratch', 'orange', {
                  dScratchPct_norm: 'スクラッチ割合差（正規化）',
                  scrScalar: 'スクラッチ量スカラー類似度',
                  scrIntervalCosineSim: 'スクラッチリズムコサイン類似度',
                  scratchSim: 'スクラッチ総合類似度',
                  'weight(scrW)': '重み (max側の割合)',
                  contribution: 'グループ寄与度',
                }],
                ['Group3: 鍵盤パターン', 'group3_pattern', 'green', {
                  dChordPct_norm: '同時押し割合差（正規化）',
                  chordScalar: '同時押しスカラー類似度',
                  kbdIntervalCosineSim: '鍵盤リズムコサイン類似度',
                  patternSim: '鍵盤パターン総合類似度',
                  'weight(kbdW)': '重み (1 - 平均スクラッチ割合)',
                  contribution: 'グループ寄与度',
                }],
                ['Group4: CN', 'group4_cn', 'purple', {
                  cnRatioA: 'CN割合 A',
                  cnRatioB: 'CN割合 B',
                  dCnRatio_norm: 'CN割合差（正規化）',
                  dCnScratch_norm: 'CNスクラッチ割合差（正規化）',
                  cnScalar: 'CNスカラー類似度',
                  cnIntervalCosineSim: 'CNリズムコサイン類似度',
                  cnSim: 'CN総合類似度',
                  'weight(cnW)': '重み (平均CN割合)',
                  contribution: 'グループ寄与度',
                }],
              ] as any[])" :key="key" class="rounded-md border border-slate-100 dark:border-slate-700 p-3">
                <div class="flex justify-between items-center mb-2">
                  <span class="text-xs font-bold text-slate-600 dark:text-slate-300">{{ label }}</span>
                  <span class="text-sm font-bold tabular-nums"
                    :class="color === 'blue' ? 'text-blue-600 dark:text-blue-400' : color === 'orange' ? 'text-orange-600 dark:text-orange-400' : color === 'green' ? 'text-green-600 dark:text-green-400' : 'text-purple-600 dark:text-purple-400'">
                    寄与: {{ ((debugResult[key]?.contribution ?? 1) * 100).toFixed(2) }}%
                  </span>
                </div>
                <div class="grid grid-cols-2 gap-x-4 gap-y-0.5 text-xs text-slate-500">
                  <template v-for="(val, k) in debugResult[key]" :key="k">
                    <span class="truncate" :title="String(k)">{{ paramLabels[k] ?? k }}</span>
                    <span class="text-right tabular-nums text-slate-700 dark:text-slate-300">{{ val }}</span>
                  </template>
                </div>
              </div>
            </div>
            <!-- 最終類似度: 各グループ寄与度を掛け合わせた最終値 -->
            <div class="mt-4 rounded-md bg-slate-900 dark:bg-slate-950 p-4 text-center">
              <div class="text-xs text-slate-400 mb-1">最終類似度</div>
              <div class="text-3xl font-bold text-white">{{ debugResult.result?.finalSimilarityPct }}</div>
              <div class="text-xs text-slate-500 mt-1">
                統合値 (G1×G2^scrW×G3^kbdW×G4^cnW): {{ debugResult.result?.combined }}
              </div>
            </div>
          </div>
        </div>
      </div>
    </Teleport>

    <!-- 謝辞: 譜面データ提供元 TexTage へのクレジット -->
    <div class="mt-8 pt-4 border-t border-slate-200 dark:border-slate-700 text-center text-xs text-slate-400 dark:text-slate-500">
      譜面データは
      <a href="https://textage.cc/" target="_blank" rel="noopener noreferrer"
         class="text-blue-500 hover:text-blue-400 underline">TexTage</a>
      を利用しています。
    </div>
  </div>
</template>

<style scoped>
/* レスポンシブは Tailwind の sm:/lg: ではなくここで書く（src/output.css が後勝ちで潰すため） */
.page-tabs {
  display: flex;
  gap: 0.25rem;
  border-bottom: 1px solid rgb(226 232 240);
}
.page-tabs button {
  padding: 0.5rem 0.9rem;
  font-size: 0.8rem;
  font-weight: 600;
  white-space: nowrap;
  color: rgb(100 116 139);
  border-bottom: 2px solid transparent;
  margin-bottom: -1px;
}
.page-tabs button.on { color: rgb(37 99 235); border-bottom-color: rgb(37 99 235); }
.dark .page-tabs { border-bottom-color: rgb(51 65 85); }
.dark .page-tabs button { color: rgb(148 163 184); }
.dark .page-tabs button.on { color: rgb(96 165 250); border-bottom-color: rgb(96 165 250); }
.chart-layout {
  display: grid;
  grid-template-columns: minmax(0, 1fr);
  gap: 1rem;
}
@media (min-width: 1024px) {
  .chart-layout { grid-template-columns: 17.5rem minmax(0, 1fr); gap: 1.5rem; align-items: start; }
  .picker { position: sticky; top: 1rem; }
}

/* ── 曲選択 ── */
.picker-inner { overflow: hidden; }
.search-input::-webkit-search-cancel-button { cursor: pointer; }
.picker-list {
  max-height: 16rem;
  overflow-y: auto;
  padding: 0.25rem;
}
@media (min-width: 1024px) {
  .picker-list { max-height: calc(100vh - 13rem); }
}
@media (max-width: 1023px) {
  .picker-list.collapsed { display: none; }
}
.reopen-btn {
  display: none;
  width: 100%;
  align-items: center;
  justify-content: center;
  gap: 0.25rem;
  padding: 0.6rem;
}
@media (max-width: 1023px) {
  .reopen-btn { display: flex; }
}
.song-item {
  display: flex;
  align-items: center;
  gap: 0.5rem;
  width: 100%;
  padding: 0.4rem 0.6rem 0.4rem 0.7rem;
  border-radius: 0.375rem;
  text-align: left;
  color: rgb(51 65 85);
  border-left: 3px solid transparent;
}
.song-item:hover { background: rgb(248 250 252); }
.song-item.active.is-ano { background: rgb(254 242 242); border-left-color: rgb(239 68 68); }
.song-item.active.is-leg { background: rgb(250 245 255); border-left-color: rgb(168 85 247); }
.dark .song-item { color: rgb(226 232 240); }
.dark .song-item:hover { background: rgb(51 65 85 / 0.5); }
.dark .song-item.active.is-ano { background: rgb(127 29 29 / 0.3); }
.dark .song-item.active.is-leg { background: rgb(88 28 135 / 0.3); }
.more-btn { width: 100%; padding: 0.6rem; }

.seg {
  display: inline-flex;
  border-radius: 0.375rem;
  overflow: hidden;
  border: 1px solid rgb(203 213 225);
  font-size: 0.75rem;
}
.seg button { padding: 0.3rem 0.55rem; font-weight: 600; white-space: nowrap; color: rgb(71 85 105); background: white; }
.seg button + button { border-left: 1px solid rgb(203 213 225); }
.seg button.on { color: white; background: rgb(37 99 235); }
.dark .seg { border-color: rgb(71 85 105); }
.dark .seg button { color: rgb(203 213 225); background: rgb(30 41 59); }
.dark .seg button + button { border-left-color: rgb(71 85 105); }
.dark .seg button.on { color: white; background: rgb(37 99 235); }
.random-btn {
  margin-left: auto;
  white-space: nowrap;
  display: inline-flex;
  align-items: center;
  gap: 0.3rem;
  padding: 0.3rem 0.6rem;
  border-radius: 0.375rem;
  font-size: 0.75rem;
  font-weight: 600;
  color: rgb(71 85 105);
  border: 1px solid rgb(203 213 225);
}
.random-btn:hover { background: rgb(248 250 252); }
.dark .random-btn { color: rgb(203 213 225); border-color: rgb(71 85 105); }
.dark .random-btn:hover { background: rgb(51 65 85); }

/* ── 未選択 ── */
.empty-state { padding: 2.5rem 1.25rem; }
.feature-list { display: grid; gap: 0.5rem; max-width: 20rem; margin-left: auto; margin-right: auto; }
.feature-list li { display: flex; align-items: center; gap: 0.5rem; }
.feature-list .dot { width: 0.5rem; height: 0.5rem; border-radius: 9999px; flex: none; }

/* ── 譜面ヘッダ ── */
.hero {
  position: relative;
  overflow: hidden;
  padding: 1.1rem 1.25rem 1.1rem 1.5rem;
}
.hero::before {
  content: '';
  position: absolute;
  inset: 0 auto 0 0;
  width: 4px;
}
.hero.is-ano::before { background: linear-gradient(to bottom, rgb(239 68 68), rgb(249 115 22)); }
.hero.is-leg::before { background: linear-gradient(to bottom, rgb(168 85 247), rgb(236 72 153)); }
.hero-top { display: flex; gap: 1rem; align-items: flex-start; justify-content: space-between; }
.hero-title { font-size: 1.25rem; line-height: 1.3; }
@media (min-width: 640px) {
  .hero-title { font-size: 1.5rem; }
}
.hero-actions { display: flex; flex-wrap: wrap; gap: 0.4rem; justify-content: flex-end; flex: none; }
@media (max-width: 639px) {
  .hero-top { flex-direction: column; }
  .hero-actions { justify-content: flex-start; }
}
.action-btn {
  display: inline-flex;
  align-items: center;
  gap: 0.3rem;
  padding: 0.35rem 0.65rem;
  border-radius: 0.375rem;
  font-size: 0.7rem;
  font-weight: 600;
  white-space: nowrap;
  color: rgb(71 85 105);
  border: 1px solid rgb(226 232 240);
}
.action-btn:hover { background: rgb(248 250 252); }
.dark .action-btn { color: rgb(203 213 225); border-color: rgb(71 85 105); }
.dark .action-btn:hover { background: rgb(51 65 85); }
.hero-meta {
  display: flex;
  flex-wrap: wrap;
  gap: 0.35rem 1.5rem;
  margin-top: 0.9rem;
}
.hero-meta dt { font-size: 0.625rem; font-weight: 700; letter-spacing: 0.06em; color: rgb(148 163 184); }
.hero-meta dd { font-weight: 700; font-variant-numeric: tabular-nums; color: rgb(51 65 85); }
.dark .hero-meta dd { color: rgb(226 232 240); }

/* ── 数値 4 枚 ── */
.stat-grid, .skeleton-grid {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 0.75rem;
}
@media (min-width: 768px) {
  .stat-grid, .skeleton-grid { grid-template-columns: repeat(4, minmax(0, 1fr)); }
}
.skeleton-wide { grid-column: 1 / -1; }
.stat { padding: 0.75rem 0.9rem; }
.stat-label { font-size: 0.7rem; font-weight: 600; color: rgb(100 116 139); }
.stat-value { margin-top: 0.15rem; font-size: 1.6rem; line-height: 1.1; font-weight: 800; font-variant-numeric: tabular-nums; }
.stat-value small { margin-left: 0.1rem; font-size: 0.75rem; font-weight: 700; opacity: 0.75; }
.stat-sub { margin-top: 0.25rem; min-height: 1rem; font-size: 0.68rem; color: rgb(148 163 184); font-variant-numeric: tabular-nums; }
.dark .stat-label { color: rgb(148 163 184); }
.dark .stat-sub { color: rgb(100 116 139); }

/* ── カード共通 ── */
.card { padding: 1rem 1.1rem; }
.card-head { display: flex; align-items: baseline; justify-content: space-between; gap: 0.75rem; margin-bottom: 0.75rem; flex-wrap: wrap; }
.card-title { font-size: 0.8rem; font-weight: 700; color: rgb(51 65 85); }
.card-note { font-size: 0.68rem; color: rgb(148 163 184); }
.dark .card-title { color: rgb(226 232 240); }
.two-col { display: grid; grid-template-columns: minmax(0, 1fr); gap: 1rem; }
@media (min-width: 768px) {
  .two-col { grid-template-columns: repeat(2, minmax(0, 1fr)); }
}

/* ── ノーツ密度 ── */
.legend { display: flex; gap: 0.75rem; font-size: 0.68rem; color: rgb(100 116 139); }
.legend span { display: inline-flex; align-items: center; gap: 0.3rem; }
.legend i { display: inline-block; width: 0.6rem; height: 0.6rem; border-radius: 2px; }
.legend i.legend-line { height: 0; width: 0.9rem; border-top: 1.5px dashed rgb(245 158 11); border-radius: 0; }
.density-wrap { position: relative; padding-bottom: 1.1rem; }
.density-max { position: absolute; top: -0.2rem; left: 0; font-size: 0.6rem; color: rgb(148 163 184); }
.density-svg {
  display: block;
  width: 100%;
  height: 8.5rem;
  border-bottom: 1px solid rgb(226 232 240);
}
.dark .density-svg { border-bottom-color: rgb(51 65 85); }
.density-hover { fill: transparent; }
.density-col:hover .density-hover { fill: rgb(148 163 184 / 0.18); }
.density-avg { stroke: rgb(245 158 11); stroke-width: 1.5; stroke-dasharray: 4 3; }
.density-axis { position: absolute; left: 0; right: 0; bottom: 0; height: 1rem; font-size: 0.6rem; color: rgb(148 163 184); }
.density-axis span { position: absolute; top: 0.15rem; transform: translateX(-50%); }

/* ── 横棒リスト（配置パターン・同時押し構成） ── */
.bar-list { display: grid; gap: 0.55rem; }
.bar-list li { display: grid; grid-template-columns: 4.5rem minmax(0, 1fr) 5.2rem; align-items: center; gap: 0.6rem; font-size: 0.75rem; }
.bar-label { font-weight: 600; color: rgb(71 85 105); }
.dark .bar-label { color: rgb(203 213 225); }
.bar-track { height: 0.55rem; border-radius: 9999px; background: rgb(241 245 249); overflow: hidden; }
.dark .bar-track { background: rgb(51 65 85); }
.bar-fill { display: block; height: 100%; border-radius: 9999px; }
.bar-value { text-align: right; font-weight: 700; color: rgb(51 65 85); white-space: nowrap; }
.bar-value small { margin-left: 0.35rem; font-weight: 500; font-size: 0.65rem; color: rgb(148 163 184); }
.dark .bar-value { color: rgb(226 232 240); }
.bar-list li.is-zero .bar-label, .bar-list li.is-zero .bar-value { color: rgb(148 163 184); font-weight: 500; }

/* ── 打鍵間隔 ── */
.rhythm-bar { display: flex; height: 1.6rem; border-radius: 0.375rem; overflow: hidden; }
.rhythm-bar > div {
  display: flex;
  align-items: center;
  justify-content: center;
  min-width: 0;
  font-size: 0.65rem;
  font-weight: 700;
  color: white;
  text-shadow: 0 1px 1px rgb(0 0 0 / 0.35);
  white-space: nowrap;
  overflow: hidden;
}

/* ── 類似譜面 ── */
.similar-list { display: grid; }
.similar-row {
  display: grid;
  grid-template-columns: 4.5rem minmax(0, 1fr) auto auto;
  align-items: center;
  gap: 0.75rem;
  padding: 0.45rem 0;
  border-top: 1px solid rgb(241 245 249);
}
.similar-row:first-child { border-top: none; }
.dark .similar-row { border-top-color: rgb(51 65 85 / 0.6); }
.sim-meter { display: flex; align-items: center; gap: 0.4rem; }
.sim-num { width: 1.4rem; font-size: 0.7rem; font-weight: 700; color: rgb(100 116 139); text-align: right; }
.sim-track { flex: 1; height: 0.35rem; border-radius: 9999px; background: rgb(241 245 249); overflow: hidden; }
.dark .sim-track { background: rgb(51 65 85); }
.sim-fill { display: block; height: 100%; background: rgb(59 130 246); border-radius: 9999px; }
.debug-btn {
  font-size: 0.65rem;
  padding: 0.15rem 0.4rem;
  border-radius: 0.25rem;
  color: rgb(99 102 241);
  border: 1px solid rgb(199 210 254);
}
@media (max-width: 639px) {
  .similar-row { grid-template-columns: 3rem minmax(0, 1fr) auto; }
  .similar-row .debug-btn { display: none; }
  .sim-track { display: none; }
}
</style>
