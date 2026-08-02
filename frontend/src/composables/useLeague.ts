import { ref } from 'vue';
import { useAuth } from './useAuth';

/** バックエンド API のベース URL。 */
const API_BASE = import.meta.env.VITE_API_BASE ?? 'http://localhost:8080';

/** ラダー種別。現在はスコアリーグ（3曲平均スコアレート）のみ（BP リーグは廃止）。 */
export type LadderType = 'score';

/** 自分のリーグ参加エントリー（1 ラダー分）。 */
export interface LeagueEntry {
  ladderType: LadderType;
  /** 現在の所属 DIVISION（0=LEGEND、1..10）。 */
  currentTier: number | null;
  /** 昇降格ポイント（-4..+4）。+4 到達で昇格、-4 到達で降格。DIVISION 変動後は 0。 */
  points: number;
  /** 参加中か（false = 休止中）。 */
  active: boolean;
  /** 連続不参加週数（3 週で自動休止）。 */
  inactiveWeeks: number;
}

/** リーグの週（1 ラダー分）。 */
export interface LeagueWeekInfo {
  id: number;
  ladderType: LadderType;
  /** 週の開始日時（JST、ISO 形式文字列）。 */
  startsAt: string;
  /** 週の終了日時（JST、ISO 形式文字列）。 */
  endsAt: string;
  status: 'draft' | 'active' | 'closed';
}

/** 週次課題曲。 */
export interface LeagueSongInfo {
  id: number;
  tier: number;
  /** グループ番号（0 始まり）。課題曲はグループ単位なので管理者の編成確認で使う。旧データ・観戦では未設定のことがある。 */
  groupIndex?: number | null;
  slot: number;
  title: string;
  difficultyName: string;
  level: number | null;
  /** ノーツ数（MAX = notes*2）。 */
  notes: number;
  /** このグループの「ライン」= 週開始時点の最高 EX（匿名のグループ共通閾値）。誰も未プレーなら null。 */
  lineEx?: number | null;
  /** このグループの「ライン」= 週開始時点の最小 BP。null なら未設定。 */
  lineMiss?: number | null;
  /** ラインのスコアレート(%)。lineEx から算出済み。 */
  lineRate?: number | null;
  /**
   * ライン保持者の表示名（同値が複数居れば全員）。管理者 overview の draft 週でのみ返る
   * （プレイヤー向けの順位表ではラインは匿名の共通閾値なので含めない）。
   */
  lineHolders?: string[];
}

/** 順位表 1 行の課題曲内訳。 */
export interface LeaguePerSong {
  slot: number;
  title: string;
  difficultyName: string;
  /** 有効か（週内プレー + ライン超えの両方を満たした）。 */
  valid: boolean;
  /** source 横断の自己ベスト EX スコア（記録なしは null）。 */
  bestEx: number | null;
  /** 自己ベストのスコアレート(%)。 */
  rate: number | null;
  /** source 横断の最小ミスカウント。 */
  bestMiss: number | null;
  /** ライン（グループ内の週開始時点の最高 EX）。誰も未プレーなら null。 */
  lineEx: number | null;
  /** ライン（グループ内の週開始時点の最小 BP）。誰も未プレー/BP 無しなら null。 */
  lineMiss: number | null;
  /** この曲で得た着順ポイント（1位=グループ人数、最下位=1。同着は平均）。 */
  points?: number | null;
  /** この曲の着順（有効化した人だけ、1始まり・同着は同順位）。未有効/未達成は null。 */
  rank?: number | null;
}

/** グループ順位表の 1 行。 */
export interface LeagueStandingRow {
  rank: number;
  userId: number;
  displayName: string;
  totalBeatPt: number;
  /** 有効曲数（0..3）。 */
  validSongs: number;
  /** 着順ポイントの3曲合計（＝得点。順位の主指標）。 */
  resultValue: number | null;
  /** 現在の昇降格ポイント（進行中の週のみ。closed 週は undefined）。 */
  points?: number;
  /** この週の順位によるポイント増減（有効 0 曲はプラス分 0 に丸め済み）。 */
  pointDelta?: number | null;
  /** 週がこのまま終わった場合のポイント（±8 にクランプ。進行中の週のみ）。 */
  projectedPoints?: number;
  /** 昇降格ゾーン（ポイントが ±8 に到達する見込みかどうか）。 */
  zone: 'promote' | 'stay' | 'relegate';
  /** 卓での立場。normal / challenge（格上の卓に挑戦）/ defense（格下の卓を防衛）。 */
  role?: 'normal' | 'challenge' | 'defense';
  /** ホーム DIVISION（0=LEGEND、1..10）。role が normal 以外のとき卓(tier)と異なる。 */
  homeTier?: number;
  /** 課題曲ごとの内訳（closed 週は空配列）。 */
  perSong: LeaguePerSong[];
}

/** GET /api/league/current の応答。 */
export interface LeagueCurrent {
  entry: LeagueEntry | null;
  week: LeagueWeekInfo | null;
  member: { tier: number; groupIndex: number; homeTier?: number; role?: 'normal' | 'challenge' | 'defense' } | null;
  /** 未参加時のみ: プレビュー表示している DIVISION（自分の BEAT-TIER 相当に最も近い開催中 DIVISION）。 */
  previewTier?: number | null;
  songs: LeagueSongInfo[];
  standings: LeagueStandingRow[] | null;
}

/** GET /api/league/overview のグループ概要。 */
export interface LeagueTierOverview {
  tier: number;
  songs: LeagueSongInfo[];
  groups: { groupIndex: number; memberCount: number }[];
}

/** GET /api/league/history の 1 行（自分の過去週成績）。 */
export interface LeagueHistoryRow {
  weekId: number;
  startsAt: string;
  endsAt: string;
  tier: number;
  groupIndex: number;
  finalRank: number | null;
  movement: 'promote' | 'stay' | 'relegate' | null;
  /** その週の順位によるポイント増減。 */
  pointDelta: number | null;
  validSongs: number | null;
  resultValue: number | null;
}

/** 管理者 overview のメンバー行（誰がどのグループに、どの立場で入ったか）。 */
export interface LeagueAdminMember {
  userId: number;
  displayName: string;
  iidxId: string | null;
  /** ホーム DIVISION（0=LEGEND、1..10）。卓(tier)と異なる場合はチャレンジ/ディフェンス。 */
  homeTier: number;
  role: 'normal' | 'challenge' | 'defense';
  /** 課題曲ごとの自己ベスト（draft 週のみ。スロット順・仮編成プレビューと同じ形）。 */
  bests: LeaguePreviewCell[];
}

/** 管理者 overview の週詳細。 */
export interface LeagueAdminWeek {
  id: number;
  ladderType: LadderType;
  startsAt: string;
  endsAt: string;
  status: string;
  tiers: {
    tier: number;
    songs: LeagueSongInfo[];
    /** 卓内のグループ構成（未編成の週は空配列）。 */
    groups: { groupIndex: number; members: LeagueAdminMember[] }[];
  }[];
  memberCount: number;
}

/** 管理者 overview の 1 ラダー分。 */
export interface LeagueAdminLadder {
  ladder: LadderType;
  activeEntryCount: number;
  draftWeek: LeagueAdminWeek | null;
  activeWeek: LeagueAdminWeek | null;
}

/** 仮編成プレビューの選手セル（1 課題曲分の自己ベスト）。 */
export interface LeaguePreviewCell {
  slot: number;
  /** 自己ベスト EX（未プレーは null）。 */
  ex: number | null;
  /** スコアレート(%)。 */
  rate: number | null;
  /** この選手がこの曲のライン（グループ最高 EX）を持っているか＝強調表示対象。 */
  isLine: boolean;
  /** アーケードでプレー済みか。 */
  played: boolean;
}

/** 仮編成プレビューの選手行。 */
export interface LeaguePreviewPlayer {
  /** ユーザー ID。プレビューを draft へ適用するときの同定に使う。 */
  userId: number;
  displayName: string;
  /** ホーム DIVISION（0=LEGEND、1..10）。 */
  homeTier: number;
  /** 卓での立場。normal / challenge（格上へ挑戦）/ defense（格下を防衛）。 */
  role: 'normal' | 'challenge' | 'defense';
  /** 課題曲ごとの自己ベスト（songs と同じ slot 順）。 */
  bests: LeaguePreviewCell[];
}

/** 仮編成プレビューの課題曲（ライン付き）。 */
export interface LeaguePreviewSong {
  slot: number;
  title: string;
  difficultyName: string;
  level: number | null;
  notes: number;
  /** ライン＝グループ内のアーケード自己ベスト最高 EX。誰も未プレーなら null。 */
  lineEx: number | null;
  /** ラインのスコアレート(%)。 */
  lineRate: number | null;
  /** ライン保持者の表示名（同値が複数居れば全員。誰も未プレーなら空）。 */
  lineHolders: string[];
}

/** 仮編成プレビューの 1 グループ。 */
export interface LeaguePreviewGroup {
  groupIndex: number;
  memberCount: number;
  songs: LeaguePreviewSong[];
  players: LeaguePreviewPlayer[];
}

/** 仮編成プレビューの 1 卓（host DIVISION）。 */
export interface LeaguePreviewTier {
  host: number;
  memberCount: number;
  groups: LeaguePreviewGroup[];
}

/** GET /api/league/admin/preview の応答（DB 非更新の仮編成）。 */
export interface LeaguePreview {
  ladder: LadderType;
  entryCount: number;
  tiers: LeaguePreviewTier[];
}

/**
 * 【Composable の役割】 リーグモード（/league）の API 呼び出しをまとめて提供する。
 *
 * - 全エンドポイントが要ログイン（JWT）。
 * - 管理系（差し替え・再抽選・週次手動実行）はサーバ側で管理者判定される（403 が返る）。
 * - 状態は呼び出し側（LeagueView）が保持する。ここは fetch 関数と loading フラグのみ。
 */
export function useLeague() {
  const { authHeaders } = useAuth();

  /** 取得中フラグ（スピナー表示用）。 */
  const isLoading = ref(false);

  /** 失敗レスポンスから error メッセージを取り出して例外化する共通処理。 */
  const raise = async (res: Response, fallback: string): Promise<never> => {
    const body = await res.json().catch(() => ({}));
    throw new Error(body?.error || `${fallback} (${res.status})`);
  };

  /** 自分の両ラダーのエントリー状態を取得する。 */
  const fetchMe = async (): Promise<LeagueEntry[]> => {
    const res = await fetch(`${API_BASE}/api/league/me`, { headers: authHeaders() });
    if (!res.ok) await raise(res, '参加状態の取得に失敗しました');
    const body = await res.json();
    return (body.entries ?? []) as LeagueEntry[];
  };

  /** 指定ラダーへ参加（または休止から復帰）する。反映は次回編成から。 */
  const join = async (ladderType: LadderType): Promise<LeagueEntry> => {
    const res = await fetch(`${API_BASE}/api/league/join`, {
      method: 'POST',
      headers: authHeaders({ 'Content-Type': 'application/json' }),
      body: JSON.stringify({ ladderType }),
    });
    if (!res.ok) await raise(res, '参加に失敗しました');
    return (await res.json()).entry as LeagueEntry;
  };

  /** 指定ラダーから離脱（休止）する。 */
  const leave = async (ladderType: LadderType): Promise<void> => {
    const res = await fetch(`${API_BASE}/api/league/leave`, {
      method: 'POST',
      headers: authHeaders({ 'Content-Type': 'application/json' }),
      body: JSON.stringify({ ladderType }),
    });
    if (!res.ok) await raise(res, '離脱に失敗しました');
  };

  /** 進行中の週（課題曲・自分のグループの順位表）を取得する。 */
  const fetchCurrent = async (ladder: LadderType): Promise<LeagueCurrent> => {
    isLoading.value = true;
    try {
      const res = await fetch(`${API_BASE}/api/league/current?ladder=${ladder}`, { headers: authHeaders() });
      if (!res.ok) await raise(res, '週情報の取得に失敗しました');
      return (await res.json()) as LeagueCurrent;
    } finally {
      isLoading.value = false;
    }
  };

  /** 任意グループの順位表を取得する（観戦・過去週）。 */
  const fetchStandings = async (
    weekId: number,
    tier: number,
    groupIndex: number
  ): Promise<{ week: LeagueWeekInfo; songs: LeagueSongInfo[]; standings: LeagueStandingRow[] }> => {
    const res = await fetch(
      `${API_BASE}/api/league/standings?weekId=${weekId}&tier=${tier}&groupIndex=${groupIndex}`,
      { headers: authHeaders() }
    );
    if (!res.ok) await raise(res, '順位表の取得に失敗しました');
    return await res.json();
  };

  /** 進行中の週の階級/グループ構成を取得する。 */
  const fetchOverview = async (
    ladder: LadderType
  ): Promise<{ week: LeagueWeekInfo | null; tiers: LeagueTierOverview[] }> => {
    const res = await fetch(`${API_BASE}/api/league/overview?ladder=${ladder}`, { headers: authHeaders() });
    if (!res.ok) await raise(res, 'リーグ構成の取得に失敗しました');
    return await res.json();
  };

  /** 自分の過去週成績を取得する。 */
  const fetchHistory = async (ladder: LadderType): Promise<LeagueHistoryRow[]> => {
    const res = await fetch(`${API_BASE}/api/league/history?ladder=${ladder}`, { headers: authHeaders() });
    if (!res.ok) await raise(res, '履歴の取得に失敗しました');
    return (await res.json()) as LeagueHistoryRow[];
  };

  // -------------------------------------------------------------------
  // 管理者用（サーバ側で管理者判定。非管理者は 403）
  // -------------------------------------------------------------------

  /** 両ラダーの draft/active 週の状況を取得する（管理者のみ）。 */
  const fetchAdminOverview = async (): Promise<LeagueAdminLadder[]> => {
    const res = await fetch(`${API_BASE}/api/league/admin/overview`, { headers: authHeaders() });
    if (!res.ok) await raise(res, '管理情報の取得に失敗しました');
    return ((await res.json()).ladders ?? []) as LeagueAdminLadder[];
  };

  /** draft 週の課題曲 1 曲を差し替える（管理者のみ）。 */
  const replaceSong = async (
    weekId: number,
    songId: number,
    title: string,
    difficultyName: string
  ): Promise<LeagueSongInfo> => {
    const res = await fetch(`${API_BASE}/api/league/admin/weeks/${weekId}/songs/${songId}/replace`, {
      method: 'POST',
      headers: authHeaders({ 'Content-Type': 'application/json' }),
      body: JSON.stringify({ title, difficultyName }),
    });
    if (!res.ok) await raise(res, '課題曲の差し替えに失敗しました');
    return (await res.json()).song as LeagueSongInfo;
  };

  /** draft 週の指定階級の課題曲を再抽選する（管理者のみ）。 */
  const redrawTier = async (weekId: number, tier: number): Promise<LeagueSongInfo[]> => {
    const res = await fetch(`${API_BASE}/api/league/admin/weeks/${weekId}/redraw?tier=${tier}`, {
      method: 'POST',
      headers: authHeaders(),
    });
    if (!res.ok) await raise(res, '再抽選に失敗しました');
    return (await res.json()).songs as LeagueSongInfo[];
  };

  /** 週次処理（締め → 編成 → 開始）を手動実行する（管理者のみ）。 */
  const runWeekly = async (ladder: LadderType): Promise<Record<string, unknown>> => {
    const res = await fetch(`${API_BASE}/api/league/admin/run-weekly?ladder=${ladder}`, {
      method: 'POST',
      headers: authHeaders(),
    });
    if (!res.ok) await raise(res, '週次処理の実行に失敗しました');
    return await res.json();
  };

  /** draft 週を手動作成する（管理者のみ）。 */
  const createDraft = async (ladder: LadderType): Promise<void> => {
    const res = await fetch(`${API_BASE}/api/league/admin/create-draft?ladder=${ladder}`, {
      method: 'POST',
      headers: authHeaders(),
    });
    if (!res.ok) await raise(res, 'draft 週の作成に失敗しました');
  };

  /**
   * 参加締切後に draft 週の編成（卓・グループ・課題曲）を確定する（管理者のみ）。
   * 開始（active 化）はしない。開始処理はこの事前編成をそのまま使う。押すたびに組み直す。
   */
  const formDraft = async (ladder: LadderType): Promise<void> => {
    const res = await fetch(`${API_BASE}/api/league/admin/form?ladder=${ladder}`, {
      method: 'POST',
      headers: authHeaders(),
    });
    if (!res.ok) await raise(res, '編成に失敗しました');
  };

  /**
   * 誤って開始した開催中(active)の週を中止し、開始前の空 draft に戻す（管理者のみ）。
   * 開始では昇降格 PT・DIVISION は変化しないため、順位・昇降格には影響しない。
   */
  const abortWeek = async (ladder: LadderType): Promise<void> => {
    const res = await fetch(`${API_BASE}/api/league/admin/abort?ladder=${ladder}`, {
      method: 'POST',
      headers: authHeaders(),
    });
    if (!res.ok) await raise(res, '中止に失敗しました');
  };

  /** 仮編成プレビューを取得する（管理者のみ・DB 非更新）。 */
  const fetchAdminPreview = async (ladder: LadderType): Promise<LeaguePreview> => {
    isLoading.value = true;
    try {
      const res = await fetch(`${API_BASE}/api/league/admin/preview?ladder=${ladder}`, { headers: authHeaders() });
      if (!res.ok) await raise(res, '仮編成の取得に失敗しました');
      return (await res.json()) as LeaguePreview;
    } finally {
      isLoading.value = false;
    }
  };

  /**
   * 仮編成プレビューをそのまま draft 週へ適用する（管理者のみ）。
   *
   * 既存の編成（メンバー・課題曲）は削除して置き換える。プレビュー生成後に参加者が
   * 増減している場合はサーバ側で拒否されるので、その場合は作り直してから適用する。
   */
  const applyPreview = async (ladder: LadderType, preview: LeaguePreview): Promise<void> => {
    const body = {
      tiers: preview.tiers.map((t) => ({
        host: t.host,
        groups: t.groups.map((g) => ({
          groupIndex: g.groupIndex,
          userIds: g.players.map((p) => p.userId),
          songs: g.songs.map((s) => ({ title: s.title, difficultyName: s.difficultyName })),
        })),
      })),
    };
    const res = await fetch(`${API_BASE}/api/league/admin/preview/apply?ladder=${ladder}`, {
      method: 'POST',
      headers: authHeaders({ 'Content-Type': 'application/json' }),
      body: JSON.stringify(body),
    });
    if (!res.ok) await raise(res, '編成の適用に失敗しました');
  };

  return {
    isLoading,
    fetchMe,
    join,
    leave,
    fetchCurrent,
    fetchStandings,
    fetchOverview,
    fetchHistory,
    fetchAdminOverview,
    replaceSong,
    redrawTier,
    runWeekly,
    createDraft,
    formDraft,
    abortWeek,
    fetchAdminPreview,
    applyPreview,
  };
}
