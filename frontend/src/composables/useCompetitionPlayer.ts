import { ref } from 'vue';
import { API_BASE } from './constants';

/**
 * 【Composable の役割】 参加者向けトークン認証 API (`/api/competition-access/player/**`) の薄いラッパ。
 *
 * 招待 URL `/competition/player/{token}` でアクセスした参加者用画面 (CompetitionPlayerView) が消費する。
 * ログイン不要・JWT 不要で、URL に埋め込まれた {@code token} がそのまま本人確認材料になる。
 *
 * Phase 4 でデータモデル変更:
 *  - 自選曲は (プレイヤー × 試合) 単位に変更。各 match 内に `myPick` を含めて返す。
 *  - 各 match に運営が指定する `requiredGenre` が付く。プレイヤーはこのジャンル内からしか選曲できない。
 *  - エンドポイントも `/picks/{matchKind}` → `/picks/match/{matchId}` に変更。
 */

export type MatchKind = 'vanguard' | 'middle' | 'captain';
export type SongDiff = 'A' | 'L';
export type SongGenre = 'NOTES' | 'PEAK' | 'CHORD' | 'CHARGE' | 'SCRATCH' | 'SOF-LAN' | 'INSANE';

export interface PlayerSelfDto {
  id: number;
  displayName: string;
  isTl: boolean;
  teamId: number;
}
export interface PlayerTeamDto {
  id: number;
  teamName: string;
  teamOrder: number;
}
export interface PlayerCompetitionDto {
  id: number;
  name: string;
  status: 'draft' | 'open' | 'locked' | 'finished';
  deadlineAt: string | null;
  lockedAt: string | null;
}

/** 自分用 pick (submittedAt 等メタ込み)。 */
export interface PlayerPickDto {
  songGenre: SongGenre;
  songLevel: number;
  songStrategyId: number;
  songTitle: string;
  songDiff: SongDiff;
  submittedAt: string;
  updatedAt: string;
}

/** 公開用 (相手にも見える) pick。メタ情報は含まない。 */
export interface OpponentPickDto {
  songGenre: SongGenre;
  songLevel: number;
  songStrategyId: number;
  songTitle: string;
  songDiff: SongDiff;
}

export interface PlayerMatchOpponentDto {
  participantId: number;
  displayName: string;
  teamName: string | null;
}
export interface PlayerMatchStrategyDto {
  enabled: boolean;
  decidedAt: string;
}
export interface PlayerMatchDto {
  matchId: number;
  matchKind: MatchKind;
  /** 運営の指定ジャンル。null なら未指定 (= まだ提出 UI を見せない)。 */
  requiredGenre: SongGenre | null;
  mySide: 'a' | 'b';
  myLocked: boolean;
  opponentLocked: boolean;
  /** 相手起用 (ラインアップ) が主催により公開されているか。未公開なら opponent は null。 */
  opponentLineupPublished: boolean;
  /** 相手自選曲が主催により公開されているか。未公開なら opponentPick は null。 */
  opponentPickPublished: boolean;
  /** 公開済のときだけ詰まる。未公開時は null。 */
  opponent: PlayerMatchOpponentDto | null;
  /** 自分の自選曲 (試合別)。 */
  myPick: PlayerPickDto | null;
  /** 相手自選曲が公開済のときだけ詰まる。未公開時は null。 */
  opponentPick: OpponentPickDto | null;
  myStrategyUse: PlayerMatchStrategyDto | null;
}

/**
 * 自チームの StrategyCard 使用枠。予選では 1 チームあたり 2 matchup 上限。
 * 既に使用済 matchup の ID 集合と上限値を返す。
 */
export interface PlayerStrategyQuotaDto {
  limit: number;
  usedMatchupCount: number;
  usedMatchupIds: number[];
}

export interface PlayerViewDto {
  participant: PlayerSelfDto;
  team: PlayerTeamDto;
  competition: PlayerCompetitionDto;
  strategyQuota: PlayerStrategyQuotaDto;
  matches: PlayerMatchDto[];
}

/** 自選曲 upsert の payload。songGenre は match.requiredGenre と一致する必要あり。 */
export interface PickPayload {
  songGenre: SongGenre;
  songLevel: number;
  songStrategyId: number;
  songTitle: string;
  songDiff: SongDiff;
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

export function useCompetitionPlayer() {
  const view = ref<PlayerViewDto | null>(null);
  const isLoading = ref(false);

  const fetchView = async (token: string): Promise<void> => {
    isLoading.value = true;
    try {
      const res = await fetch(`${API_BASE}/api/competition-access/player/${token}`);
      await throwIfError(res);
      view.value = (await res.json()) as PlayerViewDto;
    } finally {
      isLoading.value = false;
    }
  };

  /** 試合別自選曲を upsert する。サーバが requiredGenre 一致・Lv 帯を再検証する。 */
  const upsertPick = async (token: string, matchId: number, payload: PickPayload): Promise<void> => {
    const res = await fetch(
      `${API_BASE}/api/competition-access/player/${token}/picks/match/${matchId}`,
      { method: 'PUT', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify(payload) },
    );
    await throwIfError(res);
    await fetchView(token);
  };

  /** 試合別自選曲を取消す。 */
  const deletePick = async (token: string, matchId: number): Promise<void> => {
    const res = await fetch(
      `${API_BASE}/api/competition-access/player/${token}/picks/match/${matchId}`,
      { method: 'DELETE' },
    );
    await throwIfError(res);
    await fetchView(token);
  };

  /** ある試合に対する StrategyCard 使用フラグを更新する。 */
  const setStrategy = async (token: string, matchId: number, enabled: boolean): Promise<void> => {
    const res = await fetch(
      `${API_BASE}/api/competition-access/player/${token}/strategy/${matchId}`,
      { method: 'PUT', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify({ enabled }) },
    );
    await throwIfError(res);
    await fetchView(token);
  };

  return { view, isLoading, fetchView, upsertPick, deletePick, setStrategy };
}
