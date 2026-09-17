<script setup lang="ts">
/**
 * 【コンポーネントの役割】 非公式難易度表（☆11.0〜☆13.0）を折返し可能な行で一覧表示する。
 * - 難易度表に定義された全曲を、ユーザーのプレイ済みスコア / 未プレイのプレースホルダに分けて集約
 * - 各難易度ランクごとに平均スコアレート・獲得 Beat-PT を集計し、フォルダランク（A/AA/AAA 等）を算出
 * - 公式難易度（すべて / ☆11 / ☆12）で対象曲を絞り込み、集計もその範囲で再計算
 * - 展開/折りたたみで曲一覧を表示、情報モーダルとレート早見表モーダルを内包
 *
 * レイアウトは一覧性優先。フォルダ行にランク名と「次のランクまで」を出して開かなくても比較でき、
 * 開いた中は 1 曲 1 行（横幅があれば 2 段組）＋単曲ランク分布。列の出し分けは画面幅ではなく
 * このコンポーネント自身の幅で決める（<style> のコンテナクエリ）。
 *
 * @prop scores        表示・集計対象のスコアレコード配列。「歴代ベストを反映」トグル ON のときは
 *                     歴代ベスト適用済みのレコードが渡ってくる。
 * @prop historyScores 成長記録モーダル用の現行作スコア（省略時は scores を流用）。
 * @prop currentAllTimeBest 今作のスコアが歴代自己ベストの譜面。キーがある譜面は行を強調する
 *                     （トグルの ON/OFF に依らない）。値は並んだ／超えた過去作ベストで、
 *                     過去作にスコアが無い譜面（新曲の初スコアなど）は null。
 *                     過去作スコアが未取得・他ユーザー閲覧中は prop 自体が null（強調なし）。
 * @emits folder-open  フォルダが開かれた。親はこれを合図に過去作スコアを遅延取得する
 *                     （強調が見えるのは開いた中だけなので、開かれるまで取りに行かない）。
 */
import { computed, ref } from 'vue';
import { useI18n } from '../composables/useI18n';
import type { ScoreRecord } from '../utils/scoreData';
import type { RankInfo } from '../utils/beatTier';
import { getFolderRankInfoByRate, getNextFolderRankInfoByRate, getLegendPtPerSong, getFolderLegendRate, getFolderRankOffsetMax, FOLDER_RANK_DEFS, getMaxPoints } from '../utils/beatTier';
import { getScoreGradeInfo, tierLabel } from '../utils/uploadReport';
import { versionBadgeClass, versionName, versionShort } from '../utils/iidxVersions';
import { songData as songDataBodyRef, diffTable as diffTableRanksRef, getDifficultyCode } from '../composables/useGameData';
import { chartKey } from '../composables/usePastScores';
import type { PastBest } from '../composables/usePastScores';
import RankIcon from './RankIcon.vue';
import DifficultyRankingModal from './DifficultyRankingModal.vue';
import RankGrowthChartModal from './RankGrowthChartModal.vue';

const props = defineProps<{
  scores: ScoreRecord[];
  historyScores?: ScoreRecord[];
  currentAllTimeBest?: Map<string, PastBest | null> | null;
}>();

const emit = defineEmits<{ (e: 'folder-open'): void }>();

const { t } = useI18n();
/** 展開中のランクキー集合（Set でトグル）。 */
const expandedRanks = ref<Set<string>>(new Set());
/** 「フォルダランクとは？」ツールチップ表示フラグ。 */
const showInfo = ref(false);
/** レート早見表モーダル表示フラグ。 */
const showRateTable = ref(false);
/** 難易度別ランキングモーダルの対象難易度（null なら非表示）。 */
const rankingModalRank = ref<{ rank: string; totalCount: number } | null>(null);
/** 成長グラフモーダルの対象難易度（null なら非表示）。 */
const growthChartRank = ref<{ rank: string; songCount: number; currentTotalBeatPoints: number } | null>(null);

/** 公式難易度（☆11 / ☆12）での絞り込み。'all' は絞り込みなし。 */
type LevelFilter = 'all' | '11' | '12';
/** 現在選択中の公式難易度フィルタ。 */
const levelFilter = ref<LevelFilter>('all');
/** フィルタ切替ボタンの定義（☆11 / ☆12 のラベルは言語非依存）。 */
const levelFilterOptions = computed<{ value: LevelFilter; label: string }[]>(() => [
  { value: 'all', label: t('table.filterAll') },
  { value: '11', label: '☆11' },
  { value: '12', label: '☆12' },
]);

/** 開いたフォルダ内の曲の並び順（全フォルダ共通）。未プレイ曲はどの順でも末尾。 */
type SongSort = 'rateDesc' | 'rateAsc' | 'title';
const songSort = ref<SongSort>('rateDesc');
const songSortOptions = computed<{ value: SongSort; label: string }[]>(() => [
  { value: 'rateDesc', label: t('table.sortRateDesc') },
  { value: 'rateAsc', label: t('table.sortRateAsc') },
  { value: 'title', label: t('table.sortTitle') },
]);

/**
 * ランク名 → 文字色 / 帯の色。beatTier 側の color はライト専用でダーク背景に沈むため、
 * ここでダーク用を足した組を持つ。並びは上位 → 下位で、単曲ランク分布の表示順にも使う。
 */
const TIER_STYLE: Record<string, { text: string; bar: string }> = {
  Legend: { text: 'text-amber-500 dark:text-amber-400', bar: 'bg-amber-400' },
  Mythic: { text: 'text-purple-600 dark:text-purple-400', bar: 'bg-purple-500' },
  Ancient: { text: 'text-indigo-600 dark:text-indigo-400', bar: 'bg-indigo-500' },
  Master: { text: 'text-red-600 dark:text-red-400', bar: 'bg-red-500' },
  Elite: { text: 'text-orange-600 dark:text-orange-400', bar: 'bg-orange-500' },
  Commander: { text: 'text-yellow-700 dark:text-yellow-500', bar: 'bg-yellow-500' },
  Veteran: { text: 'text-emerald-600 dark:text-emerald-400', bar: 'bg-emerald-500' },
  Expert: { text: 'text-teal-600 dark:text-teal-400', bar: 'bg-teal-500' },
  Advanced: { text: 'text-cyan-600 dark:text-cyan-400', bar: 'bg-cyan-500' },
  Intermediate: { text: 'text-blue-600 dark:text-blue-400', bar: 'bg-blue-500' },
  Novice: { text: 'text-slate-600 dark:text-slate-300', bar: 'bg-slate-500' },
  Beginner: { text: 'text-slate-400 dark:text-slate-500', bar: 'bg-slate-400' },
};
const TIER_ORDER = Object.keys(TIER_STYLE);
const tierText = (name: string) => (TIER_STYLE[name] ?? TIER_STYLE.Beginner).text;
const tierBar = (name: string) => (TIER_STYLE[name] ?? TIER_STYLE.Beginner).bar;

/** スコアレートの文字色（MAX- 帯 = 紫 / AAA 帯 = 金）。それ未満は呼び出し側の基準色。 */
const rateColorClass = (rate: number, base: string) =>
  rate >= 94.45 ? 'text-purple-600 dark:text-purple-400' : rate >= 88.88 ? 'text-amber-500 dark:text-amber-400' : base;

/** 開いたフォルダ内の 1 曲 1 行ぶんの表示データ。 */
interface SongRow {
  key: string;
  song: ScoreRecord;
  /** 単曲ごとのランク（必要スコアレート表に対応）。未プレイは null。 */
  songRank: RankInfo | null;
  isLeggendaria: boolean;
  /**
   * 今作のスコアが歴代自己ベストの行か。強調表示の対象。
   * 過去作のベストに並んだ／超えた譜面に加えて、過去作にスコアが無い譜面（新曲の初スコアなど）も含む。
   */
  isAllTimeBest: boolean;
  /**
   * 「歴代ベストを反映」で過去作のスコアに置き換わった行なら、その作品番号（作品バッジに使う）。
   * 現行作のスコアがそのまま出ている行は null。
   */
  allTimeVersion: number | null;
  /** 行ホバーで出す補足（EX スコア / MAX-n / DJ LEVEL / 歴代ベストの内訳）。 */
  tooltip: string;
  /** この行の直前に「フォルダ平均」の区切りを入れるか（レート順のときだけ立つ）。 */
  avgBefore: boolean;
}

/** 単曲ランク分布の 1 区分。 */
interface TierSegment {
  name: string;
  label: string;
  count: number;
  pct: number;
  bar: string;
  text: string;
}

// ☆11.0 〜 ☆13.1 までの 0.1 刻みラベル配列を生成（レート早見表の列）。
const allFolders: string[] = [];
for (let i = 0; i <= 21; i++) allFolders.push((11.0 + i * 0.1).toFixed(1));

/**
 * 【computed の役割】 レート早見表の行データを生成する。
 * 各フォルダランク定義 × 各難易度ランクの組合せで「必要レート」を算出し、
 * 66.67% 以下は "-"、AAA/MAX-/MAX 帯域は色分けする。
 */
const rateTableRows = computed(() => {
  return FOLDER_RANK_DEFS.map(def => {
    const label = def.tier ? `${def.name} ${def.tier}` : def.name;
    const rates = allFolders.map(f => {
      const rate = getFolderLegendRate(f) - def.offset * getFolderRankOffsetMax(f);
      if (rate <= 66.666) return { text: '-', color: 'text-slate-400 dark:text-slate-500' };

      let rateColor = 'text-slate-600 dark:text-slate-300';
      if (rate >= 94.45) rateColor = 'text-purple-600 dark:text-purple-400 font-bold';
      else if (rate >= 88.88) rateColor = 'text-amber-500 dark:text-amber-400 font-bold';
      else if (rate >= 77.77) rateColor = 'text-emerald-600 dark:text-emerald-400 font-bold';

      return { text: rate.toFixed(2) + '%', color: rateColor };
    });
    return { label, color: def.color, rates };
  });
});

/** 【関数の役割】 ランク行の展開状態を反転する（展開済みなら閉じる、そうでなければ開く）。 */
const toggleRank = (rank: string) => {
  if (expandedRanks.value.has(rank)) {
    expandedRanks.value.delete(rank);
  } else {
    expandedRanks.value.add(rank);
    emit('folder-open');
  }
};

// 曲定義 (songData) を "title_difficultyCode" キーで検索可能にするルックアップ。
// ANOTHER=4 / LEGGENDARIA=10 という難易度コードで引くため事前 Map 化する。
// API から最新の楽曲データが届いた後も引けるよう computed（リアクティブ）にしている。
const songDict = computed(() => {
  const dict = new Map<string, any>();
  const body = songDataBodyRef.value;
  if (Array.isArray(body)) {
    body.forEach((s: any) => dict.set(`${s.title}_${s.difficulty}`, s));
  }
  return dict;
});

/**
 * 【computed の役割】 非公式ランク別に曲をグループ化する。
 *   - プレイ済み曲は既存 ScoreRecord をそのまま採用
 *   - 未プレイ曲は songDict から情報を引き、scoreRate=-1 のプレースホルダを生成
 *   - "Uncategorized" ランクは除外
 * 難易度表の順にイテレートするので、同じランク内の表示順はテーブル定義順になる。
 */
const groupedByRank = computed(() => {
  const groups: Record<string, ScoreRecord[]> = {};

  // プレイ済みスコアを "title_difficultyName" でマップ化（O(1) ルックアップ用）。
  const scoreMap = new Map<string, ScoreRecord>();
  props.scores.forEach(s => {
    if (s.informalRank && !s.informalRank.includes('Uncategorized')) {
      scoreMap.set(`${s.title}_${s.difficultyName}`, s);
    }
  });

  // 難易度表の全曲を走査し、ランク別グループを構築。
  (diffTableRanksRef.value || []).forEach((r: any) => {
    const rank = r.rank;
    if (rank.includes('Uncategorized')) return;
    if (!groups[rank]) groups[rank] = [];

    r.songs.forEach((songTitle: string) => {
      const isLeggendaria = songTitle.endsWith('[L]');
      const baseTitle = isLeggendaria ? songTitle.slice(0, -3) : songTitle;
      const diffName = isLeggendaria ? 'LEGGENDARIA' : 'ANOTHER';
      // 難易度名 → コードの変換は useGameData の getDifficultyCode に集約済み。
      const diffCode = String(getDifficultyCode(diffName));

      const scoreKey = `${baseTitle}_${diffName}`;
      if (scoreMap.has(scoreKey)) {
        // プレイ済み: 既存の ScoreRecord をそのまま採用。
        groups[rank].push(scoreMap.get(scoreKey)!);
      } else {
        // 未プレイ: 曲定義から notes（ノーツ数 × 2 = 満点）を引いてプレースホルダ生成。
        const def = songDict.value.get(`${baseTitle}_${diffCode}`);
        if (!def) return; // 定義が無ければスキップ（uncategorized）
        const maxScore = def.notes * 2;
        groups[rank].push({
          title: baseTitle,
          artist: def?.artist ?? '',
          genre: def?.genre ?? '',
          difficultyName: diffName,
          difficultyColor: isLeggendaria
            ? 'text-purple-700 bg-purple-100 border border-purple-300'
            : 'text-red-700 bg-red-100 border border-red-300',
          difficultyLevel: def?.level ?? null,
          clearType: 'NO PLAY',
          score: 0,
          scoreRate: -1,
          maxScore,
          informalRank: rank,
          djLevel: '-',
          pgreat: 0,
          great: 0,
          missCount: null,
          playCount: 0,
          lastPlayTime: '',
          beatTierPoints: 0,
          maxBeatTierPoints: getMaxPoints(rank),
          options: undefined,
        });
      }
    });
  });

  return groups;
});

/**
 * 【関数の役割】 難易度表の曲名表記（末尾 [L] は LEGGENDARIA）から公式難易度レベルを引く。
 * @returns 楽曲データに定義が無ければ null
 */
const officialLevelOfTableSong = (songTitle: string): number | null => {
  const isLeggendaria = songTitle.endsWith('[L]');
  const baseTitle = isLeggendaria ? songTitle.slice(0, -3) : songTitle;
  const diffCode = String(getDifficultyCode(isLeggendaria ? 'LEGGENDARIA' : 'ANOTHER'));
  const def = songDict.value.get(`${baseTitle}_${diffCode}`);
  return typeof def?.level === 'number' ? def.level : null;
};

/**
 * 【関数の役割】 スコアレコードの公式難易度レベルを引く。
 * 楽曲データ（songDict）を優先し、無ければレコード自身の値にフォールバックする。
 */
const officialLevelOfRecord = (s: ScoreRecord): number | null => {
  const diffCode = String(getDifficultyCode(s.difficultyName));
  const def = songDict.value.get(`${s.title}_${diffCode}`);
  if (typeof def?.level === 'number') return def.level;
  return typeof s.difficultyLevel === 'number' ? s.difficultyLevel : null;
};

/** 【関数の役割】 現在の公式難易度フィルタに合致するか判定（'all' は常に true）。 */
const matchesLevelFilter = (level: number | null): boolean =>
  levelFilter.value === 'all' || level === Number(levelFilter.value);

/**
 * 【computed の役割】 モーダル用の「現行作」ランク別集計（プレイ済数・合計 Beat-PT）。
 *
 * 成長記録（履歴ログ）とフォルダランキングはサーバ側の現行作データで作られるため、
 * 歴代ベストを反映した表示値ではなく、常に現行作のレコードから集計した値を渡す。
 */
const currentRankStats = computed(() => {
  const src = props.historyScores ?? props.scores;
  const stats: Record<string, { playCount: number; totalBeatPoints: number }> = {};
  src.forEach(s => {
    const rank = s.informalRank;
    if (!rank || rank.includes('Uncategorized')) return;
    const entry = stats[rank] ?? (stats[rank] = { playCount: 0, totalBeatPoints: 0 });
    entry.totalBeatPoints += s.beatTierPoints;
    if (s.score > 0) entry.playCount += 1;
  });
  return stats;
});

/** 【computed の役割】 非公式ランクごとの曲数（難易度表定義の総数、プレイ有無不問・フィルタ非適用）。 */
const rankSongCountsAll = computed(() => {
  const counts: Record<string, number> = {};
  (diffTableRanksRef.value || []).forEach((r: any) => {
    counts[r.rank] = r.songs.length;
  });
  return counts;
});

/** 【computed の役割】 公式難易度フィルタ適用後の、非公式ランクごとの曲数。 */
const rankSongCounts = computed(() => {
  if (levelFilter.value === 'all') return rankSongCountsAll.value;
  const counts: Record<string, number> = {};
  (diffTableRanksRef.value || []).forEach((r: any) => {
    counts[r.rank] = r.songs.filter((title: string) => matchesLevelFilter(officialLevelOfTableSong(title))).length;
  });
  return counts;
});

/**
 * 【computed の役割】 ランクごとの集計行データ（平均レート、合計 pt、フォルダランク等）を構築。
 * ランク順は数値降順（☆12.9 → ☆11.0）。各ランク内の曲もスコアレート降順に並べる。
 * 「MAX 基準 pt」は legendPtPerSong × totalCount。レート算出は プレイ済み曲のみを対象とする。
 * 公式難易度フィルタが有効な場合は、対象曲を絞った上で全ての集計をやり直す
 * （モーダル用の full* だけはフォルダ全体の値を保持）。
 */
const tableData = computed(() => {
  const ranks = Object.keys(groupedByRank.value);

  // "12.x" 文字列を数値として比較し、降順ソート。
  ranks.sort((a, b) => parseFloat(b) - parseFloat(a));

  const rows = ranks.map(rank => {
    const allSongs = groupedByRank.value[rank];
    // 表示・集計対象は公式難易度フィルタを通過した曲のみ（'all' なら全曲）。
    const songs = levelFilter.value === 'all'
      ? [...allSongs]
      : allSongs.filter(s => matchesLevelFilter(officialLevelOfRecord(s)));
    let totalScore = 0;
    let totalMaxScore = 0;
    let totalBeatPoints = 0;
    let maxBeatPoints = 0;

    // 並び替え。未プレイ曲（scoreRate=-1）はどの順でも末尾にまとめる。
    const played = (s: ScoreRecord) => s.scoreRate > 0;
    songs.sort((a, b) => {
      if (played(a) !== played(b)) return played(a) ? -1 : 1;
      if (songSort.value === 'title') return a.title.localeCompare(b.title, 'ja');
      return songSort.value === 'rateAsc' ? a.scoreRate - b.scoreRate : b.scoreRate - a.scoreRate;
    });

    songs.forEach(s => {
      // Beat-PT は未プレイ曲でも 0 として累積（全 playthrough の合計値）。
      totalBeatPoints += s.beatTierPoints;

      // 平均レートは「プレイ済みかつ maxScore がある曲」だけで計算する。
      if (s.maxScore > 0 && s.score > 0) {
        totalScore += s.score;
        totalMaxScore += s.maxScore;
      }
    });

    const averageRate = totalMaxScore > 0 ? (totalScore / totalMaxScore) * 100 : 0;
    const playCount = songs.filter(s => s.score > 0).length;

    const totalCount = rankSongCounts.value[rank] || songs.length;

    // MAX 参照値（Legend 到達時の理論 pt = 1 曲あたり pt × 総曲数）
    const legendPtPerSong = getLegendPtPerSong(rank);
    maxBeatPoints = legendPtPerSong > 0 ? legendPtPerSong * totalCount : 0;

    const rankInfo = getFolderRankInfoByRate(averageRate, rank);
    const nextRankInfo = getNextFolderRankInfoByRate(averageRate, rank);

    // レートベースのランクは既にプレイ済み曲の平均レートで算出されるため playedRankInfo は同値
    const playedRankInfo = rankInfo;

    // 1 曲 1 行の表示データ。レート順のときは、平均をまたぐ位置に「フォルダ平均」の区切りを 1 本入れる
    // （区切りより下 = フォルダランクを下げている曲、が一目で分かる）。
    const songRows: SongRow[] = songs.map((s, i) => {
      const prev = songs[i - 1];
      const grade = played(s) ? getScoreGradeInfo(s.score, s.maxScore) : null;
      const allTimeVersion = played(s) ? (s.allTimeVersion ?? null) : null;
      // 過去作のスコアに置き換わった行（allTimeVersion あり）は今作が負けているので、両方が立つことはない。
      const allTimeKey = chartKey(s.title, s.difficultyName);
      const isAllTimeBest = played(s) && allTimeVersion === null && (props.currentAllTimeBest?.has(allTimeKey) ?? false);
      // 並んだ／超えた過去作ベスト。過去作にスコアが無い譜面（新曲の初スコアなど）は null。
      const beaten = isAllTimeBest ? (props.currentAllTimeBest?.get(allTimeKey) ?? null) : null;
      return {
        key: `${s.title}_${s.difficultyName}`,
        song: s,
        songRank: played(s) ? getFolderRankInfoByRate(s.scoreRate, rank) : null,
        isLeggendaria: s.difficultyName === 'LEGGENDARIA',
        isAllTimeBest,
        allTimeVersion,
        tooltip: `${s.title} [${s.difficultyName}]`
          + (grade?.grade ? `\nEX ${s.score} / ${s.maxScore}  (${grade.fromMax} ・ ${grade.grade})` : '')
          + (beaten ? `\n★ ${t('table.allTimeBestRow', { version: `${beaten.version} ${versionName(beaten.version)}`, score: beaten.score })}`
            : isAllTimeBest ? `\n★ ${t('table.allTimeBestRowFirst')}` : '')
          + (allTimeVersion ? `\n${t('filter.bestVersionTag', { name: `${allTimeVersion} ${versionName(allTimeVersion)}` })}` : ''),
        avgBefore: songSort.value !== 'title' && !!prev && played(prev) && played(s)
          && (prev.scoreRate >= averageRate) !== (s.scoreRate >= averageRate),
      };
    });

    // 単曲ランク分布（ブロック単位。Ancient 4 と Ancient 3 は同じ Ancient に数える）。末尾に未プレイ。
    const tierCounts = new Map<string, number>();
    songRows.forEach(r => {
      if (r.songRank) tierCounts.set(r.songRank.name, (tierCounts.get(r.songRank.name) ?? 0) + 1);
    });
    const tierDist: TierSegment[] = TIER_ORDER.filter(name => tierCounts.has(name)).map(name => ({
      name,
      label: name,
      count: tierCounts.get(name)!,
      pct: (tierCounts.get(name)! / songs.length) * 100,
      bar: tierBar(name),
      text: tierText(name),
    }));
    if (songs.length > playCount) {
      tierDist.push({
        name: 'unplayed',
        label: t('table.notPlayed'),
        count: songs.length - playCount,
        pct: ((songs.length - playCount) / songs.length) * 100,
        bar: 'bg-slate-200 dark:bg-slate-700',
        text: 'text-slate-400 dark:text-slate-500',
      });
    }

    return {
      rank,
      songs,
      songRows,
      // 今作のスコアが歴代自己ベストの曲数（0 なら強調表示の凡例を出さない）。
      allTimeCount: songRows.filter(r => r.isAllTimeBest).length,
      tierDist,
      totalScore,
      totalMaxScore,
      totalBeatPoints,
      maxBeatPoints,
      averageRate,
      playCount,
      totalCount,
      rankInfo,
      nextRankInfo,
      playedRankInfo,
      // フォルダランキング / 成長グラフはフォルダ全体（フィルタ非適用）かつ現行作が対象なので、
      // モーダルへ渡す値だけは絞り込み前・歴代反映前の集計を保持しておく。
      fullTotalCount: rankSongCountsAll.value[rank] || allSongs.length,
      fullPlayCount: currentRankStats.value[rank]?.playCount ?? 0,
      fullTotalBeatPoints: currentRankStats.value[rank]?.totalBeatPoints ?? 0,
    };
  });

  // フィルタ時、該当曲が 1 曲も無いランク行は表示しない。
  return levelFilter.value === 'all' ? rows : rows.filter(r => r.totalCount > 0 || r.songs.length > 0);
});

type FolderRow = (typeof tableData.value)[number];

/** 【関数の役割】 フォルダランキングのモーダルを開く。 */
const openRanking = (data: FolderRow) => {
  rankingModalRank.value = { rank: data.rank, totalCount: data.fullTotalCount };
};

/** 成長記録は現行作でフォルダ全曲プレイ済みのときだけ開ける。 */
const canOpenGrowth = (data: FolderRow) => data.fullPlayCount >= data.fullTotalCount && data.fullTotalCount > 0;

/** 【関数の役割】 成長記録のモーダルを開く。 */
const openGrowth = (data: FolderRow) => {
  growthChartRank.value = { rank: data.rank, songCount: data.fullTotalCount, currentTotalBeatPoints: data.fullTotalBeatPoints };
};
</script>

<template>
  <div class="w-full bg-white dark:bg-slate-800 rounded-md border border-slate-200 dark:border-slate-700 overflow-hidden mt-6 animate-fade-in flex flex-col transition-colors duration-200">
    <div class="px-6 py-4 border-b border-slate-100 dark:border-slate-700/50 flex max-sm:flex-col sm:items-center justify-between gap-4 bg-slate-50/50 dark:bg-slate-800/50 transition-colors duration-200">
      <h3 class="font-bold text-slate-800 dark:text-slate-100 text-lg flex items-center gap-2">
        <svg xmlns="http://www.w3.org/2000/svg" class="h-5 w-5 text-indigo-500 dark:text-indigo-400 shrink-0" viewBox="0 0 20 20" fill="currentColor">
          <path d="M5 4a1 1 0 00-2 0v7.268a2 2 0 000 3.464V16a1 1 0 102 0v-1.268a2 2 0 000-3.464V4zM11 4a1 1 0 10-2 0v1.268a2 2 0 000 3.464V16a1 1 0 102 0V8.732a2 2 0 000-3.464V4zM16 3a1 1 0 011 1v7.268a2 2 0 010 3.464V16a1 1 0 11-2 0v-1.268a2 2 0 010-3.464V4a1 1 0 011-1z" />
        </svg>
        {{ t('table.unofficialSummary') }}
        <div class="relative">
          <button
            @click.stop="showInfo = !showInfo"
            :aria-label="t('table.aboutSummary')"
            :aria-expanded="showInfo"
            class="w-5 h-5 rounded-full bg-slate-200 dark:bg-slate-600 hover:bg-indigo-200 dark:hover:bg-indigo-700 text-slate-500 dark:text-slate-400 hover:text-indigo-600 dark:hover:text-indigo-300 text-[10px] font-bold flex items-center justify-center transition-colors"
            :title="t('table.aboutSummary')"
          >?</button>
          <div
            v-if="showInfo"
            class="absolute z-20 top-7 -left-16 sm:left-0 w-[280px] sm:w-72 bg-white dark:bg-slate-800 rounded-md border border-slate-200 dark:border-slate-600 shadow-xl p-4 text-xs text-slate-700 dark:text-slate-300 font-normal"
          >
            <div class="flex items-center justify-between mb-2">
               <span class="font-bold text-sm text-slate-800 dark:text-slate-100">{{ t('table.aboutFolderRank') }}</span>
              <button @click="showInfo = false" aria-label="閉じる" class="text-slate-400 hover:text-slate-600 dark:hover:text-slate-200 font-bold text-base leading-none">×</button>
            </div>
            <p class="mb-2 text-slate-600 dark:text-slate-400">{{ t('table.folderRankExplanation') }}</p>
            <div class="border-t border-slate-100 dark:border-slate-700 pt-2 mt-2 space-y-1">
              <p class="font-bold text-slate-700 dark:text-slate-200">{{ t('table.legendCriteria') }}</p>
              <p>{{ t('table.legendExplanation') }}</p>
              <p class="font-bold text-slate-700 dark:text-slate-200 mt-2">{{ t('table.rankCriteria') }}</p>
              <p>{{ t('table.rankExplanation') }}</p>
              <p class="font-bold text-slate-700 dark:text-slate-200 mt-2">{{ t('table.paleIconCriteria') }}</p>
              <p>{{ t('table.paleIconExplanation') }}</p>
            </div>
            <button
              @click.stop="showRateTable = true; showInfo = false"
              class="mt-3 w-full py-1.5 bg-indigo-500 hover:bg-indigo-600 text-white text-xs font-bold rounded-lg transition-colors"
            >{{ t('table.viewRateTable') }}</button>
          </div>
        </div>
      </h3>
      <!-- 公式難易度での絞り込み（すべて / ☆11 / ☆12） -->
      <div class="flex items-center gap-2 self-start sm:self-auto shrink-0">
        <span class="text-xs font-bold text-slate-500 dark:text-slate-400 whitespace-nowrap">{{ t('table.filterLevelLabel') }}</span>
        <div
          role="group"
          :aria-label="t('table.filterLevelLabel')"
          class="flex items-center gap-1 p-1 rounded-lg bg-slate-100 dark:bg-slate-700/50"
        >
          <button
            v-for="opt in levelFilterOptions"
            :key="opt.value"
            type="button"
            @click="levelFilter = opt.value"
            :aria-pressed="levelFilter === opt.value"
            class="px-3 py-1 text-xs font-bold rounded-md whitespace-nowrap transition-colors"
            :class="levelFilter === opt.value
              ? 'bg-white dark:bg-slate-800 text-indigo-600 dark:text-indigo-400 shadow-sm'
              : 'text-slate-500 dark:text-slate-400 hover:text-slate-700 dark:hover:text-slate-200'"
          >{{ opt.label }}</button>
        </div>
      </div>
    </div>
    <!-- Click-outside backdrop for info tooltip -->
    <div v-if="showInfo" class="fixed inset-0 z-10" @click="showInfo = false"></div>

    <!-- Difficulty Ranking Modal -->
    <DifficultyRankingModal
      v-if="rankingModalRank"
      :rank="rankingModalRank.rank"
      :total-count="rankingModalRank.totalCount"
      @close="rankingModalRank = null"
    />

    <!-- Rank Growth Chart Modal -->
    <RankGrowthChartModal
      v-if="growthChartRank"
      :rank="growthChartRank.rank"
      :song-count="growthChartRank.songCount"
      :current-total-beat-points="growthChartRank.currentTotalBeatPoints"
      @close="growthChartRank = null"
    />

    <!-- Rate Table Modal -->
    <Teleport to="body">
      <div v-if="showRateTable" class="fixed inset-0 z-50 flex items-center justify-center p-2 sm:p-4">
        <div class="fixed inset-0 bg-black/50 backdrop-blur-sm" @click="showRateTable = false"></div>
        <div class="relative z-10 bg-white dark:bg-slate-800 rounded-md shadow-xl border border-slate-200 dark:border-slate-700 w-full max-w-[95vw] max-h-[90vh] flex flex-col">
          <!-- Header -->
          <div class="flex items-center justify-between px-4 py-3 border-b border-slate-200 dark:border-slate-700 shrink-0">
            <div>
              <h3 class="font-bold text-sm sm:text-base text-slate-800 dark:text-slate-100">{{ t('table.rateTableTitle') }}</h3>
            </div>
            <button @click="showRateTable = false" aria-label="閉じる" class="w-7 h-7 rounded-full bg-slate-100 dark:bg-slate-700 hover:bg-slate-200 dark:hover:bg-slate-600 text-slate-500 dark:text-slate-400 font-bold text-sm flex items-center justify-center transition-colors shrink-0 ml-2">×</button>
          </div>
          <!-- Scrollable table -->
          <div class="overflow-auto flex-1">
            <table class="text-[10px] sm:text-xs border-collapse">
              <thead class="sticky top-0 z-20">
                <tr class="bg-slate-100 dark:bg-slate-900 text-slate-500 dark:text-slate-400">
                  <th scope="col" class="py-1.5 px-2 text-left font-bold sticky left-0 z-30 bg-slate-100 dark:bg-slate-900 min-w-[80px] sm:min-w-[110px]">{{ t('table.colRank') }}</th>
                  <th v-for="f in allFolders" :key="f" scope="col" class="py-1.5 px-1 sm:px-2 text-center font-bold whitespace-nowrap">☆{{ f }}</th>
                </tr>
              </thead>
              <tbody>
                <tr v-for="(row, idx) in rateTableRows" :key="row.label" :class="idx % 5 === 1 ? 'border-t border-slate-200 dark:border-slate-700' : ''">
                  <td class="py-1 px-2 font-bold whitespace-nowrap sticky left-0 z-10 bg-white dark:bg-slate-800" :class="row.color">{{ row.label }}</td>
                  <td v-for="(rate, i) in row.rates" :key="i" class="py-1 px-1 sm:px-2 text-center font-mono whitespace-nowrap" :class="rate.color">{{ rate.text }}</td>
                </tr>
              </tbody>
            </table>
          </div>
        </div>
      </div>
    </Teleport>
    
    <!-- フォルダ一覧。段組みと列の出し分けはこの要素の幅で決まる（<style> のコンテナクエリ）。 -->
    <div class="udt-list text-slate-700 dark:text-slate-200">
      <!-- 列見出し（1 行表示になる幅のときだけ） -->
      <div class="folder-grid folder-head bg-slate-100 dark:bg-slate-800/80 border-b border-slate-200 dark:border-slate-700 text-[11px] font-bold text-slate-500 dark:text-slate-400 transition-colors duration-200">
        <span class="a-level">{{ t('table.colDifficulty') }}</span>
        <span class="a-rate text-right">{{ t('table.colAvgRate') }}</span>
        <span class="a-rank">{{ t('table.colFolderRank') }}</span>
        <span class="a-next">{{ t('table.colNextRank') }}</span>
        <span class="a-pt text-right">{{ t('table.colTotalPtShort') }}</span>
        <span class="a-played">{{ t('table.colPlayed') }}</span>
      </div>

      <div
        v-for="data in tableData"
        :key="data.rank"
        class="border-b border-b-slate-100 dark:border-b-slate-700/50 last:border-b-0 transition-colors duration-200"
      >
        <!-- フォルダ行: 開かなくてもランク名・次のランクまでの残りが比較できる -->
        <div
          @click="toggleRank(data.rank)"
          @keydown.enter.prevent="toggleRank(data.rank)"
          @keydown.space.prevent="toggleRank(data.rank)"
          role="button"
          tabindex="0"
          :aria-expanded="expandedRanks.has(data.rank)"
          :aria-controls="`unofficial-rank-panel-${data.rank}`"
          class="folder-grid cursor-pointer group transition-colors hover:bg-blue-50/60 dark:hover:bg-slate-700/50 focus-visible:outline-none focus-visible:bg-blue-50 dark:focus-visible:bg-slate-700/60"
          :class="{ 'bg-slate-50 dark:bg-slate-800/80': expandedRanks.has(data.rank) }"
        >
          <div class="a-level font-bold text-sm text-slate-800 dark:text-slate-100 whitespace-nowrap tabular-nums">
            <span class="inline-block w-2 h-2 rounded-full mr-1.5" :class="parseFloat(data.rank) >= 12.5 ? 'bg-purple-500 dark:bg-purple-400' : 'bg-blue-500 dark:bg-blue-400'"></span>{{ data.rank }}
          </div>

          <div class="a-rate text-right font-bold text-sm tabular-nums whitespace-nowrap" :class="data.averageRate > 0 ? rateColorClass(data.averageRate, 'text-emerald-600 dark:text-emerald-400') : 'text-slate-400 dark:text-slate-500'">
            {{ data.averageRate > 0 ? data.averageRate.toFixed(2) + '%' : '-' }}
          </div>

          <!-- 全曲プレイ前は「プレイ済みの曲だけで出した暫定ランク」なので薄く出す -->
          <div
            class="a-rank flex items-center gap-1.5 min-w-0"
            :title="data.playCount > 0 && data.playCount < data.totalCount ? t('table.paleIconExplanation') : undefined"
          >
            <RankIcon class="shrink-0" :class="{ 'opacity-30': data.playCount < data.totalCount }" :rank-name="data.rankInfo.name" :tier="data.rankInfo.tier" size="xs" />
            <span
              v-if="data.playCount > 0"
              class="truncate text-xs font-bold"
              :class="[tierText(data.rankInfo.name), { 'opacity-60': data.playCount < data.totalCount }]"
            >{{ tierLabel(data.rankInfo) }}</span>
            <span v-else class="text-xs font-bold text-slate-400 dark:text-slate-500">-</span>
          </div>

          <!-- 現在ランク →（進捗）→ 次のランク。バーの右端が次のランクの必要レート -->
          <div class="a-next flex items-center gap-2 min-w-0">
            <template v-if="data.nextRankInfo.nextRank">
              <div class="flex-1 min-w-0 h-1.5 bg-slate-200 dark:bg-slate-700 rounded-full overflow-hidden">
                <div class="h-full rounded-full transition-all duration-500" :class="tierBar(data.rankInfo.name)" :style="{ width: `${data.nextRankInfo.progress}%` }"></div>
              </div>
              <span class="next-gap shrink-0 text-right text-[11px] font-bold tabular-nums whitespace-nowrap text-slate-500 dark:text-slate-400">
                {{ t('table.toNextRank', { n: (data.nextRankInfo.nextRank.minRate - data.averageRate).toFixed(2) }) }}
              </span>
              <span class="next-name truncate text-[11px] font-bold" :class="tierText(data.nextRankInfo.nextRank.name)">→ {{ tierLabel(data.nextRankInfo.nextRank) }}</span>
            </template>
            <span v-else-if="data.playCount > 0" class="text-[11px] font-bold text-amber-500 dark:text-amber-400 whitespace-nowrap">★ {{ t('table.highestTier') }}</span>
          </div>

          <div class="a-pt text-right tabular-nums whitespace-nowrap">
            <span class="text-sm font-bold" :class="tierText(data.rankInfo.name)">{{ data.totalBeatPoints.toFixed(1) }}</span>
            <span class="text-[10px] font-bold text-slate-400 dark:text-slate-500"> / {{ data.maxBeatPoints.toFixed(1) }}</span>
          </div>

          <div
            class="a-played text-xs font-bold tabular-nums whitespace-nowrap"
            :class="data.totalCount > 0 && data.playCount >= data.totalCount ? 'text-emerald-600 dark:text-emerald-400' : 'text-slate-600 dark:text-slate-300'"
          >
            {{ data.playCount }}<span class="font-normal text-slate-400 dark:text-slate-500">/{{ data.totalCount }}</span>
          </div>

          <!-- ランキング / 成長記録（横幅があるときは行内、狭いときは開いた中のボタン） -->
          <div class="a-actions items-center justify-end gap-1">
            <button
              type="button"
              @click.stop="openRanking(data)"
              :title="t('table.viewDifficultyRanking')"
              :aria-label="t('table.viewDifficultyRanking')"
              class="inline-flex items-center justify-center w-7 h-7 rounded-md bg-amber-50 hover:bg-amber-100 dark:bg-amber-900/30 dark:hover:bg-amber-900/50 text-amber-600 dark:text-amber-400 border border-amber-200 dark:border-amber-700 transition-colors"
            >
              <svg xmlns="http://www.w3.org/2000/svg" class="h-4 w-4" fill="none" viewBox="0 0 24 24" stroke="currentColor" stroke-width="2">
                <path stroke-linecap="round" stroke-linejoin="round" d="M9 12l2 2 4-4M7.835 4.697a3.42 3.42 0 001.946-.806 3.42 3.42 0 014.438 0 3.42 3.42 0 001.946.806 3.42 3.42 0 013.138 3.138 3.42 3.42 0 00.806 1.946 3.42 3.42 0 010 4.438 3.42 3.42 0 00-.806 1.946 3.42 3.42 0 01-3.138 3.138 3.42 3.42 0 00-1.946.806 3.42 3.42 0 01-4.438 0 3.42 3.42 0 00-1.946-.806 3.42 3.42 0 01-3.138-3.138 3.42 3.42 0 00-.806-1.946 3.42 3.42 0 010-4.438 3.42 3.42 0 00.806-1.946 3.42 3.42 0 013.138-3.138z" />
              </svg>
            </button>
            <button
              v-if="canOpenGrowth(data)"
              type="button"
              @click.stop="openGrowth(data)"
              :title="t('table.viewGrowthChart')"
              :aria-label="t('table.viewGrowthChart')"
              class="inline-flex items-center justify-center w-7 h-7 rounded-md bg-blue-50 hover:bg-blue-100 dark:bg-blue-900/30 dark:hover:bg-blue-900/50 text-blue-700 dark:text-blue-400 border border-blue-200 dark:border-blue-700 transition-colors"
            >
              <svg xmlns="http://www.w3.org/2000/svg" class="h-4 w-4" fill="none" viewBox="0 0 24 24" stroke="currentColor" stroke-width="2">
                <path stroke-linecap="round" stroke-linejoin="round" d="M3 17l5-5 4 4 7-7M14 9h6v6" />
              </svg>
            </button>
          </div>

          <svg xmlns="http://www.w3.org/2000/svg" class="a-chev h-4 w-4 text-slate-400 dark:text-slate-500 transform transition-transform duration-200 group-hover:text-blue-600 dark:group-hover:text-blue-400" :class="{ 'rotate-180 text-blue-600 dark:text-blue-400': expandedRanks.has(data.rank) }" viewBox="0 0 20 20" fill="currentColor">
            <path fill-rule="evenodd" d="M5.293 7.293a1 1 0 011.414 0L10 10.586l3.293-3.293a1 1 0 111.414 1.414l-4 4a1 1 0 01-1.414 0l-4-4a1 1 0 010-1.414z" clip-rule="evenodd" />
          </svg>
        </div>

        <!-- 開いた中: 単曲ランク分布 + 1 曲 1 行の一覧 -->
        <div
          v-if="expandedRanks.has(data.rank)"
          :id="`unofficial-rank-panel-${data.rank}`"
          class="folder-panel bg-slate-50/80 dark:bg-slate-900/30 border-t border-t-slate-100 dark:border-t-slate-700/50 transition-colors duration-200"
        >
          <div class="flex flex-wrap items-end gap-x-4 gap-y-2.5">
            <div v-if="data.tierDist.length > 0" class="min-w-0 flex-1 basis-72">
              <p class="section-label">{{ t('table.songRankDist') }}</p>
              <div class="mt-1 flex h-2 rounded-full overflow-hidden bg-slate-200 dark:bg-slate-700">
                <div v-for="seg in data.tierDist" :key="seg.name" :class="seg.bar" :style="{ width: `${seg.pct}%` }" :title="`${seg.label} ${seg.count}`"></div>
              </div>
              <ul class="mt-1.5 flex flex-wrap gap-x-3 gap-y-0.5 text-[11px] font-bold leading-4">
                <li v-for="seg in data.tierDist" :key="seg.name" class="inline-flex items-center gap-1 whitespace-nowrap">
                  <span class="w-2 h-2 rounded-sm shrink-0" :class="seg.bar"></span>
                  <span :class="seg.text">{{ seg.label }}</span>
                  <span class="tabular-nums text-slate-600 dark:text-slate-300">{{ seg.count }}</span>
                </li>
              </ul>
            </div>

            <div class="flex flex-wrap items-center gap-2">
              <div class="panel-actions items-center gap-2">
                <button type="button" @click="openRanking(data)" class="panel-btn text-amber-700 dark:text-amber-400 border-amber-200 dark:border-amber-700 bg-amber-50 hover:bg-amber-100 dark:bg-amber-900/30 dark:hover:bg-amber-900/50">
                  {{ t('table.difficultyRankingTitle') }}
                </button>
                <button v-if="canOpenGrowth(data)" type="button" @click="openGrowth(data)" class="panel-btn text-blue-700 dark:text-blue-400 border-blue-200 dark:border-blue-700 bg-blue-50 hover:bg-blue-100 dark:bg-blue-900/30 dark:hover:bg-blue-900/50">
                  {{ t('table.growthChartTitle') }}
                </button>
              </div>
              <div role="group" :aria-label="t('report.sortLabel')" class="flex items-center gap-1 p-1 rounded-lg bg-slate-100 dark:bg-slate-700/50">
                <button
                  v-for="opt in songSortOptions"
                  :key="opt.value"
                  type="button"
                  @click="songSort = opt.value"
                  :aria-pressed="songSort === opt.value"
                  class="px-2.5 py-1 text-[11px] font-bold rounded-md whitespace-nowrap transition-colors"
                  :class="songSort === opt.value
                    ? 'bg-white dark:bg-slate-800 text-blue-700 dark:text-blue-400 shadow-sm'
                    : 'text-slate-500 dark:text-slate-400 hover:text-slate-700 dark:hover:text-slate-200'"
                >{{ opt.label }}</button>
              </div>
            </div>
          </div>

          <!-- 凡例。色付きの行 = 今作のスコアが歴代自己ベスト（過去作のベストに並んだ／超えた、または過去作にスコアが無い）の曲 -->
          <p v-if="data.allTimeCount > 0" class="mt-2.5 flex items-center gap-1.5 text-[11px] font-bold leading-4 text-amber-700 dark:text-amber-400">
            <span class="all-time-swatch shrink-0"></span>
            <span>{{ t('table.allTimeLegend', { n: data.allTimeCount }) }}</span>
          </p>

          <ul class="song-list card mt-2.5 overflow-hidden transition-colors duration-200">
            <template v-for="(entry, i) in data.songRows" :key="entry.key">
              <li v-if="entry.avgBefore" class="avg-divider text-[10px] font-bold text-blue-700 dark:text-blue-400 bg-blue-50/70 dark:bg-blue-900/20 border-b border-b-slate-100 dark:border-b-slate-700/60">
                <span class="h-px flex-1 bg-blue-200 dark:bg-blue-800"></span>
                <span class="tabular-nums whitespace-nowrap">{{ t('table.folderAverage', { rate: data.averageRate.toFixed(2) }) }}</span>
                <span class="h-px flex-1 bg-blue-200 dark:bg-blue-800"></span>
              </li>
              <li
                class="song-row border-b-slate-100 dark:border-b-slate-700/60"
                :class="[entry.isLeggendaria ? 'border-l-purple-500' : 'border-l-red-500', { 'is-all-time': entry.isAllTimeBest }]"
                :title="entry.tooltip"
              >
                <span class="s-idx text-right text-[10px] font-bold tabular-nums text-slate-400 dark:text-slate-500">{{ i + 1 }}</span>
                <span class="s-title flex items-baseline gap-1 min-w-0" :class="{ 'opacity-50': !entry.songRank }">
                  <span class="truncate text-xs font-bold text-slate-800 dark:text-slate-100">{{ entry.song.title }}</span>
                  <span v-if="entry.isLeggendaria" class="shrink-0 text-[10px] font-bold text-purple-600 dark:text-purple-400">[L]</span>
                  <!-- 今作で歴代自己ベスト（取り込み結果の ★ と同じ印） -->
                  <span v-if="entry.isAllTimeBest" class="shrink-0 text-[11px] leading-none text-amber-500 dark:text-amber-400" aria-hidden="true">★</span>
                  <!-- 歴代反映で過去作のスコアに置き換わった行は、どの作品のスコアかを示す（スコア一覧と同じバッジ） -->
                  <span
                    v-if="entry.allTimeVersion !== null"
                    class="shrink-0 px-1 py-0.5 text-[9px] font-bold rounded border leading-none"
                    :class="versionBadgeClass(entry.allTimeVersion)"
                  >{{ versionShort(entry.allTimeVersion) }}</span>
                </span>
                <span class="s-rate text-right text-[13px] font-bold tabular-nums whitespace-nowrap" :class="entry.songRank ? rateColorClass(entry.song.scoreRate, 'text-slate-700 dark:text-slate-300') : 'text-slate-400 dark:text-slate-500'">
                  {{ entry.songRank ? entry.song.scoreRate.toFixed(2) + '%' : '-' }}
                </span>
                <span class="s-tier flex items-center gap-1 min-w-0">
                  <template v-if="entry.songRank">
                    <RankIcon :rank-name="entry.songRank.name" :tier="entry.songRank.tier" size="2xs" lite />
                    <span class="truncate text-[11px] font-bold" :class="tierText(entry.songRank.name)">{{ tierLabel(entry.songRank) }}</span>
                  </template>
                  <span v-else class="text-[11px] font-bold text-slate-400 dark:text-slate-500">{{ t('table.notPlayed') }}</span>
                </span>
                <span class="s-pt text-right text-xs font-bold font-mono tabular-nums text-slate-700 dark:text-slate-300">
                  {{ entry.song.beatTierPoints > 0 ? entry.song.beatTierPoints.toFixed(1) : '-' }}
                </span>
              </li>
            </template>
          </ul>
        </div>
      </div>

      <p v-if="tableData.length === 0" class="py-12 px-4 text-center text-sm text-slate-500 dark:text-slate-400">
        {{ t('table.noUnofficialData') }}
      </p>
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

/*
 * 列の出し分けは画面幅ではなくこの一覧自身の幅で決める（サイドバーの有無で使える幅が変わるため）。
 * Tailwind の sm: 系を使わないのは、後から読み込まれる output.css に同じプロパティの素クラスが
 * あると負けるため。scoped の属性セレクタ付きなら確実に勝てる。
 *
 *   〜679px : フォルダ行 2 段 / 曲 2 段
 *   680px〜 : フォルダ行 1 行（次のランク名と行内ボタンは省略）/ 曲 1 行
 *   920px〜 : 全列表示 / 曲は 2 段組
 */
.udt-list {
  container-type: inline-size;
}

/* ── フォルダ行。DOM は共通で、grid-area の割り当てだけを幅で差し替える ── */
.folder-grid {
  display: grid;
  align-items: center;
  column-gap: 0.5rem;
  row-gap: 0.25rem;
  padding: 0.5rem 0.75rem;
  grid-template-columns: 3.25rem minmax(0, 1fr) auto 1rem;
  grid-template-areas:
    "level  rank rate chev"
    "played next pt   chev";
}
.a-level { grid-area: level; }
.a-rate { grid-area: rate; }
.a-rank { grid-area: rank; }
.a-next { grid-area: next; }
.a-pt { grid-area: pt; }
.a-played { grid-area: played; }
.a-actions { grid-area: actions; }
.a-chev { grid-area: chev; }

.folder-head,
.a-actions,
.next-name {
  display: none;
}
.next-gap { width: 4.5rem; }
.panel-actions { display: flex; }
.folder-panel { padding: 0.75rem; }

.panel-btn {
  padding: 0.25rem 0.625rem;
  border-width: 1px;
  border-radius: 0.375rem;
  font-size: 11px;
  font-weight: 700;
  white-space: nowrap;
  transition: background-color 0.15s;
}

/* ── 曲の行。狭いときは曲名を 1 段目いっぱいに取り、数値を 2 段目に揃える ── */
.song-row {
  display: grid;
  align-items: center;
  column-gap: 0.5rem;
  padding: 0.375rem 0.625rem 0.375rem 0.5rem;
  border-left-width: 3px;
  border-bottom-width: 1px;
  break-inside: avoid;
  grid-template-columns: minmax(0, 1fr) 3.75rem 3.25rem;
  grid-template-areas:
    "title title title"
    "tier  rate  pt";
}
.s-idx { grid-area: idx; display: none; }
.s-title { grid-area: title; }
.s-rate { grid-area: rate; }
/* 未プレイ行はアイコンが無いぶん低くなるので、アイコンの高さで揃える */
.s-tier { grid-area: tier; min-height: 1.25rem; }
.s-pt { grid-area: pt; }

/*
 * 今作のスコアが歴代自己ベストの行。レート・ランクの文字色は意味を持っているので触らず、
 * 行の背景だけを歴代系の表示（トグル・取り込み結果の ★）と同じ琥珀系で淡く塗る（凡例の見本も同じ色）。
 */
.song-row.is-all-time,
.all-time-swatch {
  background-color: rgb(254 243 199 / 0.6);
}
.dark .song-row.is-all-time,
.dark .all-time-swatch {
  background-color: rgb(180 83 9 / 0.2);
}
.all-time-swatch {
  width: 0.75rem;
  height: 0.75rem;
  border-radius: 0.125rem;
  border: 1px solid rgb(252 211 77);
}
.dark .all-time-swatch { border-color: rgb(180 83 9); }

.avg-divider {
  display: flex;
  align-items: center;
  gap: 0.5rem;
  padding: 0.125rem 0.625rem;
  break-inside: avoid;
}

@container (min-width: 680px) {
  .folder-grid {
    column-gap: 0.625rem;
    padding: 0.5rem 1rem;
    grid-template-columns: 3.25rem 4.25rem 8.5rem minmax(0, 1fr) 7.5rem 3.5rem 1rem;
    grid-template-areas: "level rate rank next pt played chev";
  }
  .folder-head { display: grid; }
  .a-played { text-align: right; }
  .folder-panel { padding: 0.75rem 1rem 1rem; }

  .song-row {
    grid-template-columns: 1.25rem minmax(0, 1fr) 3.75rem 6.75rem 3.25rem;
    grid-template-areas: "idx title rate tier pt";
  }
  .s-idx { display: block; }
}

@container (min-width: 920px) {
  .folder-grid {
    column-gap: 0.75rem;
    grid-template-columns: 3.5rem 4.5rem 9.5rem minmax(0, 1fr) 7.5rem 3.75rem 4rem 1rem;
    grid-template-areas: "level rate rank next pt played actions chev";
  }
  .a-actions { display: flex; }
  .next-name { display: block; width: 6.5rem; }
  .panel-actions { display: none; }

  .song-list {
    columns: 2;
    column-gap: 0;
    column-rule: 1px solid rgb(226 232 240);
  }
  .dark .song-list { column-rule-color: rgb(51 65 85); }
}
</style>
