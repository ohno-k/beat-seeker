<script setup lang="ts">
/**
 * 【コンポーネントの役割】 ダッシュボードの「最近の更新」ウィジェット。
 * 最後に CSV を取り込んだ日（JST）に更新された譜面を一覧する。同じ日に複数回取り込んでいれば 1 つにまとめ、
 * スコアとランプは「その日の最初の取り込み前 → 最後の取り込み後」、譜面ごとの BEAT-PT 増分は合算で出す。
 *
 * データは成長記録と同じ GET /api/scores/history（管理者が他人を見るときは /api/admin/users/{id}/history）。
 * 難易度改訂などの再計算行（updatedCount=0 で diffJson が空）は「取り込み」とみなさない。
 * その日の BEAT-PT 増分は成長記録の日別まとめと同じく「その日の最後の合計 − 前日最終の合計」。
 *
 * @prop viewingUserId 管理者が他ユーザーを閲覧中ならその ID。null/undefined なら自分。
 * @prop scores 表示対象のフラットなスコア。☆レベルの参照にだけ使う（履歴の diffJson にレベルは入っていない）。
 *              アップロード後に入れ替わるので、その変化を合図に履歴も取り直す。
 * @emits open-history 成長記録ページを開く。
 */
import { ref, computed, watch } from 'vue';
import { useI18n } from '../composables/useI18n';
import { useAuth, API_BASE } from '../composables/useAuth';
import type { ScoreRecord } from '../utils/scoreData';
import type { UpdatedSong } from '../types/UploadDiff';
import { clearTypeShort } from '../utils/uploadReport';
import { toJstDate, toJstDateKey, formatJstDate } from '../utils/jstTime';

const props = defineProps<{
  viewingUserId?: number | null;
  scores?: ScoreRecord[];
}>();
const emit = defineEmits<{ (e: 'open-history'): void }>();

const { t, currentLang } = useI18n();
const { authHeaders, isLoggedIn } = useAuth();

/** 履歴 1 行のうちここで使う項目（成長記録と同じ API 応答）。 */
interface HistoryRow {
  date: string;
  totalBeatPt: number;
  beatPtIncrease: number;
  updatedCount: number;
  /** 更新譜面の配列（UpdatedSong[]）の JSON 文字列。古い行や空の更新では null / "[]"。 */
  diffJson?: string | null;
}

/** 同じ日の複数回の取り込みを 1 譜面にまとめたもの。 */
type MergedSong = UpdatedSong & { key: string; level: number | null };

/** 最初から並べる譜面数。これを超えた分は「残り n 譜面を表示」で開く。 */
const PREVIEW = 8;

const isLoading = ref(true);
/** 履歴（新しい順）。 */
const rows = ref<HistoryRow[]>([]);
const expanded = ref(false);

let loadSeq = 0;
/** 【関数の役割】 履歴を取り直す。古い応答が後から届いても捨てる（連番で判定）。 */
const load = async () => {
  const seq = ++loadSeq;
  isLoading.value = true;
  try {
    if (!isLoggedIn.value) { rows.value = []; return; }
    const endpoint = props.viewingUserId
      ? `${API_BASE}/api/admin/users/${props.viewingUserId}/history`
      : `${API_BASE}/api/scores/history`;
    const res = await fetch(endpoint, { headers: authHeaders() });
    if (seq !== loadSeq) return;
    const data = res.ok ? await res.json() : [];
    rows.value = (Array.isArray(data) ? (data as HistoryRow[]) : [])
      .sort((a, b) => (toJstDate(b.date)?.getTime() ?? 0) - (toJstDate(a.date)?.getTime() ?? 0));
    expanded.value = false;
  } catch {
    if (seq === loadSeq) rows.value = [];
  } finally {
    if (seq === loadSeq) isLoading.value = false;
  }
};
watch([() => props.viewingUserId, () => props.scores], load, { immediate: true });

/** diffJson を配列にする。壊れていたり空なら []。 */
function parseSongs(json: string | null | undefined): UpdatedSong[] {
  if (!json || json === '[]') return [];
  try {
    const v = JSON.parse(json);
    return Array.isArray(v) ? v : [];
  } catch {
    return [];
  }
}

/** 取り込みの行か（難易度改訂などの再計算行を除く）。 */
const isPlayRow = (r: HistoryRow) => (r.updatedCount ?? 0) > 0 || parseSongs(r.diffJson).length > 0;

/** 【computed の役割】 「曲名_難易度」→ ☆レベル。props.scores から引く。 */
const levelByChart = computed(() => {
  const m = new Map<string, number | null>();
  for (const s of props.scores ?? []) m.set(`${s.title}_${s.difficultyName}`, s.difficultyLevel ?? null);
  return m;
});

/**
 * 【computed の役割】 最後に取り込んだ日のまとめ。取り込みが 1 件も無ければ null。
 *  - date: その日の最新の取り込み日時
 *  - uploads: その日の取り込み回数
 *  - songs: 更新譜面（BEAT-PT 増分の大きい順 → スコア増分の大きい順）
 *  - beatPtIncrease: その日の BEAT-PT 増分
 */
const latestDay = computed(() => {
  const first = rows.value.find(isPlayRow);
  if (!first) return null;
  const dayKey = toJstDateKey(first.date);
  const dayAllRows = rows.value.filter(r => toJstDateKey(r.date) === dayKey);   // 新しい順（再計算行も含む）
  const dayPlayRows = dayAllRows.filter(isPlayRow);

  // 古い順に重ねる: 最初に出てきた行の old を、最後に出てきた行の new を採用し、増分は合算
  const merged = new Map<string, MergedSong>();
  for (const r of [...dayPlayRows].reverse()) {
    for (const s of parseSongs(r.diffJson)) {
      const key = `${s.title}_${s.difficulty}`;
      const cur = merged.get(key);
      if (!cur) {
        merged.set(key, { ...s, key, level: levelByChart.value.get(key) ?? null, beatPtIncrease: s.beatPtIncrease ?? 0 });
        continue;
      }
      merged.set(key, {
        ...cur,
        newScore: s.newScore,
        scoreIncrease: s.newScore - cur.oldScore,
        newClearType: s.newClearType,
        // 日の最初と最後で結局同じランプなら「改善」とは言わない
        clearTypeImproved: (cur.clearTypeImproved || s.clearTypeImproved) && cur.oldClearType !== s.newClearType,
        newBeatPt: s.newBeatPt,
        beatPtIncrease: (cur.beatPtIncrease ?? 0) + (s.beatPtIncrease ?? 0),
        scoreRate: s.scoreRate ?? cur.scoreRate,
        maxScore: s.maxScore || cur.maxScore,
        informalRank: s.informalRank ?? cur.informalRank,
        allTimeBestUpdated: cur.allTimeBestUpdated || s.allTimeBestUpdated,
      });
    }
  }
  const songs = Array.from(merged.values())
    .sort((a, b) => (b.beatPtIncrease - a.beatPtIncrease) || (b.scoreIncrease - a.scoreIncrease));

  // その日の増分: 最後の合計 − 前日最終の合計（前の行が無い＝初回なら各行の増分の合算）
  const lastOfDay = dayAllRows[0];
  const firstOfDay = dayAllRows[dayAllRows.length - 1];
  const prevRow = rows.value[rows.value.indexOf(firstOfDay) + 1];
  const beatPtIncrease = prevRow
    ? (lastOfDay.totalBeatPt ?? 0) - (prevRow.totalBeatPt ?? 0)
    : dayAllRows.reduce((sum, r) => sum + (r.beatPtIncrease ?? 0), 0);

  return { date: first.date, uploads: dayPlayRows.length, songs, beatPtIncrease };
});

/** 【computed の役割】 一覧に出す譜面（折りたたみ中は先頭 PREVIEW 件）。 */
const shownSongs = computed(() => {
  const songs = latestDay.value?.songs ?? [];
  return expanded.value ? songs : songs.slice(0, PREVIEW);
});

/** 取り込み日のラベル（言語ごとの日付書式・JST）。 */
const dateLabel = computed(() => {
  if (!latestDay.value) return '';
  const locale = currentLang.value === 'ko' ? 'ko-KR' : currentLang.value === 'en' ? 'en-US' : 'ja-JP';
  return formatJstDate(latestDay.value.date, locale);
});

/** 符号付きの小数表記（"+12.3" / "-0.4"）。 */
const signed = (v: number, digits: number) => `${v >= 0 ? '+' : ''}${v.toFixed(digits)}`;

const DIFF_SHORT: Record<string, string> = { BEGINNER: 'B', NORMAL: 'N', HYPER: 'H', ANOTHER: 'A', LEGGENDARIA: 'L' };
const DIFF_CLASS: Record<string, string> = {
  BEGINNER: 'bg-emerald-100 text-emerald-700 dark:bg-emerald-900/40 dark:text-emerald-300',
  NORMAL: 'bg-blue-100 text-blue-700 dark:bg-blue-900/40 dark:text-blue-300',
  HYPER: 'bg-amber-100 text-amber-700 dark:bg-amber-900/40 dark:text-amber-300',
  ANOTHER: 'bg-red-100 text-red-700 dark:bg-red-900/40 dark:text-red-300',
  LEGGENDARIA: 'bg-purple-100 text-purple-700 dark:bg-purple-900/40 dark:text-purple-300',
};
const diffShort = (d: string) => DIFF_SHORT[d] ?? d;
const diffClass = (d: string) => DIFF_CLASS[d] ?? 'bg-slate-100 text-slate-600 dark:bg-slate-700 dark:text-slate-300';
</script>

<template>
  <div class="bg-white dark:bg-slate-800 p-4 sm:p-6 rounded-md border border-slate-200 dark:border-slate-700 transition-colors duration-200">
    <div class="flex flex-wrap items-center justify-between gap-x-3 gap-y-1 mb-3">
      <h3 class="text-sm sm:text-base font-bold text-slate-800 dark:text-slate-100 flex items-center gap-2 min-w-0">
        <svg class="w-4 h-4 text-blue-600 dark:text-blue-400 shrink-0" fill="none" viewBox="0 0 24 24" stroke="currentColor" stroke-width="2" aria-hidden="true">
          <path stroke-linecap="round" stroke-linejoin="round" d="M7 16a4 4 0 01-.88-7.903A5 5 0 1115.9 6L16 6a5 5 0 011 9.9M15 13l-3-3m0 0l-3 3m3-3v12" />
        </svg>
        {{ t('dashboard.recent.title') }}
        <span v-if="latestDay" class="text-xs font-bold text-slate-400 dark:text-slate-500 whitespace-nowrap">{{ dateLabel }}</span>
      </h3>
      <button
        type="button"
        @click="emit('open-history')"
        class="text-xs font-bold text-blue-700 dark:text-blue-400 hover:underline whitespace-nowrap shrink-0"
      >{{ t('dashboard.recent.viewHistory') }} →</button>
    </div>

    <p v-if="isLoading" class="text-xs text-slate-400 dark:text-slate-500 py-4 text-center">{{ t('common.loading') }}</p>
    <p v-else-if="!latestDay" class="text-xs text-slate-400 dark:text-slate-500 py-4 text-center">{{ t('dashboard.recent.empty') }}</p>
    <template v-else>
      <!-- その日のまとめ -->
      <div class="flex flex-wrap items-center gap-x-4 gap-y-1 text-xs text-slate-500 dark:text-slate-400 mb-2">
        <span class="font-bold text-slate-700 dark:text-slate-200">{{ t('dashboard.recent.updatedCount', { n: latestDay.songs.length }) }}</span>
        <span class="font-bold tabular-nums" :class="latestDay.beatPtIncrease >= 0 ? 'text-emerald-600 dark:text-emerald-400' : 'text-red-500'">
          BEAT-PT {{ signed(latestDay.beatPtIncrease, 1) }}
        </span>
        <span v-if="latestDay.uploads > 1">{{ t('dashboard.recent.uploads', { n: latestDay.uploads }) }}</span>
      </div>

      <!-- 更新譜面。狭い幅では曲名行とスコア行に折り返す
           （曲名側は flex-1 ではなく grow + basis: flex-1 は古い output.css に basis 0 で上書きされ、曲名が幅 0 に潰れる） -->
      <ul class="divide-y divide-slate-100 dark:divide-slate-700">
        <li v-for="s in shownSongs" :key="s.key" class="py-2 flex flex-wrap items-center gap-x-3 gap-y-1 text-xs">
          <div class="flex items-center gap-1.5 min-w-0 grow basis-48">
            <span class="shrink-0 px-1.5 py-0.5 rounded text-[10px] font-bold" :class="diffClass(s.difficulty)">{{ diffShort(s.difficulty) }}</span>
            <span v-if="s.level" class="shrink-0 text-[10px] font-bold text-slate-400 dark:text-slate-500">☆{{ s.level }}</span>
            <span class="font-bold text-slate-800 dark:text-slate-100 truncate" :title="s.title">{{ s.title }}</span>
            <span v-if="s.allTimeBestUpdated" class="shrink-0 px-1 py-0.5 rounded text-[9px] font-bold bg-amber-100 dark:bg-amber-900/40 text-amber-700 dark:text-amber-300">{{ t('dashboard.recent.allTimeBest') }}</span>
          </div>
          <div class="flex flex-wrap items-center gap-x-3 gap-y-0.5 tabular-nums ml-auto">
            <span class="text-slate-500 dark:text-slate-400 whitespace-nowrap">
              <template v-if="s.oldScore > 0">{{ s.oldScore.toLocaleString() }} → </template>
              <span class="font-bold text-slate-800 dark:text-slate-100">{{ s.newScore.toLocaleString() }}</span>
              <span v-if="s.scoreIncrease > 0" class="ml-1 font-bold text-emerald-600 dark:text-emerald-400">+{{ s.scoreIncrease.toLocaleString() }}</span>
            </span>
            <span v-if="s.clearTypeImproved && s.oldClearType !== s.newClearType" class="font-bold text-blue-600 dark:text-blue-400 whitespace-nowrap">
              {{ clearTypeShort(s.oldClearType) || '—' }} → {{ clearTypeShort(s.newClearType) }}
            </span>
            <span v-if="s.beatPtIncrease > 0.005" class="font-bold text-violet-600 dark:text-violet-400 whitespace-nowrap">+{{ s.beatPtIncrease.toFixed(2) }} pt</span>
          </div>
        </li>
      </ul>
      <button
        v-if="latestDay.songs.length > PREVIEW"
        type="button"
        @click="expanded = !expanded"
        class="mt-2 text-xs font-bold text-blue-700 dark:text-blue-400 hover:underline"
      >{{ expanded ? t('dashboard.recent.showLess') : t('dashboard.recent.showMore', { n: latestDay.songs.length - PREVIEW }) }}</button>
    </template>
  </div>
</template>
