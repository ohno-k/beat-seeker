import { ref } from 'vue';
import { API_BASE } from './constants';

/**
 * 【Composable の役割】 TL (チームリーダー) 専用 URL `/competition/tl/{token}` 経由で
 * 自チームのラインアップを管理するための API ラッパ。
 *
 * 招待トークンと同様、ログイン不要。URL の {@code token} がそのまま本人確認材料。
 * 認可は CompetitionTlController 側で「token → team」解決時にチェックされる。
 */

export type MatchKind = 'vanguard' | 'middle' | 'captain';
export type SongGenre = 'NOTES' | 'PEAK' | 'CHORD' | 'CHARGE' | 'SCRATCH' | 'SOF-LAN' | 'INSANE';

export interface TlTeamDto {
  id: number;
  teamName: string;
  teamOrder: number;
}

export interface TlCompetitionDto {
  id: number;
  name: string;
  status: 'draft' | 'open' | 'locked' | 'finished';
  deadlineAt: string | null;
  lockedAt: string | null;
}

export interface TlMemberDto {
  id: number;
  displayName: string;
  isTl: boolean;
  /** 予選で既に消費済のコスト (決勝 matchup は除外)。 */
  spentCost: number;
  /** 残りコスト = 初期 - spentCost。負の値にはならない (サーバ側で 0 未満アサインを拒否)。 */
  remainingCost: number;
}

export interface TlAssignedMineDto {
  participantId: number;
  displayName: string;
  isTl: boolean;
  /** 自チーム slot の場合のみ提出有無を含める (相手側にはこの情報を出さない設計)。 */
  pickSubmitted: boolean;
}
export interface TlAssignedOpponentDto {
  participantId: number;
  displayName: string;
}

export interface TlMatchDto {
  matchId: number;
  matchKind: MatchKind;
  /** 運営の指定ジャンル。null なら未指定。 */
  requiredGenre: SongGenre | null;
  myLocked: boolean;
  opponentLocked: boolean;
  myAssigned: TlAssignedMineDto | null;
  opponentAssigned: TlAssignedOpponentDto | null;
}


export interface TlMatchupDto {
  matchupId: number;
  matchupOrder: number;
  mySide: 'a' | 'b';
  /** 自軍のラインアップが主催により公開されているか。 */
  myLineupPublished: boolean;
  /** 相手チームのラインアップが主催により公開されているか。未公開なら opponentAssigned は null。 */
  opponentLineupPublished: boolean;
  opponentTeam: { id: number | null; teamName: string | null };
  /** vanguard → middle → captain の固定順で 3 件返る。 */
  matches: TlMatchDto[];
}

export interface TlViewDto {
  team: TlTeamDto;
  competition: TlCompetitionDto;
  members: TlMemberDto[];
  matchups: TlMatchupDto[];
  /** 予選プレイヤー初期コスト (デフォルト 80)。 */
  initialCost: number;
  /** matchKind → 1 戦あたりの消費コスト。 */
  costPerKind: Record<MatchKind, number>;
}

/** 運営チャット 1 メッセージ。sender = 'tl' (自分) / 'admin' (運営)。 */
export interface ChatMessageDto {
  id: number;
  sender: 'tl' | 'admin';
  body: string;
  createdAt: string;
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

export function useCompetitionTl() {
  const view = ref<TlViewDto | null>(null);
  const isLoading = ref(false);

  const fetchView = async (token: string): Promise<void> => {
    isLoading.value = true;
    try {
      const res = await fetch(`${API_BASE}/api/competition-access/tl/${token}`);
      await throwIfError(res);
      view.value = (await res.json()) as TlViewDto;
    } finally {
      isLoading.value = false;
    }
  };

  /**
   * 試合 1 件にメンバーをアサインする。{@code participantId = null} で空席に戻す。
   * サーバ側でロック・自チームメンバー・同一matchup重複の検証を行う。
   */
  const assign = async (token: string, matchId: number, participantId: number | null): Promise<void> => {
    const res = await fetch(
      `${API_BASE}/api/competition-access/tl/${token}/match/${matchId}/assign`,
      { method: 'PUT', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify({ participantId }) },
    );
    await throwIfError(res);
    await fetchView(token);
  };

  const unassign = (token: string, matchId: number) => assign(token, matchId, null);

  // ── 運営チャット ─────────────────────────────────────────
  /** 自チームスレッドのチャット履歴を取得 (古い順)。 */
  const fetchChat = async (token: string): Promise<ChatMessageDto[]> => {
    const res = await fetch(`${API_BASE}/api/competition-access/tl/${token}/chat`);
    await throwIfError(res);
    return (await res.json()) as ChatMessageDto[];
  };

  /** 運営へメッセージを送信 (送信時に運営へメール通知が飛ぶ)。 */
  const sendChat = async (token: string, body: string): Promise<ChatMessageDto> => {
    const res = await fetch(`${API_BASE}/api/competition-access/tl/${token}/chat`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ body }),
    });
    await throwIfError(res);
    return (await res.json()) as ChatMessageDto;
  };

  return { view, isLoading, fetchView, assign, unassign, fetchChat, sendChat };
}
