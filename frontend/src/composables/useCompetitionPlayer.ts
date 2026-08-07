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

// 戦種別 (予選 3 戦 / 決勝 7 戦) の定義は competitionMatchKinds に集約。
export type { MatchKind } from './competitionMatchKinds';
import type { MatchKind } from './competitionMatchKinds';
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
  /** "team5" (既存) または "individual4" (個人戦)。view 内ロジックの分岐用。 */
  format?: 'team5' | 'individual4';
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

// ── 個人戦 (individual4) 用 read-only ビュー ────────────

export interface PlayerIndividualSlotDto {
  slotPosition: number;
  participantId: number | null;
  participantName: string | null;
  isMe: boolean;
  score1: number | null;
  score2: number | null;
  score3: number | null;
  score4: number | null;
  rank1: number | null;
  rank2: number | null;
  rank3: number | null;
  rank4: number | null;
  points1: number | null;
  points2: number | null;
  points3: number | null;
  points4: number | null;
  totalPoints: number | null;
}
export interface PlayerIndividualMatchDto {
  matchId: number;
  matchOrder: number;
  isFinals: boolean;
  finalsBucket: number | null;
  resultRecordedAt: string | null;
  song1Title: string | null;
  song2Title: string | null;
  song3Title: string | null;
  song4Title: string | null;
  slots: PlayerIndividualSlotDto[];
}
export interface PlayerIndividualSelfDto {
  id: number;
  displayName: string;
  teamId: null;
  isTl: false;
}
export interface PlayerIndividualViewDto {
  participant: PlayerIndividualSelfDto;
  competition: PlayerCompetitionDto; // format === 'individual4'
  matches: PlayerIndividualMatchDto[];
}

/** 取得結果が team5 か individual4 のどちらの shape か判別するための discriminator。 */
export type AnyPlayerView =
  | { kind: 'team5'; data: PlayerViewDto }
  | { kind: 'individual4'; data: PlayerIndividualViewDto };

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
  /** team5 用 view。individual4 大会の場合は別 composable を使う。 */
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

  // StrategyCard 発動可否の決定は TL に移管 (useCompetitionTl#setStrategy)。
  // 選手画面は myStrategyUse を閲覧表示するのみで、ここから更新はしない。

  return { view, isLoading, fetchView, upsertPick, deletePick };
}
