<script setup lang="ts">
/**
 * 【コンポーネントの役割】 全ユーザーの Beat-PT / Rate-PT ランキング表示 + 管理者用シミュレーション機能。
 * - 3 モード切替:
 *    1. beat       … Beat-PT ランキング（全員）
 *    2. rate       … Rate-PT ランキング（showRateTier が true のときのみ選択可能）
 *    3. simulation … 管理者限定。非公式難易度表の「ドラフト」が採用された場合の順位変動を試算
 * - 都道府県 TOP ランカー（バーチャル）を混ぜて表示できるトグル付き
 * - プライベートプロフィールのユーザーは個別 API で合計 pt を追加取得してから専用ビューへ遷移
 * - 自分の行までスクロールする "goToMyRank" ボタンを内包
 * - ページネーション（1 ページ 50 件）
 *
 * @emits view-user 公開ユーザーの詳細表示要求。
 * @emits view-private-user プライベートユーザー閲覧時（合計 pt を同梱）。
 * @emits view-top-ranker 都道府県 TOP ランカー（バーチャルプロフィール）閲覧要求。
 */
import { ref, onMounted, watch, computed, nextTick } from 'vue';
import RankIcon from './RankIcon.vue';
import RankingScatterChart, { type ScatterPoint } from './RankingScatterChart.vue';
import { getRankInfo, getRateTierRankInfo } from '../utils/beatTier';
import { useAuth } from '../composables/useAuth';
import { useAdmin } from '../composables/useAdmin';
import { useRateTierVisibility } from '../composables/useRateTierVisibility';
import { useI18n } from '../composables/useI18n';
import { diffTable as diffTableRanks } from '../composables/useGameData';

/** Beat-PT ランキング API が返すユーザー 1 人分のエントリ。 */
interface BeatRankingEntry {
  userId: number | null;
  privacyLevel: number | null;
  displayName: string;
  iidxId: string;
  totalBeatPt: number;
  rankChange: number | null;
  lastUpdatedAt: string | null;
}

interface TopRankerEntry {
  versionNum: number;
  versionName: string;
  prefectureFileNum: number;
  prefectureName: string;
  beatPt: number;
}

type MergedBeatRow =
  | { kind: 'user'; rank: number; entry: BeatRankingEntry }
  | { kind: 'topRanker'; totalBeatPt: number; versionNum: number; versionName: string; prefectureFileNum: number; prefectureName: string };

interface RateRankingEntry {
  userId: number | null;
  privacyLevel: number | null;
  displayName: string;
  iidxId: string;
  totalRatePt: number;
  rankChange: number | null;
  lastUpdatedAt: string | null;
}

interface RateTopRankerEntry {
  versionNum: number;
  versionName: string;
  prefectureFileNum: number;
  prefectureName: string;
  ratePt: number;
}

type MergedRateRow =
  | { kind: 'user'; rank: number; entry: RateRankingEntry }
  | { kind: 'topRanker'; totalRatePt: number; versionNum: number; versionName: string; prefectureFileNum: number; prefectureName: string };

interface SimulationEntry {
  displayName: string;
  iidxId: string;
  currentBeatPt: number;
  simulatedBeatPt: number;
  ptDelta: number;
  currentRank: number;
  simulatedRank: number;
  rankDelta: number;
}

/**
 * 【関数の役割】 最終更新日時を「今日/昨日/一昨日/N 日前/1 週間以上前」の相対表現に変換する。
 * i18n キー経由でローカライズ済みの文字列を返す。
 */
function formatLastUpdated(dateStr: string | null): string {
  if (!dateStr) return '-';
  const now = new Date();
  const date = new Date(dateStr);
  const diffDays = Math.floor((now.getTime() - date.getTime()) / (1000 * 60 * 60 * 24));
  if (diffDays === 0) return t('common.today');
  if (diffDays === 1) return t('common.yesterday');
  if (diffDays === 2) return t('common.dayBeforeYesterday');
  if (diffDays <= 6) return t('common.daysAgo', { days: diffDays });
  return t('common.moreThanAWeekAgo');
}

const { t } = useI18n();

const emit = defineEmits<{
    (e: 'view-user', payload: { id: number; displayName: string; iidxId: string }): void;
    (e: 'view-private-user', payload: { id: number; displayName: string; iidxId: string; totalBeatPt: number; totalRatePt: number }): void;
    (e: 'view-top-ranker', payload: { versionNum: number; versionName: string; prefectureFileNum: number; prefectureName: string }): void;
}>();

/**
 * 【関数の役割】 ランキング行クリック時のハンドラ。
 * - 匿名ユーザー（userId null）は無視
 * - privacyLevel !== 0（非公開寄り）はプライベートビュー用に合計 pt を追加取得してから view-private-user を emit
 *    - 一覧 API でも未取得なら既存 ranking キャッシュから iidxId で探索してフォールバック
 * - 公開ユーザーは view-user をそのまま emit
 */
async function handleUserRowClick(entry: { userId: number | null; privacyLevel: number | null; displayName: string; iidxId: string }) {
    if (entry.userId == null) return;
    const priv = entry.privacyLevel ?? 1;
    if (priv !== 0) {
        let totalBeatPt = 0;
        let totalRatePt = 0;
        try {
            const resp = await fetch(`${API_BASE}/api/scores/user-tier-totals/${entry.userId}`, { headers: authHeaders.value });
            if (resp.ok) {
                const data = await resp.json();
                totalBeatPt = Number(data.totalBeatPt ?? 0);
                totalRatePt = Number(data.totalRatePt ?? 0);
            }
        } catch {}
        if (totalBeatPt === 0 || totalRatePt === 0) {
            if (beatRanking.value.length === 0) { try { await fetchBeatRanking(); } catch {} }
            if (rateRanking.value.length === 0) { try { await fetchRateRanking(); } catch {} }
            if (totalBeatPt === 0) {
                const beatEntry = beatRanking.value.find(u => u.iidxId === entry.iidxId);
                if (beatEntry?.totalBeatPt) totalBeatPt = beatEntry.totalBeatPt;
            }
            if (totalRatePt === 0) {
                const rateEntry = rateRanking.value.find(u => u.iidxId === entry.iidxId);
                if (rateEntry?.totalRatePt) totalRatePt = rateEntry.totalRatePt;
            }
        }
        emit('view-private-user', {
            id: entry.userId,
            displayName: entry.displayName,
            iidxId: entry.iidxId,
            totalBeatPt,
            totalRatePt,
        });
        return;
    }
    emit('view-user', { id: entry.userId, displayName: entry.displayName, iidxId: entry.iidxId });
}

/** 【関数の役割】 都道府県 TOP ランカー（バーチャルプロフィール）行クリック時、view-top-ranker を発火する。 */
function handleTopRankerRowClick(row: { versionNum: number; versionName: string; prefectureFileNum: number; prefectureName: string }) {
    emit('view-top-ranker', {
        versionNum: row.versionNum,
        versionName: row.versionName,
        prefectureFileNum: row.prefectureFileNum,
        prefectureName: row.prefectureName,
    });
}

const { user, authHeaders } = useAuth();
const { showRateTier } = useRateTierVisibility();
const API_BASE = import.meta.env.VITE_API_BASE ?? 'http://localhost:8080';

/** 【computed の役割】 管理者判定。simulation モード表示制御に使用。判定ロジックは useAdmin に集約。 */
const { isAdmin } = useAdmin();

/** 現在のビューモード（beat / rate / simulation）。 */
const viewMode = ref<'beat' | 'rate' | 'simulation'>('beat');
// Rate-Tier 表示設定がオフに切り替わったら、rate モードから beat へ強制退避する。
watch(showRateTier, (val) => {
    if (!val && viewMode.value === 'rate') viewMode.value = 'beat';
});
/** Beat-PT ランキング本体（API 取得後）。 */
const beatRanking = ref<BeatRankingEntry[]>([]);
/** Rate-PT ランキング本体。 */
const rateRanking = ref<RateRankingEntry[]>([]);
/** Beat-PT 都道府県 TOP ランカー一覧。 */
const topRankers = ref<TopRankerEntry[]>([]);
/** Rate-PT 都道府県 TOP ランカー一覧。 */
const rateTopRankers = ref<RateTopRankerEntry[]>([]);
/** 初回ロード中フラグ。 */
const isLoading = ref(true);
/** エラーメッセージ（i18n キー非経由の文字列）。 */
const error = ref('');
/** 都道府県 TOP ランカー（バーチャル）を表に混ぜて表示するか。 */
const showTopRankers = ref(false);

// ページネーション（1 ページ 50 件）
const PAGE_SIZE = 50;
const beatPage = ref(1);
const ratePage = ref(1);

/**
 * 【computed の役割】 Beat-PT のユーザー行と都道府県 TOP ランカー行を pt 降順でマージした統合ビュー。
 * showTopRankers が false のときはユーザー行だけ。ユーザー行に連番 rank を付与する。
 */
const mergedBeatRanking = computed<MergedBeatRow[]>(() => {
    const userRows: MergedBeatRow[] = beatRanking.value.map((entry, i) => ({
        kind: 'user',
        rank: i + 1,
        entry,
    }));
    const topRows: MergedBeatRow[] = showTopRankers.value ? topRankers.value.map(r => ({
        kind: 'topRanker',
        totalBeatPt: r.beatPt,
        versionNum: r.versionNum,
        versionName: r.versionName,
        prefectureFileNum: r.prefectureFileNum,
        prefectureName: r.prefectureName,
    })) : [];
    const all = [...userRows, ...topRows];
    all.sort((a, b) => {
        const aPt = a.kind === 'user' ? a.entry.totalBeatPt : a.totalBeatPt;
        const bPt = b.kind === 'user' ? b.entry.totalBeatPt : b.totalBeatPt;
        return bPt - aPt;
    });
    return all;
});

/** 【computed の役割】 mergedBeatRanking の Rate-PT 版。構造は同じで合計値が totalRatePt に置換される。 */
const mergedRateRanking = computed<MergedRateRow[]>(() => {
    const userRows: MergedRateRow[] = rateRanking.value.map((entry, i) => ({
        kind: 'user',
        rank: i + 1,
        entry,
    }));
    const topRows: MergedRateRow[] = showTopRankers.value ? rateTopRankers.value.map(r => ({
        kind: 'topRanker',
        totalRatePt: r.ratePt,
        versionNum: r.versionNum,
        versionName: r.versionName,
        prefectureFileNum: r.prefectureFileNum,
        prefectureName: r.prefectureName,
    })) : [];
    const all = [...userRows, ...topRows];
    all.sort((a, b) => {
        const aPt = a.kind === 'user' ? a.entry.totalRatePt : a.totalRatePt;
        const bPt = b.kind === 'user' ? b.entry.totalRatePt : b.totalRatePt;
        return bPt - aPt;
    });
    return all;
});

/**
 * 【computed の役割】 散布図用にユーザーごとの BEAT-PT × RATE-PT 座標を生成する。
 * - iidxId をキーに beatRanking と rateRanking をジョイン
 * - 両方とも > 0 のユーザーのみ採用（log 軸に乗らない 0 値を除外）
 * - showTopRankers が ON のときは都道府県 TOP ランカーも (versionName×prefectureName) で結合して追加
 * - 自分の点は isMe=true でハイライト対象に分類される
 */
const scatterPoints = computed<ScatterPoint[]>(() => {
    const myIidx = user.value?.iidxId;
    const ratePtByIidx = new Map<string, number>();
    for (const r of rateRanking.value) {
        ratePtByIidx.set(r.iidxId, r.totalRatePt);
    }
    const points: ScatterPoint[] = [];
    for (const b of beatRanking.value) {
        const rate = ratePtByIidx.get(b.iidxId) ?? 0;
        if (b.totalBeatPt <= 0 || rate <= 0) continue;
        points.push({
            iidxId: b.iidxId,
            displayName: b.displayName || 'Unnamed Player',
            beatPt: b.totalBeatPt,
            ratePt: rate,
            isMe: !!myIidx && b.iidxId === myIidx,
            isTopRanker: false,
        });
    }
    if (showTopRankers.value) {
        const rateTopByKey = new Map<string, number>();
        for (const t of rateTopRankers.value) {
            rateTopByKey.set(`${t.versionNum} ${t.prefectureFileNum}`, t.ratePt);
        }
        for (const t of topRankers.value) {
            const key = `${t.versionNum} ${t.prefectureFileNum}`;
            const rate = rateTopByKey.get(key) ?? 0;
            if (t.beatPt <= 0 || rate <= 0) continue;
            points.push({
                iidxId: key,
                displayName: `TOP: ${t.versionName} × ${t.prefectureName}`,
                beatPt: t.beatPt,
                ratePt: rate,
                isMe: false,
                isTopRanker: true,
            });
        }
    }
    return points;
});

const beatTotalPages = computed(() => Math.max(1, Math.ceil(mergedBeatRanking.value.length / PAGE_SIZE)));
const rateTotalPages = computed(() => Math.max(1, Math.ceil(mergedRateRanking.value.length / PAGE_SIZE)));

const paginatedBeatRanking = computed(() => {
    const start = (beatPage.value - 1) * PAGE_SIZE;
    return mergedBeatRanking.value.slice(start, start + PAGE_SIZE);
});
const paginatedRateRanking = computed(() => {
    const start = (ratePage.value - 1) * PAGE_SIZE;
    return mergedRateRanking.value.slice(start, start + PAGE_SIZE);
});

/**
 * 【関数の役割】 ログインユーザー自身の行までページを切り替えスムーズスクロールする。
 * rate モードなら ratePage を、それ以外なら beatPage を変更し、DOM 更新後に scrollIntoView。
 * 自分がランキング対象外（iidxId 未マッチ）なら何もしない。
 */
function goToMyRank() {
    if (!user.value) return;
    let idx: number;
    if (viewMode.value === 'rate') {
        idx = mergedRateRanking.value.findIndex(r => r.kind === 'user' && r.entry.iidxId === user.value!.iidxId);
    } else {
        idx = mergedBeatRanking.value.findIndex(r => r.kind === 'user' && r.entry.iidxId === user.value!.iidxId);
    }
    if (idx === -1) return;
    const page = Math.floor(idx / PAGE_SIZE) + 1;
    if (viewMode.value === 'rate') {
        ratePage.value = page;
    } else {
        beatPage.value = page;
    }
    // DOM 更新後に対象行へスクロール。
    nextTick(() => {
        const row = document.getElementById(`ranking-row-${user.value!.iidxId}`);
        if (row) row.scrollIntoView({ behavior: 'smooth', block: 'center' });
    });
}

// モード切替時はどちらのページカーソルも 1 ページ目に戻す。
watch(viewMode, () => {
    beatPage.value = 1;
    ratePage.value = 1;
});

// ---- シミュレーションモード（管理者用）----
/** シミュレーション結果（ドラフト採用時の順位変動予測）。 */
const simulationData = ref<SimulationEntry[]>([]);
/** シミュレーション API 呼び出し中フラグ。 */
const isSimulationLoading = ref(false);
/** シミュレーションエラーメッセージ。 */
const simulationError = ref('');
/** ドラフト難易度表 vs 現行との差分リスト（昇格 / 降格表示用）。 */
const draftDiffChanges = ref<{ title: string; oldRank: string; newRank: string }[]>([]);
/** 【computed の役割】 昇格（新ランク > 旧ランク）の差分のみ抽出。 */
const promotionChanges = computed(() => draftDiffChanges.value.filter(c => parseFloat(c.newRank) > parseFloat(c.oldRank)));
/** 【computed の役割】 降格（新ランク < 旧ランク）の差分のみ抽出。 */
const demotionChanges = computed(() => draftDiffChanges.value.filter(c => parseFloat(c.newRank) < parseFloat(c.oldRank)));

/** 【関数の役割】 Beat-PT ランキング本体と都道府県 TOP ランカーを並列取得し、0 pt の TOP ランカーは除外する。 */
async function fetchBeatRanking() {
    const [rankRes, topRes] = await Promise.all([
        fetch(`${API_BASE}/api/scores/ranking`),
        fetch(`${API_BASE}/api/scores/ranking/top-rankers`),
    ]);
    if (!rankRes.ok) throw new Error('beat');
    beatRanking.value = await rankRes.json();
    if (topRes.ok) {
        const raw: TopRankerEntry[] = await topRes.json();
        topRankers.value = raw.filter(r => r.beatPt > 0);
    }
}

/** 【関数の役割】 Rate-PT ランキング本体と都道府県 TOP ランカー（Rate 版）を並列取得する。 */
async function fetchRateRanking() {
    const [rankRes, topRes] = await Promise.all([
        fetch(`${API_BASE}/api/scores/rate-ranking`),
        fetch(`${API_BASE}/api/scores/rate-ranking/top-rankers`),
    ]);
    if (!rankRes.ok) throw new Error('rate');
    rateRanking.value = await rankRes.json();
    if (topRes.ok) {
        const raw: RateTopRankerEntry[] = await topRes.json();
        rateTopRankers.value = raw.filter(r => r.ratePt > 0);
    }
}



/**
 * 【関数の役割】 ドラフト難易度表が採用された場合のランク変動をシミュレートする（管理者限定）。
 * 流れ：
 *   1. 現行ランキング未ロードなら先に取得
 *   2. simulation-aggregate / draft / active-difficulty-table を並列取得
 *   3. 現行とドラフトの差分（changes[]）を計算
 *   4. simEntries にシミュレーション後の順位 (simulatedRank) を付与して simulationData にセット
 */
async function fetchSimulationData() {
    isSimulationLoading.value = true;
    simulationError.value = '';
    try {
        // 通常ランキング未ロードなら現在順位を埋めるため先に取得する。
        if (beatRanking.value.length === 0) {
            await fetchBeatRanking();
        }

        const [simRes, draftRes, activeDiffRes] = await Promise.all([
            fetch(`${API_BASE}/api/admin/scores/simulation-aggregate`, { headers: authHeaders() }),
            fetch(`${API_BASE}/api/admin/game-data/difficulty-table/draft`, { headers: authHeaders() }),
            fetch(`${API_BASE}/api/game-data/difficulty-table`),
        ]);
        if (!simRes.ok) throw new Error('シミュレーションの取得に失敗しました');
        if (!draftRes.ok) throw new Error('ドラフト難易度表の取得に失敗しました');

        const simEntries: Omit<SimulationEntry, 'currentRank' | 'simulatedRank' | 'rankDelta'>[] = await simRes.json();
        const draftTable: { ranks: { rank: string; songs: string[] }[] } = await draftRes.json();
        const activeDiff: { ranks: { rank: string; songs: string[] }[] } = activeDiffRes.ok
            ? await activeDiffRes.json()
            : { ranks: diffTableRanks.value };

        // Compute draft changes for display
        const activeMap = new Map<string, string>();
        activeDiff.ranks.forEach(r => r.songs.forEach(s => activeMap.set(s, r.rank)));
        const changes: { title: string; oldRank: string; newRank: string }[] = [];
        draftTable.ranks.forEach(r => {
            r.songs.forEach(s => {
                const activeRank = activeMap.get(s);
                if (activeRank !== undefined && activeRank !== r.rank) {
                    changes.push({ title: s, oldRank: activeRank, newRank: r.rank });
                }
            });
        });
        draftDiffChanges.value = changes;

        // Sort by simulated BEAT-PT descending
        const sortedBySim = [...simEntries].sort((a, b) => b.simulatedBeatPt - a.simulatedBeatPt);

        // Current rank = position in the regular ranking
        const currentRankMap = new Map<string, number>();
        beatRanking.value.forEach((e, i) => currentRankMap.set(e.iidxId, i + 1));

        const simRankMap = new Map<string, number>();
        sortedBySim.forEach((e, i) => simRankMap.set(e.iidxId, i + 1));

        simulationData.value = sortedBySim.map(e => ({
            ...e,
            currentRank: currentRankMap.get(e.iidxId) ?? 0,
            simulatedRank: simRankMap.get(e.iidxId) ?? 0,
            rankDelta: (currentRankMap.get(e.iidxId) ?? 0) - (simRankMap.get(e.iidxId) ?? 0),
        }));
    } catch (e: any) {
        simulationError.value = e.message || 'シミュレーションの取得に失敗しました';
    } finally {
        isSimulationLoading.value = false;
    }
}

// 初回マウントで Beat-PT ランキングを取得。
// 散布図（BEAT × RATE）を表示するために、RATE 表示が有効ならマウント時に Rate も並行取得する。
onMounted(async () => {
    try {
        const tasks: Promise<unknown>[] = [fetchBeatRanking()];
        if (showRateTier.value) {
            tasks.push(fetchRateRanking().catch(e => console.error(e)));
        }
        await Promise.all(tasks);
    } catch (e) {
        console.error(e);
        error.value = t('ranking.error');
    } finally {
        isLoading.value = false;
    }
});

// モード切替時に未取得なら lazy にデータを引く。
// rate / simulation はユーザーが明示的に切り替えない限りネットワークを消費しない設計。
watch(viewMode, async (mode) => {
    if (mode === 'rate' && rateRanking.value.length === 0) {
        isLoading.value = true;
        error.value = '';
        try {
            await fetchRateRanking();
        } catch (e) {
            console.error(e);
            error.value = t('ranking.error');
        } finally {
            isLoading.value = false;
        }
    }
    if (mode === 'simulation' && simulationData.value.length === 0) {
        fetchSimulationData();
    }
});
</script>

<template>
  <div class="w-full max-w-4xl space-y-6 animate-fade-in">
    <div class="bg-white dark:bg-slate-800 p-6 sm:p-8 rounded-3xl shadow-sm border border-slate-200 dark:border-slate-700 transition-colors">
      <!-- Header -->
      <div class="flex items-center gap-4 mb-6">
        <div class="p-3 rounded-2xl bg-amber-100 dark:bg-amber-900/30 transition-colors">
          <svg xmlns="http://www.w3.org/2000/svg" class="h-8 w-8 text-amber-600 dark:text-amber-400" fill="none" viewBox="0 0 24 24" stroke="currentColor">
            <path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M9 12l2 2 4-4M7.835 4.697a3.42 3.42 0 001.946-.806 3.42 3.42 0 014.438 0 3.42 3.42 0 001.946.806 3.42 3.42 0 013.138 3.138 3.42 3.42 0 00.806 1.946 3.42 3.42 0 010 4.438 3.42 3.42 0 00-.806 1.946 3.42 3.42 0 01-3.138 3.138 3.42 3.42 0 00-1.946.806 3.42 3.42 0 01-4.438 0 3.42 3.42 0 00-1.946-.806 3.42 3.42 0 01-3.138-3.138 3.42 3.42 0 00-.806-1.946 3.42 3.42 0 010-4.438 3.42 3.42 0 00.806-1.946 3.42 3.42 0 013.138-3.138z" />
          </svg>
        </div>
        <div>
          <h2 class="text-2xl font-black text-slate-800 dark:text-slate-100">{{ t('ranking.title') }}</h2>
          <p class="text-slate-500 dark:text-slate-400 font-medium text-sm">{{ t('ranking.subtitle') }}</p>
        </div>
      </div>


      <!-- BEAT-TIER × RATE-TIER 散布図 -->
      <div v-if="showRateTier && !isLoading && !error && scatterPoints.length > 0"
        class="mb-6 p-4 sm:p-5 rounded-2xl bg-slate-50 dark:bg-slate-900/40 border border-slate-200 dark:border-slate-700">
        <div class="flex items-center justify-between gap-2 mb-3 flex-wrap">
          <h3 class="text-sm font-black uppercase tracking-widest text-slate-600 dark:text-slate-300">
            BEAT-TIER × RATE-TIER 分布
          </h3>
          <span class="text-[10px] font-bold text-slate-400 dark:text-slate-500">
            {{ scatterPoints.length.toLocaleString() }} プレイヤー
          </span>
        </div>
        <RankingScatterChart :points="scatterPoints" />
      </div>

      <!-- Find My Rank Button + Mode Toggle -->
      <div class="flex items-center gap-3 mb-6 flex-wrap">
        <button
          v-if="user && (viewMode === 'beat' || viewMode === 'rate')"
          @click="goToMyRank"
          class="flex items-center gap-1.5 px-3 py-1.5 rounded-xl text-xs font-bold bg-blue-50 dark:bg-blue-900/30 text-blue-600 dark:text-blue-400 border border-blue-200 dark:border-blue-700 hover:bg-blue-100 dark:hover:bg-blue-900/50 transition-colors"
        >
          <svg xmlns="http://www.w3.org/2000/svg" class="h-3.5 w-3.5" fill="none" viewBox="0 0 24 24" stroke="currentColor"><path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M21 21l-6-6m2-5a7 7 0 11-14 0 7 7 0 0114 0z" /></svg>
          {{ t('ranking.findMyRank') }}
        </button>
      </div>

      <!-- Mode Toggle -->
      <div class="flex items-center justify-between gap-4 mb-6 flex-wrap">
        <div class="flex gap-1 p-1 bg-slate-100 dark:bg-slate-700/50 rounded-xl w-fit">
          <button
            @click="viewMode = 'beat'"
            class="px-4 py-1.5 rounded-lg text-xs font-black uppercase tracking-widest transition-all"
            :class="viewMode === 'beat'
              ? 'bg-white dark:bg-slate-600 text-blue-600 dark:text-blue-400 shadow-sm'
              : 'text-slate-400 dark:text-slate-500 hover:text-slate-600 dark:hover:text-slate-300'"
          >Beat-Tier</button>
          <button
            v-if="showRateTier"
            @click="viewMode = 'rate'"
            class="px-4 py-1.5 rounded-lg text-xs font-black uppercase tracking-widest transition-all"
            :class="viewMode === 'rate'
              ? 'bg-white dark:bg-slate-600 text-emerald-600 dark:text-emerald-400 shadow-sm'
              : 'text-slate-400 dark:text-slate-500 hover:text-slate-600 dark:hover:text-slate-300'"
          >Rate-Tier</button>
          <button
            v-if="isAdmin"
            @click="viewMode = 'simulation'"
            class="px-4 py-1.5 rounded-lg text-xs font-black uppercase tracking-widest transition-all"
            :class="viewMode === 'simulation'
              ? 'bg-white dark:bg-slate-600 text-amber-600 dark:text-amber-400 shadow-sm'
              : 'text-slate-400 dark:text-slate-500 hover:text-slate-600 dark:hover:text-slate-300'"
          >難易度シミュ</button>
        </div>
        <label v-if="viewMode === 'beat' || viewMode === 'rate'" class="flex items-center gap-2 cursor-pointer group whitespace-nowrap">
          <div class="relative inline-flex items-center">
            <input type="checkbox" v-model="showTopRankers" class="sr-only peer">
            <div class="w-9 h-5 bg-slate-200 dark:bg-slate-700 rounded-full peer peer-checked:bg-amber-500 dark:peer-checked:bg-amber-600 transition-colors"></div>
            <div class="absolute left-0.5 top-0.5 w-4 h-4 bg-white rounded-full transition-transform peer-checked:translate-x-4"></div>
          </div>
          <span class="text-xs sm:text-sm font-bold text-slate-600 dark:text-slate-400 group-hover:text-slate-800 dark:group-hover:text-slate-200 transition-colors">TOPランカー仮想ユーザを表示</span>
        </label>
      </div>

      <!-- Loading / Error / Empty -->
      <div v-if="isLoading" class="flex flex-col items-center justify-center py-20">
        <div class="w-12 h-12 border-4 border-blue-100 dark:border-slate-700 border-t-blue-600 dark:border-t-blue-500 rounded-full animate-spin mb-4"></div>
        <p class="text-slate-500 dark:text-slate-400 font-bold">{{ t('ranking.loading') }}</p>
      </div>

      <div v-else-if="error" class="p-6 bg-red-50 dark:bg-red-900/20 text-red-600 dark:text-red-400 rounded-2xl text-center font-bold">
        {{ t('ranking.error') }}
      </div>

      <template v-else>
        <!-- Beat-Tier ranking -->
        <div v-if="viewMode === 'beat'">
          <div v-if="beatRanking.length === 0" class="text-center py-20 border-2 border-dashed border-slate-200 dark:border-slate-700 rounded-3xl">
            <p class="text-slate-500 dark:text-slate-400 font-bold">{{ t('ranking.empty') }}</p>
          </div>
          <div v-else class="overflow-x-auto">
            <table class="w-full">
              <thead>
                <tr class="text-left border-b border-slate-100 dark:border-slate-700/50">
                  <th class="pb-4 pl-4 text-xs font-black text-slate-400 uppercase tracking-widest w-28">{{ t('ranking.colRank') }}</th>
                  <th class="pb-4 text-xs font-black text-slate-400 uppercase tracking-widest">{{ t('ranking.colPlayer') }}</th>
                  <th class="pb-4 text-xs font-black text-slate-400 uppercase tracking-widest w-20 text-center">{{ t('ranking.colTier') }}</th>
                  <th class="pb-4 text-xs font-black text-slate-400 uppercase tracking-widest text-right">{{ t('ranking.colPoints', { type: 'BEAT' }) }}</th>
                  <th class="pb-4 text-xs font-black text-slate-400 uppercase tracking-widest text-right pr-4">{{ t('ranking.colUpdatedAt') }}</th>
                </tr>
              </thead>
              <tbody class="divide-y divide-slate-50 dark:divide-slate-700/30">
                <template v-for="(row, idx) in paginatedBeatRanking" :key="row.kind === 'user' ? `u-${row.entry.iidxId}` : `t-${row.versionName}-${row.prefectureName}`">
                  <tr v-if="row.kind === 'user'"
                    :id="`ranking-row-${row.entry.iidxId}`"
                    class="group transition-colors"
                    :class="[
                      user && row.entry.iidxId === user.iidxId
                        ? 'bg-blue-50 dark:bg-blue-900/20 border-l-4 border-l-blue-500'
                        : 'hover:bg-slate-50 dark:hover:bg-slate-700/30',
                      row.entry.userId != null ? 'cursor-pointer' : ''
                    ]"
                    @click="handleUserRowClick(row.entry)">
                    <td class="py-3 pl-4">
                      <div class="flex items-center gap-2">
                        <div class="flex items-center justify-center w-7 h-7 rounded-lg font-black text-xs"
                          :class="[
                            row.rank === 1 ? 'bg-amber-100 text-amber-700 dark:bg-amber-500 dark:text-white' :
                            row.rank === 2 ? 'bg-slate-200 text-slate-700 dark:bg-slate-400 dark:text-white' :
                            row.rank === 3 ? 'bg-orange-100 text-orange-700 dark:bg-orange-400 dark:text-white' :
                            user && row.entry.iidxId === user.iidxId ? 'bg-blue-500 text-white' :
                            'text-slate-400 border border-slate-100 dark:border-slate-700'
                          ]">
                          {{ row.rank }}
                        </div>
                        <span v-if="row.entry.rankChange === null" class="text-[10px] font-bold text-blue-500">NEW</span>
                        <span v-else-if="row.entry.rankChange > 0" class="text-[10px] font-bold text-emerald-500">▲{{ row.entry.rankChange }}</span>
                        <span v-else-if="row.entry.rankChange < 0" class="text-[10px] font-bold text-red-500">▼{{ Math.abs(row.entry.rankChange) }}</span>
                        <span v-else class="text-[10px] font-bold text-slate-300 dark:text-slate-600">-</span>
                      </div>
                    </td>
                    <td class="py-3">
                      <div class="flex items-center gap-2">
                        <span class="font-bold text-base transition-colors"
                          :class="user && row.entry.iidxId === user.iidxId
                            ? 'text-blue-700 dark:text-blue-300'
                            : 'text-slate-800 dark:text-slate-100 group-hover:text-blue-600 dark:group-hover:text-blue-400'">
                          {{ row.entry.displayName || 'Unnamed Player' }}
                        </span>
                        <span v-if="(row.entry.privacyLevel ?? 1) !== 0" class="text-xs text-slate-400" :title="(row.entry.privacyLevel ?? 1) === 2 ? '非公開' : 'フレンドのみ公開'">🔒</span>
                        <span v-if="user && row.entry.iidxId === user.iidxId"
                          class="text-[9px] font-black uppercase tracking-wider px-1.5 py-0.5 rounded bg-blue-500 text-white">{{ t('ranking.you') }}</span>
                      </div>
                    </td>
                    <td class="py-3 px-2 text-center">
                      <div class="flex justify-center">
                        <RankIcon :rank-name="getRankInfo(row.entry.totalBeatPt).name" :tier="getRankInfo(row.entry.totalBeatPt).tier" size="md" disable-party :is-supporter="row.entry.isSupporter" />
                      </div>
                    </td>
                    <td class="py-3 text-right">
                      <div class="flex items-baseline justify-end gap-1">
                        <span class="text-xl font-black tabular-nums"
                          :class="user && row.entry.iidxId === user.iidxId ? 'text-blue-700 dark:text-blue-300' : 'text-slate-800 dark:text-slate-100'">
                          {{ row.entry.totalBeatPt.toLocaleString(undefined, { minimumFractionDigits: 1, maximumFractionDigits: 1 }) }}
                        </span>
                        <span class="text-[9px] font-bold text-slate-400 uppercase tracking-widest">BEAT-PT</span>
                      </div>
                    </td>
                    <td class="py-3 text-right pr-4">
                      <span class="text-xs font-medium tabular-nums"
                        :class="formatLastUpdated(row.entry.lastUpdatedAt) === t('common.today') ? 'text-emerald-600 dark:text-emerald-400' : 'text-slate-400 dark:text-slate-500'">
                        {{ formatLastUpdated(row.entry.lastUpdatedAt) }}
                      </span>
                    </td>
                  </tr>
                  <tr v-else
                    class="bg-amber-50/50 dark:bg-amber-900/10 cursor-pointer hover:bg-amber-100/60 dark:hover:bg-amber-900/20 transition-colors"
                    @click="handleTopRankerRowClick(row)">
                    <td class="py-2 pl-4">
                      <div class="flex items-center justify-center w-7 h-7 rounded-lg text-slate-300 dark:text-slate-600 text-sm">―</div>
                    </td>
                    <td class="py-2">
                      <div class="flex items-center gap-2">
                        <span class="text-[10px] font-black uppercase tracking-widest px-1.5 py-0.5 rounded bg-amber-200 dark:bg-amber-800 text-amber-700 dark:text-amber-200">TOP</span>
                        <span class="font-bold text-sm text-slate-600 dark:text-slate-300">
                          {{ row.versionName }} × {{ row.prefectureName }}
                        </span>
                      </div>
                    </td>
                    <td class="py-2 px-2 text-center">
                      <div class="flex justify-center">
                        <RankIcon :rank-name="getRankInfo(row.totalBeatPt).name" :tier="getRankInfo(row.totalBeatPt).tier" size="md" disable-party />
                      </div>
                    </td>
                    <td class="py-2 text-right">
                      <div class="flex items-baseline justify-end gap-1">
                        <span class="text-lg font-black tabular-nums text-slate-500 dark:text-slate-400">
                          {{ row.totalBeatPt.toLocaleString(undefined, { minimumFractionDigits: 1, maximumFractionDigits: 1 }) }}
                        </span>
                        <span class="text-[9px] font-bold text-slate-400 uppercase tracking-widest">BEAT-PT</span>
                      </div>
                    </td>
                    <td class="py-2 text-right pr-4"></td>
                  </tr>
                </template>
              </tbody>
            </table>
            <!-- Beat Pagination -->
            <div v-if="beatTotalPages > 1" class="flex items-center justify-center gap-2 mt-6 pt-4 border-t border-slate-100 dark:border-slate-700/50">
              <button @click="beatPage = 1" :disabled="beatPage === 1"
                class="px-2 py-1 rounded-lg text-xs font-bold transition-colors disabled:opacity-30 disabled:cursor-not-allowed text-slate-500 hover:bg-slate-100 dark:hover:bg-slate-700">
                &laquo;
              </button>
              <button @click="beatPage--" :disabled="beatPage === 1"
                class="px-2 py-1 rounded-lg text-xs font-bold transition-colors disabled:opacity-30 disabled:cursor-not-allowed text-slate-500 hover:bg-slate-100 dark:hover:bg-slate-700">
                &lsaquo;
              </button>
              <span class="text-xs font-bold text-slate-500 dark:text-slate-400 px-2 tabular-nums">
                {{ beatPage }} / {{ beatTotalPages }}
              </span>
              <button @click="beatPage++" :disabled="beatPage === beatTotalPages"
                class="px-2 py-1 rounded-lg text-xs font-bold transition-colors disabled:opacity-30 disabled:cursor-not-allowed text-slate-500 hover:bg-slate-100 dark:hover:bg-slate-700">
                &rsaquo;
              </button>
              <button @click="beatPage = beatTotalPages" :disabled="beatPage === beatTotalPages"
                class="px-2 py-1 rounded-lg text-xs font-bold transition-colors disabled:opacity-30 disabled:cursor-not-allowed text-slate-500 hover:bg-slate-100 dark:hover:bg-slate-700">
                &raquo;
              </button>
            </div>
          </div>
        </div>

        <!-- Rate-Tier ranking -->
        <div v-else-if="viewMode === 'rate'">
          <div v-if="rateRanking.length === 0" class="text-center py-20 border-2 border-dashed border-slate-200 dark:border-slate-700 rounded-3xl">
            <p class="text-slate-500 dark:text-slate-400 font-bold" v-html="t('ranking.emptyRate')"></p>
          </div>
          <div v-else class="overflow-x-auto">
            <table class="w-full">
              <thead>
                <tr class="text-left border-b border-slate-100 dark:border-slate-700/50">
                  <th class="pb-4 pl-4 text-xs font-black text-slate-400 uppercase tracking-widest w-28">{{ t('ranking.colRank') }}</th>
                  <th class="pb-4 text-xs font-black text-slate-400 uppercase tracking-widest">{{ t('ranking.colPlayer') }}</th>
                  <th class="pb-4 text-xs font-black text-slate-400 uppercase tracking-widest w-20 text-center">{{ t('ranking.colTier') }}</th>
                  <th class="pb-4 text-xs font-black text-emerald-500 uppercase tracking-widest text-right">{{ t('ranking.colPoints', { type: 'RATE' }) }}</th>
                  <th class="pb-4 text-xs font-black text-slate-400 uppercase tracking-widest text-right pr-4">{{ t('ranking.colUpdatedAt') }}</th>
                </tr>
              </thead>
              <tbody class="divide-y divide-slate-50 dark:divide-slate-700/30">
                <template v-for="row in paginatedRateRanking" :key="row.kind === 'user' ? `u-${row.entry.iidxId}` : `t-${row.versionName}-${row.prefectureName}`">
                  <tr v-if="row.kind === 'user'"
                    :id="`ranking-row-${row.entry.iidxId}`"
                    class="group transition-colors"
                    :class="[
                      user && row.entry.iidxId === user.iidxId
                        ? 'bg-emerald-50 dark:bg-emerald-900/20 border-l-4 border-l-emerald-500'
                        : 'hover:bg-slate-50 dark:hover:bg-slate-700/30',
                      row.entry.userId != null ? 'cursor-pointer' : ''
                    ]"
                    @click="handleUserRowClick(row.entry)">
                    <td class="py-3 pl-4">
                      <div class="flex items-center gap-2">
                        <div class="flex items-center justify-center w-7 h-7 rounded-lg font-black text-xs"
                          :class="[
                            row.rank === 1 ? 'bg-amber-100 text-amber-700 dark:bg-amber-500 dark:text-white' :
                            row.rank === 2 ? 'bg-slate-200 text-slate-700 dark:bg-slate-400 dark:text-white' :
                            row.rank === 3 ? 'bg-orange-100 text-orange-700 dark:bg-orange-400 dark:text-white' :
                            user && row.entry.iidxId === user.iidxId ? 'bg-emerald-500 text-white' :
                            'text-slate-400 border border-slate-100 dark:border-slate-700'
                          ]">
                          {{ row.rank }}
                        </div>
                        <span v-if="row.entry.rankChange === null" class="text-[10px] font-bold text-blue-500">NEW</span>
                        <span v-else-if="row.entry.rankChange > 0" class="text-[10px] font-bold text-emerald-500">▲{{ row.entry.rankChange }}</span>
                        <span v-else-if="row.entry.rankChange < 0" class="text-[10px] font-bold text-red-500">▼{{ Math.abs(row.entry.rankChange) }}</span>
                        <span v-else class="text-[10px] font-bold text-slate-300 dark:text-slate-600">-</span>
                      </div>
                    </td>
                    <td class="py-3">
                      <div class="flex items-center gap-2">
                        <span class="font-bold text-base transition-colors"
                          :class="user && row.entry.iidxId === user.iidxId
                            ? 'text-emerald-700 dark:text-emerald-300'
                            : 'text-slate-800 dark:text-slate-100 group-hover:text-emerald-600 dark:group-hover:text-emerald-400'">
                          {{ row.entry.displayName || 'Unnamed Player' }}
                        </span>
                        <span v-if="(row.entry.privacyLevel ?? 1) !== 0" class="text-xs text-slate-400" :title="(row.entry.privacyLevel ?? 1) === 2 ? '非公開' : 'フレンドのみ公開'">🔒</span>
                        <span v-if="user && row.entry.iidxId === user.iidxId"
                          class="text-[9px] font-black uppercase tracking-wider px-1.5 py-0.5 rounded bg-emerald-500 text-white">{{ t('ranking.you') }}</span>
                      </div>
                    </td>
                    <td class="py-3 px-2 text-center">
                      <div class="flex justify-center">
                        <RankIcon :rank-name="getRateTierRankInfo(row.entry.totalRatePt).name" :tier="getRateTierRankInfo(row.entry.totalRatePt).tier" size="md" disable-party :is-supporter="row.entry.isSupporter" />
                      </div>
                    </td>
                    <td class="py-3 text-right">
                      <div class="flex items-baseline justify-end gap-1">
                        <span class="text-xl font-black tabular-nums"
                          :class="user && row.entry.iidxId === user.iidxId ? 'text-emerald-700 dark:text-emerald-300' : 'text-slate-800 dark:text-slate-100'">
                          {{ row.entry.totalRatePt.toLocaleString(undefined, { minimumFractionDigits: 1, maximumFractionDigits: 1 }) }}
                        </span>
                        <span class="text-[9px] font-bold text-emerald-500 uppercase tracking-widest">RATE-PT</span>
                      </div>
                    </td>
                    <td class="py-3 text-right pr-4">
                      <span class="text-xs font-medium tabular-nums"
                        :class="formatLastUpdated(row.entry.lastUpdatedAt) === t('common.today') ? 'text-emerald-600 dark:text-emerald-400' : 'text-slate-400 dark:text-slate-500'">
                        {{ formatLastUpdated(row.entry.lastUpdatedAt) }}
                      </span>
                    </td>
                  </tr>
                  <tr v-else
                    class="bg-amber-50/50 dark:bg-amber-900/10 cursor-pointer hover:bg-amber-100/60 dark:hover:bg-amber-900/20 transition-colors"
                    @click="handleTopRankerRowClick(row)">
                    <td class="py-2 pl-4">
                      <div class="flex items-center justify-center w-7 h-7 rounded-lg text-slate-300 dark:text-slate-600 text-sm">―</div>
                    </td>
                    <td class="py-2">
                      <div class="flex items-center gap-2">
                        <span class="text-[10px] font-black uppercase tracking-widest px-1.5 py-0.5 rounded bg-amber-200 dark:bg-amber-800 text-amber-700 dark:text-amber-200">TOP</span>
                        <span class="font-bold text-sm text-slate-600 dark:text-slate-300">
                          {{ row.versionName }} × {{ row.prefectureName }}
                        </span>
                      </div>
                    </td>
                    <td class="py-2 px-2 text-center">
                      <div class="flex justify-center">
                        <RankIcon :rank-name="getRateTierRankInfo(row.totalRatePt).name" :tier="getRateTierRankInfo(row.totalRatePt).tier" size="md" disable-party />
                      </div>
                    </td>
                    <td class="py-2 text-right">
                      <div class="flex items-baseline justify-end gap-1">
                        <span class="text-lg font-black tabular-nums text-slate-500 dark:text-slate-400">
                          {{ row.totalRatePt.toLocaleString(undefined, { minimumFractionDigits: 1, maximumFractionDigits: 1 }) }}
                        </span>
                        <span class="text-[9px] font-bold text-emerald-500 uppercase tracking-widest">RATE-PT</span>
                      </div>
                    </td>
                    <td class="py-2 text-right pr-4"></td>
                  </tr>
                </template>
              </tbody>
            </table>
            <!-- Rate Pagination -->
            <div v-if="rateTotalPages > 1" class="flex items-center justify-center gap-2 mt-6 pt-4 border-t border-slate-100 dark:border-slate-700/50">
              <button @click="ratePage = 1" :disabled="ratePage === 1"
                class="px-2 py-1 rounded-lg text-xs font-bold transition-colors disabled:opacity-30 disabled:cursor-not-allowed text-slate-500 hover:bg-slate-100 dark:hover:bg-slate-700">
                &laquo;
              </button>
              <button @click="ratePage--" :disabled="ratePage === 1"
                class="px-2 py-1 rounded-lg text-xs font-bold transition-colors disabled:opacity-30 disabled:cursor-not-allowed text-slate-500 hover:bg-slate-100 dark:hover:bg-slate-700">
                &lsaquo;
              </button>
              <span class="text-xs font-bold text-slate-500 dark:text-slate-400 px-2 tabular-nums">
                {{ ratePage }} / {{ rateTotalPages }}
              </span>
              <button @click="ratePage++" :disabled="ratePage === rateTotalPages"
                class="px-2 py-1 rounded-lg text-xs font-bold transition-colors disabled:opacity-30 disabled:cursor-not-allowed text-slate-500 hover:bg-slate-100 dark:hover:bg-slate-700">
                &rsaquo;
              </button>
              <button @click="ratePage = rateTotalPages" :disabled="ratePage === rateTotalPages"
                class="px-2 py-1 rounded-lg text-xs font-bold transition-colors disabled:opacity-30 disabled:cursor-not-allowed text-slate-500 hover:bg-slate-100 dark:hover:bg-slate-700">
                &raquo;
              </button>
            </div>
          </div>
        </div>
        <!-- Simulation tab (admin only) -->
        <div v-if="viewMode === 'simulation' && isAdmin">
          <!-- Draft changes summary -->
          <div v-if="draftDiffChanges.length > 0" class="mb-4 space-y-2">
            <div v-if="promotionChanges.length > 0" class="p-3 bg-emerald-50 dark:bg-emerald-900/20 border border-emerald-200 dark:border-emerald-800/50 rounded-xl">
              <p class="text-xs font-bold text-emerald-700 dark:text-emerald-400 mb-2">▲ 昇格 ({{ promotionChanges.length }}件)</p>
              <div class="flex flex-wrap gap-1.5">
                <span v-for="c in promotionChanges" :key="c.title"
                  class="text-[11px] px-2 py-0.5 bg-white dark:bg-slate-800 border border-emerald-200 dark:border-emerald-700 rounded-full text-slate-700 dark:text-slate-300">
                  {{ c.title.length > 20 ? c.title.slice(0, 18) + '…' : c.title }}
                  <span class="line-through text-slate-400">{{ c.oldRank }}</span>→<span class="font-bold text-emerald-600 dark:text-emerald-400">{{ c.newRank }}</span>
                </span>
              </div>
            </div>
            <div v-if="demotionChanges.length > 0" class="p-3 bg-red-50 dark:bg-red-900/20 border border-red-200 dark:border-red-800/50 rounded-xl">
              <p class="text-xs font-bold text-red-700 dark:text-red-400 mb-2">▼ 降格 ({{ demotionChanges.length }}件)</p>
              <div class="flex flex-wrap gap-1.5">
                <span v-for="c in demotionChanges" :key="c.title"
                  class="text-[11px] px-2 py-0.5 bg-white dark:bg-slate-800 border border-red-200 dark:border-red-700 rounded-full text-slate-700 dark:text-slate-300">
                  {{ c.title.length > 20 ? c.title.slice(0, 18) + '…' : c.title }}
                  <span class="line-through text-slate-400">{{ c.oldRank }}</span>→<span class="font-bold text-red-600 dark:text-red-400">{{ c.newRank }}</span>
                </span>
              </div>
            </div>
          </div>
          <div v-else-if="!isSimulationLoading && !simulationError" class="mb-4 p-3 bg-slate-50 dark:bg-slate-800/50 border border-slate-200 dark:border-slate-700 rounded-xl text-xs text-slate-500 dark:text-slate-400">
            難易度表のドラフト変更がありません。
          </div>

          <div v-if="isSimulationLoading" class="flex flex-col items-center justify-center py-20">
            <div class="w-12 h-12 border-4 border-amber-100 dark:border-slate-700 border-t-amber-500 rounded-full animate-spin mb-4"></div>
            <p class="text-slate-500 dark:text-slate-400 font-bold">シミュレーション計算中...</p>
          </div>
          <div v-else-if="simulationError" class="p-6 bg-red-50 dark:bg-red-900/20 text-red-600 dark:text-red-400 rounded-2xl text-center font-bold">
            {{ simulationError }}
          </div>
          <div v-else class="overflow-x-auto">
            <table class="w-full text-sm">
              <thead>
                <tr class="text-left border-b border-slate-100 dark:border-slate-700/50">
                  <th class="pb-3 pl-4 text-xs font-black text-slate-400 uppercase tracking-widest w-24">順位変動</th>
                  <th class="pb-3 text-xs font-black text-slate-400 uppercase tracking-widest">プレイヤー</th>
                  <th class="pb-3 text-xs font-black text-slate-400 uppercase tracking-widest text-right">現在 BEAT-PT</th>
                  <th class="pb-3 text-xs font-black text-amber-500 uppercase tracking-widest text-right pr-4">適用後 BEAT-PT</th>
                </tr>
              </thead>
              <tbody class="divide-y divide-slate-50 dark:divide-slate-700/30">
                <tr v-for="entry in simulationData" :key="entry.iidxId"
                  class="group transition-colors hover:bg-slate-50 dark:hover:bg-slate-700/30">
                  <td class="py-3 pl-4">
                    <div class="flex items-center gap-2">
                      <div class="flex items-center justify-center w-7 h-7 rounded-lg font-black text-xs"
                        :class="entry.iidxId === user?.iidxId
                          ? 'bg-blue-500 text-white'
                          : 'text-slate-400 border border-slate-100 dark:border-slate-700'">
                        {{ entry.simulatedRank }}
                      </div>
                      <span v-if="entry.rankDelta > 0" class="text-[10px] font-bold text-emerald-500">▲{{ entry.rankDelta }}</span>
                      <span v-else-if="entry.rankDelta < 0" class="text-[10px] font-bold text-red-500">▼{{ Math.abs(entry.rankDelta) }}</span>
                      <span v-else class="text-[10px] font-bold text-slate-300 dark:text-slate-600">-</span>
                    </div>
                  </td>
                  <td class="py-3">
                    <div class="flex items-center gap-2">
                      <span class="font-bold text-sm text-slate-800 dark:text-slate-100">{{ entry.displayName || 'Unnamed' }}</span>
                      <span v-if="entry.iidxId === user?.iidxId"
                        class="text-[9px] font-black uppercase tracking-wider px-1.5 py-0.5 rounded bg-blue-500 text-white">YOU</span>
                    </div>
                    <div class="text-[10px] text-slate-400 mt-0.5">{{ entry.iidxId }} / 現在{{ entry.currentRank }}位</div>
                  </td>
                  <td class="py-3 text-right">
                    <span class="text-base font-bold tabular-nums text-slate-500 dark:text-slate-400">
                      {{ entry.currentBeatPt.toLocaleString(undefined, { minimumFractionDigits: 1, maximumFractionDigits: 1 }) }}
                    </span>
                  </td>
                  <td class="py-3 text-right pr-4">
                    <div class="flex flex-col items-end">
                      <span class="text-base font-black tabular-nums text-amber-600 dark:text-amber-400">
                        {{ entry.simulatedBeatPt.toLocaleString(undefined, { minimumFractionDigits: 1, maximumFractionDigits: 1 }) }}
                      </span>
                      <span class="text-[10px] font-bold tabular-nums"
                        :class="entry.ptDelta > 0 ? 'text-emerald-500' : entry.ptDelta < 0 ? 'text-red-500' : 'text-slate-400'">
                        {{ entry.ptDelta > 0 ? '+' : '' }}{{ entry.ptDelta.toLocaleString(undefined, { minimumFractionDigits: 1, maximumFractionDigits: 1 }) }}
                      </span>
                    </div>
                  </td>
                </tr>
              </tbody>
            </table>
          </div>
        </div>
      </template>

    </div>
  </div>
</template>

<style scoped>
.animate-fade-in {
  animation: fadeIn 0.4s ease-out forwards;
}

@keyframes fadeIn {
  from { opacity: 0; transform: translateY(10px); }
  to { opacity: 1; transform: translateY(0); }
}
</style>
