<script setup lang="ts">
/**
 * SongAverageView.vue
 *
 * 【Viewの役割】
 * 各 BEAT-Tier（Legend〜Beginner の12階層）ごとに算出した
 * 「曲別の平均スコア」を一覧化して表示する。
 * 自分のベストスコアと比較して「勝っている/負けている」フィルタも可能。
 *
 * 【主な機能】
 * - 曲ごと × Tier 別の平均スコア＋達成率を表形式で表示
 * - Tier の "小分類"（同じ Tier 内の1〜5）へのドリルダウン
 * - Lv11/Lv12、検索、勝敗フィルタ、カラムソート、ページネーション対応
 */
import { ref, computed, watch, onMounted } from 'vue';
import { useI18n } from '../composables/useI18n';
import { useAuth } from '../composables/useAuth';
import { songData as songDataBody, getDifficultyCode } from '../composables/useGameData';
import RankIcon from '../components/RankIcon.vue';
import { getRankInfo } from '../utils/beatTier';

const { t } = useI18n();
const { user, authHeaders } = useAuth();
const API_BASE = import.meta.env.VITE_API_BASE ?? 'http://localhost:8080';

// BEAT-Tier の順序（上位が先頭）。列の並びや「有効 Tier」判定に使用。
const TIER_ORDER = [
  'Legend', 'Mythic', 'Ancient', 'Master', 'Elite',
  'Commander', 'Veteran', 'Expert', 'Advanced', 'Intermediate', 'Novice', 'Beginner',
] as const;
type BeatTierName = typeof TIER_ORDER[number];

// サブ Tier（同じ BEAT-Tier の 1〜5 段階）単位の平均
interface SubTierEntry {
  avgScore: number;
  avgRate: number;
  userCount: number;
}

// 大分類（例: Master 全体）の平均エントリ。内部に 1〜5 の subTiers を保持
interface AverageEntry {
  avgScore: number;
  avgRate: number;
  userCount: number;
  subTiers: Record<number, SubTierEntry>;
}

// 表示用の曲1行ぶんのデータ
interface SongRow {
  title: string;
  difficultyName: 'ANOTHER' | 'LEGGENDARIA';
  difficultyLevel: number;
  averages: Partial<Record<BeatTierName, AverageEntry>>;
  maxScore: number; // この譜面のMAXスコア（notes×2）
}

// サーバから生データとして受け取る配列。tierData は JSON 文字列で保存されている
const rawData = ref<{
  title: string; difficultyName: string; difficultyLevel: number;
  tierData: string;
}[]>([]);
// キー: "曲名_ANOTHER" または "曲名_LEGGENDARIA" → 自分のベストスコア
const myScoresMap = ref<Map<string, number>>(new Map());
const isLoading = ref(true);
const error = ref('');

// Tier 内の1〜5 にドリルダウン中の Tier 名（null の時は大分類表示）
const detailTier = ref<BeatTierName | null>(null);

// 検索・レベル表示の絞り込み
const searchQuery = ref('');
const showLv11 = ref(true);
const showLv12 = ref(true);

// 勝敗比較フィルタ（選択中の値。「適用」ボタンを押すまでは反映されない）
const filterTier = ref<BeatTierName | ''>('');
const filterWinning = ref(false);
const filterLosing = ref(false);
// 実際に反映されているフィルタの state（Apply 時にコピーされる）
const appliedFilterTier = ref<BeatTierName | ''>('');
const appliedFilterWinning = ref(false);
const appliedFilterLosing = ref(false);

/**
 * 勝敗フィルタを「適用」する。選択中の値を applied* 変数にコピー。
 * ページを1ページ目にリセット。
 */
function applyFilter() {
  appliedFilterTier.value = filterTier.value;
  appliedFilterWinning.value = filterWinning.value;
  appliedFilterLosing.value = filterLosing.value;
  currentPage.value = 1;
}

// ソート設定（level/title/Tier名 または "Tier名-サブレベル" 形式）
const sortKey = ref<'level' | 'title' | BeatTierName | string>('level');
const sortDir = ref<'asc' | 'desc'>('desc');
const currentPage = ref(1);
const PAGE_SIZE = 50;

// ── ノーツ数→MAXスコア の検索用 Map ────────────────────────
// songDataBody から "タイトル_難易度コード" → MAXスコア（notes×2）の Map を構築
const notesMap = computed(() => {
  const map = new Map<string, number>();
  if (songDataBody.value) {
    for (const s of songDataBody.value) {
      if (s.notes) map.set(`${s.title}_${s.difficulty}`, s.notes * 2);
    }
  }
  return map;
});

/**
 * 難易度名（ANOTHER/LEGGENDARIA）からMAXスコアを取得する。
 * 難易度名 → コードの変換は useGameData の getDifficultyCode に集約済み。
 * @param title 曲名
 * @param difficultyName "ANOTHER" または "LEGGENDARIA"
 */
function getMaxScore(title: string, difficultyName: string): number {
  // song_data.json 側の difficulty コード: ANOTHER=4, LEGGENDARIA=10
  const code = getDifficultyCode(difficultyName);
  if (code === undefined) return 0;
  return notesMap.value.get(`${title}_${code}`) ?? 0;
}

// ── 生データから表示用 SongRow[] を構築 ──────────────────────
/**
 * rawData を正規化して表示用の配列に変換する computed。
 * 手順1: 曲 × 難易度ごとに Map を作成（MAXスコア不明な曲はスキップ）
 * 手順2: tierData JSON を parse し、大分類の下に小分類(サブTier)を格納
 * 手順3: 最後に小分類の加重平均から大分類の avgScore/avgRate/userCount を算出
 */
const rows = computed<SongRow[]>(() => {
  const songMap = new Map<string, SongRow>();

  for (const entry of rawData.value) {
    const songKey = `${entry.title}_${entry.difficultyName}`;
    // 曲の初期化（未登録なら MAX を調べて登録。MAX不明な曲はスキップ）
    if (!songMap.has(songKey)) {
      const maxScore = getMaxScore(entry.title, entry.difficultyName);
      if (maxScore === 0) continue;
      songMap.set(songKey, {
        title: entry.title,
        difficultyName: entry.difficultyName as 'ANOTHER' | 'LEGGENDARIA',
        difficultyLevel: entry.difficultyLevel,
        averages: {},
        maxScore,
      });
    }
    const row = songMap.get(songKey)!;

    // tierData は JSON 文字列なので parse。壊れていても無視して続行
    let tiersArr: any[] = [];
    try {
      if (entry.tierData) tiersArr = JSON.parse(entry.tierData);
    } catch { /* IGNORE */ }

    for (const tData of tiersArr) {
      const tier = tData.beatTier as BeatTierName;
      if (!TIER_ORDER.includes(tier)) continue; // 未知の Tier 名は無視

      // 大分類エントリを未生成なら作成
      if (!row.averages[tier]) {
        row.averages[tier] = { avgScore: 0, avgRate: 0, userCount: 0, subTiers: {} };
      }

      // サブ Tier (1〜5) の平均スコア・達成率・ユーザ数を格納
      const level = Number(tData.tierLevel || 0);
      const avgScore = Math.round(Number(tData.avgScore));
      row.averages[tier].subTiers[level] = {
        avgScore,
        avgRate: (avgScore / row.maxScore) * 100,
        userCount: Math.round(Number(tData.userCount)),
      };
    }
  }

  // 大分類の「加重平均」を算出（サブTierごとの userCount で重み付け）
  for (const row of songMap.values()) {
    for (const tier of Object.keys(row.averages) as BeatTierName[]) {
      const avg = row.averages[tier]!;
      let sumScore = 0;
      let totalCount = 0;
      for (const sub of Object.values(avg.subTiers)) {
        sumScore += sub.avgScore * sub.userCount;
        totalCount += sub.userCount;
      }
      if (totalCount > 0) {
        avg.avgScore = Math.round(sumScore / totalCount);
        avg.userCount = totalCount;
        avg.avgRate = (avg.avgScore / row.maxScore) * 100;
      }
    }
  }

  return Array.from(songMap.values());
});

// 実際にデータが存在する Tier の一覧（TIER_ORDER と同じ順序で返す）
const activeTiers = computed<BeatTierName[]>(() => {
  const present = new Set<BeatTierName>();
  for (const row of rows.value) {
    for (const tier of TIER_ORDER) {
      if (row.averages[tier]) present.add(tier);
    }
  }
  return TIER_ORDER.filter(t => present.has(t));
});

// 表の列定義: 大分類（broad）か小分類（sub）かの判別 + その Tier/レベル
type ColumnDef = { type: 'broad', tier: BeatTierName } | { type: 'sub', tier: BeatTierName, level: number };

/**
 * 現在表示すべき列の一覧。
 * detailTier が指定されていれば、その Tier の 5→4→3→2→1 の小分類列を返す。
 * そうでなければ、大分類の Tier 一覧を返す。
 */
const activeColumns = computed<ColumnDef[]>(() => {
  if (detailTier.value) {
    return [5, 4, 3, 2, 1].map(l => ({ type: 'sub', tier: detailTier.value!, level: l }));
  }
  return activeTiers.value.map(t => ({ type: 'broad', tier: t }));
});

/**
 * 検索・レベル・勝敗フィルタを適用し、ソートした行を返す computed。
 * 手順1: Lv11/Lv12 表示トグルでフィルタ
 * 手順2: タイトル部分一致検索（小文字化して比較）
 * 手順3: 勝敗フィルタ（選択中 Tier の平均 vs 自分ベスト。ユーザ数<3 の Tier は母数不足で除外）
 * 手順4: sortKey/sortDir に従ってソート。"Master-5" のような形式はサブTierソート
 * 達成率が無効（ユーザ数<3）な値は -1 として並べ、末尾へ送る
 */
const filteredRows = computed<SongRow[]>(() => {
  let result = rows.value;
  // レベルでフィルタ
  result = result.filter(r =>
    (r.difficultyLevel === 11 && showLv11.value) ||
    (r.difficultyLevel === 12 && showLv12.value)
  );
  // タイトル検索（部分一致、大文字小文字無視）
  if (searchQuery.value.trim()) {
    const q = searchQuery.value.trim().toLowerCase();
    result = result.filter(r => r.title.toLowerCase().includes(q));
  }
  // 勝敗フィルタ（適用ボタンを押した時だけ有効）
  if (appliedFilterTier.value && (appliedFilterWinning.value || appliedFilterLosing.value)) {
    const tier = appliedFilterTier.value as BeatTierName;
    result = result.filter(r => {
      const avg = r.averages[tier];
      if (!avg || avg.userCount < 3) return false; // 母数3未満は統計的に弱いので除外
      const myScore = myScoresMap.value.get(`${r.title}_${r.difficultyName}`);
      if (!myScore) return true; // 未プレイ曲は常に表示（灰色で表示）
      if (appliedFilterWinning.value && appliedFilterLosing.value) return true; // 両方チェック = フィルタ無し相当
      if (appliedFilterWinning.value) return myScore > avg.avgScore;
      if (appliedFilterLosing.value) return myScore < avg.avgScore;
      return false;
    });
  }
  // 配列コピーしてからソート（元の rows を破壊しないため）
  return [...result].sort((a, b) => {
    // タイトル列は日本語ロケールで文字列比較
    if (sortKey.value === 'title') {
      const cmp = a.title.localeCompare(b.title, 'ja');
      return sortDir.value === 'asc' ? cmp : -cmp;
    }
    let va: number, vb: number;
    if (sortKey.value === 'level') {
      va = a.difficultyLevel;
      vb = b.difficultyLevel;
    } else if (typeof sortKey.value === 'string' && sortKey.value.includes('-')) {
      // 例: "Master-5" → サブTier(Master の 5)の達成率でソート
      const [tName, tLevel] = sortKey.value.split('-');
      const ak = a.averages[tName as BeatTierName]?.subTiers[Number(tLevel)];
      const bk = b.averages[tName as BeatTierName]?.subTiers[Number(tLevel)];
      va = (ak && ak.userCount >= 3) ? ak.avgRate : -1;
      vb = (bk && bk.userCount >= 3) ? bk.avgRate : -1;
    } else {
      // 大分類 Tier 単位でソート
      const ak = a.averages[sortKey.value as BeatTierName];
      const bk = b.averages[sortKey.value as BeatTierName];
      va = (ak && ak.userCount >= 3) ? ak.avgRate : -1;
      vb = (bk && bk.userCount >= 3) ? bk.avgRate : -1;
    }
    return sortDir.value === 'asc' ? va - vb : vb - va;
  });
});

// 総ページ数（少なくとも1ページ）
const totalPages = computed(() => Math.max(1, Math.ceil(filteredRows.value.length / PAGE_SIZE)));

// 現在ページ分だけスライスした行
const pagedRows = computed(() =>
  filteredRows.value.slice((currentPage.value - 1) * PAGE_SIZE, currentPage.value * PAGE_SIZE)
);

/**
 * 列ヘッダクリック時にソート方向をトグル / 別列ならソートキーを切替。
 * 同じ列 → desc ↔ asc 反転、別の列 → desc に初期化。
 * @param col ソート対象の列キー
 */
function toggleSort(col: typeof sortKey.value) {
  if (sortKey.value === col) {
    sortDir.value = sortDir.value === 'desc' ? 'asc' : 'desc';
  } else {
    sortKey.value = col;
    sortDir.value = 'desc';
  }
  currentPage.value = 1;
}

// ── 表示用のヘルパー ────────────────────────────────────────
/**
 * 達成率(%)に応じた色クラス。MAX-/AAA/AA/A の閾値で色分け。
 * データ無し (0) は極薄グレーで "—" を表示するための色
 */
function rateColorClass(rate: number): string {
  if (rate >= 94.44) return 'text-purple-500 dark:text-purple-400';  // MAX- 以上
  if (rate >= 88.88) return 'text-yellow-500 dark:text-yellow-400';  // AAA 以上
  if (rate >= 77.77) return 'text-blue-500 dark:text-blue-400';      // AA 以上
  if (rate >= 66.66) return 'text-emerald-500 dark:text-emerald-400';// A 以上
  if (rate > 0)      return 'text-slate-500 dark:text-slate-400';
  return 'text-slate-200 dark:text-slate-700';
}

/**
 * 平均と自分ベストの比較による色クラス。
 * 自分のスコアが平均より高い = 緑、低い = 赤、未プレイ or 同点 = 灰。
 */
function comparisonClass(avgScore: number, rowTitle: string, rowDiff: string): string {
  const myScore = myScoresMap.value.get(`${rowTitle}_${rowDiff}`);
  if (!myScore) return 'text-slate-400 dark:text-slate-500';
  if (myScore > avgScore) return 'text-emerald-500 dark:text-emerald-400 font-bold';
  if (myScore < avgScore) return 'text-red-500 dark:text-red-400 font-bold';
  return 'text-slate-400 dark:text-slate-500';
}

/**
 * Tier 名から、その Tier に対応する Rate-Tier の代表色を返す。
 * 各 Tier の代表レート値から getRankInfo の色を取得し、font-bold 類は除去。
 */
function tierColor(tier: BeatTierName): string {
  const map: Record<BeatTierName, number> = {
    Legend: 18000, Mythic: 17500, Ancient: 17000, Master: 16500,
    Elite: 16000, Commander: 15500, Veteran: 15000, Expert: 14000,
    Advanced: 13000, Intermediate: 12000, Novice: 10000, Beginner: 0,
  };
  return getRankInfo(map[tier]).color.replace(' font-black', '').replace(' font-bold', '');
}

// 絞り込み条件やドリルダウンが変わったら必ず1ページ目に戻す
watch([searchQuery, showLv11, showLv12, detailTier], () => { currentPage.value = 1; });

// ── データ取得 ──────────────────────────────────────────────
/**
 * 平均データと自分のベストスコアを並列フェッチする。
 * 手順1: /api/scores/song-arena-averages で公開の Tier 別平均を取得
 * 手順2: ログイン中なら /api/scores/me で自分のスコアも取得し、
 *        "タイトル_難易度" → ベストスコア の Map を作成
 */
async function loadData() {
  isLoading.value = true;
  error.value = '';
  try {
    const fetches: Promise<void>[] = [
      // 公開データ: Tier別の平均
      fetch(`${API_BASE}/api/scores/song-arena-averages`)
        .then(r => { if (!r.ok) throw new Error('データの取得に失敗しました'); return r.json(); })
        .then(d => { rawData.value = d; }),
    ];
    if (user.value) {
      // ログイン時のみ自分のベストスコアも取得
      fetches.push(
        fetch(`${API_BASE}/api/scores/me`, { headers: authHeaders() })
          .then(r => r.ok ? r.json() : [])
          .then((flat: { title: string; difficultyName: string; score: number }[]) => {
            // 同じ譜面でも複数プレイ履歴があるため、最大値のみ保持
            const map = new Map<string, number>();
            for (const s of flat) {
              const k = `${s.title}_${s.difficultyName}`;
              if ((s.score ?? 0) > (map.get(k) ?? 0)) map.set(k, s.score);
            }
            myScoresMap.value = map;
          })
      );
    }
    await Promise.all(fetches);
  } catch (e: any) {
    error.value = e.message ?? 'データ取得エラー';
  } finally {
    isLoading.value = false;
  }
}

// マウント時に1度だけデータを取得
onMounted(loadData);
</script>

<template>
  <div class="w-full max-w-full space-y-6 animate-fade-in">
    <div class="bg-white dark:bg-slate-800 p-6 sm:p-8 rounded-3xl shadow-sm border border-slate-200 dark:border-slate-700 transition-colors">

      <!-- ページヘッダ: タイトルアイコン + 見出し + サブタイトル -->
      <div class="flex items-center gap-4 mb-6">
        <div class="p-3 rounded-2xl bg-indigo-100 dark:bg-indigo-900/30">
          <svg xmlns="http://www.w3.org/2000/svg" class="h-8 w-8 text-indigo-600 dark:text-indigo-400" fill="none" viewBox="0 0 24 24" stroke="currentColor">
            <path stroke-linecap="round" stroke-linejoin="round" stroke-width="2"
              d="M9 19v-6a2 2 0 00-2-2H5a2 2 0 00-2 2v6a2 2 0 002 2h2a2 2 0 002-2zm0 0V9a2 2 0 012-2h2a2 2 0 012 2v10m-6 0a2 2 0 002 2h2a2 2 0 002-2m0 0V5a2 2 0 012-2h2a2 2 0 012 2v14a2 2 0 01-2 2h-2a2 2 0 01-2-2z" />
          </svg>
        </div>
        <div>
          <h2 class="text-2xl font-black text-slate-800 dark:text-slate-100">{{ t('songAvg.title') }}</h2>
          <p class="text-slate-500 dark:text-slate-400 font-medium text-sm">{{ t('songAvg.subtitle') }}</p>
        </div>
      </div>

      <!-- 検索ボックス + レベルトグル + 勝敗フィルタ + 色凡例 -->
      <div class="flex flex-wrap items-center gap-3 mb-5">
        <input
          v-model="searchQuery"
          type="text"
          :placeholder="t('songAvg.searchPlaceholder')"
          class="w-64 px-4 py-2 rounded-xl border border-slate-200 dark:border-slate-600 bg-white dark:bg-slate-700 text-slate-800 dark:text-slate-100 text-sm placeholder:text-slate-400 focus:outline-none focus:ring-2 focus:ring-indigo-400"
        />
        <label class="flex items-center gap-1.5 cursor-pointer text-sm font-bold text-slate-600 dark:text-slate-300 select-none">
          <input type="checkbox" v-model="showLv11" class="w-4 h-4 rounded accent-indigo-500" />
          ☆11
        </label>
        <label class="flex items-center gap-1.5 cursor-pointer text-sm font-bold text-slate-600 dark:text-slate-300 select-none">
          <input type="checkbox" v-model="showLv12" class="w-4 h-4 rounded accent-indigo-500" />
          ☆12
        </label>

        <!-- 勝敗比較フィルタ（ログイン時のみ表示。未ログインだと自分のベストが無いので意味が無い） -->
        <template v-if="user">
          <div class="flex items-center gap-2 flex-wrap">
            <select
              v-model="filterTier"
              class="px-3 py-2 rounded-xl border border-slate-200 dark:border-slate-600 bg-white dark:bg-slate-700 text-slate-800 dark:text-slate-100 text-sm focus:outline-none focus:ring-2 focus:ring-indigo-400"
            >
              <option value="">ティアを選択</option>
              <option v-for="tier in activeTiers" :key="tier" :value="tier">{{ tier }}</option>
            </select>
            <label class="flex items-center gap-1.5 cursor-pointer text-sm font-bold text-emerald-600 dark:text-emerald-400 select-none">
              <input type="checkbox" v-model="filterWinning" class="w-4 h-4 rounded accent-emerald-500" />
              勝っている
            </label>
            <label class="flex items-center gap-1.5 cursor-pointer text-sm font-bold text-red-500 dark:text-red-400 select-none">
              <input type="checkbox" v-model="filterLosing" class="w-4 h-4 rounded accent-red-500" />
              負けている
            </label>
            <button
              @click="applyFilter"
              class="px-4 py-2 rounded-xl bg-indigo-500 hover:bg-indigo-600 text-white text-sm font-bold transition-colors"
            >適用</button>
            <button
              v-if="appliedFilterTier"
              @click="filterTier = ''; filterWinning = false; filterLosing = false; applyFilter()"
              class="px-3 py-2 rounded-xl border border-slate-200 dark:border-slate-600 text-slate-500 dark:text-slate-400 text-sm font-bold hover:bg-slate-100 dark:hover:bg-slate-700 transition-colors"
            >クリア</button>
          </div>
        </template>
        <div class="flex flex-wrap gap-x-3 gap-y-1 text-[11px] font-bold">
          <span class="text-purple-500">MAX- ≥94.44%</span>
          <span class="text-yellow-500">AAA ≥88.88%</span>
          <span class="text-blue-500">AA ≥77.77%</span>
          <span class="text-emerald-500">A ≥66.66%</span>
        </div>
      </div>

      <!-- ローディング / エラー 表示分岐 -->
      <div v-if="isLoading" class="flex flex-col items-center justify-center py-20">
        <div class="w-12 h-12 border-4 border-indigo-100 dark:border-slate-700 border-t-indigo-600 dark:border-t-indigo-500 rounded-full animate-spin mb-4"></div>
        <p class="text-slate-500 dark:text-slate-400 font-bold">{{ t('songAvg.loading') }}</p>
      </div>
      <div v-else-if="error" class="p-6 bg-red-50 dark:bg-red-900/20 text-red-600 dark:text-red-400 rounded-2xl text-center font-bold">
        {{ error }}
      </div>

      <!-- メインの平均スコア比較テーブル -->
      <div v-else class="overflow-x-auto">
        <p class="text-xs text-slate-400 dark:text-slate-500 mb-2">
          {{ filteredRows.length }} {{ t('songAvg.songs') }}
          &nbsp;({{ (currentPage - 1) * PAGE_SIZE + 1 }}–{{ Math.min(currentPage * PAGE_SIZE, filteredRows.length) }})
        </p>
        <div v-if="detailTier" class="mb-4 flex items-center justify-between pb-2 border-b border-indigo-100 dark:border-indigo-900/50">
          <div class="flex items-center gap-3">
            <button @click="detailTier = null" class="flex items-center gap-1.5 px-3 py-1.5 rounded-lg bg-slate-100 hover:bg-slate-200 dark:bg-slate-700 dark:hover:bg-slate-600 text-slate-600 dark:text-slate-300 font-bold transition-colors text-xs sm:text-sm">
              <svg xmlns="http://www.w3.org/2000/svg" class="h-4 w-4" fill="none" viewBox="0 0 24 24" stroke="currentColor"><path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M10 19l-7-7m0 0l7-7m-7 7h18" /></svg>
              {{ t('common.back') || '戻る' }}
            </button>
            <span class="font-black text-slate-700 dark:text-slate-200" :class="tierColor(detailTier!)">
              {{ detailTier }}の詳細
            </span>
          </div>
        </div>

        <table class="text-sm border-collapse" style="min-width: max-content">
          <thead>
            <tr class="border-b border-slate-100 dark:border-slate-700/50">
              <th
                class="pb-3 pl-2 pr-1 text-left text-xs font-black text-slate-400 uppercase tracking-widest cursor-pointer hover:text-slate-600 whitespace-nowrap"
                @click="toggleSort('level')"
              >☆ <span v-if="sortKey === 'level'">{{ sortDir === 'desc' ? '▼' : '▲' }}</span></th>
              <th
                class="pb-3 pr-6 text-left text-xs font-black text-slate-400 uppercase tracking-widest cursor-pointer hover:text-slate-600"
                @click="toggleSort('title')"
              >{{ t('songAvg.colSong') }} <span v-if="sortKey === 'title'">{{ sortDir === 'desc' ? '▼' : '▲' }}</span></th>
              
              <th
                v-for="col in activeColumns" :key="col.type === 'broad' ? col.tier : col.tier + '-' + col.level"
                class="pb-3 px-2 text-center text-xs font-black uppercase tracking-widest whitespace-nowrap align-top"
                :class="[tierColor(col.tier)]"
              >
                <!-- 大分類カラム -->
                <div v-if="col.type === 'broad'" class="relative flex flex-col items-center gap-0.5 group/header rounded-xl p-1 transition-colors" :class="{'cursor-pointer hover:bg-slate-50 dark:hover:bg-slate-700/50': true}" @click="toggleSort(col.tier)">
                  <RankIcon :rank-name="col.tier" size="sm" disable-party />
                  <span class="mt-1">{{ col.tier }}</span>
                  <span v-if="sortKey === col.tier" class="text-[10px]">{{ sortDir === 'desc' ? '▼' : '▲' }}</span>
                  
                  <button 
                    v-if="col.tier !== 'Legend' && col.tier !== 'Beginner'"
                    @click.stop="detailTier = col.tier"
                    class="absolute top-0 right-0 p-1 rounded-full bg-white dark:bg-slate-700 shadow-sm border border-slate-200 dark:border-slate-600 opacity-0 group-hover/header:opacity-100 transition-opacity hover:scale-110 hover:text-indigo-500"
                    title="1〜5ごとの平均スコアを見る"
                  >
                    <svg xmlns="http://www.w3.org/2000/svg" class="h-3 w-3" fill="none" viewBox="0 0 24 24" stroke="currentColor"><path stroke-linecap="round" stroke-linejoin="round" stroke-width="2.5" d="M21 21l-6-6m2-5a7 7 0 11-14 0 7 7 0 0114 0zM10 7v3m0 0v3m0-3h3m-3 0H7" /></svg>
                  </button>
                </div>

                <!-- 小分類（ドリルダウン）カラム -->
                <div v-else class="flex flex-col items-center gap-0.5 cursor-pointer hover:opacity-80 p-1" @click="toggleSort(`${col.tier}-${col.level}`)">
                  <RankIcon :rank-name="col.tier" :tier="col.level" size="sm" disable-party />
                  <span class="mt-1">{{ col.tier }} {{ col.level }}</span>
                  <span v-if="sortKey === `${col.tier}-${col.level}`" class="text-[10px]">{{ sortDir === 'desc' ? '▼' : '▲' }}</span>
                </div>
              </th>
            </tr>
          </thead>
          <tbody class="divide-y divide-slate-50 dark:divide-slate-700/30">
            <tr
              v-for="row in pagedRows"
              :key="`${row.title}_${row.difficultyName}`"
              class="group hover:bg-slate-50 dark:hover:bg-slate-700/30 transition-colors"
            >
              <!-- 左端: ☆レベル + 難易度（ANOTHER=A/LEGGENDARIA=L）バッジ -->
              <td class="py-2 pl-2 pr-1 whitespace-nowrap">
                <div class="flex flex-col items-start gap-0.5">
                  <span class="text-xs font-black text-slate-500 dark:text-slate-400">☆{{ row.difficultyLevel }}</span>
                  <span
                    class="text-[9px] font-black px-1 py-0.5 rounded leading-none"
                    :class="row.difficultyName === 'LEGGENDARIA'
                      ? 'bg-purple-100 dark:bg-purple-900/30 text-purple-600 dark:text-purple-400'
                      : 'bg-red-100 dark:bg-red-900/30 text-red-600 dark:text-red-400'"
                  >{{ row.difficultyName === 'LEGGENDARIA' ? 'L' : 'A' }}</span>
                </div>
              </td>
              <!-- 曲名（長い場合は省略記号で1行表示） -->
              <td class="py-2 pr-6 max-w-[200px]">
                <span class="font-semibold text-slate-800 dark:text-slate-100 text-sm leading-tight line-clamp-1 block" :title="row.title">
                  {{ row.title }}
                </span>
              </td>
              <!-- Tier別 平均スコア + 達成率 + 自分との勝敗色 -->
              <td v-for="col in activeColumns" :key="col.type === 'broad' ? col.tier : col.tier + '-' + col.level" class="py-2 px-2 text-center">
                
                <template v-if="col.type === 'broad'">
                  <template v-if="row.averages[col.tier] && row.averages[col.tier]!.userCount >= 3">
                    <div class="font-bold tabular-nums" :class="rateColorClass(row.averages[col.tier]!.avgRate)">
                      {{ row.averages[col.tier]!.avgScore.toLocaleString() }}
                    </div>
                    <div class="text-[10px] tabular-nums" :class="comparisonClass(row.averages[col.tier]!.avgScore, row.title, row.difficultyName)">
                      {{ row.averages[col.tier]!.avgRate.toFixed(1) }}%
                    </div>
                  </template>
                  <span v-else class="text-slate-200 dark:text-slate-700 text-xs">—</span>
                </template>

                <template v-else>
                  <template v-if="row.averages[col.tier] && row.averages[col.tier]!.subTiers[col.level] && row.averages[col.tier]!.subTiers[col.level].userCount >= 3">
                    <div class="font-bold tabular-nums" :class="rateColorClass(row.averages[col.tier]!.subTiers[col.level]!.avgRate)">
                      {{ row.averages[col.tier]!.subTiers[col.level]!.avgScore.toLocaleString() }}
                    </div>
                    <div class="text-[10px] tabular-nums" :class="comparisonClass(row.averages[col.tier]!.subTiers[col.level]!.avgScore, row.title, row.difficultyName)">
                      {{ row.averages[col.tier]!.subTiers[col.level]!.avgRate.toFixed(1) }}%
                    </div>
                  </template>
                  <span v-else class="text-slate-200 dark:text-slate-700 text-xs">—</span>
                </template>

              </td>
            </tr>
          </tbody>
        </table>
        <!-- ページネーション: 最初/前/ページ番号/次/最後 -->
        <div v-if="totalPages > 1" class="flex items-center justify-center gap-2 mt-6">
          <button
            @click="currentPage = 1"
            :disabled="currentPage === 1"
            class="px-2 py-1 rounded-lg text-xs font-bold disabled:opacity-30 text-slate-500 dark:text-slate-400 hover:bg-slate-100 dark:hover:bg-slate-700 transition-colors"
          >«</button>
          <button
            @click="currentPage--"
            :disabled="currentPage === 1"
            class="px-3 py-1 rounded-lg text-xs font-bold disabled:opacity-30 text-slate-500 dark:text-slate-400 hover:bg-slate-100 dark:hover:bg-slate-700 transition-colors"
          >‹</button>

          <template v-for="p in totalPages" :key="p">
            <button
              v-if="p === 1 || p === totalPages || Math.abs(p - currentPage) <= 2"
              @click="currentPage = p"
              class="w-8 h-8 rounded-lg text-xs font-bold transition-colors"
              :class="p === currentPage
                ? 'bg-indigo-500 text-white'
                : 'text-slate-500 dark:text-slate-400 hover:bg-slate-100 dark:hover:bg-slate-700'"
            >{{ p }}</button>
            <span
              v-else-if="p === currentPage - 3 || p === currentPage + 3"
              class="text-slate-300 dark:text-slate-600 text-xs"
            >…</span>
          </template>

          <button
            @click="currentPage++"
            :disabled="currentPage === totalPages"
            class="px-3 py-1 rounded-lg text-xs font-bold disabled:opacity-30 text-slate-500 dark:text-slate-400 hover:bg-slate-100 dark:hover:bg-slate-700 transition-colors"
          >›</button>
          <button
            @click="currentPage = totalPages"
            :disabled="currentPage === totalPages"
            class="px-2 py-1 rounded-lg text-xs font-bold disabled:opacity-30 text-slate-500 dark:text-slate-400 hover:bg-slate-100 dark:hover:bg-slate-700 transition-colors"
          >»</button>
        </div>
      </div>

    </div>
  </div>
</template>
