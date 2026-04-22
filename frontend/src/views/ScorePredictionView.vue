<script setup lang="ts">
/**
 * ScorePredictionView.vue
 *
 * 【Viewの役割】
 * 選択した曲に対して、過去の類似曲スコアから「予測スコア」を算出して表示するページ。
 * 譜面の傾向プロファイル（実効BPM、皿率、同時押し率、配置パターン、小節ごとノーツ密度など）も
 * まとめて表示し、さらに類似曲との比較や、管理者向けの類似度デバッグ機能も持つ。
 *
 * 【主な機能】
 * - 曲選択（Lv11/12 の ANOTHER/LEGGENDARIA、textage 有りのみ）
 * - 予測APIの呼び出しと結果表示（自分 or 閲覧中ユーザー）
 * - 傾向プロファイル取得、タグ/配置パターン/ノーツ分布可視化
 * - 管理者モードでは類似度計算の内訳を取得可能
 */
import { ref, computed, watch } from 'vue';
import { useAuth, API_BASE } from '../composables/useAuth';
import { useI18n } from '../composables/useI18n';
import { useGameData, type SongDataEntry } from '../composables/useGameData';

// props: 他人のIDを指定された場合の閲覧モード（admin=管理者、friend=フレンド閲覧）
const props = defineProps<{
  viewingUserId?: number | null;
  viewingMode?: 'admin' | 'friend' | null;
}>();

const { t } = useI18n();
const { isLoggedIn, authHeaders } = useAuth();
const { songDataBody } = useGameData();

// adminモードで別ユーザーを閲覧中かどうか
const isAdminViewing = computed(() =>
  props.viewingMode === 'admin' && props.viewingUserId != null
);

// ── 曲選択 ──────────────────────────────────────────────────
const searchQuery = ref('');                          // 検索文字列（曲名/アーティスト）
const selectedEntry = ref<SongDataEntry | null>(null); // 現在選択中の曲

/**
 * 予測対象として使える曲の集合を computed で取得。
 * ANOTHER(difficulty=4) / LEGGENDARIA(10) で、かつレベル11-12、かつ textage（譜面コード）を持つものに限定。
 */
const targetEntries = computed((): SongDataEntry[] => {
  return songDataBody.value.filter(
    s => (s.difficulty === '4' || s.difficulty === '10') && (s.level === 11 || s.level === 12) && !!s.textage
  );
});

/**
 * 検索文字列を考慮してフィルタした曲リスト。
 * 空クエリなら先頭50件、クエリ有りなら部分一致で最大80件。
 */
const filteredEntries = computed((): SongDataEntry[] => {
  const q = searchQuery.value.trim().toLowerCase();
  if (!q) return targetEntries.value.slice(0, 50);
  return targetEntries.value
    .filter(s => s.title.toLowerCase().includes(q) || s.artist.toLowerCase().includes(q))
    .slice(0, 80);
});

/**
 * 曲を選択する。
 * 同じ曲の重複選択は（管理者閲覧モードでなければ）スキップし、それ以外なら予測APIを発火。
 */
function selectEntry(entry: SongDataEntry) {
  if (selectedEntry.value?.textage === entry.textage && !isAdminViewing.value) return;
  selectedEntry.value = entry;
  predictionResult.value = null;
  predictionError.value = '';
  fetchPrediction(entry.textage!);
}

// 閲覧ユーザーが変わったら、現在の曲で再度予測を取り直す（管理者がユーザー切替時など）
watch(() => props.viewingUserId, () => {
  predictionResult.value = null;
  predictionError.value = '';
  if (selectedEntry.value?.textage) {
    fetchPrediction(selectedEntry.value.textage);
  }
});

// difficulty コードから表示名 ("ANOTHER"/"LEGGENDARIA") に変換
function diffLabel(difficulty: string): string {
  return difficulty === '10' ? 'LEGGENDARIA' : 'ANOTHER';
}

// 難易度バッジの Tailwind クラス（LEGGENDARIA=紫、ANOTHER=赤）
function diffBadgeClass(difficulty: string): string {
  return difficulty === '10'
    ? 'bg-purple-100 text-purple-700 dark:bg-purple-900/40 dark:text-purple-300'
    : 'bg-red-100 text-red-700 dark:bg-red-900/40 dark:text-red-300';
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

// ── 類似度デバッグ（管理者モード専用） ───────────────────────
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

// 予測結果の型。予測スコア・現在スコア・類似曲の配列などを持つ
interface PredictionResult {
  textage: string;
  title: string;
  difficulty: string;
  level: number;
  notes: number;
  dominantEff16: number;       // 主要実効16分BPM
  predictedScore: number;      // 予測スコア
  predictedScoreRate: number;  // 予測達成率
  currentScore?: number;       // 現在のベストスコア（あれば）
  currentScoreRate?: number;
  similarSongs: SimilarSong[]; // 予測元になった類似曲一覧
  message?: string;
  error?: string;
}

const predictionResult = ref<PredictionResult | null>(null);
const predictionError = ref('');
const isLoading = ref(false);

/**
 * 予測APIを呼び出す。
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
/**
 * 達成率 (0〜100%) から DJ LEVEL（MAX/MAX-/AAA/AA/A/B/C/D/E/F）を算出。
 * 閾値は公式の理論値に合わせ、MAX=100、AAA=8/9(88.89%)、AA=7/9(77.78%)...。
 */
function djLevel(rate: number): string {
  if (rate >= 100) return 'MAX';
  if (rate >= 94.45) return 'MAX-';
  if (rate >= 88.89) return 'AAA';
  if (rate >= 77.78) return 'AA';
  if (rate >= 66.67) return 'A';
  if (rate >= 55.56) return 'B';
  if (rate >= 44.44) return 'C';
  if (rate >= 33.33) return 'D';
  if (rate >= 22.22) return 'E';
  return 'F';
}

// DJ LEVEL 用の色クラス（MAX-=紫、AAA=黄、AA=青、A=緑、B=暗灰、それ以下=灰）
function djLevelClass(rate: number): string {
  if (rate >= 94.45) return 'text-purple-600 dark:text-purple-400 font-extrabold';
  if (rate >= 88.89) return 'text-yellow-500 dark:text-yellow-400 font-extrabold';
  if (rate >= 77.78) return 'text-blue-500 dark:text-blue-400 font-bold';
  if (rate >= 66.67) return 'text-green-500 dark:text-green-400 font-bold';
  if (rate >= 55.56) return 'text-slate-700 dark:text-slate-300 font-semibold';
  return 'text-slate-500 dark:text-slate-400';
}

/**
 * 表示用スコアデータをまとめる computed。
 * 予測スコア・予測達成率に加え、現在スコア／達成率との差分を計算。
 * 差分は小数第2位で四捨五入（x10 → 整数化 → /10）
 */
const displayScore = computed(() => {
  const r = predictionResult.value;
  if (!r) return null;
  const diff = (r.currentScore != null)
    ? r.predictedScore - r.currentScore
    : null;
  const diffRate = (r.currentScoreRate != null)
    ? Math.round((r.predictedScoreRate - r.currentScoreRate) * 10) / 10
    : null;
  return { score: r.predictedScore, rate: r.predictedScoreRate, diff, diffRate };
});

// ── 譜面傾向プロファイルの型定義 ────────────────────────────
/**
 * 譜面1つ分の「傾向」を示すデータ。
 * BPM、皿率、同時押し率、配置パターン（縦連/階段/トリル/二重階段）、
 * 小節ごとのノーツ分布（全体/鍵盤/皿）、タグなどを保持する。
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
  tagsJson: string | null;
  intervalDistJson: string | null;
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

// ── 配置パターンバッジ（トリル/階段/二重階段/縦連打）─────────
interface PatternBadge {
  key: string;
  label: string;
  count: number;
  pct: number;
  colorClass: string;
}

// 各配置パターンの定義テーブル: ラベル・色・countKey/pctKey（プロファイル上のフィールド名）
const PATTERN_DEFS: { key: string; label: string; countKey: keyof TendencyProfile; pctKey: keyof TendencyProfile; color: string; colorBg: string }[] = [
  { key: 'trill',   label: 'トリル',   countKey: 'trillCount',   pctKey: 'trillPct',   color: 'text-sky-700 dark:text-sky-300',    colorBg: 'bg-sky-100 dark:bg-sky-900/40' },
  { key: 'stairs',  label: '階段',     countKey: 'stairsCount',  pctKey: 'stairsPct',  color: 'text-emerald-700 dark:text-emerald-300', colorBg: 'bg-emerald-100 dark:bg-emerald-900/40' },
  { key: 'dstairs', label: '二重階段', countKey: 'dstairsCount', pctKey: 'dstairsPct', color: 'text-violet-700 dark:text-violet-300', colorBg: 'bg-violet-100 dark:bg-violet-900/40' },
  { key: 'jack',    label: '縦連打',   countKey: 'jackCount',    pctKey: 'jackPct',    color: 'text-rose-700 dark:text-rose-300',   colorBg: 'bg-rose-100 dark:bg-rose-900/40' },
];

/**
 * プロファイルから配置パターンバッジ配列を構築する computed。
 * 手順1: プロファイル未取得なら空配列
 * 手順2: PATTERN_DEFS をループしつつ、countKey の値が null/0 ではないものだけ採用
 * 手順3: ラベル・カウント・割合・色クラスをセットしたバッジ配列を返す
 */
const patternBadges = computed((): PatternBadge[] => {
  const p = tendencyProfile.value;
  if (!p) return [];
  return PATTERN_DEFS
    .filter(d => (p[d.countKey] as number | null) != null && (p[d.countKey] as number) > 0)
    .map(d => ({
      key: d.key,
      label: d.label,
      count: p[d.countKey] as number,
      pct: p[d.pctKey] as number,
      colorClass: `${d.colorBg} ${d.color}`,
    }));
});

// ── タグバッジ（譜面属性: 皿多い/同時押し寄り/32分あり/高速 など） ─────
interface TagBadge { tag: string; label: string; colorClass: string; }

// サーバから返ってくるタグ文字列 → 表示ラベル + 色 の対応表
const TAG_DISPLAY: Record<string, { label: string; colorClass: string }> = {
  scratch_very_heavy: { label: '皿: 非常に多い', colorClass: 'bg-orange-100 text-orange-700 dark:bg-orange-900/40 dark:text-orange-300' },
  scratch_heavy:      { label: '皿: 多い',       colorClass: 'bg-orange-100 text-orange-700 dark:bg-orange-900/40 dark:text-orange-300' },
  scratch_low:        { label: '皿: 少ない',     colorClass: 'bg-slate-100 text-slate-600 dark:bg-slate-700/60 dark:text-slate-300' },
  chord_heavy:        { label: '同時押し寄り',   colorClass: 'bg-blue-100 text-blue-700 dark:bg-blue-900/40 dark:text-blue-300' },
  single_heavy:       { label: '単鍵寄り',       colorClass: 'bg-teal-100 text-teal-700 dark:bg-teal-900/40 dark:text-teal-300' },
  has_32nd:           { label: '32分あり',        colorClass: 'bg-red-100 text-red-700 dark:bg-red-900/40 dark:text-red-300' },
  has_triplet:        { label: '3連あり',         colorClass: 'bg-amber-100 text-amber-700 dark:bg-amber-900/40 dark:text-amber-300' },
  soflan:             { label: 'ソフラン',        colorClass: 'bg-amber-100 text-amber-700 dark:bg-amber-900/40 dark:text-amber-300' },
  high_effective_bpm: { label: '高速',            colorClass: 'bg-red-100 text-red-700 dark:bg-red-900/40 dark:text-red-300' },
  mid_effective_bpm:  { label: '中速',            colorClass: 'bg-yellow-100 text-yellow-700 dark:bg-yellow-900/40 dark:text-yellow-300' },
  low_effective_bpm:  { label: '低速',            colorClass: 'bg-cyan-100 text-cyan-700 dark:bg-cyan-900/40 dark:text-cyan-300' },
};

/**
 * tagsJson（配列が JSON 文字列）から表示用のバッジ配列を算出。
 * has_jack/trill/stairs/dstairs 系は patternBadges で別扱いするので除外。
 */
const tagBadges = computed((): TagBadge[] => {
  const p = tendencyProfile.value;
  if (!p?.tagsJson) return [];
  try {
    const tags = JSON.parse(p.tagsJson) as string[];
    return tags
      // 配置パターン系のタグは patternBadges で表示しているためここでは除外
      .filter(t => TAG_DISPLAY[t] && !t.startsWith('has_jack') && !t.startsWith('jack_')
                 && !t.startsWith('has_trill') && !t.startsWith('trill_')
                 && !t.startsWith('has_stairs') && !t.startsWith('stairs_')
                 && !t.startsWith('has_dstairs') && !t.startsWith('dstairs_'))
      .map(t => ({ tag: t, ...TAG_DISPLAY[t] }));
  } catch { return []; }
});

// ── 小節ごとノーツ数の折れ線／棒グラフ用データ ──────────────
// 小節単位の全ノーツ数配列（JSON 文字列 → number[] にパース）
const measureNotes = computed((): number[] => {
  if (!tendencyProfile.value?.measureNotesJson) return [];
  try { return JSON.parse(tendencyProfile.value.measureNotesJson); }
  catch { return []; }
});

// 小節単位の鍵盤ノーツ数（皿と分けて可視化するため）
const measureNotesKbd = computed((): number[] => {
  if (!tendencyProfile.value?.measureNotesKbdJson) return [];
  try { return JSON.parse(tendencyProfile.value.measureNotesKbdJson); }
  catch { return []; }
});

// 小節単位の皿ノーツ数
const measureNotesScr = computed((): number[] => {
  if (!tendencyProfile.value?.measureNotesScrJson) return [];
  try { return JSON.parse(tendencyProfile.value.measureNotesScrJson); }
  catch { return []; }
});

// 鍵盤/皿の分離データが存在するかどうか（存在するなら積み上げ表示）
const hasSplitMeasure = computed(() => measureNotesKbd.value.length > 0);

// グラフの高さスケール用: 最大ノーツ数（1未満にならないように）
const measureMax = computed(() => Math.max(...measureNotes.value, 1));

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

// ノーツ分布1エントリ分: 音符名・割合(%)・実数
interface NoteDistEntry {
  label: string;
  pct: number;
  count: number;
}

// 分布バーの色候補（項目順にラウンドロビンで割り当て）
const NOTE_COLORS = [
  'bg-blue-500',
  'bg-emerald-500',
  'bg-amber-500',
  'bg-rose-500',
  'bg-violet-500',
  'bg-cyan-500',
  'bg-orange-500',
  'bg-teal-500',
  'bg-pink-500',
  'bg-indigo-500',
  'bg-lime-500',
];

/**
 * ノーツ分布配列の computed。
 * 手順1: intervalDistJson を tick(文字列キー) → 詳細 の Map としてパース
 * 手順2: TICK_TO_NOTE で既知の tick のみラベル付きで entries に追加
 * 手順3: TICK_TO_NOTE の表記順（全音符→64分）にソート
 * 手順4: 合計が100未満なら「その他」で埋める
 */
const noteDistribution = computed((): NoteDistEntry[] => {
  if (!tendencyProfile.value?.intervalDistJson) return [];
  try {
    const dist = JSON.parse(tendencyProfile.value.intervalDistJson) as Record<string, { name: string; count: number; pct: number; eff16: number }>;
    const tickMap = new Map(TICK_TO_NOTE.map(t => [String(t.tick), t.label]));

    const entries: NoteDistEntry[] = [];
    let knownPct = 0;
    for (const [tick, data] of Object.entries(dist)) {
      const label = tickMap.get(tick);
      if (label && data.pct > 0) {
        entries.push({ label, pct: data.pct, count: data.count });
        knownPct += data.pct;
      }
    }
    // TICK_TO_NOTE の順に並べる（全音符→64分）
    const order = new Map(TICK_TO_NOTE.map((t, i) => [t.label, i]));
    entries.sort((a, b) => (order.get(a.label) ?? 99) - (order.get(b.label) ?? 99));
    // 未知の tick による残りを「その他」として追加
    const otherPct = Math.round((100 - knownPct) * 10) / 10;
    if (otherPct > 0) {
      entries.push({ label: 'その他', pct: otherPct, count: 0 });
    }
    return entries;
  } catch {
    return [];
  }
});

// 現在の曲の傾向プロファイル（選択変更時にAPIから取得して格納）
const tendencyProfile = ref<TendencyProfile | null>(null);

// 選択曲が変更されたら傾向プロファイルをAPIから取得する watch
watch(selectedEntry, async (entry) => {
  tendencyProfile.value = null;
  if (!entry?.textage) return;
  try {
    const res = await fetch(
      `${API_BASE}/api/analysis/tendency-profile?textage=${encodeURIComponent(entry.textage)}`,
      { headers: authHeaders() }
    );
    if (res.ok) {
      tendencyProfile.value = await res.json();
    }
  } catch {
    // 取得失敗はフェイルセーフで無視（予測自体は独立に動作）
  }
});

</script>

<template>
  <div class="px-4 py-6">
    <!-- ページ見出しとサブタイトル -->
    <h2 class="text-2xl font-bold text-slate-800 dark:text-white mb-1">
      {{ t('nav.scorePrediction') }}
    </h2>
    <p class="text-sm text-slate-500 dark:text-slate-400 mb-4">
      {{ t('scorePrediction.subtitle') }}
    </p>

    <!-- 管理者が他ユーザーを閲覧中の注意バナー -->
    <div v-if="isAdminViewing"
      class="mb-5 flex items-center gap-2 rounded-lg bg-indigo-50 dark:bg-indigo-900/30 border border-indigo-200 dark:border-indigo-700 px-4 py-2.5 text-sm text-indigo-700 dark:text-indigo-300 font-medium"
    >
      <svg xmlns="http://www.w3.org/2000/svg" class="h-4 w-4 shrink-0" fill="none" viewBox="0 0 24 24" stroke="currentColor" stroke-width="2">
        <path stroke-linecap="round" stroke-linejoin="round" d="M5.121 17.804A13.937 13.937 0 0112 16c2.5 0 4.847.655 6.879 1.804M15 10a3 3 0 11-6 0 3 3 0 016 0zM19 10a7 7 0 11-14 0 7 7 0 0114 0z" />
      </svg>
      閲覧中ユーザーのスコアを使用して予測しています
    </div>

    <!-- 未ログイン時: この機能はスコア参照が必要なのでログイン促しのみ表示 -->
    <div v-if="!isLoggedIn"
      class="rounded-xl border border-amber-300 bg-amber-50 dark:bg-amber-900/20 dark:border-amber-700 p-6 text-center">
      <p class="text-amber-700 dark:text-amber-300 font-medium">{{ t('scorePrediction.loginRequired') }}</p>
    </div>

    <!-- メインレイアウト: 左カラム=曲選択 / 右カラム=予測結果 -->
    <div v-else class="flex flex-col lg:flex-row gap-6">

      <!-- 左カラム: 検索ボックス + 曲リスト -->
      <div class="w-full lg:w-80 shrink-0 flex flex-col gap-3">
        <input
          v-model="searchQuery"
          type="text"
          :placeholder="t('scorePrediction.searchPlaceholder')"
          class="w-full px-4 py-2.5 rounded-lg border border-slate-300 dark:border-slate-600
                 bg-white dark:bg-slate-800 text-slate-800 dark:text-white
                 focus:outline-none focus:ring-2 focus:ring-blue-500 text-sm"
        />
        <div class="text-xs text-slate-400 dark:text-slate-500">
          {{ filteredEntries.length }} {{ t('scorePrediction.songCount') }}
        </div>

        <div class="flex flex-col gap-1 max-h-[70vh] overflow-y-auto pr-1">
          <button
            v-for="entry in filteredEntries"
            :key="entry.textage"
            @click="selectEntry(entry)"
            class="w-full text-left px-3 py-2.5 rounded-lg border transition-colors text-sm"
            :class="selectedEntry?.textage === entry.textage
              ? 'border-blue-500 bg-blue-50 dark:bg-blue-900/30 text-blue-700 dark:text-blue-300'
              : 'border-slate-200 dark:border-slate-700 bg-white dark:bg-slate-800 hover:bg-slate-50 dark:hover:bg-slate-700 text-slate-700 dark:text-slate-300'"
          >
            <div class="flex items-start gap-2">
              <span
                class="shrink-0 mt-0.5 px-1.5 py-0.5 rounded text-xs font-bold"
                :class="diffBadgeClass(entry.difficulty)"
              >{{ diffLabel(entry.difficulty) }}</span>
              <div class="min-w-0">
                <div class="font-medium truncate">{{ entry.title }}</div>
                <div class="text-xs text-slate-400 dark:text-slate-500">Lv.{{ entry.level }}</div>
              </div>
            </div>
          </button>
        </div>
      </div>

      <!-- 右カラム: 結果パネル（未選択/ローディング/エラー/結果の4状態） -->
      <div class="flex-1 min-w-0">

        <!-- 未選択: ユーザーに曲選択を促すプレースホルダー -->
        <div v-if="!selectedEntry"
          class="h-48 flex items-center justify-center text-slate-400 dark:text-slate-500 text-sm border-2 border-dashed border-slate-200 dark:border-slate-700 rounded-xl">
          {{ t('scorePrediction.selectSong') }}
        </div>

        <!-- ローディング: 予測APIレスポンス待ちのスピナー -->
        <div v-else-if="isLoading"
          class="h-48 flex items-center justify-center text-slate-400 dark:text-slate-500">
          <svg class="animate-spin h-6 w-6 mr-2" fill="none" viewBox="0 0 24 24">
            <circle class="opacity-25" cx="12" cy="12" r="10" stroke="currentColor" stroke-width="4"/>
            <path class="opacity-75" fill="currentColor" d="M4 12a8 8 0 018-8v8z"/>
          </svg>
          {{ t('scorePrediction.calculating') }}
        </div>

        <!-- エラー: サーバエラー or 通信エラー表示 -->
        <div v-else-if="predictionError"
          class="rounded-xl border border-red-300 bg-red-50 dark:bg-red-900/20 dark:border-red-700 p-6">
          <p class="text-red-600 dark:text-red-400 text-sm">{{ predictionError }}</p>
        </div>

        <!-- 結果: 予測成功時に曲ヘッダ/傾向/予測スコア/類似曲を縦に表示 -->
        <div v-else-if="predictionResult" class="flex flex-col gap-4">

          <!-- 曲ヘッダ: 難易度バッジ + タイトル + Lv/ノーツ数/BPM/ソフラン表示 -->
          <div class="rounded-xl border border-slate-200 dark:border-slate-700 bg-white dark:bg-slate-800 p-5">
            <div class="flex flex-wrap items-start gap-3 mb-3">
              <span
                class="px-2 py-1 rounded text-xs font-bold shrink-0"
                :class="diffBadgeClass(predictionResult.difficulty)"
              >{{ diffLabel(predictionResult.difficulty) }}</span>
              <div>
                <div class="text-lg font-bold text-slate-800 dark:text-white">{{ predictionResult.title }}</div>
                <div class="text-sm text-slate-500 dark:text-slate-400">
                  Lv.{{ predictionResult.level }}
                  &nbsp;·&nbsp;{{ predictionResult.notes }} notes
                  <template v-if="tendencyProfile">
                    &nbsp;·&nbsp;{{ tendencyProfile.bpmRaw }} BPM
                    <span v-if="tendencyProfile.isSoflan" class="ml-1 text-amber-500 font-semibold">(ソフラン)</span>
                  </template>
                </div>
              </div>
            </div>

            <!-- 3指標サマリ: 実効BPM / SCR(皿)率 / 同時押し率 -->
            <div v-if="tendencyProfile" class="grid grid-cols-3 gap-3 text-center">
              <div class="rounded-lg bg-slate-50 dark:bg-slate-700/50 p-2">
                <div class="text-xs text-slate-400 dark:text-slate-500">実効BPM</div>
                <div class="text-base font-bold text-slate-700 dark:text-slate-200">{{ tendencyProfile.dominantEff16.toFixed(0) }}</div>
              </div>
              <div class="rounded-lg bg-orange-50 dark:bg-orange-900/20 p-2">
                <div class="text-xs text-orange-500 dark:text-orange-400">SCR率</div>
                <div class="text-base font-bold text-orange-600 dark:text-orange-300">{{ tendencyProfile.scratchPct.toFixed(1) }}%</div>
              </div>
              <div class="rounded-lg bg-blue-50 dark:bg-blue-900/20 p-2">
                <div class="text-xs text-blue-500 dark:text-blue-400">同時押し率</div>
                <div class="text-base font-bold text-blue-600 dark:text-blue-300">{{ tendencyProfile.chordPct.toFixed(1) }}%</div>
              </div>
            </div>

            <!-- 譜面属性バッジ: タグ(皿多い/高速 等) + 配置パターン(トリル/階段/縦連/二重階段) -->
            <div v-if="tagBadges.length || patternBadges.length" class="mt-3">
              <div class="text-xs font-medium text-slate-400 dark:text-slate-500 mb-2">譜面属性</div>
              <div class="flex flex-wrap gap-1.5">
                <span v-for="tb in tagBadges" :key="tb.tag"
                  class="inline-flex items-center px-2 py-0.5 rounded-full text-xs font-bold"
                  :class="tb.colorClass"
                >{{ tb.label }}</span>
                <span v-for="pb in patternBadges" :key="pb.key"
                  class="inline-flex items-center gap-1 px-2 py-0.5 rounded-full text-xs font-bold"
                  :class="pb.colorClass"
                  :title="`${pb.count}回 / ${pb.pct}%`"
                >
                  {{ pb.label }}
                  <span class="opacity-70 font-normal">{{ pb.pct }}%</span>
                </span>
              </div>
            </div>

            <!-- 小節ごとノーツ密度グラフ: 小節=列、各列に鍵盤ノーツ(灰)/皿ノーツ(赤)を積み上げ -->
            <div v-if="measureNotes.length" class="mt-3">
              <div class="text-xs font-medium text-slate-400 dark:text-slate-500 mb-2">ノーツ密度（小節）</div>
              <div class="flex items-end gap-px bg-slate-50 dark:bg-slate-700/30 rounded-lg p-1"
                   :style="{ height: `${measureMax * 4 + 10}px` }">
                <div v-for="(n, i) in measureNotes" :key="i"
                  class="flex-1 min-w-0 flex flex-col-reverse gap-px"
                  :title="`小節${i + 1}: ${n}ノーツ${hasSplitMeasure ? ` (鍵盤${measureNotesKbd[i] ?? 0} / 皿${measureNotesScr[i] ?? 0})` : ''}`"
                >
                  <!-- 皿ノーツ(赤)が上に、鍵盤ノーツ(灰)が下に積み上がる -->
                  <template v-if="hasSplitMeasure">
                    <div v-for="b in (measureNotesScr[i] ?? 0)" :key="'s' + b"
                      class="w-full rounded-[1px] bg-rose-500"
                      style="height: 3px"
                    />
                    <div v-for="b in (measureNotesKbd[i] ?? 0)" :key="'k' + b"
                      class="w-full rounded-[1px] bg-slate-200 dark:bg-slate-300"
                      style="height: 3px"
                    />
                  </template>
                  <!-- フォールバック: 分割データが無い時は密度に応じて色を変える単一バー -->
                  <template v-else>
                    <div v-for="b in n" :key="b"
                      class="w-full rounded-[1px]"
                      :class="n > measureMax * 0.8 ? 'bg-rose-400 dark:bg-rose-500' : n > measureMax * 0.5 ? 'bg-amber-400 dark:bg-amber-500' : 'bg-blue-400 dark:bg-blue-500'"
                      style="height: 3px"
                    />
                  </template>
                </div>
              </div>
              <div class="flex justify-between text-[10px] text-slate-400 dark:text-slate-500 mt-1 px-1">
                <span>1</span>
                <span>{{ measureNotes.length }}</span>
              </div>
            </div>

            <!-- 音符割合分布: 全音符〜64分までの割合をスタックバー + 凡例で表示 -->
            <div v-if="noteDistribution.length" class="mt-3">
              <div class="text-xs font-medium text-slate-400 dark:text-slate-500 mb-2">音符割合</div>
              <!-- スタックバー本体: 各音符の割合で幅を決める -->
              <div class="w-full h-6 rounded-lg overflow-hidden flex">
                <div v-for="(nd, i) in noteDistribution" :key="nd.label"
                  class="h-full flex items-center justify-center text-[10px] font-bold text-white transition-all"
                  :class="NOTE_COLORS[i % NOTE_COLORS.length]"
                  :style="{ width: `${nd.pct}%` }"
                  :title="`${nd.label}: ${nd.pct}%`"
                >
                  <span v-if="nd.pct >= 5">{{ nd.pct }}%</span>
                </div>
              </div>
              <!-- 色と音符名の凡例 -->
              <div class="flex flex-wrap gap-x-3 gap-y-1 mt-2">
                <div v-for="(nd, i) in noteDistribution" :key="nd.label"
                  class="flex items-center gap-1 text-[11px]">
                  <span class="inline-block w-2.5 h-2.5 rounded-sm shrink-0"
                    :class="NOTE_COLORS[i % NOTE_COLORS.length]"></span>
                  <span class="font-bold text-slate-600 dark:text-slate-300">{{ nd.label }}</span>
                  <span class="tabular-nums text-slate-400 dark:text-slate-500">{{ nd.pct }}%</span>
                </div>
              </div>
            </div>
          </div>

          <!-- 予測スコアパネル: 大きなスコア表示 + DJ LEVEL + 現在との差分 + 達成率プログレスバー -->
          <div class="rounded-xl border border-slate-200 dark:border-slate-700 bg-white dark:bg-slate-800 p-5">
            <h3 class="text-sm font-semibold text-slate-500 dark:text-slate-400 uppercase tracking-wide mb-4">
              {{ t('scorePrediction.prediction') }}
            </h3>

            <div v-if="predictionResult.message && predictionResult.predictedScore === 0"
              class="text-sm text-slate-500 dark:text-slate-400 py-4 text-center">
              {{ predictionResult.message }}
            </div>

            <div v-else-if="displayScore" class="flex flex-col sm:flex-row items-center gap-6">
              <!-- 予測スコア大表示 + MAX表記 + DJ LEVEL + 現在スコアとの差分 -->
              <div class="text-center shrink-0">
                <div class="text-4xl font-extrabold text-slate-800 dark:text-white tabular-nums">
                  {{ displayScore.score.toLocaleString() }}
                </div>
                <div class="text-sm text-slate-400 dark:text-slate-500 mt-0.5">
                  / {{ (predictionResult.notes * 2).toLocaleString() }}
                </div>
                <div class="mt-2 text-2xl font-bold" :class="djLevelClass(displayScore.rate)">
                  {{ djLevel(displayScore.rate) }}
                </div>
                <!-- 現在の自分のベストとの差分（プラスは緑、マイナスは赤） -->
                <div v-if="displayScore.diff != null" class="mt-2 text-xs tabular-nums"
                  :class="displayScore.diff > 0
                    ? 'text-emerald-600 dark:text-emerald-400'
                    : displayScore.diff < 0
                    ? 'text-red-500 dark:text-red-400'
                    : 'text-slate-400 dark:text-slate-500'"
                >
                  <span class="font-bold">{{ displayScore.diff > 0 ? '+' : '' }}{{ displayScore.diff.toLocaleString() }}</span>
                  <span class="ml-1">({{ displayScore.diffRate! > 0 ? '+' : '' }}{{ displayScore.diffRate }}%)</span>
                  <div class="text-slate-400 dark:text-slate-500 font-normal mt-0.5">vs 現在スコア</div>
                </div>
              </div>

              <!-- 達成率プログレスバー: スコアレート(%)を塗り、A/AA/AAA の目安目盛をその下に表示 -->
              <div class="flex-1 w-full">
                <div class="flex justify-between text-xs text-slate-500 dark:text-slate-400 mb-1">
                  <span>{{ t('scorePrediction.scoreRate') }}</span>
                  <span>{{ displayScore.rate }}%</span>
                </div>
                <div class="w-full h-3 bg-slate-100 dark:bg-slate-700 rounded-full overflow-hidden">
                  <div
                    class="h-full rounded-full transition-all duration-700"
                    :class="displayScore.rate >= 88.89
                      ? 'bg-yellow-400'
                      : displayScore.rate >= 77.78
                      ? 'bg-blue-400'
                      : displayScore.rate >= 66.67
                      ? 'bg-green-400'
                      : 'bg-slate-400'"
                    :style="{ width: `${Math.min(displayScore.rate, 100)}%` }"
                  />
                </div>
                <!-- A/AA/AAA の目安位置ラベル（プログレスバーの下にマーカー配置） -->
                <div class="relative h-4 mt-1">
                  <div v-for="(mark, idx) in [
                    { label: 'A', pct: 66.67, cls: 'text-green-500' },
                    { label: 'AA', pct: 77.78, cls: 'text-blue-500' },
                    { label: 'AAA', pct: 88.89, cls: 'text-yellow-500' },
                  ]" :key="idx"
                    class="absolute text-xs font-bold -translate-x-1/2"
                    :class="mark.cls"
                    :style="{ left: `${mark.pct}%` }"
                  >{{ mark.label }}</div>
                </div>
              </div>
            </div>
          </div>

          <!-- 類似譜面テーブル: 予測の根拠になった類似曲一覧。行クリックで類似度デバッグモーダル -->
          <div v-if="predictionResult.similarSongs.length"
            class="rounded-xl border border-slate-200 dark:border-slate-700 bg-white dark:bg-slate-800 p-5">
            <h3 class="text-sm font-semibold text-slate-500 dark:text-slate-400 uppercase tracking-wide mb-3">
              {{ t('scorePrediction.similarSongs') }}
              <span class="ml-1 font-normal">({{ predictionResult.similarSongs.length }}件)</span>
            </h3>
            <div class="overflow-x-auto">
              <table class="w-full text-sm">
                <thead>
                  <tr class="text-xs text-slate-400 dark:text-slate-500 border-b border-slate-100 dark:border-slate-700">
                    <th class="text-left pb-2 pr-3 font-medium">曲名</th>
                    <th class="text-left pb-2 pr-3 font-medium whitespace-nowrap">難易度</th>
                    <th class="text-right pb-2 pr-3 font-medium whitespace-nowrap">自分のスコア</th>
                    <th class="text-right pb-2 pr-3 font-medium whitespace-nowrap">スコア率</th>
                    <th class="text-right pb-2 font-medium whitespace-nowrap">類似度</th>
                  </tr>
                </thead>
                <tbody>
                  <tr
                    v-for="song in predictionResult.similarSongs"
                    :key="`${song.title}_${song.difficultyName}`"
                    class="border-b border-slate-50 dark:border-slate-700/50 last:border-0 cursor-pointer hover:bg-slate-50 dark:hover:bg-slate-700/40 transition-colors"
                    @click="fetchSimilarityDebug(song.textage)"
                  >
                    <td class="py-2 pr-3 text-slate-700 dark:text-slate-300 max-w-[180px] truncate">{{ song.title }}</td>
                    <td class="py-2 pr-3">
                      <span class="px-1.5 py-0.5 rounded text-xs font-bold"
                        :class="song.difficultyName === 'LEGGENDARIA'
                          ? 'bg-purple-100 text-purple-700 dark:bg-purple-900/40 dark:text-purple-300'
                          : 'bg-red-100 text-red-700 dark:bg-red-900/40 dark:text-red-300'">
                        {{ song.difficultyName === 'LEGGENDARIA' ? 'LEGG' : 'ANO' }}
                      </span>
                    </td>
                    <td class="py-2 pr-3 text-right tabular-nums" :class="song.played ? 'text-slate-700 dark:text-slate-300' : 'text-slate-400 dark:text-slate-500'">
                      <template v-if="song.played">{{ song.score!.toLocaleString() }}</template>
                      <template v-else>-</template>
                    </td>
                    <td class="py-2 pr-3 text-right tabular-nums" :class="song.played ? djLevelClass(song.scoreRate!) : 'text-slate-400 dark:text-slate-500'">
                      <template v-if="song.played">{{ song.scoreRate }}%</template>
                      <template v-else>未プレイ</template>
                    </td>
                    <td class="py-2 text-right tabular-nums text-slate-500 dark:text-slate-400">
                      {{ (song.similarity * 100).toFixed(2) }}%
                    </td>
                  </tr>
                </tbody>
              </table>
            </div>
          </div>

          <!-- 類似度デバッグモーダル（管理者機能）: 計算過程を4グループに分けて表示 -->
          <Teleport to="body">
            <div v-if="debugResult || isDebugLoading"
              class="fixed inset-0 z-50 flex items-center justify-center bg-black/50 p-4"
              @click.self="debugResult = null">
              <div class="bg-white dark:bg-slate-800 rounded-2xl shadow-xl w-full max-w-2xl max-h-[90vh] overflow-y-auto p-6">
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
                    <div class="flex-1 bg-slate-50 dark:bg-slate-900 rounded-xl p-3">
                      <div class="text-xs text-slate-400 mb-1">対象曲 (A)</div>
                      <div class="font-bold text-slate-800 dark:text-slate-100">{{ debugResult.songA?.title }}</div>
                      <div class="text-xs text-slate-500">難易度 {{ debugResult.songA?.informalRank }}</div>
                    </div>
                    <div class="flex-1 bg-slate-50 dark:bg-slate-900 rounded-xl p-3">
                      <div class="text-xs text-slate-400 mb-1">参照曲 (B)</div>
                      <div class="font-bold text-slate-800 dark:text-slate-100">{{ debugResult.songB?.title }}</div>
                      <div class="text-xs text-slate-500">難易度 {{ debugResult.songB?.informalRank }}</div>
                    </div>
                  </div>
                  <!-- 生データ比較テーブル: ノーツ密度・BPM・スクラッチ割合などを並べて表示 -->
                  <div class="mb-4">
                    <div class="text-xs font-bold text-slate-500 uppercase tracking-wide mb-2">生データ比較</div>
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
                    ] as any[])" :key="key" class="rounded-xl border border-slate-100 dark:border-slate-700 p-3">
                      <div class="flex justify-between items-center mb-2">
                        <span class="text-xs font-bold text-slate-600 dark:text-slate-300">{{ label }}</span>
                        <span class="text-sm font-black tabular-nums"
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
                  <div class="mt-4 rounded-xl bg-slate-900 dark:bg-slate-950 p-4 text-center">
                    <div class="text-xs text-slate-400 mb-1">最終類似度</div>
                    <div class="text-3xl font-black text-white">{{ debugResult.result?.finalSimilarityPct }}</div>
                    <div class="text-xs text-slate-500 mt-1">
                      統合値 (G1×G2^scrW×G3^kbdW×G4^cnW): {{ debugResult.result?.combined }}
                    </div>
                  </div>
                </div>
              </div>
            </div>
          </Teleport>

        </div><!-- /predictionResult -->

      </div><!-- /右パネル -->
    </div><!-- /flex -->

    <!-- 謝辞: 譜面データ提供元 TexTage へのクレジット -->
    <div class="mt-8 pt-4 border-t border-slate-200 dark:border-slate-700 text-center text-xs text-slate-400 dark:text-slate-500">
      譜面データは
      <a href="https://textage.cc/" target="_blank" rel="noopener noreferrer"
         class="text-blue-500 hover:text-blue-400 underline">TexTage</a>
      を利用しています。
    </div>
  </div>
</template>
