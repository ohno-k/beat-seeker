import { ref } from 'vue';
import { useAuth } from './useAuth';
import { groupFlatScores } from './useScores';
import type { ScoreData } from '../types/ScoreData';

/** バックエンド API のベース URL。 */
const API_BASE = import.meta.env.VITE_API_BASE ?? 'http://localhost:8080';

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

  return { isLoading, fetchParticipants, fetchParticipantScores, addParticipant, updateNote, removeParticipant };
}
