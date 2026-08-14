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

/**
 * 大会フォーマット。
 * - {@code team5}: 5 チーム × 4 名総当たり団体戦 (既存)。
 * - {@code individual4}: 4 人対戦 × 1 曲制の個人戦 (12 名 or 16 名)。
 */
export type CompetitionFormat = 'team5' | 'individual4';

export interface CompetitionSummary {
  id: number;
  name: string;
  format: CompetitionFormat;
  status: CompetitionStatus;
  deadlineAt: string | null;
  createdAt: string;
  lockedAt: string | null;
  createdById: number | null;
  /** OBS ブラウザソース公開トークン。発行前は null。 */
  obsToken: string | null;
  /** 観戦客向け対戦表公開トークン (team5 用)。発行前は null。 */
  spectatorToken: string | null;
  /**
   * 起用クローズ済みか (サーバ側で deadlineAt と現在時刻(JST)から算出した派生状態)。
   * true の間は TL の起用編集 (選手の割り当て) のみが締め切られる。自選曲提出は対象外。
   */
  lineupClosed: boolean;
  /** 起用 (オーダー) 公開日時 (ISO ローカル日時)。null なら公開日時未設定 (自動公開しない)。 */
  lineupPublishAt: string | null;
  /**
   * 起用 (オーダー) 公開済みか (サーバ側で lineupPublishAt と現在時刻(JST)から算出した派生状態)。
   * true になると対戦相手・観戦 URL・選手 URL に起用が公開される。起用クローズとは独立。
   */
  lineupPublished: boolean;
  /**
   * 決勝の起用クローズ日時 (ISO ローカル日時)。null なら未設定 = 決勝の起用はいつでも編集できる。
   * 決勝は予選終了後に生成されるため、予選の deadlineAt とは別スケジュールで持つ。
   */
  finalsDeadlineAt: string | null;
  /** 決勝の起用がクローズ済みか (finalsDeadlineAt からの派生状態)。 */
  finalsLineupClosed: boolean;
  /** 決勝の起用公開日時 (ISO ローカル日時)。null なら未設定 = 決勝の起用は非公開のまま。 */
  finalsLineupPublishAt: string | null;
  /** 決勝の起用が公開済みか (finalsLineupPublishAt からの派生状態)。 */
  finalsLineupPublished: boolean;
}

export interface CompetitionTeamDto {
  id: number;
  teamName: string;
  teamOrder: number;
  tlToken: string;
}

/** 運営チャット 1 メッセージ。sender = 'tl' (チームリーダー) / 'admin' (運営)。 */
export interface ChatMessageDto {
  id: number;
  sender: 'tl' | 'admin';
  body: string;
  createdAt: string;
}

/** チーム単位のチャットスレッド (管理画面の一覧用)。 */
export interface ChatThreadDto {
  teamId: number;
  teamName: string;
  /** 未読 (TL 発・運営未読) 件数。 */
  unreadCount: number;
  messages: ChatMessageDto[];
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
  /** 起用 (ラインアップ) が相手に公開済みか。起用クローズ日時 (deadlineAt) 到達で自動公開 (手動公開は廃止)。 */
  lineupPublished: boolean;
  /** 決勝 matchup フラグ。コスト/StrategyCard 制限の対象外。 */
  isFinals: boolean;
  /**
   * 運営が「設定済み (実施対象)」にしたか。
   * false の間は未設定 (matchupOrder=0) でプレイヤー/TL に表示されない。
   * 設定すると選んだ順に matchupOrder が採番され公開される。
   */
  configured: boolean;
}

export type CompetitionSongGenre = 'NOTES' | 'PEAK' | 'CHORD' | 'CHARGE' | 'SCRATCH' | 'SOF-LAN' | 'INSANE';

// 戦種別 (予選 3 戦 / 決勝 7 戦) の定義は competitionMatchKinds に集約。
export type { MatchKind } from './competitionMatchKinds';
import type { MatchKind } from './competitionMatchKinds';

export interface CompetitionMatchDto {
  id: number;
  matchupId: number;
  matchKind: MatchKind;
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
  /** A 側プレイヤーが StrategyCard 発動予定か (TL が決定)。 */
  strategyUsedA: boolean;
  /** B 側プレイヤーが StrategyCard 発動予定か (TL が決定)。 */
  strategyUsedB: boolean;
  /** A 側 TL が「発動する / 発動しない」を選択済みか。false = 未決定。 */
  strategyDecidedA: boolean;
  /** B 側 TL が「発動する / 発動しない」を選択済みか。false = 未決定。 */
  strategyDecidedB: boolean;
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
  /**
   * 1 曲目を運営が手動指定したか。true の枠は自選曲 / StrategyCard 抽選曲による自動導出を行わず、
   * 記録済みの song1StrategyId / song1Title をそのまま使う。
   */
  song1Manual: boolean;
  /** 2 曲目を運営が手動指定したか。 */
  song2Manual: boolean;
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
  /** 1 曲目を手動指定した枠として保存するか。 */
  song1Manual: boolean;
  /** 2 曲目を手動指定した枠として保存するか。 */
  song2Manual: boolean;
}

// ── Song Reveal 連携用 DTO ─────────────────────────────

export interface CompetitionRevealPick {
  songGenre: CompetitionSongGenre;
  songLevel: number;
  songStrategyId: number;
  songTitle: string;
  songDiff: 'A' | 'L';
}

/**
 * Strategy 発動側がサーバで抽選した曲。playerXStrategyResult として返る。
 * 「相手の曲がランダム化される」結果なので、A 側 strategy 申告 → playerAStrategyResult には
 * B 側の置き換わった曲 (B が実際に演奏する曲) が入る。
 */
export interface CompetitionStrategyResult {
  songStrategyId: number;
  songTitle: string;
  songVersion: string;
  songDiff: 'A' | 'L';
  songLevel: number;
  songGenre: CompetitionSongGenre;
}

export interface CompetitionRevealMatch {
  matchId: number;
  matchKind: MatchKind;
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
  /**
   * 相殺フラグ。A/B 双方が発動した試合は StrategyCard が打ち消し合い、両者とも自選曲を演奏する。
   * true の間はサーバが抽選を行わないため playerXStrategyResult も常に null で返る。
   */
  strategyCanceled: boolean;
  /** A 側申告時の抽選結果 = B 側が演奏する曲。相殺時は null。 */
  playerAStrategyResult: CompetitionStrategyResult | null;
  /** B 側申告時の抽選結果 = A 側が演奏する曲。相殺時は null。 */
  playerBStrategyResult: CompetitionStrategyResult | null;
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
  /**
   * StrategyCard を発動した予選 matchup 数。
   * 発動を決めただけでは増えず、その試合の結果が記録された時点でカウントされる。
   */
  strategyUsedMatchupCount: number;
  /** 予選で発動できる matchup 数の上限 (= 表示の分母)。 */
  strategyLimit: number;
}

/**
 * 5x5 マトリクス表示用に、各 matchup での両側総合ポイントを返す entry。
 * recorded=false のエントリも含まれる (画面で「?」表示)。
 */
export interface CompetitionMatchupBreakdown {
  matchupId: number;
  teamAId: number;
  teamBId: number;
  aSongPoints: number;
  bSongPoints: number;
  aMatchupPoints: number;
  bMatchupPoints: number;
  aTotalPoints: number;
  bTotalPoints: number;
  recorded: boolean;
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
  /** マトリクス用 breakdown (10 matchup ぶん)。 */
  matchupBreakdown: CompetitionMatchupBreakdown[];
  /** 予選で 1 チームが StrategyCard を発動できる matchup 数の上限。 */
  strategyLimit: number;
}

// ── 大会サマリー (試合別 / 選手別) ──────────────────────
// backend の CompetitionTeamSummaryService が返す形と 1:1。団体戦 (team5) 専用。
// 順位表が「チームの勝ち点」だけを返すのに対し、こちらは 1 曲単位のスコアと勝敗まで展開する。

/** 勝敗コード (試合別ビュー)。A = A 側の勝ち / B = B 側の勝ち / D = 引分。 */
export type SummaryResult = 'A' | 'B' | 'D';

/** 勝敗コード (選手別ビュー。本人視点)。 */
export type SummaryOutcome = 'win' | 'lose' | 'draw';

/** 試合別ビューでの 1 曲。スコア未入力の曲は score が null で winner も null。 */
export interface CompetitionSummarySong {
  /** 1 = A 側の曲 / 2 = B 側の曲。 */
  index: number;
  title: string | null;
  scoreA: number | null;
  scoreB: number | null;
  winner: SummaryResult | null;
}

/** 選手別ビューでの 1 曲 (本人視点にスコアを読み替えたもの)。 */
export interface CompetitionSummaryOwnSong {
  index: number;
  title: string | null;
  /** その曲が本人の自選枠か (A 側なら 1 曲目 / B 側なら 2 曲目)。 */
  ownPick: boolean;
  ownScore: number | null;
  opponentScore: number | null;
  outcome: SummaryOutcome | null;
}

/** 試合別ビューの 1 試合 (先鋒戦 / 中堅戦 …)。 */
export interface CompetitionSummaryMatch {
  matchId: number;
  matchKind: string;
  matchKindLabel: string;
  requiredGenre: string | null;
  /** その戦で 1 曲勝つごとにチームへ入る戦ポイント (予選/決勝で異なる)。 */
  pointsPerSong: number;
  /** 起用 (選手名) が公開済みか。false の場合 playerA/B は null でマスクされている。 */
  lineupPublished: boolean;
  playerAId: number | null;
  playerAName: string | null;
  playerBId: number | null;
  playerBName: string | null;
  recorded: boolean;
  resultRecordedAt: string | null;
  songs: CompetitionSummarySong[];
  /** 獲得曲数。引分の曲は両者が取ったものとして両側に +1 される (結果記録の運営仕様)。 */
  aSongsWon: number;
  bSongsWon: number;
  /** 純粋な曲勝ち数 (引分を含まない)。 */
  aSongWins: number;
  bSongWins: number;
  songDraws: number;
  aPoints: number;
  bPoints: number;
  result: SummaryResult | null;
}

/** 試合別ビューの 1 matchup (チーム vs チーム)。 */
export interface CompetitionSummaryMatchup {
  matchupId: number;
  matchupOrder: number;
  isFinals: boolean;
  configured: boolean;
  teamAId: number | null;
  teamAName: string | null;
  teamBId: number | null;
  teamBName: string | null;
  aPoints: number;
  bPoints: number;
  aMatchWins: number;
  bMatchWins: number;
  matchDraws: number;
  /** matchup 内の全試合が記録済か。false の間は result が null。 */
  recorded: boolean;
  result: SummaryResult | null;
  matches: CompetitionSummaryMatch[];
}

/** 選手別ビューでの 1 出場試合 (本人視点)。 */
export interface CompetitionSummaryPlayerMatch {
  matchId: number;
  matchupId: number;
  matchupOrder: number;
  isFinals: boolean;
  matchKind: string;
  matchKindLabel: string;
  requiredGenre: string | null;
  side: 'A' | 'B';
  opponentId: number | null;
  opponentName: string | null;
  opponentTeamName: string | null;
  songs: CompetitionSummaryOwnSong[];
  songsWon: number;
  opponentSongsWon: number;
  songWins: number;
  songLosses: number;
  songDraws: number;
  points: number;
  opponentPoints: number;
  result: SummaryOutcome;
}

/** 選手別ビューの 1 選手 (通算成績 + 出場試合)。結果記録済みの試合だけが入る。 */
export interface CompetitionSummaryPlayer {
  participantId: number;
  displayName: string;
  isTl: boolean;
  teamId: number | null;
  teamName: string | null;
  teamOrder: number | null;
  matchCount: number;
  wins: number;
  draws: number;
  losses: number;
  songWins: number;
  songLosses: number;
  songDraws: number;
  /** 本人が稼いだ戦ポイント / 相手に与えた戦ポイント。 */
  pointsFor: number;
  pointsAgainst: number;
  matches: CompetitionSummaryPlayerMatch[];
}

export interface CompetitionSummaryDto {
  competition: { id: number; name: string; status: string; format: string };
  /**
   * 認証不要の公開 API から取得したか。
   * true の場合は観戦 URL と同じマスクが掛かっている (未設定 matchup は含まれず、
   * 起用公開日時を過ぎていない試合の選手名は null、その試合は選手別にも積まれない)。
   */
  publicView: boolean;
  teams: CompetitionTeamDto[];
  matchups: CompetitionSummaryMatchup[];
  players: CompetitionSummaryPlayer[];
}

export interface CompetitionDetail extends CompetitionSummary {
  teams: CompetitionTeamDto[];
  participants: CompetitionParticipantDto[];
  /** draft 状態の間は欠落。open 以降に 10 件登場する (team5)。 */
  matchups?: CompetitionMatchupDto[];
  /** draft 状態の間は欠落。open 以降に 30 件登場する (team5)。 */
  matches?: CompetitionMatchDto[];
  /** individual4 専用: open 以降に予選 18 or 20 試合 + 決勝 3 or 4 試合が登場する。 */
  individualMatches?: CompetitionIndividualMatchDto[];
}

// ── 個人戦 (individual4) 用 DTO ───────────────────────
// IIDX ARENA モード相当: 1 試合 = 4 曲 × 4 人。曲は match 側 (song1〜song4) に、
// 各プレイヤースロットには 4 曲ぶんのスコア + 1 曲ごとの順位 + ポイント + 試合総ポイントを格納。

export interface CompetitionIndividualSlotDto {
  id: number;
  slotPosition: number; // 1..4
  /** 抽選番号モード用。自動配置モードでは null。 */
  slotNumber: number | null;
  participantId: number | null;
  participantName: string | null;
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

export interface CompetitionIndividualMatchDto {
  id: number;
  matchOrder: number;
  isFinals: boolean;
  finalsBucket: number | null;
  resultRecordedAt: string | null;
  song1StrategyId: number | null;
  song1Title: string | null;
  song2StrategyId: number | null;
  song2Title: string | null;
  song3StrategyId: number | null;
  song3Title: string | null;
  song4StrategyId: number | null;
  song4Title: string | null;
  slots: CompetitionIndividualSlotDto[];
}

/** 個人戦の試合結果記録 payload。4 曲のメタ + 4 スロット × 各曲順位 (1〜4)。 */
export interface IndividualResultSlotPayload {
  slotPosition: number;
  /** 1 曲目の順位 (1〜4 / null)。クリックでサイクル選択。 */
  rank1: number | null;
  rank2: number | null;
  rank3: number | null;
  rank4: number | null;
}
export interface IndividualResultPayload {
  song1StrategyId: number | null;
  song1Title: string | null;
  song2StrategyId: number | null;
  song2Title: string | null;
  song3StrategyId: number | null;
  song3Title: string | null;
  song4StrategyId: number | null;
  song4Title: string | null;
  slots: IndividualResultSlotPayload[];
}

/** 個人戦の順位表 1 行 (参加者単位)。 */
export interface CompetitionIndividualStandingsRow {
  participantId: number;
  displayName: string;
  prelimRank: number;
  prelimPoints: number;
  first: number;
  second: number;
  third: number;
  fourth: number;
  /** 「参加者が割当済かつ未記録」の予選試合数。OBS 順位表で「残試合数」として表示。 */
  remainingMatches: number;
  finalsBucket: number | null;
  finalsRank: number | null;
  finalsPoints: number;
  finalRank: number | null;
}
export interface CompetitionIndividualStandingsDto {
  rows: CompetitionIndividualStandingsRow[];
  prelimMatchCount: number;
  prelimRecordedCount: number;
  allPrelimRecorded: boolean;
  finalsExists: boolean;
  finalsMatchCount: number;
  finalsRecordedCount: number;
  allFinalsRecorded: boolean;
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

  /**
   * 新規大会を作成。format=team5 (デフォルト) なら 5 チーム枠を生成、
   * format=individual4 ならチーム枠は作らず空の draft を返す。
   */
  const createCompetition = async (
    name: string,
    format: CompetitionFormat = 'team5',
  ): Promise<CompetitionDetail> => {
    isLoading.value = true;
    try {
      const res = await fetch(`${API_BASE}/api/competitions`, {
        method: 'POST',
        headers: authHeaders(),
        body: JSON.stringify({ name, format }),
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

  /**
   * 大会サマリー (試合別 + 選手別の全結果) を取得する。団体戦 (team5) 専用。
   * サマリー画面 `/competition/summary/{id}` がこれ 1 本で描画に必要なデータを揃える。
   *
   * サマリーは誰でも閲覧できるので、認証不要の公開 API を基本線とする。ただし主催が開いた場合は
   * 未設定 matchup や未公開の起用まで見たいので、ログイン中なら先に主催 API を試し、
   * 権限が無ければ (401/403/404) 公開 API に落とす。返る形は同じで、公開側は
   * `publicView: true` とマスク済みのデータになる。
   */
  const fetchSummary = async (competitionId: number): Promise<CompetitionSummaryDto> => {
    if (localStorage.getItem(TOKEN_KEY)) {
      const res = await fetch(
        `${API_BASE}/api/competitions/${competitionId}/summary`,
        { headers: authHeaders() },
      );
      if (res.ok) return (await res.json()) as CompetitionSummaryDto;
      // 主催以外のログインユーザーはここに来る。公開版で読み直す。
    }
    const res = await fetch(`${API_BASE}/api/competition-access/summary/${competitionId}`);
    await throwIfError(res);
    return (await res.json()) as CompetitionSummaryDto;
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
   * matchup を「設定済み (実施対象)」⇄「未設定」に切り替える。
   * 設定すると選んだ順に matchupOrder が採番され、プレイヤー/TL に公開される。
   * 解除すると未設定に戻り、残りの設定済み matchup の順番が 1..k に詰め直される。
   */
  const configureMatchup = async (
    competitionId: number,
    matchupId: number,
    configured: boolean,
  ): Promise<void> => {
    const res = await fetch(
      `${API_BASE}/api/competitions/${competitionId}/matchups/${matchupId}/configure`,
      { method: 'PUT', headers: authHeaders(), body: JSON.stringify({ configured }) },
    );
    await throwIfError(res);
    await fetchCompetition(competitionId);
  };

  /**
   * matchup の左右 (A 側 / B 側) を入れ替える。teamA↔teamB と各試合の A/B 対フィールド
   * (選手・ロック・自選曲公開・スコア・勝敗) を対称に入れ替える。自選曲/StrategyCard は参加者に自動追従。
   */
  const swapMatchupSides = async (competitionId: number, matchupId: number): Promise<void> => {
    const res = await fetch(
      `${API_BASE}/api/competitions/${competitionId}/matchups/${matchupId}/swap-sides`,
      { method: 'PUT', headers: authHeaders() },
    );
    await throwIfError(res);
    await fetchCompetition(competitionId);
  };

  // 手動ロック (setMatchLock) は廃止。起用クローズは大会全体の deadlineAt (setDeadline) で自動制御する。

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

  // ── 個人戦 (individual4) 用 API ─────────────────────

  /** 個人戦に参加者を追加 (招待トークンも自動発行)。 */
  const addIndividualParticipant = async (
    competitionId: number,
    displayName: string,
  ): Promise<void> => {
    const res = await fetch(
      `${API_BASE}/api/competitions/${competitionId}/individual/participants`,
      { method: 'POST', headers: authHeaders(), body: JSON.stringify({ displayName }) },
    );
    await throwIfError(res);
    await fetchCompetition(competitionId);
  };

  /** 個人戦の参加者表示名を更新。 */
  const updateIndividualParticipant = async (
    competitionId: number,
    participantId: number,
    displayName: string,
  ): Promise<void> => {
    const res = await fetch(
      `${API_BASE}/api/competitions/${competitionId}/individual/participants/${participantId}`,
      { method: 'PUT', headers: authHeaders(), body: JSON.stringify({ displayName }) },
    );
    await throwIfError(res);
    await fetchCompetition(competitionId);
  };

  /** 個人戦の参加者を削除。 */
  const deleteIndividualParticipant = async (
    competitionId: number,
    participantId: number,
  ): Promise<void> => {
    const res = await fetch(
      `${API_BASE}/api/competitions/${competitionId}/individual/participants/${participantId}`,
      { method: 'DELETE', headers: authHeaders() },
    );
    await throwIfError(res);
    await fetchCompetition(competitionId);
  };

  /** 個人戦の参加者の招待トークンを再採番。 */
  const regenerateIndividualParticipantToken = async (
    competitionId: number,
    participantId: number,
  ): Promise<void> => {
    const res = await fetch(
      `${API_BASE}/api/competitions/${competitionId}/individual/participants/${participantId}/regenerate-token`,
      { method: 'POST', headers: authHeaders() },
    );
    await throwIfError(res);
    await fetchCompetition(competitionId);
  };

  /** 個人戦を draft → open へ遷移し、予選試合表を自動生成する。 */
  const openIndividualCompetition = async (competitionId: number): Promise<void> => {
    const res = await fetch(
      `${API_BASE}/api/competitions/${competitionId}/individual/open`,
      { method: 'POST', headers: authHeaders() },
    );
    await throwIfError(res);
    await fetchCompetition(competitionId);
  };

  /** 個人戦の試合 1 件の 4 スロット分の結果 (曲・スコア) を記録/更新。 */
  const setIndividualMatchResult = async (
    competitionId: number,
    matchId: number,
    payload: IndividualResultPayload,
  ): Promise<void> => {
    const res = await fetch(
      `${API_BASE}/api/competitions/${competitionId}/individual/matches/${matchId}/result`,
      { method: 'PUT', headers: authHeaders(), body: JSON.stringify(payload) },
    );
    await throwIfError(res);
    await fetchCompetition(competitionId);
  };

  /** 個人戦の試合結果を未記録に戻す。 */
  const clearIndividualMatchResult = async (
    competitionId: number,
    matchId: number,
  ): Promise<void> => {
    const res = await fetch(
      `${API_BASE}/api/competitions/${competitionId}/individual/matches/${matchId}/result`,
      { method: 'DELETE', headers: authHeaders() },
    );
    await throwIfError(res);
    await fetchCompetition(competitionId);
  };

  /** 個人戦の順位表を取得。 */
  const fetchIndividualStandings = async (
    competitionId: number,
  ): Promise<CompetitionIndividualStandingsDto> => {
    const res = await fetch(
      `${API_BASE}/api/competitions/${competitionId}/individual/standings`,
      { headers: authHeaders() },
    );
    await throwIfError(res);
    return (await res.json()) as CompetitionIndividualStandingsDto;
  };

  /** 個人戦で予選全試合記録後、上位 4 人ずつのバケットで決勝を生成。 */
  const generateIndividualFinals = async (competitionId: number): Promise<void> => {
    const res = await fetch(
      `${API_BASE}/api/competitions/${competitionId}/individual/generate-finals`,
      { method: 'POST', headers: authHeaders() },
    );
    await throwIfError(res);
    await fetchCompetition(competitionId);
  };

  /**
   * 抽選番号モードで個人戦を draft → open に遷移する。
   * matches[i] = 試合 i+1 の 4 件のスロット番号 (1〜参加者数)。
   */
  const openIndividualWithNumbers = async (
    competitionId: number,
    matches: number[][],
  ): Promise<void> => {
    const res = await fetch(
      `${API_BASE}/api/competitions/${competitionId}/individual/open-with-numbers`,
      { method: 'POST', headers: authHeaders(), body: JSON.stringify({ matches }) },
    );
    await throwIfError(res);
    await fetchCompetition(competitionId);
  };

  /** 抽選結果 (番号 → 参加者 ID) を適用し、全スロットを埋める。 */
  const assignIndividualLottery = async (
    competitionId: number,
    assignments: { number: number; participantId: number }[],
  ): Promise<void> => {
    const res = await fetch(
      `${API_BASE}/api/competitions/${competitionId}/individual/lottery-assign`,
      { method: 'PUT', headers: authHeaders(), body: JSON.stringify({ assignments }) },
    );
    await throwIfError(res);
    await fetchCompetition(competitionId);
  };

  /** OBS ブラウザソース公開トークンを発行/再発行する。 */
  const regenerateObsToken = async (competitionId: number): Promise<string> => {
    const res = await fetch(
      `${API_BASE}/api/competitions/${competitionId}/individual/regenerate-obs-token`,
      { method: 'POST', headers: authHeaders() },
    );
    await throwIfError(res);
    const data = await res.json();
    await fetchCompetition(competitionId);
    return data.obsToken as string;
  };

  // ── 運営チャット (TL ⇄ 運営) ─────────────────────────────
  /** 大会内の全チームのチャットスレッドを取得 (未読数付き)。 */
  const fetchChatThreads = async (competitionId: number): Promise<ChatThreadDto[]> => {
    const res = await fetch(
      `${API_BASE}/api/competitions/${competitionId}/chat`,
      { headers: authHeaders() },
    );
    await throwIfError(res);
    return (await res.json()) as ChatThreadDto[];
  };

  /** 指定チームの TL へ運営返信を送信する。 */
  const sendChatReply = async (
    competitionId: number,
    teamId: number,
    body: string,
  ): Promise<ChatMessageDto> => {
    const res = await fetch(
      `${API_BASE}/api/competitions/${competitionId}/teams/${teamId}/chat`,
      { method: 'POST', headers: authHeaders(), body: JSON.stringify({ body }) },
    );
    await throwIfError(res);
    return (await res.json()) as ChatMessageDto;
  };

  /** 指定チームの TL 発メッセージを既読にする (未読バッジのクリア)。 */
  const markChatRead = async (competitionId: number, teamId: number): Promise<void> => {
    const res = await fetch(
      `${API_BASE}/api/competitions/${competitionId}/teams/${teamId}/chat/mark-read`,
      { method: 'POST', headers: authHeaders() },
    );
    await throwIfError(res);
  };

  /**
   * 起用クローズ日時 (deadlineAt) を設定/解除する。
   * @param deadlineAt ISO ローカル日時文字列 (例 "2026-06-20T21:00")。null / '' で締切解除。
   */
  const setDeadline = async (competitionId: number, deadlineAt: string | null): Promise<void> => {
    const res = await fetch(
      `${API_BASE}/api/competitions/${competitionId}/deadline`,
      { method: 'PUT', headers: authHeaders(), body: JSON.stringify({ deadlineAt }) },
    );
    await throwIfError(res);
    await fetchCompetition(competitionId);
  };

  /**
   * 起用 (オーダー) 公開日時 (lineupPublishAt) を設定/解除する。起用クローズ日時 (deadlineAt) とは独立。
   * 設定した日時を過ぎると対戦相手・観戦 URL・選手 URL に起用が自動公開される。
   * @param lineupPublishAt ISO ローカル日時文字列 (例 "2026-06-20T21:00")。null / '' で公開日時解除。
   */
  const setLineupPublishAt = async (competitionId: number, lineupPublishAt: string | null): Promise<void> => {
    const res = await fetch(
      `${API_BASE}/api/competitions/${competitionId}/lineup-publish-at`,
      { method: 'PUT', headers: authHeaders(), body: JSON.stringify({ lineupPublishAt }) },
    );
    await throwIfError(res);
    await fetchCompetition(competitionId);
  };

  /**
   * 決勝の起用クローズ日時 (finalsDeadlineAt) を設定/解除する。予選の deadlineAt とは独立。
   * null / '' の間は決勝の起用をいつでも編集できる (決勝生成直後の既定状態)。
   */
  const setFinalsDeadline = async (competitionId: number, finalsDeadlineAt: string | null): Promise<void> => {
    const res = await fetch(
      `${API_BASE}/api/competitions/${competitionId}/finals-deadline`,
      { method: 'PUT', headers: authHeaders(), body: JSON.stringify({ finalsDeadlineAt }) },
    );
    await throwIfError(res);
    await fetchCompetition(competitionId);
  };

  /**
   * 決勝の起用公開日時 (finalsLineupPublishAt) を設定/解除する。
   * null / '' の間は決勝の起用が相手 TL・観戦 URL・選手 URL に一切公開されない。
   */
  const setFinalsLineupPublishAt = async (
    competitionId: number,
    finalsLineupPublishAt: string | null,
  ): Promise<void> => {
    const res = await fetch(
      `${API_BASE}/api/competitions/${competitionId}/finals-lineup-publish-at`,
      { method: 'PUT', headers: authHeaders(), body: JSON.stringify({ finalsLineupPublishAt }) },
    );
    await throwIfError(res);
    await fetchCompetition(competitionId);
  };

  /** 観戦客向け対戦表公開トークンを発行/再発行する (team5 用)。 */
  const regenerateSpectatorToken = async (competitionId: number): Promise<string> => {
    const res = await fetch(
      `${API_BASE}/api/competitions/${competitionId}/regenerate-spectator-token`,
      { method: 'POST', headers: authHeaders() },
    );
    await throwIfError(res);
    const data = await res.json();
    await fetchCompetition(competitionId);
    return data.spectatorToken as string;
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
    configureMatchup,
    swapMatchupSides,
    publishPick,
    deleteCompetition,
    regenerateParticipantToken,
    regenerateTlToken,
    fetchRevealData,
    setMatchResult,
    clearMatchResult,
    fetchStandings,
    fetchSummary,
    generateFinals,
    // 個人戦 (individual4) 用
    addIndividualParticipant,
    updateIndividualParticipant,
    deleteIndividualParticipant,
    regenerateIndividualParticipantToken,
    openIndividualCompetition,
    setIndividualMatchResult,
    clearIndividualMatchResult,
    fetchIndividualStandings,
    generateIndividualFinals,
    openIndividualWithNumbers,
    assignIndividualLottery,
    regenerateObsToken,
    // team5 観戦公開用
    regenerateSpectatorToken,
    // 起用クローズ日時 (手動ロックの代替)
    setDeadline,
    // 起用公開日時 (deadlineAt とは独立)
    setLineupPublishAt,
    // 決勝の起用クローズ日時 / 起用公開日時 (予選とは別スケジュール)
    setFinalsDeadline,
    setFinalsLineupPublishAt,
    // 運営チャット
    fetchChatThreads,
    sendChatReply,
    markChatRead,
  };
}
