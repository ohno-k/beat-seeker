/**
 * 【ユーティリティの役割】 プレイ成果レポートに載せるリーグモードの進捗（スナップショット）を作る。
 *
 * 2026-09-23: 以前はレポートを開くたびに「今」のリーグ状況を取っていたため、成長記録から開いた過去の
 * レポートにも今週のリーグが出ていた。アップロード時点の状況をここで作って成長記録（score_history_logs.league_json）
 * に保存し、過去のレポートはその保存分を表示する。今回の更新に課題曲が含まれないときは作らない（表示もしない）。
 */
import type { LeagueCurrent } from '../composables/useLeague';
import type { UpdatedSong } from '../types/UploadDiff';

/** リーグ課題曲 1 曲分の内訳（自己ベストとラインの比較・今回の更新有無）。 */
export interface LeagueSongProgress {
  slot: number;
  title: string;
  difficultyName: string;
  level: number | null;
  /** リザルトが有効か（週内プレー + ライン超え）。 */
  valid: boolean;
  /** 自己ベスト EX とそのスコアレート(%)。未プレーは null。 */
  bestEx: number | null;
  rate: number | null;
  /** グループ共通のライン（週開始時点の最高 EX）とそのレート(%)。誰も未プレーなら null。 */
  lineEx: number | null;
  lineRate: number | null;
  /** 今回のアップロードでこの譜面が更新されたか。 */
  updated: boolean;
  /** 今回の EX 増加量（updated のときのみ 1 以上）。 */
  scoreIncrease: number;
  /** 今回のアップロードでラインを超えて有効化されたか。 */
  justActivated: boolean;
  /** ライン超えに必要な残り EX。達成済み・ライン未設定なら null。 */
  toLine: number | null;
}

/** アップロード時点のリーグの進捗。成長記録に JSON で保存するので、形を変えるときは古い形も読めるようにする。 */
export interface LeagueReportSnapshot {
  /** 開催回の通し番号（#6 など）と期間。どの週のリーグかを示す。 */
  weekNo: number | null;
  startsAt: string | null;
  endsAt: string | null;
  tier: number;
  groupIndex: number;
  rank: number;
  groupSize: number;
  validSongs: number;
  /** 集計対象の課題曲数（管理者が無効化した曲を除いた数。通常は 3）。 */
  songCount: number;
  resultValue: number | null;
  projectedPoints: number;
  zone: 'promote' | 'stay' | 'relegate';
  songs: LeagueSongProgress[];
}

/**
 * 【関数の役割】 リーグの現在の状況と今回の更新曲から、レポート用のスナップショットを作る。
 *
 * 次のどれかなら null（レポートにリーグ欄を出さない）:
 *  - 開催中(active)の週にメンバーとして参加していない（途中参加者は翌週から）
 *  - 今回の更新に、集計対象の課題曲が 1 曲も含まれていない
 */
export function buildLeagueSnapshot(cur: LeagueCurrent, userId: number, updatedSongs: UpdatedSong[]): LeagueReportSnapshot | null {
  if (!cur.member || cur.week?.status !== 'active' || !cur.standings) return null;
  const myRow = cur.standings.find((r) => r.userId === userId);
  if (!myRow) return null;

  const updatedByChart = new Map<string, UpdatedSong>();
  for (const u of updatedSongs) updatedByChart.set(`${u.title}|${u.difficulty}`, u);

  // 管理者が無効化した課題曲（解禁不可能な選曲など）は集計対象外なので、この報告からも外す。
  const allSongs = cur.songs || [];
  const scoredSongs = allSongs.filter((s) => !s.disabled);
  if (!scoredSongs.some((s) => updatedByChart.has(`${s.title}|${s.difficultyName}`))) return null;

  return {
    weekNo: cur.week.weekNo ?? null,
    startsAt: cur.week.startsAt ?? null,
    endsAt: cur.week.endsAt ?? null,
    tier: cur.member.tier,
    groupIndex: cur.member.groupIndex,
    rank: myRow.rank,
    groupSize: cur.standings.length,
    validSongs: myRow.validSongs,
    songCount: allSongs.length ? scoredSongs.length : 3,
    resultValue: myRow.resultValue,
    projectedPoints: myRow.projectedPoints ?? 0,
    zone: myRow.zone,
    songs: scoredSongs.map((s) => {
      const ps = myRow.perSong?.find((p) => p.slot === s.slot);
      const up = updatedByChart.get(`${s.title}|${s.difficultyName}`);
      const valid = !!ps?.valid;
      const bestEx = ps?.bestEx ?? null;
      const lineEx = ps?.lineEx ?? s.lineEx ?? null;
      // レートはノーツ数（MAX = notes * 2）から出す。自己ベスト側はサーバ計算値をそのまま使う。
      const lineRate = lineEx != null && s.notes > 0 ? (lineEx / (s.notes * 2)) * 100 : null;
      return {
        slot: s.slot,
        title: s.title,
        difficultyName: s.difficultyName,
        level: s.level ?? null,
        valid,
        bestEx,
        rate: ps?.rate ?? null,
        lineEx,
        lineRate,
        updated: !!up,
        scoreIncrease: up?.scoreIncrease ?? 0,
        // 今回の更新で初めてラインを超えた曲（＝このアップロードで有効化された曲）。
        justActivated: !!up && valid && (lineEx == null || up.oldScore <= lineEx),
        // ライン超えに必要な残り EX（ラインちょうどでは無効なので +1 必要）。
        toLine: !valid && lineEx != null ? lineEx - (bestEx ?? 0) + 1 : null,
      };
    }),
  };
}

/** 【関数の役割】 成長記録に保存した JSON を読む（壊れていれば null）。 */
export function parseLeagueSnapshot(json: string | null | undefined): LeagueReportSnapshot | null {
  if (!json) return null;
  try {
    const v = JSON.parse(json);
    return v && typeof v === 'object' && Array.isArray(v.songs) ? (v as LeagueReportSnapshot) : null;
  } catch {
    return null;
  }
}
