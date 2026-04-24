<script setup lang="ts">
/**
 * 【Viewの役割】 アーケードモード。今プレイすべき譜面をユースケース別に提案する画面。
 *
 * 機能（4モード切替）:
 *  - Rival:   フレンドを選んで「自分が負けている曲」を差分順に列挙。
 *  - Border:  AA / AAA / MAX- のスコアボーダー目前曲を並べ、ちょい伸ばしを支援。
 *  - Beat-PT: BEAT-Tier ポイントを上げるために次に伸ばすべき曲候補。単曲目標 Pt も指定可。
 *  - Rate-PT: RATE-Tier ポイントを上げるための曲候補（ANOTHER/LEGGENDARIA 限定）。
 *  - 共通フィルタ: Lv.11 / Lv.12 のトグル、上位50件だけを表示。
 *
 * 依存:
 *  - `useScores.fetchMyScores` — 自分のスコア取得。
 *  - `useFriends` — フレンド一覧＆フレンドスコア取得。
 *  - `utils/beatTier` — Tier ポイント計算。
 *  - `utils/scoreData.flattenScores` — ScoreData（曲ごと）を ScoreRecord（譜面ごと）にフラット化。
 */
import { ref, computed, onMounted, watch } from 'vue';
import { useScores } from '../composables/useScores';
import { useFriends } from '../composables/useFriends';
import { useAuth, API_BASE } from '../composables/useAuth';
import { useAdmin } from '../composables/useAdmin';
import { useI18n } from '../composables/useI18n';
import { flattenScores, type ScoreRecord } from '../utils/scoreData';
import type { ScoreData, DifficultyStats } from '../types/ScoreData';
import { calculateScoreRateTierPoints, SCORE_RATE_THRESHOLDS, calculatePoints } from '../utils/beatTier';

const { t } = useI18n();
const { authHeaders } = useAuth();
const { isAdmin } = useAdmin();

/** モード選択。'potential' = 伸びしろ（pair-scatter 予測ベース） */
type Mode = 'rival' | 'border' | 'beat-pt' | 'rate-pt' | 'potential';
/** Border モードのターゲットグレード */
type BorderTarget = 'aa' | 'aaa' | 'max-minus';

/** 推奨カード1枚ぶんの表示データ（どのモードでも共通型） */
interface SongCard {
  key: string;
  title: string;
  difficultyName: string;
  difficultyColor: string;
  difficultyLevel: number | null;
  score: number;
  maxScore: number;
  scoreRate: number;
  djLevel: string;
  informalRank: string | undefined;
  beatTierPoints: number;
  maxBeatTierPoints: number;
  gap: number;
  gapLabel: string;
  subLabel: string;
  friendScore?: number;
  /** 伸びしろモード専用: 予測精度。LOW のときは UI でバッジ表示する */
  accuracy?: 'HIGH' | 'LOW';
  /** 伸びしろモード専用: 予測スコア / 予測レート(%)。スコア行に「現在 → 予測」で表示する */
  predictedScore?: number;
  predictedRate?: number;
}

const { fetchMyScores } = useScores();
const { fetchFriends, fetchFriendScores, friends } = useFriends();

/** 初期データロード中フラグ */
const isLoading = ref(true);
/** 自分のスコア（ScoreRecord にフラット化済み） */
const myScores = ref<ScoreRecord[]>([]);
/** 現在のモード。null なら選択画面 */
const mode = ref<Mode | null>(null);
/** Lv.11 譜面を対象に含めるか */
const showLv11 = ref(true);
/** Lv.12 譜面を対象に含めるか */
const showLv12 = ref(true);
/** Rival モードで選択中のフレンドID */
const selectedFriendId = ref<number | null>(null);
/** 選択中フレンドのスコア */
const friendScores = ref<ScoreRecord[]>([]);
/** フレンドスコア取得中フラグ */
const isFriendLoading = ref(false);
/** Rival 並び替え: 接戦順 / 差が大きい順 */
const rivalSortClosest = ref(true);
/** Border モードの目標グレード */
const borderTarget = ref<BorderTarget>('aa');
/** Beat-PT 目標ポイント（単曲目標） */
const beatPtTarget = ref<number | null>(null);
/** Rate-PT 目標ポイント（単曲目標） */
const ratePtTarget = ref<number | null>(null);

/** 伸びしろモード: API レスポンスをキャッシュ */
interface GrowthPotentialItem {
  title: string;
  difficultyName: string;
  difficultyLevel: number;
  currentScore: number;
  predictedScore: number;
  gap: number;
  supportCount: number;
  /** "HIGH" = |r|≧0.95 ペアで算出、"LOW" = |r|≧0.90 までフォールバック */
  accuracy?: 'HIGH' | 'LOW';
  maxScore?: number;
  currentRate?: number;
  predictedRate?: number;
}
const potentialItems = ref<GrowthPotentialItem[]>([]);
/** 伸びしろデータ取得中フラグ（初回計算は数秒かかる） */
const isPotentialLoading = ref(false);
const potentialError = ref('');
/** 検証用: 閲覧対象ユーザー ID。null = 自分。管理者のみ切替可能。 */
const potentialViewUserId = ref<number | null>(null);
/** 検証用: ユーザー一覧（管理者ドロップダウンのソース） */
interface AdminUser { id: number; displayName: string; iidxId: string; }
const adminUsers = ref<AdminUser[]>([]);
/** 検証用: 他人のスコア（被験者の現在スコアを表示するため別途取得） */
const viewedUserScores = ref<ScoreRecord[]>([]);
const isLoadingViewedScores = ref(false);

/** AA のスコア率下限（%） */
const AA_RATE = 77.77;
/** AAA のスコア率下限（%） */
const AAA_RATE = 88.88;
/** MAX- のスコア率下限（%） */
const MAX_MINUS_RATE = 94.44;

/** BEAT-Tier のボーナス区切り。次の閾値達成までの曲抽出に使う */
const BEAT_BONUS_THRESHOLDS = [
  { rate: 77.78, label: 'AA' },
  { rate: 88.89, label: 'AAA' },
  { rate: 94.45, label: 'MAX-' },
  { rate: 100,   label: 'MAX' },
] as const;


/** privacyLevel=2（スコア非公開）のフレンドを除外した選択肢 */
const publicFriends = computed(() =>
  friends.value.filter(f => (f.privacyLevel ?? 0) !== 2)
);

/**
 * 【関数の役割】 API が返すフラットなスコア配列を、曲単位＋難易度別の ScoreData 構造に組み直す。
 * フレンドスコアは別形式で返ってくるため、一旦自分用と同じ形へ寄せる。
 */
function groupFriendScores(flatScores: any[]): ScoreData[] {
  const grouped = new Map<string, any>();
  const emptyDiff = (): DifficultyStats => ({
    difficulty: null, score: 0, pgreat: 0, great: 0, missCount: null,
    clearType: 'NO PLAY', djLevel: '---'
  });
  flatScores.forEach((s: any) => {
    if (!grouped.has(s.title)) {
      grouped.set(s.title, {
        version: '0', title: s.title, genre: s.genre || '', artist: s.artist || '',
        playCount: 0, lastPlayTime: '',
        beginner: emptyDiff(), normal: emptyDiff(), hyper: emptyDiff(),
        another: emptyDiff(), leggendaria: emptyDiff()
      });
    }
    const entry = grouped.get(s.title);
    const diffKey = s.difficultyName.toLowerCase();
    if (entry[diffKey]) {
      entry[diffKey] = {
        id: s.id, difficulty: s.difficultyLevel, score: s.score,
        pgreat: s.pgreat || 0, great: s.great || 0, missCount: s.missCount,
        clearType: s.clearType, djLevel: s.djLevel
      };
    }
  });
  return Array.from(grouped.values());
}

/**
 * 【関数の役割】 スコアレート閾値を越えるための最小スコアを算出。
 * `floor(max * t / 100) + 1` で、「ちょうどそのラインを越える」最小値を返す。
 */
function borderScore(maxScore: number, threshold: number): number {
  return Math.floor(maxScore * threshold / 100) + 1;
}

/**
 * 【関数の役割】 ある非公式ランクで、指定 BEAT-PT ちょうどを与えるスコアレートを二分探索で求める。
 * `calculatePoints` は単調増加なので二分探索で精度よく逆算できる。
 * @param targetPt 目標ポイント
 * @param informalRank 非公式ランク（未定なら 100% で打ち切り）
 */
function findScoreRateForBeatPt(targetPt: number, informalRank: string | undefined): number {
  if (!informalRank) return 100;
  let lo = 66.667, hi = 100;
  for (let i = 0; i < 60; i++) {
    const mid = (lo + hi) / 2;
    if (calculatePoints(mid, informalRank) < targetPt) lo = mid;
    else hi = mid;
  }
  return hi;
}

/** Beat-PT 目標に対し、現時点で達成済みの曲数を集計（達成状況バッジ用） */
const beatPtAchievedCount = computed(() => {
  if (!beatPtTarget.value || beatPtTarget.value <= 0) return null;
  const eligible = myScores.value.filter(s => s.informalRank && s.maxScore > 0);
  const achieved = eligible.filter(s => s.beatTierPoints >= beatPtTarget.value!);
  return { achieved: achieved.length, total: eligible.length };
});

/** Rate-PT 目標に対し、現時点で達成済みの曲数を集計 */
const ratePtAchievedCount = computed(() => {
  if (!ratePtTarget.value || ratePtTarget.value <= 0) return null;
  const eligible = myScores.value.filter(s =>
    (s.difficultyName === 'ANOTHER' || s.difficultyName === 'LEGGENDARIA') && s.maxScore > 0 && s.scoreRate > 0
  );
  const achieved = eligible.filter(s => calculateScoreRateTierPoints(s.scoreRate) >= ratePtTarget.value!);
  return { achieved: achieved.length, total: eligible.length };
});

// マウント時に自分のスコアとフレンド一覧を並列取得する
onMounted(async () => {
  isLoading.value = true;
  try {
    const raw = await fetchMyScores();
    myScores.value = flattenScores(raw);
  } finally {
    isLoading.value = false;
  }
  fetchFriends();
});

/**
 * 【関数】 伸びしろデータを取得する。初回はバックエンドのキャッシュ構築（数秒）が走る。
 * potentialViewUserId が null なら自分（/api/analysis/growth-potential）、
 * それ以外なら管理者向け（/api/admin/growth-potential?userId=X）を叩く。
 * @param force 既存データを破棄して再取得するかどうか
 */
async function fetchPotential(force = false) {
  if (!force && potentialItems.value.length > 0 && !potentialError.value) return;
  isPotentialLoading.value = true;
  potentialError.value = '';
  potentialItems.value = [];
  try {
    const url = potentialViewUserId.value == null
      ? `${API_BASE}/api/analysis/growth-potential`
      : `${API_BASE}/api/admin/growth-potential?userId=${potentialViewUserId.value}`;
    const res = await fetch(url, { headers: authHeaders() });
    if (!res.ok) throw new Error(`HTTP ${res.status}`);
    const data = await res.json() as { items: GrowthPotentialItem[] };
    potentialItems.value = data.items ?? [];
  } catch (e: any) {
    potentialError.value = e?.message ?? '通信エラー';
  } finally {
    isPotentialLoading.value = false;
  }
}

/**
 * 【関数】 管理者用ユーザー一覧を取得（初回のみ）。検証ドロップダウンのソース。
 */
async function loadAdminUsers() {
  if (adminUsers.value.length > 0 || !isAdmin.value) return;
  try {
    const res = await fetch(`${API_BASE}/api/admin/users`, { headers: authHeaders() });
    if (!res.ok) return;
    const list = await res.json() as AdminUser[];
    // 表示名で並べる（昇順）
    adminUsers.value = list.sort((a, b) =>
      (a.displayName || a.iidxId).localeCompare(b.displayName || b.iidxId, 'ja'));
  } catch (e) {
    console.error('loadAdminUsers failed', e);
  }
}

/**
 * 【関数】 閲覧対象ユーザーのスコアを取得する（被験者の現在スコアと比較するため）。
 * 自分（potentialViewUserId=null）の場合は myScores を使うので何もしない。
 */
async function loadViewedUserScores() {
  if (potentialViewUserId.value == null) {
    viewedUserScores.value = [];
    return;
  }
  isLoadingViewedScores.value = true;
  try {
    const res = await fetch(`${API_BASE}/api/admin/users/${potentialViewUserId.value}/scores`, { headers: authHeaders() });
    if (!res.ok) { viewedUserScores.value = []; return; }
    const raw = await res.json();
    // /api/admin/users/{id}/scores はフラットな配列で返ってくるので、
    // groupFriendScores で曲単位 ScoreData に組み直してから flattenScores で ScoreRecord に展開する。
    viewedUserScores.value = flattenScores(groupFriendScores(raw));
  } catch (e) {
    console.error('loadViewedUserScores failed', e);
    viewedUserScores.value = [];
  } finally {
    isLoadingViewedScores.value = false;
  }
}

// 伸びしろモードに切り替えたら（初回のみ）取得する。管理者ならユーザー一覧も同時に取得。
watch(mode, (m) => {
  if (m === 'potential') {
    fetchPotential();
    if (isAdmin.value) loadAdminUsers();
  }
});

// 検証対象ユーザーが切り替わったら、伸びしろと該当ユーザーのスコアを再取得
watch(potentialViewUserId, () => {
  if (mode.value === 'potential') {
    fetchPotential(true);
    loadViewedUserScores();
  }
});

// フレンド選択変更時に、そのフレンドのスコアを取得する（空選択時はクリア）
watch(selectedFriendId, async (id) => {
  if (!id) { friendScores.value = []; return; }
  isFriendLoading.value = true;
  try {
    const raw = await fetchFriendScores(id);
    friendScores.value = flattenScores(groupFriendScores(raw));
  } finally {
    isFriendLoading.value = false;
  }
});

/** Lv.11/Lv.12 チェックボックスでフィルタした自スコア */
const levelFiltered = computed(() =>
  myScores.value.filter(s =>
    (s.difficultyLevel === 11 && showLv11.value) ||
    (s.difficultyLevel === 12 && showLv12.value)
  )
);

/**
 * 【主要 computed】 4モード共通の推奨カード配列を返す。
 *
 * 処理の流れ:
 *  - Rival:   フレンドスコアと照合し「負けている曲」だけ抽出して差分順に並べる。
 *  - Border:  目標グレードに達していない曲を「必要スコア差」で並べる。
 *  - Beat-PT: 目標 Pt 指定時は単曲目標、未指定時は「次のスコア率閾値」を狙う。
 *  - Rate-PT: 同じロジックを ANOTHER/LEGGENDARIA 限定・Rate 閾値ベースで適用。
 *  - 各モード共通で上位50件にトリムする（表示パフォーマンス配慮）。
 */
const suggestions = computed((): SongCard[] => {
  if (!mode.value) return [];
  const base = levelFiltered.value;

  if (mode.value === 'rival') {
    if (!selectedFriendId.value || friendScores.value.length === 0) return [];
    const friendMap = new Map(friendScores.value.map(s => [`${s.title}_${s.difficultyName}`, s]));
    const cards: SongCard[] = [];
    for (const s of base) {
      const f = friendMap.get(`${s.title}_${s.difficultyName}`);
      if (!f || f.score <= 0 || f.score <= s.score) continue;
      const gap = f.score - s.score;
      cards.push({
        key: `${s.title}_${s.difficultyName}`,
        title: s.title,
        difficultyName: s.difficultyName, difficultyColor: s.difficultyColor, difficultyLevel: s.difficultyLevel,
        score: s.score, maxScore: s.maxScore, scoreRate: s.scoreRate,
        djLevel: s.djLevel, informalRank: s.informalRank,
        beatTierPoints: s.beatTierPoints, maxBeatTierPoints: s.maxBeatTierPoints,
        gap,
        gapLabel: t('arcade.gapRival', { gap }),
        subLabel: t('arcade.subRival', { my: s.score.toLocaleString(), rival: f.score.toLocaleString() }),
        friendScore: f.score
      });
    }
    return cards.sort((a, b) => rivalSortClosest.value ? a.gap - b.gap : b.gap - a.gap).slice(0, 50);
  }

  if (mode.value === 'border') {
    const config = {
      'aa':        { minRate: 0,        maxRate: AA_RATE,       label: 'AA',   targetRate: AA_RATE },
      'aaa':       { minRate: AA_RATE,  maxRate: AAA_RATE,      label: 'AAA',  targetRate: AAA_RATE },
      'max-minus': { minRate: AAA_RATE, maxRate: MAX_MINUS_RATE, label: 'MAX-', targetRate: MAX_MINUS_RATE },
    }[borderTarget.value];

    return base
      .filter(s => s.maxScore > 0 && s.scoreRate > config.minRate && s.scoreRate < config.maxRate)
      .map(s => {
        const bScore = borderScore(s.maxScore, config.targetRate);
        const gap = bScore - s.score;
        return {
          key: `${s.title}_${s.difficultyName}`,
          title: s.title,
          difficultyName: s.difficultyName, difficultyColor: s.difficultyColor, difficultyLevel: s.difficultyLevel,
          score: s.score, maxScore: s.maxScore, scoreRate: s.scoreRate,
          djLevel: s.djLevel, informalRank: s.informalRank,
          beatTierPoints: s.beatTierPoints, maxBeatTierPoints: s.maxBeatTierPoints,
          gap: Math.max(0, gap),
          gapLabel: gap > 0
            ? t('arcade.gapBorder', { label: config.label, gap })
            : t('arcade.borderAchieved', { label: config.label }),
          subLabel: t('arcade.subBorder', { rate: s.scoreRate.toFixed(2), target: config.targetRate }),
        };
      })
      .filter(s => s.gap > 0)
      .sort((a, b) => a.gap - b.gap)
      .slice(0, 50);
  }

  if (mode.value === 'beat-pt') {
    const target = beatPtTarget.value;
    const cards: SongCard[] = [];
    for (const s of myScores.value) {
      if (!s.informalRank || s.maxScore <= 0) continue; // 達成不可能
      if (target && target > 0) {
        // 単曲目標モード
        if (s.beatTierPoints >= target) continue; // 達成済み
        if (s.maxBeatTierPoints < target - 0.005) continue; // 達成不可能
        const targetRate = findScoreRateForBeatPt(target, s.informalRank);
        const targetScore = Math.ceil(s.maxScore * targetRate / 100);
        const scoreGap = targetScore - s.score;
        if (scoreGap <= 0) continue;
        cards.push({
          key: `${s.title}_${s.difficultyName}`,
          title: s.title,
          difficultyName: s.difficultyName, difficultyColor: s.difficultyColor, difficultyLevel: s.difficultyLevel,
          score: s.score, maxScore: s.maxScore, scoreRate: s.scoreRate,
          djLevel: s.djLevel, informalRank: s.informalRank,
          beatTierPoints: s.beatTierPoints, maxBeatTierPoints: s.maxBeatTierPoints,
          gap: scoreGap,
          gapLabel: t('arcade.gapPtNext', { gap: scoreGap.toLocaleString() }),
          subLabel: t('arcade.subBeatPtTarget', { current: s.beatTierPoints.toFixed(2), target: target.toFixed(2) }),
        });
      } else {
        // 目標未入力: 次のボーナス閾値まで表示
        if (s.beatTierPoints >= s.maxBeatTierPoints * 0.999) continue; // 達成済み
        const nextTh = BEAT_BONUS_THRESHOLDS.find(th => s.scoreRate < th.rate);
        if (!nextTh) continue;
        const targetScore = nextTh.rate >= 100 ? s.maxScore : Math.ceil(s.maxScore * nextTh.rate / 100);
        const scoreGap = targetScore - s.score;
        if (scoreGap <= 0) continue;
        const newRate = (targetScore / s.maxScore) * 100;
        const ptGain = Math.max(0, calculatePoints(newRate, s.informalRank) - s.beatTierPoints);
        if (ptGain < 0.005) continue;
        cards.push({
          key: `${s.title}_${s.difficultyName}`,
          title: s.title,
          difficultyName: s.difficultyName, difficultyColor: s.difficultyColor, difficultyLevel: s.difficultyLevel,
          score: s.score, maxScore: s.maxScore, scoreRate: s.scoreRate,
          djLevel: s.djLevel, informalRank: s.informalRank,
          beatTierPoints: s.beatTierPoints, maxBeatTierPoints: s.maxBeatTierPoints,
          gap: scoreGap,
          gapLabel: t('arcade.gapPtNext', { gap: scoreGap.toLocaleString() }),
          subLabel: t('arcade.subBeatPtNext', { label: nextTh.label, gain: ptGain.toFixed(2) }),
        });
      }
    }
    return cards.sort((a, b) => a.gap - b.gap).slice(0, 50);
  }

  if (mode.value === 'potential') {
    // 伸びしろモード: backend の予測スコアと現在スコアの差分を表示
    if (potentialItems.value.length === 0) return [];
    // 表示情報の参照元: 自分閲覧時は myScores、他ユーザー検証時は viewedUserScores
    const sourceScores = potentialViewUserId.value == null ? myScores.value : viewedUserScores.value;
    const myMap = new Map(sourceScores.map(s => [`${s.title}_${s.difficultyName}`, s]));
    const cards: SongCard[] = [];
    for (const item of potentialItems.value) {
      if (item.gap <= 0) continue; // 伸びしろがない or マイナスは無視
      // Lv11/12 フィルタ（他モードと同じ）
      if (item.difficultyLevel === 11 && !showLv11.value) continue;
      if (item.difficultyLevel === 12 && !showLv12.value) continue;
      if (item.difficultyLevel !== 11 && item.difficultyLevel !== 12) continue;

      const my = myMap.get(`${item.title}_${item.difficultyName}`);
      if (!my) continue; // 自分のスコアが見つからない（理論的にはあり得ない）

      const predictedScoreRounded = Math.round(item.predictedScore);
      const gapRounded = Math.round(item.gap);
      cards.push({
        key: `${item.title}_${item.difficultyName}`,
        title: item.title,
        difficultyName: item.difficultyName,
        difficultyColor: my.difficultyColor,
        difficultyLevel: item.difficultyLevel,
        score: item.currentScore,
        maxScore: item.maxScore ?? my.maxScore,
        scoreRate: item.currentRate ?? my.scoreRate,
        djLevel: my.djLevel,
        informalRank: my.informalRank,
        beatTierPoints: my.beatTierPoints,
        maxBeatTierPoints: my.maxBeatTierPoints,
        gap: gapRounded,
        gapLabel: `+${gapRounded.toLocaleString()}`,
        subLabel: t('arcade.subPotential', { count: item.supportCount }),
        accuracy: item.accuracy,
        predictedScore: predictedScoreRounded,
        predictedRate: item.predictedRate,
      });
    }
    // backend が gap 降順で返すのでそのまま、上位50件
    return cards.slice(0, 50);
  }

  if (mode.value === 'rate-pt') {
    const target = ratePtTarget.value;
    const cards: SongCard[] = [];
    for (const s of myScores.value) {
      if (s.difficultyName !== 'ANOTHER' && s.difficultyName !== 'LEGGENDARIA') continue;
      if (s.maxScore <= 0 || s.scoreRate <= 0) continue; // 達成不可能
      const currentPt = calculateScoreRateTierPoints(s.scoreRate);
      if (target && target > 0) {
        // 単曲目標モード
        if (currentPt >= target) continue; // 達成済み
        const targetTh = SCORE_RATE_THRESHOLDS.find(th => th.points >= target);
        if (!targetTh) continue; // 達成不可能 (target > 512)
        const targetScore = Math.ceil(s.maxScore * targetTh.rate / 100);
        const scoreGap = targetScore - s.score;
        if (scoreGap <= 0) continue;
        cards.push({
          key: `${s.title}_${s.difficultyName}`,
          title: s.title,
          difficultyName: s.difficultyName, difficultyColor: s.difficultyColor, difficultyLevel: s.difficultyLevel,
          score: s.score, maxScore: s.maxScore, scoreRate: s.scoreRate,
          djLevel: s.djLevel, informalRank: s.informalRank,
          beatTierPoints: s.beatTierPoints, maxBeatTierPoints: s.maxBeatTierPoints,
          gap: scoreGap,
          gapLabel: t('arcade.gapPtNext', { gap: scoreGap.toLocaleString() }),
          subLabel: t('arcade.subRatePtTarget', { current: currentPt.toFixed(1), target }),
        });
      } else {
        // 目標未入力: 次の閾値まで表示
        const nextTh = SCORE_RATE_THRESHOLDS.find(th => th.rate > s.scoreRate);
        if (!nextTh) continue; // 達成済み
        const targetScore = Math.ceil(s.maxScore * nextTh.rate / 100);
        const scoreGap = targetScore - s.score;
        if (scoreGap <= 0) continue;
        const ptGain = nextTh.points - currentPt;
        cards.push({
          key: `${s.title}_${s.difficultyName}`,
          title: s.title,
          difficultyName: s.difficultyName, difficultyColor: s.difficultyColor, difficultyLevel: s.difficultyLevel,
          score: s.score, maxScore: s.maxScore, scoreRate: s.scoreRate,
          djLevel: s.djLevel, informalRank: s.informalRank,
          beatTierPoints: s.beatTierPoints, maxBeatTierPoints: s.maxBeatTierPoints,
          gap: scoreGap,
          gapLabel: t('arcade.gapPtNext', { gap: scoreGap.toLocaleString() }),
          subLabel: t('arcade.subRatePtNext', { rate: nextTh.rate, gain: ptGain.toFixed(1) }),
        });
      }
    }
    return cards.sort((a, b) => a.gap - b.gap).slice(0, 50);
  }

  return [];
});

/** 現在のモードラベル（結果ヘッダに表示） */
const modeLabel = computed((): string => {
  if (!mode.value) return '';
  return {
    'rival':     t('arcade.modeLabelRival'),
    'border':    t('arcade.modeLabelBorder'),
    'beat-pt':   t('arcade.modeLabelBeatPt'),
    'rate-pt':   t('arcade.modeLabelRatePt'),
    'potential': t('arcade.modeLabelPotential'),
  }[mode.value] ?? '';
});

/** 現在のモードの並び替えルール説明文 */
const modeSortNote = computed((): string => {
  if (!mode.value) return '';
  if (mode.value === 'rival') return rivalSortClosest.value ? t('arcade.sortNoteRivalClosest') : t('arcade.sortNoteRivalWidest');
  return {
    'border':    t('arcade.sortNoteBorder'),
    'beat-pt':   t('arcade.sortNoteBeatPt'),
    'rate-pt':   t('arcade.sortNoteRatePt'),
    'potential': t('arcade.sortNotePotential'),
  }[mode.value] ?? '';
});

/** 選択中フレンドオブジェクト（空メッセージ時の名前表示用） */
const selectedFriend = computed(() =>
  friends.value.find(f => f.id === selectedFriendId.value) ?? null
);
</script>

<template>
  <div class="min-h-screen bg-slate-50 dark:bg-slate-900 pb-12">

    <!-- スティッキーなフィルタバー: 画面上端に固定されタイトルとLv.11/12トグルを表示 -->
    <div class="sticky top-0 z-20 bg-white dark:bg-slate-800 border-b border-slate-200 dark:border-slate-700 shadow-sm">
      <div class="px-4 py-3 flex items-center justify-between gap-3">
        <div class="flex items-center gap-2">
          <svg xmlns="http://www.w3.org/2000/svg" class="h-5 w-5 text-violet-600 shrink-0" fill="none" viewBox="0 0 24 24" stroke="currentColor">
            <path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M9 20l-5.447-2.724A1 1 0 013 16.382V5.618a1 1 0 011.447-.894L9 7m0 13l6-3m-6 3V7m6 10l4.553 2.276A1 1 0 0021 18.382V7.618a1 1 0 00-.553-.894L15 4m0 13V4m0 0L9 7" />
          </svg>
          <span class="font-black text-slate-800 dark:text-white text-sm tracking-wide">{{ t('arcade.title') }}</span>
        </div>

        <!-- レベル絞り込みチェックボックス -->
        <div class="flex items-center gap-3">
          <label class="flex items-center gap-1 cursor-pointer select-none">
            <input type="checkbox" v-model="showLv11" class="w-4 h-4 rounded accent-violet-500 cursor-pointer" />
            <span class="text-xs font-black" :class="showLv11 ? 'text-violet-600 dark:text-violet-400' : 'text-slate-400'">Lv.11</span>
          </label>
          <label class="flex items-center gap-1 cursor-pointer select-none">
            <input type="checkbox" v-model="showLv12" class="w-4 h-4 rounded accent-violet-500 cursor-pointer" />
            <span class="text-xs font-black" :class="showLv12 ? 'text-violet-600 dark:text-violet-400' : 'text-slate-400'">Lv.12</span>
          </label>
        </div>
      </div>
    </div>

    <div class="px-4 py-5 space-y-5">

      <!-- 初期ロード中のスピナー -->
      <div v-if="isLoading" class="flex flex-col items-center justify-center py-20 gap-4">
        <div class="w-10 h-10 border-4 border-violet-100 border-t-violet-600 rounded-full animate-spin"></div>
        <p class="text-slate-500 font-bold text-sm">{{ t('arcade.loading') }}</p>
      </div>

      <template v-else>

        <!-- モード選択: 2x2 グリッド。同じモード再クリックで null に戻す -->
        <div>
          <p class="text-[10px] font-black text-slate-400 uppercase tracking-widest mb-3">{{ t('arcade.chooseGoal') }}</p>
          <div class="grid grid-cols-2 gap-3">

            <!-- BEAT-PT ボタン（紫） -->
            <button
              @click="mode = mode === 'beat-pt' ? null : 'beat-pt'"
              class="flex flex-col items-start p-4 rounded-2xl border-2 transition-all text-left active:scale-95"
              :class="mode === 'beat-pt'
                ? 'bg-violet-600 border-violet-600 text-white shadow-lg shadow-violet-200 dark:shadow-violet-900'
                : 'bg-white dark:bg-slate-800 border-slate-200 dark:border-slate-700 text-slate-700 dark:text-slate-200'"
            >
              <span class="text-2xl mb-1">⭐</span>
              <span class="text-sm font-black leading-tight whitespace-pre-line">{{ t('arcade.beatPtBtn') }}</span>
            </button>

            <!-- RATE-PT ボタン（藍） -->
            <button
              @click="mode = mode === 'rate-pt' ? null : 'rate-pt'"
              class="flex flex-col items-start p-4 rounded-2xl border-2 transition-all text-left active:scale-95"
              :class="mode === 'rate-pt'
                ? 'bg-indigo-600 border-indigo-600 text-white shadow-lg shadow-indigo-200 dark:shadow-indigo-900'
                : 'bg-white dark:bg-slate-800 border-slate-200 dark:border-slate-700 text-slate-700 dark:text-slate-200'"
            >
              <span class="text-2xl mb-1">📈</span>
              <span class="text-sm font-black leading-tight whitespace-pre-line">{{ t('arcade.ratePtBtn') }}</span>
            </button>

            <!-- Rival ボタン（赤） -->
            <button
              @click="mode = mode === 'rival' ? null : 'rival'"
              class="flex flex-col items-start p-4 rounded-2xl border-2 transition-all text-left active:scale-95"
              :class="mode === 'rival'
                ? 'bg-rose-600 border-rose-600 text-white shadow-lg shadow-rose-200 dark:shadow-rose-900'
                : 'bg-white dark:bg-slate-800 border-slate-200 dark:border-slate-700 text-slate-700 dark:text-slate-200'"
            >
              <span class="text-2xl mb-1">🔥</span>
              <span class="text-sm font-black leading-tight whitespace-pre-line">{{ t('arcade.rivalBtn') }}</span>
            </button>

            <!-- Border ボタン（琥珀） -->
            <button
              @click="mode = mode === 'border' ? null : 'border'"
              class="flex flex-col items-start p-4 rounded-2xl border-2 transition-all text-left active:scale-95"
              :class="mode === 'border'
                ? 'bg-amber-500 border-amber-500 text-white shadow-lg shadow-amber-200 dark:shadow-amber-900'
                : 'bg-white dark:bg-slate-800 border-slate-200 dark:border-slate-700 text-slate-700 dark:text-slate-200'"
            >
              <span class="text-2xl mb-1">🎵</span>
              <span class="text-sm font-black leading-tight whitespace-pre-line">{{ t('arcade.borderBtn') }}</span>
            </button>

            <!-- 伸びしろ ボタン（青緑、全幅）。新機能アピールのため col-span-2 -->
            <button
              @click="mode = mode === 'potential' ? null : 'potential'"
              class="col-span-2 flex flex-col items-start p-4 rounded-2xl border-2 transition-all text-left active:scale-95"
              :class="mode === 'potential'
                ? 'bg-cyan-600 border-cyan-600 text-white shadow-lg shadow-cyan-200 dark:shadow-cyan-900'
                : 'bg-white dark:bg-slate-800 border-slate-200 dark:border-slate-700 text-slate-700 dark:text-slate-200'"
            >
              <div class="flex items-center gap-2 w-full">
                <span class="text-2xl">🌱</span>
                <div class="flex-1">
                  <div class="text-sm font-black">{{ t('arcade.potentialBtn') }}</div>
                  <div class="text-[10px] font-bold opacity-70 mt-0.5">{{ t('arcade.potentialBtnDesc') }}</div>
                </div>
                <span class="text-[9px] font-black uppercase tracking-wider px-1.5 py-0.5 rounded"
                  :class="mode === 'potential' ? 'bg-white/20' : 'bg-cyan-100 dark:bg-cyan-900/40 text-cyan-700 dark:text-cyan-300'">
                  NEW
                </span>
              </div>
            </button>

          </div>
        </div>

        <!-- 伸びしろモード（管理者限定）: 検証用ユーザー切替ドロップダウン -->
        <div v-if="mode === 'potential' && isAdmin" class="bg-white dark:bg-slate-800 rounded-2xl border border-slate-200 dark:border-slate-700 p-4 space-y-2">
          <div class="flex items-baseline justify-between gap-2">
            <p class="text-xs font-black text-cyan-600 dark:text-cyan-400 uppercase tracking-widest">{{ t('arcade.adminViewLabel') }}</p>
            <span class="text-[10px] font-bold text-slate-400">{{ t('arcade.adminOnly') }}</span>
          </div>
          <select
            v-model="potentialViewUserId"
            class="w-full px-3 py-2.5 bg-slate-50 dark:bg-slate-700 border border-slate-200 dark:border-slate-600 rounded-xl text-sm font-bold text-slate-800 dark:text-white focus:outline-none focus:ring-2 focus:ring-cyan-400"
          >
            <option :value="null">{{ t('arcade.viewSelf') }}</option>
            <option v-for="u in adminUsers" :key="u.id" :value="u.id">
              {{ u.displayName || t('scatter.noName') }} ({{ u.iidxId }})
            </option>
          </select>
          <p v-if="isLoadingViewedScores" class="text-[10px] text-slate-400">{{ t('arcade.loadingViewedScores') }}</p>
          <p v-else-if="potentialViewUserId != null" class="text-[10px] text-slate-500 dark:text-slate-400">
            {{ t('arcade.adminViewHint') }}
          </p>
        </div>

        <!-- Rival モード: フレンド選択＋並び替え切替 -->
        <div v-if="mode === 'rival'" class="bg-white dark:bg-slate-800 rounded-2xl border border-slate-200 dark:border-slate-700 p-4 space-y-3">
          <p class="text-xs font-black text-slate-500 uppercase tracking-widest">{{ t('arcade.selectRival') }}</p>
          <select
            v-model="selectedFriendId"
            class="w-full px-3 py-2.5 bg-slate-50 dark:bg-slate-700 border border-slate-200 dark:border-slate-600 rounded-xl text-sm font-bold text-slate-800 dark:text-white focus:outline-none focus:ring-2 focus:ring-rose-400"
          >
            <option :value="null">{{ t('arcade.selectFriendPlaceholder') }}</option>
            <option v-for="f in publicFriends" :key="f.id" :value="f.id">{{ f.displayName }}</option>
          </select>

          <!-- 並び替えトグル: 接戦順 / 差が大きい順 -->
          <div v-if="selectedFriendId" class="flex items-center justify-between">
            <span class="text-xs font-bold text-slate-500">{{ t('arcade.sort') }}</span>
            <div class="flex rounded-lg overflow-hidden border border-slate-200 dark:border-slate-600 text-xs font-bold">
              <button
                @click="rivalSortClosest = true"
                class="px-3 py-1.5 transition-colors"
                :class="rivalSortClosest ? 'bg-rose-600 text-white' : 'bg-white dark:bg-slate-700 text-slate-600 dark:text-slate-300'"
              >{{ t('arcade.sortClosest') }}</button>
              <button
                @click="rivalSortClosest = false"
                class="px-3 py-1.5 transition-colors"
                :class="!rivalSortClosest ? 'bg-rose-600 text-white' : 'bg-white dark:bg-slate-700 text-slate-600 dark:text-slate-300'"
              >{{ t('arcade.sortWidest') }}</button>
            </div>
          </div>

          <!-- フレンドスコア取得中のインジケータ -->
          <div v-if="isFriendLoading" class="flex items-center gap-2 text-xs text-slate-500 font-bold">
            <div class="w-4 h-4 border-2 border-rose-200 border-t-rose-500 rounded-full animate-spin"></div>
            {{ t('arcade.loadingScores') }}
          </div>
        </div>

        <!-- Border モード: 目標グレード選択（AA/AAA/MAX-） -->
        <div v-if="mode === 'border'" class="bg-white dark:bg-slate-800 rounded-2xl border border-slate-200 dark:border-slate-700 p-4 space-y-3">
          <p class="text-xs font-black text-slate-500 uppercase tracking-widest">{{ t('arcade.borderTarget') }}</p>
          <select
            v-model="borderTarget"
            class="w-full px-3 py-2.5 bg-slate-50 dark:bg-slate-700 border border-slate-200 dark:border-slate-600 rounded-xl text-sm font-bold text-slate-800 dark:text-white focus:outline-none focus:ring-2 focus:ring-amber-400"
          >
            <option value="aa">{{ t('arcade.borderAaOption') }}</option>
            <option value="aaa">{{ t('arcade.borderAaaOption') }}</option>
            <option value="max-minus">{{ t('arcade.borderMaxMinusOption') }}</option>
          </select>
        </div>

        <!-- BEAT-PT モード: 単曲目標 Pt 入力（未入力なら次の閾値狙いに自動切替） -->
        <div v-if="mode === 'beat-pt'" class="bg-white dark:bg-slate-800 rounded-2xl border border-slate-200 dark:border-slate-700 p-4 space-y-3">
          <p class="text-xs font-black text-slate-500 uppercase tracking-widest">{{ t('arcade.beatPtTargetLabel') }}</p>
          <input
            type="number"
            v-model.number="beatPtTarget"
            min="0"
            step="any"
            :placeholder="t('arcade.beatPtTargetPlaceholder')"
            class="w-full px-3 py-2.5 bg-slate-50 dark:bg-slate-700 border border-slate-200 dark:border-slate-600 rounded-xl text-sm font-bold text-slate-800 dark:text-white focus:outline-none focus:ring-2 focus:ring-violet-400"
          />
          <p v-if="beatPtAchievedCount" class="text-xs font-bold text-slate-500">
            {{ t('arcade.achievedCount', { achieved: beatPtAchievedCount.achieved, total: beatPtAchievedCount.total }) }}
          </p>
        </div>

        <!-- RATE-PT モード: 単曲目標 Pt 入力 -->
        <div v-if="mode === 'rate-pt'" class="bg-white dark:bg-slate-800 rounded-2xl border border-slate-200 dark:border-slate-700 p-4 space-y-3">
          <p class="text-xs font-black text-slate-500 uppercase tracking-widest">{{ t('arcade.ratePtTargetLabel') }}</p>
          <input
            type="number"
            v-model.number="ratePtTarget"
            min="0"
            step="1"
            :placeholder="t('arcade.ratePtTargetPlaceholder')"
            class="w-full px-3 py-2.5 bg-slate-50 dark:bg-slate-700 border border-slate-200 dark:border-slate-600 rounded-xl text-sm font-bold text-slate-800 dark:text-white focus:outline-none focus:ring-2 focus:ring-indigo-400"
          />
          <p v-if="ratePtAchievedCount" class="text-xs font-bold text-slate-500">
            {{ t('arcade.achievedCount', { achieved: ratePtAchievedCount.achieved, total: ratePtAchievedCount.total }) }}
          </p>
        </div>

        <!-- 結果表示: ヘッダ＋空状態メッセージ＋カードリスト -->
        <template v-if="mode">
          <!-- 結果ヘッダ: モード名・ソート説明・件数 -->
          <div class="flex items-center justify-between">
            <div>
              <p class="font-black text-slate-800 dark:text-white text-sm">{{ modeLabel }}</p>
              <p class="text-[10px] text-slate-400 font-bold mt-0.5">{{ modeSortNote }}</p>
            </div>
            <span class="text-xs font-black text-slate-400 bg-slate-100 dark:bg-slate-700 px-2 py-1 rounded-full">
              {{ t('arcade.count', { n: suggestions.length }) }}
            </span>
          </div>

          <!-- 空状態メッセージ: モードごとに文言を変える -->
          <div v-if="mode === 'rival' && !selectedFriendId" class="text-center py-12">
            <p class="text-4xl mb-3">👆</p>
            <p class="text-sm font-bold text-slate-500">{{ t('arcade.pickFriendHint') }}</p>
          </div>

          <div v-else-if="mode === 'rival' && selectedFriendId && !isFriendLoading && suggestions.length === 0" class="text-center py-12">
            <p class="text-4xl mb-3">🎉</p>
            <p class="text-sm font-bold text-slate-600 dark:text-slate-300">{{ t('arcade.noRivalLoss', { name: selectedFriend?.displayName ?? '' }) }}</p>
          </div>

          <!-- 伸びしろモード: ローディング / エラー -->
          <div v-else-if="mode === 'potential' && (isPotentialLoading || isLoadingViewedScores)" class="text-center py-12">
            <div class="w-10 h-10 border-4 border-cyan-100 border-t-cyan-600 rounded-full animate-spin mx-auto mb-3"></div>
            <p class="text-sm font-bold text-slate-500">
              {{ potentialViewUserId == null ? t('arcade.buildingModel') : t('arcade.computingForUser') }}
            </p>
          </div>
          <div v-else-if="mode === 'potential' && potentialError" class="text-center py-12">
            <p class="text-4xl mb-3">⚠️</p>
            <p class="text-sm font-bold text-red-600 dark:text-red-400">{{ potentialError }}</p>
          </div>

          <div v-else-if="mode !== 'rival' && suggestions.length === 0" class="text-center py-12">
            <p class="text-4xl mb-3">✨</p>
            <p class="text-sm font-bold text-slate-600 dark:text-slate-300">{{ t('arcade.noSongs') }}</p>
          </div>

          <!-- 推奨曲カードリスト: 上位50件まで -->
          <div v-else-if="suggestions.length > 0" class="space-y-2">
            <div
              v-for="(s, i) in suggestions"
              :key="s.key"
              class="bg-white dark:bg-slate-800 rounded-2xl border border-slate-100 dark:border-slate-700 p-3.5 flex items-start gap-3 shadow-sm"
            >
              <!-- 順位番号: ソート順に 1 から振る -->
              <div class="shrink-0 w-6 text-center text-[11px] font-black text-slate-400 dark:text-slate-500 pt-0.5">
                {{ i + 1 }}
              </div>

              <!-- メインコンテンツ: タイトル・バッジ・スコア情報・サブラベル -->
              <div class="flex-1 min-w-0">
                <!-- 曲タイトル -->
                <p class="font-black text-slate-900 dark:text-white text-sm leading-tight truncate">{{ s.title }}</p>

                <!-- バッジ列: 難易度・非公式ランク・DJ LEVEL -->
                <div class="flex flex-wrap items-center gap-1.5 mt-1.5">
                  <span class="px-1.5 py-0.5 rounded text-[10px] font-black" :class="s.difficultyColor">
                    {{ s.difficultyName.slice(0, 3) }} {{ s.difficultyLevel }}
                  </span>
                  <span v-if="s.informalRank" class="px-1.5 py-0.5 rounded text-[10px] font-black bg-slate-100 dark:bg-slate-700 text-slate-600 dark:text-slate-300">
                    ☆{{ s.informalRank.match(/(\d+\.\d+)/)?.[1] ?? s.informalRank }}
                  </span>
                  <span class="px-1.5 py-0.5 rounded text-[10px] font-bold bg-slate-50 dark:bg-slate-700/50 text-slate-500">
                    {{ s.djLevel }}
                  </span>
                  <!-- 伸びしろモード LOW 精度バッジ: 0.90 フォールバックで算出された予測 -->
                  <span
                    v-if="s.accuracy === 'LOW'"
                    class="px-1.5 py-0.5 rounded text-[10px] font-black bg-amber-100 text-amber-700 dark:bg-amber-900/40 dark:text-amber-300"
                    :title="t('arcade.lowAccuracyTooltip')"
                  >
                    {{ t('arcade.lowAccuracyBadge') }}
                  </span>
                </div>

                <!-- スコア行: 現在スコア／最大スコア／レート。
                     伸びしろモード(predictedScore あり)では「現在 → 予測」を矢印で表示 -->
                <div class="flex items-baseline gap-2 mt-1.5">
                  <span class="text-xs font-bold text-slate-500 dark:text-slate-400">
                    {{ s.score.toLocaleString() }}
                    <template v-if="s.predictedScore != null">
                      <span class="mx-1 text-cyan-600 dark:text-cyan-400">→</span>
                      <span class="text-cyan-700 dark:text-cyan-300">{{ s.predictedScore.toLocaleString() }}</span>
                    </template>
                    <span v-if="s.maxScore > 0" class="text-[10px] font-normal"> / {{ s.maxScore.toLocaleString() }}</span>
                  </span>
                  <span v-if="s.scoreRate > 0" class="text-[10px] font-bold text-slate-400">
                    {{ s.scoreRate.toFixed(2) }}%<template v-if="s.predictedRate != null">
                      <span class="mx-0.5 text-cyan-600 dark:text-cyan-400">→</span>
                      <span class="text-cyan-700 dark:text-cyan-300">{{ s.predictedRate.toFixed(2) }}%</span>
                    </template>
                  </span>
                </div>

                <!-- サブラベル: モードごとに「現在値→目標値」等の補足情報 -->
                <p class="text-[10px] text-slate-400 dark:text-slate-500 font-bold mt-0.5">{{ s.subLabel }}</p>
              </div>

              <!-- ギャップラベル: 目標までの差分を色付きバッジで表示 -->
              <div class="shrink-0 text-right">
                <span
                  class="inline-block px-2.5 py-1 rounded-xl text-xs font-black whitespace-nowrap"
                  :class="{
                    'bg-rose-100 text-rose-700 dark:bg-rose-900/40 dark:text-rose-300': mode === 'rival',
                    'bg-amber-100 text-amber-700 dark:bg-amber-900/40 dark:text-amber-300': mode === 'border',
                    'bg-violet-100 text-violet-700 dark:bg-violet-900/40 dark:text-violet-300': mode === 'beat-pt',
                    'bg-indigo-100 text-indigo-700 dark:bg-indigo-900/40 dark:text-indigo-300': mode === 'rate-pt',
                    'bg-cyan-100 text-cyan-700 dark:bg-cyan-900/40 dark:text-cyan-300': mode === 'potential',
                  }"
                >
                  {{ s.gapLabel }}
                </span>
              </div>
            </div>
          </div>
        </template>

      </template>
    </div>
  </div>
</template>
