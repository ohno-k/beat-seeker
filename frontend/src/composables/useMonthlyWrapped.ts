/**
 * 【Composable の役割】 月末振り返り（Spotify Wrapped 風）の API クライアントを提供する。
 *
 * バックエンドの 2 エンドポイントを叩く:
 *  - `/api/scores/monthly-wrapped`        … 認証必須・自分の振り返り
 *  - `/api/users/{userId}/monthly-wrapped` … 公開・他ユーザー（privacyLevel=0 のみ）
 *
 * いずれも返却型は `MonthlyWrapped` で統一しており、WrappedView 側は
 * 「誰の振り返りを引いてきたか」を意識せず同じカード群でレンダリングできる。
 *
 * 使い方:
 * ```ts
 * const { data, isLoading, error, fetchMyWrapped } = useMonthlyWrapped();
 * await fetchMyWrapped(2026, 5);
 * ```
 */
import { ref } from 'vue';
import { useAuth } from './useAuth';
import { API_BASE } from './constants';

/**
 * 月内に AAA / MAX- に新規到達した譜面 1 件分。
 * informalRank は "12.3" 等のマスタ値（未登録時は null）。
 * achievementType は "AAA" または "MAX-"（MAX- は AAA より上位の達成扱い）。
 */
export interface TopAchievement {
  title: string;
  difficulty: string;
  informalRank: string | null;
  achievementType: 'AAA' | 'MAX-' | string;
  newScore: number | null;
  maxScore: number;
}

/**
 * 月内に伸びた譜面 1 件分のサマリ。バックエンド DTO と 1:1 対応。
 *
 * 各 PT 系（BEAT/RATE）について「月初値・月末値・増加量」を持つ。
 * 譜面が PT 計算の対象外（HYPER の高難易度、譜面マスタ未マッチ等）の場合は 0 のまま返る。
 */
export interface TopUpdatedSong {
  title: string;
  difficulty: string;
  oldScore: number | null;
  newScore: number | null;
  scoreIncrease: number;
  oldClearType: string | null;
  newClearType: string | null;
  clearTypeImproved: boolean;
  oldBeatPt: number;
  newBeatPt: number;
  beatPtIncrease: number;
  oldRatePt: number;
  newRatePt: number;
  ratePtIncrease: number;
}

/**
 * 月末振り返りのレスポンス本体。
 *
 * 各 PT（BEAT/RATE/KENBAN/SARA）について start/end/increase の 3 値を持ち、
 * クリア種別・DJ レベルは月内増加分を Map で返す。
 * `newBeatPtBest` / `newRatePtBest` は月末値が全期間最高と一致した場合に true。
 */
export interface MonthlyWrapped {
  year: number;
  month: number;
  displayMonth: string;
  startDate: string;
  endDate: string;
  userId: number;
  displayName: string;
  isSupporter: boolean;
  /** RATE-TIER をプロフィールに表示するかどうか。false なら振り返り上の RATE-PT 系も非表示にする。 */
  showRateTier: boolean;
  showKenbanSaraTier: boolean;
  startBeatPt: number;
  endBeatPt: number;
  beatPtIncrease: number;
  startRatePt: number;
  endRatePt: number;
  ratePtIncrease: number;
  startKenbanPt: number;
  endKenbanPt: number;
  kenbanPtIncrease: number;
  startSaraPt: number;
  endSaraPt: number;
  saraPtIncrease: number;
  uploadCount: number;
  totalUpdatedCount: number;
  uniqueUpdatedSongCount: number;
  clearTypeIncrease: Record<string, number>;
  djLevelIncrease: Record<string, number>;
  /** スコア増加量降順の TOP20。後方互換のため残しているが、新規プレー曲が独占しやすい点に注意。 */
  topUpdatedSongs: TopUpdatedSong[];
  /** 現在の BEAT-PT 降順 × 月内更新フィルタの TOP10。 */
  topBeatPtSongs: TopUpdatedSong[];
  /** RATE-PT 増加量降順の TOP10。ANOTHER/LEGGENDARIA のみ対象。 */
  topRatePtSongs: TopUpdatedSong[];
  /** 月内に AAA / MAX- に新規到達した譜面、非公式難易度降順の TOP5。 */
  topAchievements: TopAchievement[];
  prevMonthBeatPtIncrease: number;
  allTimeMaxBeatPt: number;
  allTimeMaxRatePt: number;
  newBeatPtBest: boolean;
  newRatePtBest: boolean;
}

export function useMonthlyWrapped() {
  const { authHeaders } = useAuth();
  const data = ref<MonthlyWrapped | null>(null);
  const isLoading = ref(false);
  const error = ref<string | null>(null);

  /**
   * ログインユーザー自身の振り返りを取得する。
   * 認証エラーは error にメッセージを残し、data は null のまま。
   */
  const fetchMyWrapped = async (year: number, month: number) => {
    isLoading.value = true;
    error.value = null;
    try {
      const res = await fetch(
        `${API_BASE}/api/scores/monthly-wrapped?year=${year}&month=${month}`,
        { headers: authHeaders() }
      );
      if (!res.ok) {
        throw new Error(`Fetch failed: ${res.status}`);
      }
      data.value = await res.json();
    } catch (e: any) {
      error.value = e?.message ?? 'unknown error';
      data.value = null;
    } finally {
      isLoading.value = false;
    }
  };

  /**
   * 指定ユーザーの公開振り返りを取得する（SNS 流入経由・OGP 展開用）。
   * privacyLevel != 0 のユーザーや存在しない userId は 403/404 で error にメッセージが入る。
   */
  const fetchPublicWrapped = async (userId: number, year: number, month: number) => {
    isLoading.value = true;
    error.value = null;
    try {
      const res = await fetch(
        `${API_BASE}/api/users/${userId}/monthly-wrapped?year=${year}&month=${month}`
      );
      if (!res.ok) {
        throw new Error(`Fetch failed: ${res.status}`);
      }
      data.value = await res.json();
    } catch (e: any) {
      error.value = e?.message ?? 'unknown error';
      data.value = null;
    } finally {
      isLoading.value = false;
    }
  };

  return { data, isLoading, error, fetchMyWrapped, fetchPublicWrapped };
}
