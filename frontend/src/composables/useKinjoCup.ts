import { ref } from 'vue';
import { useAuth } from './useAuth';

/** バックエンド API のベース URL。 */
const API_BASE = import.meta.env.VITE_API_BASE ?? 'http://localhost:8080';

/** 閲覧アクセス拒否の種別。401=未ログイン / 403=ホワイトリスト外。 */
export type KinjoCupAccessCode = 'unauthorized' | 'forbidden';

/**
 * 参加者一覧の取得が権限で弾かれたときに投げるエラー。
 * 呼び出し側は `code` を見て「ログインが必要」か「権限なし」かを出し分ける。
 */
export class KinjoCupAccessError extends Error {
  constructor(public code: KinjoCupAccessCode) {
    super(code);
    this.name = 'KinjoCupAccessError';
  }
}

/**
 * きんじょー杯 特設ページ（/kinjocup）に掲載する参加者 1 人分のサマリ。
 * 実力データ（総合力 / 段位 / アリーナ）はバックエンドが User から都度引いて返す。
 */
export interface KinjoCupParticipant {
  /** 参加者エントリの ID（削除時に使う。userId とは別物）。 */
  id: number;
  /** beat-seeker ユーザーの ID（/user/:userId の詳細リンクに使う）。 */
  userId: number;
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
   * 参加者一覧を取得する（閲覧ホワイトリストのみ・総合力降順でサーバから返る）。
   * 401/403 は {@link KinjoCupAccessError}、その他の失敗は通常の Error を投げる。
   */
  const fetchParticipants = async (): Promise<KinjoCupParticipant[]> => {
    isLoading.value = true;
    try {
      const res = await fetch(`${API_BASE}/api/kinjocup/participants`, {
        headers: authHeaders(),
      });
      if (res.status === 401) throw new KinjoCupAccessError('unauthorized');
      if (res.status === 403) throw new KinjoCupAccessError('forbidden');
      if (!res.ok) {
        throw new Error(`一覧の取得に失敗しました (${res.status})`);
      }
      return (await res.json()) as KinjoCupParticipant[];
    } finally {
      isLoading.value = false;
    }
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

  return { isLoading, fetchParticipants, addParticipant, removeParticipant };
}
