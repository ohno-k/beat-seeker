import { ref } from 'vue';
import { API_BASE } from './constants';

/**
 * 【Composable の役割】 観戦客向け対戦表 `/competition/spectator/{token}` 経由で
 * 公開済みの対戦表を読み取り専用で取得する API ラッパ (team5 用)。
 *
 * ログイン不要。URL の {@code token} がそのまま閲覧キー。
 * サーバ側 (CompetitionSpectatorController) が staged reveal を壊さないよう、
 * 起用公開済みのラインアップ・指定ジャンル・記録済みの結果だけを返す。
 */

// 戦種別 (予選 3 戦 / 決勝 7 戦) の定義は competitionMatchKinds に集約。
export type { MatchKind } from './competitionMatchKinds';
import type { MatchKind } from './competitionMatchKinds';
export type SongGenre = 'NOTES' | 'PEAK' | 'CHORD' | 'CHARGE' | 'SCRATCH' | 'SOF-LAN' | 'INSANE';

export interface SpectatorCompetitionDto {
  id: number;
  name: string;
  status: 'draft' | 'open' | 'locked' | 'finished';
  deadlineAt: string | null;
  lockedAt: string | null;
}

export interface SpectatorTeamDto {
  id: number;
  teamName: string;
  teamOrder: number;
}

export interface SpectatorMatchDto {
  matchId: number;
  matchKind: MatchKind;
  /** 運営の指定ジャンル。null なら未指定。 */
  requiredGenre: SongGenre | null;
  /** A 側起用名。ラインアップ未公開 or 未割当なら null。 */
  playerAName: string | null;
  /** B 側起用名。ラインアップ未公開 or 未割当なら null。 */
  playerBName: string | null;
  /** 結果が記録済みか。false の間は以下の結果フィールドは含まれない。 */
  resultRecorded: boolean;
  aSongsWon?: number | null;
  bSongsWon?: number | null;
  resultRecordedAt?: string | null;
  song1Title?: string | null;
  song1ScoreA?: number | null;
  song1ScoreB?: number | null;
  song2Title?: string | null;
  song2ScoreA?: number | null;
  song2ScoreB?: number | null;
}

export interface SpectatorMatchupDto {
  matchupId: number;
  matchupOrder: number;
  isFinals: boolean;
  teamA: SpectatorTeamDto | null;
  teamB: SpectatorTeamDto | null;
  /** A 側のラインアップが主催により公開されているか。 */
  lineupPublishedA: boolean;
  /** B 側のラインアップが主催により公開されているか。 */
  lineupPublishedB: boolean;
  /** 先鋒 → … → 大将 の固定順。予選は 3 件、決勝は 7 件返る。 */
  matches: SpectatorMatchDto[];
}

export interface SpectatorViewDto {
  competition: SpectatorCompetitionDto;
  teams: SpectatorTeamDto[];
  /** 設定済み matchup を matchupOrder 昇順で。未設定は含まれない。 */
  matchups: SpectatorMatchupDto[];
}

async function throwIfError(res: Response): Promise<void> {
  if (res.ok) return;
  let msg = `HTTP ${res.status}`;
  try {
    const data = await res.json();
    if (data && typeof data.message === 'string') msg = data.message;
  } catch { /* not JSON */ }
  throw new Error(msg);
}

export function useCompetitionSpectator() {
  const view = ref<SpectatorViewDto | null>(null);
  const isLoading = ref(false);

  const fetchView = async (token: string): Promise<void> => {
    isLoading.value = true;
    try {
      const res = await fetch(`${API_BASE}/api/competition-access/spectator/${token}`);
      await throwIfError(res);
      view.value = (await res.json()) as SpectatorViewDto;
    } finally {
      isLoading.value = false;
    }
  };

  return { view, isLoading, fetchView };
}
