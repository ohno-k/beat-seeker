import { ref } from 'vue';
import { API_BASE } from './constants';
import type { PlayerIndividualViewDto } from './useCompetitionPlayer';

/**
 * 【Composable の役割】 個人戦 (`Competition.format = "individual4"`) の参加者向け
 * 読み取り専用ビュー API ラッパ。
 *
 * 招待 URL は team5 と同じ `/api/competition-access/player/{token}` を使う (バックエンドが
 * 大会フォーマットに応じて自動で異なる shape を返す)。本 composable は応答が
 * {@link PlayerIndividualViewDto} 形式である前提で型付けされている。
 *
 * 個人戦には StrategyCard / 自選曲提出フェーズが無いので、本人スケジュールと試合結果
 * のリードオンリー表示のみ提供する。
 */

async function throwIfError(res: Response): Promise<void> {
  if (res.ok) return;
  let msg = `HTTP ${res.status}`;
  try {
    const data = await res.json();
    if (data && typeof data.message === 'string') msg = data.message;
  } catch { /* not JSON */ }
  throw new Error(msg);
}

export function useCompetitionPlayerIndividual() {
  const view = ref<PlayerIndividualViewDto | null>(null);
  const isLoading = ref(false);

  const fetchView = async (token: string): Promise<void> => {
    isLoading.value = true;
    try {
      const res = await fetch(`${API_BASE}/api/competition-access/player/${token}`);
      await throwIfError(res);
      view.value = (await res.json()) as PlayerIndividualViewDto;
    } finally {
      isLoading.value = false;
    }
  };

  return { view, isLoading, fetchView };
}
