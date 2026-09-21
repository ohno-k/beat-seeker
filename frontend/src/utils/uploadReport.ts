/**
 * プレイ成果レポート（UploadResultModal）と、その X 共有画像（UploadReportShareImage）で共用する
 * 表示用ロジック。どちらも同じ {@link UpdatedSong} を別レイアウトで描くため、判定はここに寄せる。
 */
import { getFolderRankIndexByRate, getFolderRankInfoByRate } from './beatTier';
import type { RankInfo } from './beatTier';
import type { UpdatedSong, UploadDiffResult } from '../types/UploadDiff';

/** DJ LEVEL の区分名。E 未満は F。 */
export type GradeName = 'AAA' | 'AA' | 'A' | 'B' | 'C' | 'D' | 'E' | 'F';

export interface ScoreGradeInfo {
  /** "MAX" または "MAX-n"。maxScore 不明なら空文字。 */
  fromMax: string;
  /** "AAA" / "AA+123" / "F" など。 */
  grade: string;
  /** 色分け用の区分名（grade から +n を除いたもの）。 */
  gradeName: GradeName | '';
  /** 一つ上の区分までの不足 EX（"AAA-" + gap の形で表示する）。AAA 到達済みなら null。 */
  nextGrade: { name: string; gap: number } | null;
  /**
   * 近い方のボーダー基準の表記（ゲーム内の DJ LEVEL 表示と同じ考え方）。
   * 現在区分の下限より上のボーダー（AAA なら MAX、それ以外なら一つ上の区分）の方が近ければ
   * "MAX-12" / "AAA-30" のように、そうでなければ "AA+50" のように返す。1 行しか出せない狭い画面用。
   */
  nearest: string;
}

const GRADE_STEPS: { name: GradeName; ninths: number }[] = [
  { name: 'AAA', ninths: 8 },
  { name: 'AA', ninths: 7 },
  { name: 'A', ninths: 6 },
  { name: 'B', ninths: 5 },
  { name: 'C', ninths: 4 },
  { name: 'D', ninths: 3 },
  { name: 'E', ninths: 2 },
];

/** DJ LEVEL のしきい値（AAA=8/9, AA=7/9 ...。端数は切り上げ）。 */
const gradeThreshold = (maxScore: number, ninths: number) => Math.ceil((maxScore * ninths) / 9);

/**
 * 【関数の役割】 スコアを DJ LEVEL に変換し、MAX からの距離 / 現在グレード / 次グレードまでの距離を返す。
 */
export function getScoreGradeInfo(newScore: number, maxScore: number): ScoreGradeInfo {
  if (!maxScore || maxScore <= 0) return { fromMax: '', grade: '', gradeName: '', nextGrade: null, nearest: '' };
  const fromMaxN = maxScore - newScore;
  const fromMax = fromMaxN === 0 ? 'MAX' : `MAX-${fromMaxN}`;

  for (let i = 0; i < GRADE_STEPS.length; i++) {
    const g = GRADE_STEPS[i];
    const thresh = gradeThreshold(maxScore, g.ninths);
    if (newScore >= thresh) {
      const above = newScore - thresh;
      const upper = i > 0 ? GRADE_STEPS[i - 1] : null;
      const nextGrade = upper ? { name: upper.name + '-', gap: gradeThreshold(maxScore, upper.ninths) - newScore } : null;
      const grade = above === 0 ? g.name : `${g.name}+${above}`;
      // 上のボーダー（AAA なら MAX、それ以外なら一つ上の区分）までの距離。同距離なら現在区分の表記を優先。
      const upperGap = nextGrade ? nextGrade.gap : fromMaxN;
      const upperLabel = nextGrade ? `${nextGrade.name}${nextGrade.gap}` : fromMax;
      const nearest = upperGap < above ? upperLabel : grade;
      return { fromMax, grade, gradeName: g.name, nextGrade, nearest };
    }
  }
  // E 未満の場合は次ターゲットを E に設定して F として返す。F に下限は無いので nearest は常に E までの距離。
  const nextGrade = { name: 'E-', gap: gradeThreshold(maxScore, 2) - newScore };
  return { fromMax, grade: 'F', gradeName: 'F', nextGrade, nearest: `${nextGrade.name}${nextGrade.gap}` };
}

/** 今回の更新で新たに AAA に乗った譜面か（旧スコアは AAA 未満、新スコアは AAA 以上）。 */
export function isNewAaa(song: Pick<UpdatedSong, 'oldScore' | 'newScore' | 'maxScore'>): boolean {
  if (!song.maxScore || song.maxScore <= 0) return false;
  const aaa = gradeThreshold(song.maxScore, 8);
  return song.newScore >= aaa && song.oldScore < aaa;
}

/**
 * 【関数の役割】 informalRank 文字列から数値部分（例: "12.0"）だけを抽出。
 * "Uncategorized" 等の非数値ランクは null を返す（バッジ表示の判定に使う）。
 */
export function getNumericRank(informalRank: string | undefined): string | null {
  if (!informalRank) return null;
  const m = informalRank.match(/(\d+\.\d+)/);
  return m ? m[1] : null;
}

/**
 * 【関数の役割】 1 譜面の score rate と informalRank から、単曲ティア（フォルダランクと同基準）を返す。
 * informalRank（☆11.0〜13.0）が無い・スコアが 0 以下・非数値ランクの場合は null。
 */
export function getSongTierInfoByRate(rate: number | undefined, informalRank: string | undefined): RankInfo | null {
  const rank = getNumericRank(informalRank);
  if (!rank || !rate || rate <= 0) return null;
  const info = getFolderRankInfoByRate(rate, rank);
  if (!info || info.name === 'Beginner') return null;
  return info;
}

export function getSongTierInfo(song: { scoreRate?: number; informalRank?: string }): RankInfo | null {
  return getSongTierInfoByRate(song.scoreRate, song.informalRank);
}

/**
 * 【関数の役割】 旧スコアでの単曲ティアと新スコアでの単曲ティアを返す。
 * スコア更新でティアが変動した場合のみ `oldTier` を非 null で返す（変動なしなら null）。
 * 旧スコア = 0 や maxScore 不明の場合は oldTier は出さない（=「初プレイで上がった」扱い）。
 */
export function getSongTierTransition(
  song: { scoreRate?: number; oldScore?: number; maxScore?: number; informalRank?: string },
): { oldTier: RankInfo | null; newTier: RankInfo | null } {
  const newTier = getSongTierInfoByRate(song.scoreRate, song.informalRank);
  if (!newTier) return { oldTier: null, newTier: null };
  const maxScore = song.maxScore ?? 0;
  const oldScore = song.oldScore ?? 0;
  if (maxScore <= 0 || oldScore <= 0) return { oldTier: null, newTier };
  const oldTier = getSongTierInfoByRate((oldScore / maxScore) * 100, song.informalRank);
  if (!oldTier) return { oldTier: null, newTier };
  const same = oldTier.name === newTier.name && (oldTier.tier ?? '') === (newTier.tier ?? '');
  return { oldTier: same ? null : oldTier, newTier };
}

/** ティアの表示名（"Elite 1" / "Legend"）。 */
export function tierLabel(info: RankInfo | null | undefined): string {
  if (!info) return '---';
  return info.tier ? `${info.name} ${info.tier}` : info.name;
}

/** クリアランプの短縮表記（行内に収めるため）。未知の値はそのまま返す。 */
const CLEAR_TYPE_SHORT: Record<string, string> = {
  'FULLCOMBO CLEAR': 'FULL COMBO',
  'EX HARD CLEAR': 'EX HARD',
  'HARD CLEAR': 'HARD',
  'CLEAR': 'CLEAR',
  'EASY CLEAR': 'EASY',
  'ASSIST CLEAR': 'ASSIST',
  'FAILED': 'FAILED',
  'NO PLAY': 'NO PLAY',
};

export function clearTypeShort(type: string | undefined): string {
  if (!type) return '';
  return CLEAR_TYPE_SHORT[type] ?? type;
}

/** レポート冒頭に並べる集計値。 */
export interface ReportStats {
  /** 更新された譜面数。 */
  updated: number;
  /** EX スコアの合計増加量。 */
  exGain: number;
  /** クリアランプが上がった譜面数。 */
  lampUp: number;
  /** 過去作が持っていた歴代ベストを塗り替えた譜面数。 */
  allTimeBest: number;
  /** 今回新たに AAA に乗った譜面数。 */
  newAaa: number;
  /** BEAT-PT TOP100 に入っている更新譜面数。 */
  top100: number;
}

export function computeReportStats(diff: UploadDiffResult | null): ReportStats {
  const songs = diff?.updatedSongs ?? [];
  return {
    updated: songs.length,
    exGain: songs.reduce((sum, s) => sum + Math.max(0, s.scoreIncrease || 0), 0),
    lampUp: songs.filter(s => s.clearTypeImproved).length,
    allTimeBest: songs.filter(s => s.allTimeBestUpdated).length,
    newAaa: songs.filter(isNewAaa).length,
    top100: songs.filter(s => s.isInTop100 && s.beatPtIncrease > 0).length,
  };
}

export type StatKey = keyof ReportStats;

/**
 * 【関数の役割】 集計タイルに出す項目を選ぶ。常に出す 2 つ（更新譜面・EX 合計）に、値が 1 以上の
 * 項目を優先順で足して count 枚にする。足りなければ 0 件の項目で埋めて枚数を保つ。
 */
export function pickStatTiles(stats: ReportStats, count = 4): { key: StatKey; value: number }[] {
  const order: StatKey[] = ['updated', 'exGain', 'allTimeBest', 'newAaa', 'lampUp', 'top100'];
  const always: StatKey[] = ['updated', 'exGain'];
  const picked = order.filter(k => always.includes(k) || stats[k] > 0).slice(0, count);
  for (const k of order) {
    if (picked.length >= count) break;
    if (!picked.includes(k)) picked.push(k);
  }
  return picked.map(key => ({ key, value: stats[key] }));
}

/** 集計タイルの値の表示形式（EX 合計だけ "+12,345"、他は件数）。 */
export function formatStatValue(key: StatKey, value: number): string {
  return key === 'exGain' ? `+${value.toLocaleString('en-US')}` : String(value);
}

/** 更新曲 1 行を一意に指すキー（曲名 + 難易度）。共有画像の行キーと、自由選択の選択状態で共用する。 */
export function songKey(song: Pick<UpdatedSong, 'title' | 'difficulty'>): string {
  return `${song.title} ${song.difficulty}`;
}

/** 更新曲の並び順。レポートの一覧・共有画像・自由選択の候補リストで共用する。 */
export type SongSort = 'beat' | 'rate' | 'tier' | 'gain' | 'level';

/**
 * 【関数の役割】 単曲ティアの並び替え用の値（小さいほど上位）。
 *
 * ティア階段上の連続位置（0 = Legend、50 = Novice 1）をそのまま返す
 * （{@link getFolderRankIndexByRate}）。並べるとティア順になり、同じティアの中では
 * 次のティアに近い曲が先に来る。
 * 単曲ティアが付かない譜面（難易度表の対象外・Beginner 帯）は末尾に回す。
 */
export function songTierSortValue(song: { scoreRate?: number; informalRank?: string }): number {
  if (!getSongTierInfo(song)) return Number.POSITIVE_INFINITY;
  const rank = getNumericRank(song.informalRank) ?? undefined;
  return getFolderRankIndexByRate(song.scoreRate ?? 0, rank);
}

/** 【関数の役割】 更新曲を指定の順に並べた新しい配列を返す（入力は変更しない）。同順位は BEAT-PT の高い順。 */
export function sortUpdatedSongs(songs: UpdatedSong[], sort: SongSort): UpdatedSong[] {
  const byBeat = (a: UpdatedSong, b: UpdatedSong) => b.newBeatPt - a.newBeatPt;
  const level = (s: UpdatedSong) => Number(getNumericRank(s.informalRank) ?? 0);
  const list = [...songs];
  switch (sort) {
    case 'rate': return list.sort((a, b) => b.newRatePt - a.newRatePt);
    case 'gain': return list.sort((a, b) => b.scoreIncrease - a.scoreIncrease || byBeat(a, b));
    case 'level': return list.sort((a, b) => level(b) - level(a) || byBeat(a, b));
    case 'tier': {
      // Infinity 同士の引き算は NaN になるので、値は先に求めて比較で並べる。
      const value = new Map(list.map(s => [s, songTierSortValue(s)]));
      return list.sort((a, b) => {
        const va = value.get(a)!;
        const vb = value.get(b)!;
        return va === vb ? byBeat(a, b) : va < vb ? -1 : 1;
      });
    }
    default: return list.sort(byBeat);
  }
}

/** 共有画像に載せる更新曲の上限。 */
export const SHARE_MAX_SONGS = 10;

/** 共有画像の右端の列に出す指標（BEAT-PT / RATE-PT / 単曲ティア）。 */
export type ShareColumn = 'beat' | 'rate' | 'tier';

/** 共有画像内の曲タイトル。LEGGENDARIA は [L] を付けて ANOTHER と区別する（IIDX コミュニティの慣習表記）。 */
export function displayTitle(song: Pick<UpdatedSong, 'title' | 'difficulty'>): string {
  return song.difficulty === 'LEGGENDARIA' ? `${song.title} [L]` : song.title;
}
