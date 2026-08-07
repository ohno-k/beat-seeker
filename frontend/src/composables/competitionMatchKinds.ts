/**
 * 【モジュールの役割】 team5 大会の「戦種別 (matchKind)」定義をフロント側で 1 箇所に集約したもの。
 *
 * backend の {@code CompetitionMatchKinds} と対になる定義。予選と決勝で構成が異なる:
 *  - 予選 1 matchup = 3 戦 (先鋒 → 中堅 → 大将)
 *  - 決勝 1 matchup = 7 戦 (先鋒 → 次鋒 → 五将 → 中堅 → 三将 → 副将 → 大将)
 *
 * 先鋒 / 中堅 / 大将 は予選・決勝で同じコードを使い Lv 帯も共通。獲得ポイントだけ表が分かれる。
 * ラベルは i18n キー `competition.matchKind.{kind}` でも引けるが、日本語固定の画面
 * (運営 / 観戦 / SongReveal) 用に KIND_LABEL_JA も置いている。
 */

export type MatchKind =
  | 'vanguard'  // 先鋒
  | 'second'    // 次鋒 (決勝のみ)
  | 'fifth'     // 五将 (決勝のみ)
  | 'middle'    // 中堅
  | 'third'     // 三将 (決勝のみ)
  | 'vice'      // 副将 (決勝のみ)
  | 'captain';  // 大将

/** 決勝 1 matchup ぶんの戦種別 (この順で表示する)。予選の 3 戦もこの順序に従う。 */
export const FINALS_MATCH_KINDS: MatchKind[] = [
  'vanguard', 'second', 'fifth', 'middle', 'third', 'vice', 'captain',
];

/** 予選 1 matchup ぶんの戦種別。 */
export const PRELIM_MATCH_KINDS: MatchKind[] = ['vanguard', 'middle', 'captain'];

/** 戦種別 → 日本語ラベル。 */
export const KIND_LABEL_JA: Record<MatchKind, string> = {
  vanguard: '先鋒戦',
  second: '次鋒戦',
  fifth: '五将戦',
  middle: '中堅戦',
  third: '三将戦',
  vice: '副将戦',
  captain: '大将戦',
};

/** 戦種別 → 選曲可能な Lv 帯。 */
export const LEVELS_FOR_KIND: Record<MatchKind, number[]> = {
  vanguard: [8, 9, 10],
  second: [10],
  fifth: [11],
  middle: [11],
  third: [12],
  vice: [12],
  captain: [12],
};

/** 戦種別 → Lv 帯の表示文字列 ('Lv 8-10' / 'Lv 11' など)。 */
export const kindLevelLabel = (kind: MatchKind): string => {
  const levels = LEVELS_FOR_KIND[kind] ?? [];
  if (levels.length === 0) return '';
  if (levels.length === 1) return `Lv ${levels[0]}`;
  return `Lv ${levels[0]}-${levels[levels.length - 1]}`;
};

/** 予選の 1 曲あたり獲得ポイント (backend CompetitionMatchKinds.PRELIM_POINTS と対)。 */
export const PRELIM_POINTS_PER_SONG: Partial<Record<MatchKind, number>> = {
  vanguard: 2,
  middle: 3,
  captain: 4,
};

/** 決勝の 1 曲あたり獲得ポイント (backend CompetitionMatchKinds.FINALS_POINTS と対)。 */
export const FINALS_POINTS_PER_SONG: Record<MatchKind, number> = {
  vanguard: 4,
  second: 4,
  fifth: 5,
  middle: 5,
  third: 6,
  vice: 6,
  captain: 7,
};

/**
 * 1 曲勝つごとにチームへ入る戦ポイント。予選と決勝でポイント表が異なる。
 * 順位集計の本体は backend の CompetitionTeamStandingsService なので、
 * フロントでは「その matchup の総合結果を即時表示する」用途だけに使う。
 */
export const pointsPerSong = (kind: MatchKind, isFinals: boolean): number =>
  (isFinals ? FINALS_POINTS_PER_SONG[kind] : PRELIM_POINTS_PER_SONG[kind]) ?? 0;

/** 表示順のインデックス (先鋒 0 〜 大将 6)。未知の kind は末尾に寄せる。 */
export const kindOrder = (kind: MatchKind): number => {
  const idx = FINALS_MATCH_KINDS.indexOf(kind);
  return idx < 0 ? 99 : idx;
};

/** 決勝で連続出場になる組み合わせか (隣り合う戦かどうか)。 */
export const isAdjacentKind = (a: MatchKind, b: MatchKind): boolean =>
  Math.abs(kindOrder(a) - kindOrder(b)) === 1;

/** その戦の Lv 帯が 12 のみか。INSANE (Lv12 しか無いジャンル) を指定できる戦の判定に使う。 */
export const isLevel12Only = (kind: MatchKind): boolean => {
  const levels = LEVELS_FOR_KIND[kind] ?? [];
  return levels.length === 1 && levels[0] === 12;
};

/**
 * 決勝で 1 人の選手を起用できる最大戦数。
 * 1 チーム 4 人で 7 枠を埋めるため複数起用が前提 (上限 2 戦・連続出場禁止)。
 * 7 枠 ÷ 2 戦 = 最低 4 人必要なので、全枠が埋まれば 4 人全員の出場が保証される。
 */
export const FINALS_MAX_MATCHES_PER_PLAYER = 2;
