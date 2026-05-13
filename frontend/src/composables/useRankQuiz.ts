import { ref, computed, readonly } from 'vue';
import { useAuth, API_BASE } from './useAuth';

/**
 * 【Composable の役割】 「非公式難易度クイズ」のサーバー進捗 (Lv/XP) と
 * 復習プールを管理し、回答送信 API を提供する。
 *
 * - モジュールトップで進捗 ref を保持するシングルトン構成。
 *   サイドバーウィジェットとモーダル本体で同じ状態を共有する。
 * - クイズの問題生成自体はクライアント側で行う（モーダル内で組み立て）。
 *   ここでは進捗永続化と復習対象の取得・XP 加算のみを扱う。
 */

/** 復習プール 1 件分。サーバーが mastered=false の譜面を返したもの。 */
export interface ReviewPoolItem {
  title: string;
  difficultyName: string;
  correctRank: string;
  mistakeCount: number;
  reviewStreak: number;
}

/** 進捗ステート（GET /progress のレスポンスをそのままマップ）。 */
export interface QuizProgress {
  xp: number;
  level: number;
  xpForCurrentLevel: number;
  xpForNextLevel: number;
  totalAnswered: number;
  totalCorrect: number;
  reviewPool: ReviewPoolItem[];
  reviewPoolCount: number;
}

/** 回答送信レスポンス。 */
export interface AnswerResult {
  correct: boolean;
  xpGained: number;
  xp: number;
  level: number;
  leveledUp: boolean;
  xpForCurrentLevel: number;
  xpForNextLevel: number;
}

const progress = ref<QuizProgress | null>(null);
const isLoading = ref(false);
const loadError = ref('');

export function useRankQuiz() {
  const { authHeaders, isLoggedIn } = useAuth();

  /** サーバー進捗を取得して ref に反映する。未ログインなら何もしない。 */
  const fetchProgress = async (): Promise<QuizProgress | null> => {
    if (!isLoggedIn.value) {
      progress.value = null;
      return null;
    }
    isLoading.value = true;
    loadError.value = '';
    try {
      const res = await fetch(`${API_BASE}/api/rank-quiz/progress`, {
        headers: authHeaders(),
      });
      if (!res.ok) throw new Error(`HTTP ${res.status}`);
      const data = (await res.json()) as QuizProgress;
      progress.value = data;
      return data;
    } catch (e: any) {
      loadError.value = e?.message ?? 'fetch failed';
      return null;
    } finally {
      isLoading.value = false;
    }
  };

  /**
   * 1 問分の回答を送信する。サーバーは XP / Lv の更新後の値を返してくる。
   * 受信値で進捗 ref も更新する。
   */
  const submitAnswer = async (params: {
    title: string;
    difficultyName: string;
    correctRank: string;
    chosenRank: string;
    isReview: boolean;
  }): Promise<AnswerResult | null> => {
    if (!isLoggedIn.value) return null;
    try {
      const res = await fetch(`${API_BASE}/api/rank-quiz/answer`, {
        method: 'POST',
        headers: authHeaders({ 'Content-Type': 'application/json' }),
        body: JSON.stringify(params),
      });
      if (!res.ok) throw new Error(`HTTP ${res.status}`);
      const data = (await res.json()) as AnswerResult;
      // 進捗 ref を即時更新（サイドバーウィジェットの Lv/XP 表示が連動する）。
      if (progress.value) {
        progress.value = {
          ...progress.value,
          xp: data.xp,
          level: data.level,
          xpForCurrentLevel: data.xpForCurrentLevel,
          xpForNextLevel: data.xpForNextLevel,
          totalAnswered: progress.value.totalAnswered + 1,
          totalCorrect: progress.value.totalCorrect + (data.correct ? 1 : 0),
        };
      }
      return data;
    } catch (e: any) {
      loadError.value = e?.message ?? 'submit failed';
      return null;
    }
  };

  /** Lv 進捗バー（0〜100%）。 */
  const levelProgressPct = computed(() => {
    const p = progress.value;
    if (!p) return 0;
    const span = p.xpForNextLevel - p.xpForCurrentLevel;
    if (span <= 0) return 100;
    const pos = p.xp - p.xpForCurrentLevel;
    return Math.max(0, Math.min(100, (pos / span) * 100));
  });

  return {
    progress: readonly(progress),
    isLoading: readonly(isLoading),
    loadError: readonly(loadError),
    levelProgressPct,
    fetchProgress,
    submitAnswer,
  };
}
