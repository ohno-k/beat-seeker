import { ref } from 'vue';
import { API_BASE } from './constants';
import { TOKEN_KEY } from './constants';

/**
 * 【Composable の役割】 大会主催向け管理 API (`/api/competitions/**`) の薄いラッパ。
 *
 * フェーズ 1 で実装したバックエンド (CompetitionAdminController) を Vue 側から呼び出す。
 * 4 ID ホワイトリスト (18 / 19 / 23 / 210) 判定はサーバ側で行われ、それ以外のユーザーは 500 が返る。
 *
 * 同時編集する大会は通常 1 件なので、現在表示中の大会詳細をローカル ref で保持する。
 */

export type CompetitionStatus = 'draft' | 'open' | 'locked' | 'finished';

export interface CompetitionSummary {
  id: number;
  name: string;
  status: CompetitionStatus;
  deadlineAt: string | null;
  createdAt: string;
  lockedAt: string | null;
  createdById: number | null;
}

export interface CompetitionTeamDto {
  id: number;
  teamName: string;
  teamOrder: number;
  tlToken: string;
}

export interface CompetitionParticipantDto {
  id: number;
  teamId: number;
  displayName: string;
  inviteToken: string;
  isTl: boolean;
  createdAt: string;
}

export interface CompetitionMatchupDto {
  id: number;
  matchupOrder: number;
  teamAId: number;
  teamBId: number;
  /** A 側のラインアップ (TL の起用) を B 側に公開しているか。主催が任意のタイミングで切替。 */
  lineupPublishedA: boolean;
  /** B 側のラインアップを A 側に公開しているか。 */
  lineupPublishedB: boolean;
  /** 決勝 matchup フラグ。コスト/StrategyCard 制限の対象外。 */
  isFinals: boolean;
}

export type CompetitionSongGenre = 'NOTES' | 'PEAK' | 'CHORD' | 'CHARGE' | 'SCRATCH' | 'SOF-LAN' | 'INSANE';

export interface CompetitionMatchDto {
  id: number;
  matchupId: number;
  matchKind: 'vanguard' | 'middle' | 'captain';
  /** 運営が試合に指定したジャンル。null なら未指定 (= プレイヤー側に提出 UI を出さない)。 */
  requiredGenre: CompetitionSongGenre | null;
  playerAId: number | null;
  playerBId: number | null;
  lockedA: boolean;
  lockedB: boolean;
  lockedAAt: string | null;
  lockedBAt: string | null;
  /** A 側の自選曲を B 側に公開しているか。試合直前で主催が切り替える想定。 */
  pickPublishedA: boolean;
  /** B 側の自選曲を A 側に公開しているか。 */
  pickPublishedB: boolean;
  /** 試合結果: A 側勝ち曲数 (0/1/2)。null = 未記録。スコアから自動派生。 */
  aSongsWon: number | null;
  /** 試合結果: B 側勝ち曲数 (0/1/2)。null = 未記録。 */
  bSongsWon: number | null;
  /** 試合結果が記録された日時。null = 未記録。 */
  resultRecordedAt: string | null;
  // R-4: 1 戦 = 2 曲制の詳細スコア
  song1StrategyId: number | null;
  song1Title: string | null;
  song1ScoreA: number | null;
  song1ScoreB: number | null;
  song2StrategyId: number | null;
  song2Title: string | null;
  song2ScoreA: number | null;
  song2ScoreB: number | null;
}

/** 試合結果スコア入力 payload (両曲ぶん)。 */
export interface MatchResultPayload {
  song1StrategyId: number | null;
  song1Title: string | null;
  song1ScoreA: number | null;
  song1ScoreB: number | null;
  song2StrategyId: number | null;
  song2Title: string | null;
  song2ScoreA: number | null;
  song2ScoreB: number | null;
}

// ── Song Reveal 連携用 DTO ─────────────────────────────

export interface CompetitionRevealPick {
  songGenre: CompetitionSongGenre;
  songLevel: number;
  songStrategyId: number;
  songTitle: string;
  songDiff: 'A' | 'L';
}

export interface CompetitionRevealMatch {
  matchId: number;
  matchKind: 'vanguard' | 'middle' | 'captain';
  requiredGenre: CompetitionSongGenre | null;
  matchupOrder: number;
  teamAName: string | null;
  teamBName: string | null;
  playerAName: string | null;
  playerBName: string | null;
  playerAPick: CompetitionRevealPick | null;
  playerBPick: CompetitionRevealPick | null;
  playerAStrategyUsed: boolean;
  playerBStrategyUsed: boolean;
}

export interface CompetitionRevealData {
  competitionId: number;
  competitionName: string;
  matches: CompetitionRevealMatch[];
}

// ── 順位表 / 決勝生成 ────────────────────────────────

export interface CompetitionStandingsRow {
  teamId: number;
  teamName: string;
  teamOrder: number;
  rank: number;
  songPoints: number;
  matchupPoints: number;
  totalPoints: number;
  wins: number;
  draws: number;
  losses: number;
}

export interface CompetitionStandingsDto {
  rows: CompetitionStandingsRow[];
  /** 予選 matchup の総数 (= 10)。 */
  prelimMatchupCount: number;
  /** 結果記録済みの予選 matchup 数。 */
  prelimRecordedCount: number;
  /** 全予選結果が記録済か。これが true なら決勝生成可能。 */
  allPrelimRecorded: boolean;
  /** 決勝 matchup が既に生成されているか。 */
  finalsExists: boolean;
}

export interface CompetitionDetail extends CompetitionSummary {
  teams: CompetitionTeamDto[];
  participants: CompetitionParticipantDto[];
  /** draft 状態の間は欠落。open 以降に 10 件登場する。 */
  matchups?: CompetitionMatchupDto[];
  /** draft 状態の間は欠落。open 以降に 30 件登場する。 */
  matches?: CompetitionMatchDto[];
}

/** Authorization ヘッダを共通生成 (useAuth と同じ TOKEN_KEY を参照)。 */
function authHeaders(): Record<string, string> {
  const headers: Record<string, string> = { 'Content-Type': 'application/json' };
  const token = localStorage.getItem(TOKEN_KEY);
  if (token) headers['Authorization'] = `Bearer ${token}`;
  return headers;
}

/**
 * エラーレスポンスを統一的に Error に変換する。
 * バックエンドは `{ message: "..." }` を返すので、その文字列を投げる。
 */
async function throwIfError(res: Response): Promise<void> {
  if (res.ok) return;
  let msg = `HTTP ${res.status}`;
  try {
    const data = await res.json();
    if (data && typeof data.message === 'string') msg = data.message;
  } catch { /* JSON でなければ status のまま */ }
  throw new Error(msg);
}

export function useCompetitionAdmin() {
  /** 一覧キャッシュ。初回 list 呼び出し後に埋まる。 */
  const competitions = ref<CompetitionSummary[]>([]);
  /** 詳細編集中の 1 件。null なら一覧表示モード。 */
  const currentCompetition = ref<CompetitionDetail | null>(null);
  /** 読み込み・更新中フラグ (UI のスピナー用)。 */
  const isLoading = ref(false);

  /** 大会一覧を取得して `competitions` を更新する。 */
  const fetchCompetitions = async (): Promise<void> => {
    isLoading.value = true;
    try {
      const res = await fetch(`${API_BASE}/api/competitions`, { headers: authHeaders() });
      await throwIfError(res);
      competitions.value = await res.json();
    } finally {
      isLoading.value = false;
    }
  };

  /** 新規大会を作成。レスポンスは詳細 (5 チーム枠込み)。 */
  const createCompetition = async (name: string): Promise<CompetitionDetail> => {
    isLoading.value = true;
    try {
      const res = await fetch(`${API_BASE}/api/competitions`, {
        method: 'POST',
        headers: authHeaders(),
        body: JSON.stringify({ name }),
      });
      await throwIfError(res);
      const detail = (await res.json()) as CompetitionDetail;
      currentCompetition.value = detail;
      return detail;
    } finally {
      isLoading.value = false;
    }
  };

  /** 大会 1 件の詳細を取得し、`currentCompetition` にセットする。 */
  const fetchCompetition = async (id: number): Promise<CompetitionDetail> => {
    isLoading.value = true;
    try {
      const res = await fetch(`${API_BASE}/api/competitions/${id}`, { headers: authHeaders() });
      await throwIfError(res);
      const detail = (await res.json()) as CompetitionDetail;
      currentCompetition.value = detail;
      return detail;
    } finally {
      isLoading.value = false;
    }
  };

  /** チーム名をリネームし、currentCompetition を再取得。 */
  const renameTeam = async (competitionId: number, teamId: number, teamName: string): Promise<void> => {
    const res = await fetch(
      `${API_BASE}/api/competitions/${competitionId}/teams/${teamId}`,
      { method: 'PUT', headers: authHeaders(), body: JSON.stringify({ teamName }) },
    );
    await throwIfError(res);
    await fetchCompetition(competitionId);
  };

  /** 参加者を追加し、currentCompetition を再取得。 */
  const addParticipant = async (
    competitionId: number,
    teamId: number,
    payload: { displayName: string; isTl: boolean },
  ): Promise<void> => {
    const res = await fetch(
      `${API_BASE}/api/competitions/${competitionId}/teams/${teamId}/participants`,
      { method: 'POST', headers: authHeaders(), body: JSON.stringify(payload) },
    );
    await throwIfError(res);
    await fetchCompetition(competitionId);
  };

  /** 参加者を更新し、currentCompetition を再取得。 */
  const updateParticipant = async (
    competitionId: number,
    participantId: number,
    payload: { displayName?: string; isTl?: boolean },
  ): Promise<void> => {
    const res = await fetch(
      `${API_BASE}/api/competitions/${competitionId}/participants/${participantId}`,
      { method: 'PUT', headers: authHeaders(), body: JSON.stringify(payload) },
    );
    await throwIfError(res);
    await fetchCompetition(competitionId);
  };

  /** 参加者を削除し、currentCompetition を再取得。 */
  const deleteParticipant = async (competitionId: number, participantId: number): Promise<void> => {
    const res = await fetch(
      `${API_BASE}/api/competitions/${competitionId}/participants/${participantId}`,
      { method: 'DELETE', headers: authHeaders() },
    );
    await throwIfError(res);
    await fetchCompetition(competitionId);
  };

  /**
   * 参加者の招待トークンを再採番する。誤公開時のリカバリ用。
   * 既存提出は残るが、新トークンを当該参加者に再配布する必要がある。
   */
  const regenerateParticipantToken = async (
    competitionId: number,
    participantId: number,
  ): Promise<void> => {
    const res = await fetch(
      `${API_BASE}/api/competitions/${competitionId}/participants/${participantId}/regenerate-token`,
      { method: 'POST', headers: authHeaders() },
    );
    await throwIfError(res);
    await fetchCompetition(competitionId);
  };

  /**
   * 試合 1 件の詳細スコア (2 曲分) を記録/更新する。
   * サーバ側でスコア比較 → aSongsWon / bSongsWon を自動算出する。
   */
  const setMatchResult = async (
    competitionId: number,
    matchId: number,
    payload: MatchResultPayload,
  ): Promise<void> => {
    const res = await fetch(
      `${API_BASE}/api/competitions/${competitionId}/matches/${matchId}/result`,
      { method: 'PUT', headers: authHeaders(), body: JSON.stringify(payload) },
    );
    await throwIfError(res);
    await fetchCompetition(competitionId);
  };

  /** 試合結果を未記録に戻す。 */
  const clearMatchResult = async (competitionId: number, matchId: number): Promise<void> => {
    const res = await fetch(
      `${API_BASE}/api/competitions/${competitionId}/matches/${matchId}/result`,
      { method: 'DELETE', headers: authHeaders() },
    );
    await throwIfError(res);
    await fetchCompetition(competitionId);
  };

  /** 現在の順位表を取得する。 */
  const fetchStandings = async (competitionId: number): Promise<CompetitionStandingsDto> => {
    const res = await fetch(
      `${API_BASE}/api/competitions/${competitionId}/standings`,
      { headers: authHeaders() },
    );
    await throwIfError(res);
    return (await res.json()) as CompetitionStandingsDto;
  };

  /** 予選全試合記録後、TOP2 で決勝 matchup を生成。 */
  const generateFinals = async (competitionId: number): Promise<void> => {
    const res = await fetch(
      `${API_BASE}/api/competitions/${competitionId}/generate-finals`,
      { method: 'POST', headers: authHeaders() },
    );
    await throwIfError(res);
    await fetchCompetition(competitionId);
  };

  /** TL トークンを再採番する。 */
  const regenerateTlToken = async (competitionId: number, teamId: number): Promise<void> => {
    const res = await fetch(
      `${API_BASE}/api/competitions/${competitionId}/teams/${teamId}/regenerate-tl-token`,
      { method: 'POST', headers: authHeaders() },
    );
    await throwIfError(res);
    await fetchCompetition(competitionId);
  };

  /**
   * 大会を関連レコードごと完全削除する。
   * 試合・matchup・参加者・チーム・自選曲・StrategyCard 使用記録まで全てまとめて消える。
   */
  const deleteCompetition = async (competitionId: number): Promise<void> => {
    const res = await fetch(
      `${API_BASE}/api/competitions/${competitionId}`,
      { method: 'DELETE', headers: authHeaders() },
    );
    await throwIfError(res);
    // 一覧から消す + 詳細を閉じる
    competitions.value = competitions.value.filter(c => c.id !== competitionId);
    if (currentCompetition.value?.id === competitionId) {
      currentCompetition.value = null;
    }
  };

  /** draft → open に遷移。サーバ側で 10 matchup + 30 match が自動生成される。 */
  const openCompetition = async (competitionId: number): Promise<void> => {
    const res = await fetch(
      `${API_BASE}/api/competitions/${competitionId}/open`,
      { method: 'POST', headers: authHeaders() },
    );
    await throwIfError(res);
    await fetchCompetition(competitionId);
  };

  /**
   * Song Reveal 用に、大会内の全試合 (player 名 + 自選曲 + StrategyCard 使用フラグ) を一括取得する。
   * 主催 (4 ID) ログイン状態で叩く前提。
   */
  const fetchRevealData = async (competitionId: number): Promise<CompetitionRevealData> => {
    const res = await fetch(
      `${API_BASE}/api/competitions/${competitionId}/reveal`,
      { headers: authHeaders() },
    );
    await throwIfError(res);
    return (await res.json()) as CompetitionRevealData;
  };

  /**
   * 試合に運営側のジャンル制約を設定する。null を渡せば指定解除。
   * INSANE は captain 戦のみ受け付ける (サーバ側で検証)。
   */
  const setMatchGenre = async (
    competitionId: number,
    matchId: number,
    genre: CompetitionSongGenre | null,
  ): Promise<void> => {
    const res = await fetch(
      `${API_BASE}/api/competitions/${competitionId}/matches/${matchId}/genre`,
      { method: 'PUT', headers: authHeaders(), body: JSON.stringify({ genre }) },
    );
    await throwIfError(res);
    await fetchCompetition(competitionId);
  };

  /**
   * matchup のラインアップ公開状態を更新する。
   * 公開されると相手チームの TL / プレイヤーが起用名を見られるようになる。
   */
  const publishLineup = async (
    competitionId: number,
    matchupId: number,
    side: 'a' | 'b' | 'both',
    published: boolean,
  ): Promise<void> => {
    const res = await fetch(
      `${API_BASE}/api/competitions/${competitionId}/matchups/${matchupId}/lineup-publish`,
      { method: 'PUT', headers: authHeaders(), body: JSON.stringify({ side, published }) },
    );
    await throwIfError(res);
    await fetchCompetition(competitionId);
  };

  /**
   * match の自選曲ロック (編集禁止フラグ) を切替。公開とは独立で、ロックされても未公開状態を維持できる。
   * 通常フロー: 締切時刻にロック → 試合直前に publishPick で開示。
   */
  const setMatchLock = async (
    competitionId: number,
    matchId: number,
    side: 'a' | 'b' | 'both',
    locked: boolean,
  ): Promise<void> => {
    const res = await fetch(
      `${API_BASE}/api/competitions/${competitionId}/matches/${matchId}/lock`,
      { method: 'PUT', headers: authHeaders(), body: JSON.stringify({ side, locked }) },
    );
    await throwIfError(res);
    await fetchCompetition(competitionId);
  };

  /**
   * match の自選曲公開状態を更新する。
   * 公開されると相手側プレイヤーが自選曲を見られるようになり、StrategyCard 決定が可能になる。
   */
  const publishPick = async (
    competitionId: number,
    matchId: number,
    side: 'a' | 'b' | 'both',
    published: boolean,
  ): Promise<void> => {
    const res = await fetch(
      `${API_BASE}/api/competitions/${competitionId}/matches/${matchId}/pick-publish`,
      { method: 'PUT', headers: authHeaders(), body: JSON.stringify({ side, published }) },
    );
    await throwIfError(res);
    await fetchCompetition(competitionId);
  };

  return {
    competitions,
    currentCompetition,
    isLoading,
    fetchCompetitions,
    createCompetition,
    fetchCompetition,
    renameTeam,
    addParticipant,
    updateParticipant,
    deleteParticipant,
    openCompetition,
    setMatchGenre,
    publishLineup,
    publishPick,
    setMatchLock,
    deleteCompetition,
    regenerateParticipantToken,
    regenerateTlToken,
    fetchRevealData,
    setMatchResult,
    clearMatchResult,
    fetchStandings,
    generateFinals,
  };
}
