import { ref } from 'vue';
import { useAuth } from './useAuth';
import { groupFlatScores } from './useScores';
import type { ScoreData } from '../types/ScoreData';

/** バックエンド API のベース URL。 */
const API_BASE = import.meta.env.VITE_API_BASE ?? 'http://localhost:8080';

/** 勝敗マトリクスの 1 プレイヤー（勝ち数降順で並ぶ）。 */
export interface MatrixPlayer {
  userId: number;
  displayName: string;
  /** 総勝ち数。 */
  wins: number;
  /** 総負け数。 */
  losses: number;
  /** 総引き分け数。 */
  draws: number;
  /** 勝率(%)（決着が無ければ 0）。 */
  winRate: number;
}

/** 勝敗マトリクスの 1 セル（行プレイヤー視点の対戦成績）。対角は null。 */
export interface MatrixCell {
  /** 行プレイヤーの勝ち数。 */
  w: number;
  /** 行プレイヤーの負け数。 */
  l: number;
  /** 引き分け数。 */
  d: number;
}

/** 1 区分（LV10以下/LV11/LV12）の勝敗マトリクス。players の並びに matrix の行・列が揃う。 */
export interface MatrixBracket {
  players: MatrixPlayer[];
  matrix: (MatrixCell | null)[][];
}

/** /api/kinjocup/matrix の応答。 */
export interface KinjoCupMatrix {
  lv10: MatrixBracket;
  lv11: MatrixBracket;
  lv12: MatrixBracket;
}

/** 対戦内訳の 1 譜面（A 視点の勝敗付き）。 */
export interface MatchupChart {
  title: string;
  difficultyName: string;
  level: number;
  /** ノーツ数。MAX = notes*2。グレード(MAX-/AAA+...)算出用。 */
  notes: number;
  /** 行プレイヤー(A)の EX スコア。 */
  scoreA: number;
  /** 列プレイヤー(B)の EX スコア。 */
  scoreB: number;
  /** A 視点の勝敗。 */
  result: 'win' | 'lose' | 'draw';
}

/** /api/kinjocup/matchup の応答（A vs B の内訳）。 */
export interface KinjoCupMatchup {
  playerA: { userId: number; displayName: string };
  playerB: { userId: number; displayName: string };
  bracket: string;
  summary: { aWins: number; bWins: number; draws: number; total: number };
  charts: MatchupChart[];
}

/** 曲別順位の 1 プレイヤー（平均順位の良い順に並ぶ）。 */
export interface SongRankPlayer {
  userId: number;
  displayName: string;
  /** プレイした譜面数。 */
  played: number;
  /** 1 位を取った譜面数。 */
  firstPlaces: number;
  /** 平均順位（プレイ譜面の順位平均）。未プレイは null。 */
  avgRank: number | null;
}

/** 曲別順位の 1 セル（その譜面でのプレイヤーの順位とスコア）。未プレイは null。 */
export interface SongRankCell {
  rank: number;
  score: number;
}

/** 曲別順位の 1 譜面（cells は players の並びに揃う）。 */
export interface SongRankChart {
  title: string;
  difficultyName: string;
  level: number;
  /** プレイ人数（順位の母数）。 */
  players: number;
  cells: (SongRankCell | null)[];
}

/** 1 区分の曲別順位。 */
export interface SongRankBracket {
  players: SongRankPlayer[];
  charts: SongRankChart[];
}

/** /api/kinjocup/song-ranks の応答。 */
export interface KinjoCupSongRanks {
  lv10: SongRankBracket;
  lv11: SongRankBracket;
  lv12: SongRankBracket;
}

/**
 * きんじょー杯 特設ページ（/kinjocup）に掲載する参加者 1 人分のサマリ。
 * 実力データ（総合力 / 段位 / アリーナ）はバックエンドが User から都度引いて返す。
 */
export interface KinjoCupParticipant {
  /** 参加者エントリの ID（削除時に使う。userId とは別物）。 */
  id: number;
  /** beat-seeker ユーザーの ID（詳細表示・スコア取得に使う）。 */
  userId: number;
  /** IIDX ID（例: "1234-5678"）。詳細ダッシュボードのランキング照合に使う。 */
  iidxId: string;
  /** DJ ネーム。 */
  displayName: string;
  /** 段位（例: "皆伝"）。未設定は空文字。 */
  danRank: string;
  /** アリーナクラス（例: "A1"）。未設定は空文字。 */
  arenaRank: string;
  /** 総合力（Beat-Pt）。ドラフトの主軸指標。 */
  totalBeatPt: number;
  /** 最終アップロード日時（データ鮮度の目安）。null あり。 */
  lastUploadedAt: string | null;
  /** 主催者が書くドラフト選考メモ（無ければ空文字）。 */
  note: string;
  /** LV12 ANOTHER/LEGGENDARIA の AAA 達成数。 */
  lv12AaaCount: number;
  /** LV12 ANOTHER/LEGGENDARIA の MAX- 達成数。 */
  lv12MaxMinusCount: number;
  /** LV12 ANOTHER/LEGGENDARIA の総譜面数（AAA数/MAX-数 の分母）。 */
  lv12Total: number;
  /** RATE-TIER 下限（100曲目）のスコアレート(%)。RATE-PT対象が100曲未満なら null。 */
  rateFloorScoreRate: number | null;
  /** RATE-PT 対象（スコア率 >= 77.77%）の譜面数。 */
  rateEligibleCount: number;
}

/**
 * 【Composable の役割】 きんじょー杯 特設ページの参加者名簿 API をまとめて提供する。
 *
 * - 一覧取得は公開エンドポイント（ログイン不要）。
 * - 追加・削除は管理者のみ。サーバ側で再度権限チェックされる（403 が返る）。
 */
export function useKinjoCup() {
  const { authHeaders } = useAuth();

  /** 取得中フラグ（スピナー表示用）。 */
  const isLoading = ref(false);

  /**
   * 参加者一覧を取得する（公開・総合力降順でサーバから返る）。
   * 失敗時は例外を投げる（呼び出し側でエラー表示）。
   */
  const fetchParticipants = async (): Promise<KinjoCupParticipant[]> => {
    isLoading.value = true;
    try {
      const res = await fetch(`${API_BASE}/api/kinjocup/participants`);
      if (!res.ok) {
        throw new Error(`一覧の取得に失敗しました (${res.status})`);
      }
      return (await res.json()) as KinjoCupParticipant[];
    } finally {
      isLoading.value = false;
    }
  };

  /**
   * 参加者同士の勝敗マトリクス（LV10以下/LV11/LV12）を取得する（公開）。
   */
  const fetchMatrix = async (): Promise<KinjoCupMatrix> => {
    const res = await fetch(`${API_BASE}/api/kinjocup/matrix`);
    if (!res.ok) {
      throw new Error(`マトリクスの取得に失敗しました (${res.status})`);
    }
    return (await res.json()) as KinjoCupMatrix;
  };

  /**
   * 参加者内での曲別順位（LV10以下/LV11/LV12）を取得する（公開）。
   */
  const fetchSongRanks = async (): Promise<KinjoCupSongRanks> => {
    const res = await fetch(`${API_BASE}/api/kinjocup/song-ranks`);
    if (!res.ok) {
      throw new Error(`曲別順位の取得に失敗しました (${res.status})`);
    }
    return (await res.json()) as KinjoCupSongRanks;
  };

  /**
   * マトリクスのセル（A vs B）の対戦内訳を取得する（公開）。
   * @param a       行プレイヤーの userId
   * @param b       列プレイヤーの userId
   * @param bracket 'lv10' / 'lv11' / 'lv12'
   */
  const fetchMatchup = async (
    a: number,
    b: number,
    bracket: 'lv10' | 'lv11' | 'lv12'
  ): Promise<KinjoCupMatchup> => {
    const res = await fetch(`${API_BASE}/api/kinjocup/matchup?a=${a}&b=${b}&bracket=${bracket}`);
    if (!res.ok) {
      throw new Error(`対戦内訳の取得に失敗しました (${res.status})`);
    }
    return (await res.json()) as KinjoCupMatchup;
  };

  /**
   * 指定参加者のスコア一覧を取得する（特設ページ内のダッシュボード/スコア一覧用）。
   * 名簿登録者のみ取得でき、プライバシー設定に関わらずサーバが返す。
   * 戻り値は曲単位にグルーピング済みの ScoreData[]（ScoreDashboard/ScoreSummary にそのまま渡せる）。
   *
   * @param userId 参加者の beat-seeker ユーザー ID
   */
  const fetchParticipantScores = async (userId: number): Promise<ScoreData[]> => {
    const res = await fetch(`${API_BASE}/api/kinjocup/participants/${userId}/scores`);
    if (res.status === 404) {
      throw new Error('対象は参加者として登録されていません');
    }
    if (!res.ok) {
      throw new Error(`スコアの取得に失敗しました (${res.status})`);
    }
    const flat = await res.json();
    return groupFlatScores(flat);
  };

  /**
   * 参加者を名簿に追加する（管理者のみ）。
   * @param userId 追加対象の beat-seeker ユーザー ID
   * @returns 追加された参加者サマリ
   */
  const addParticipant = async (userId: number): Promise<KinjoCupParticipant> => {
    const res = await fetch(`${API_BASE}/api/kinjocup/participants`, {
      method: 'POST',
      headers: authHeaders({ 'Content-Type': 'application/json' }),
      body: JSON.stringify({ userId }),
    });
    if (!res.ok) {
      const body = await res.json().catch(() => ({}));
      throw new Error(body?.error || `追加に失敗しました (${res.status})`);
    }
    return (await res.json()) as KinjoCupParticipant;
  };

  /**
   * 参加者のメモを更新する（管理者のみ）。空文字を送るとメモ消去。
   * @param id   参加者エントリの ID
   * @param note 新しいメモ本文
   * @returns 更新後の参加者サマリ
   */
  const updateNote = async (id: number, note: string): Promise<KinjoCupParticipant> => {
    const res = await fetch(`${API_BASE}/api/kinjocup/participants/${id}/note`, {
      method: 'PUT',
      headers: authHeaders({ 'Content-Type': 'application/json' }),
      body: JSON.stringify({ note }),
    });
    if (!res.ok) {
      const body = await res.json().catch(() => ({}));
      throw new Error(body?.error || `メモの保存に失敗しました (${res.status})`);
    }
    return (await res.json()) as KinjoCupParticipant;
  };

  /**
   * 参加者を名簿から削除する（管理者のみ）。
   * @param id 参加者エントリの ID（userId ではない）
   */
  const removeParticipant = async (id: number): Promise<void> => {
    const res = await fetch(`${API_BASE}/api/kinjocup/participants/${id}`, {
      method: 'DELETE',
      headers: authHeaders(),
    });
    if (!res.ok && res.status !== 204) {
      const body = await res.json().catch(() => ({}));
      throw new Error(body?.error || `削除に失敗しました (${res.status})`);
    }
  };

  return { isLoading, fetchParticipants, fetchMatrix, fetchMatchup, fetchSongRanks, fetchParticipantScores, addParticipant, updateNote, removeParticipant };
}
