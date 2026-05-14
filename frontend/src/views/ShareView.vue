<script setup lang="ts">
/**
 * 【Viewの役割】 共有 URL（`/share/:token`）経由で、未ログインの第三者にデータを見せる公開ページ。
 *
 * 機能:
 *  - `/api/share/{token}/info` から公開対象ユーザーと scope フラグを取得
 *  - scope に応じて以下を切替描画:
 *      - dashboard → ScoreDashboard
 *      - scores    → ScoreSummary
 *      - history   → UploadHistory（shareToken 経由で履歴を取得）
 *      - profile   → ProfileDashboard（shareToken 経由で履歴・スコアを取得し、URL共有/通知設定は非表示）
 *  - 410（失効/期限切れ）・404 を専用メッセージで案内
 *  - `<meta name="robots" content="noindex">` を動的に挿入（検索インデックス除外）
 */
import { computed, onMounted, onBeforeUnmount, ref, watch } from 'vue';
import { useRoute } from 'vue-router';
import ScoreDashboard from '../components/ScoreDashboard.vue';
import ScoreSummary from '../components/ScoreSummary.vue';
import UploadHistory from '../components/UploadHistory.vue';
import ProfileDashboard from '../components/ProfileDashboard.vue';
import type { ScoreData, DifficultyStats } from '../types/ScoreData';
import { API_BASE } from '../composables/constants';
import { flattenScores } from '../utils/scoreData';
import { calculateTotalPoints } from '../utils/beatTier';

const route = useRoute();
/**
 * トークン文字列。
 * 通常は Vue Router の params から取得するが、初期ロード時の競合に備えて
 * window.location.pathname からのフォールバックを併用する。
 */
const token = computed(() => {
    const fromRoute = String(route.params.token || '');
    if (fromRoute) return fromRoute;
    const match = window.location.pathname.match(/^\/share\/([^/]+)/);
    return match ? decodeURIComponent(match[1]) : '';
});

/** 共有ビューの中で表示中のセクション。 */
type ShareSection = 'dashboard' | 'scores' | 'history' | 'profile';
const activeSection = ref<ShareSection>('dashboard');

interface ShareInfo {
    scopeDashboard: boolean;
    scopeScores: boolean;
    scopeHistory: boolean;
    scopeProfile: boolean;
    expiresAt: string | null;
    user: {
        id: number;
        displayName: string;
        iidxId: string;
        danRank: string;
        arenaRank: string;
        playSide: string;
        isSupporter: boolean;
        showSupporterBorder: boolean;
        showRateTier: boolean;
        totalBeatPt: number;
    };
}

const isLoading = ref(true);
const errorState = ref<'none' | 'notfound' | 'expired' | 'revoked' | 'unknown'>('none');
const info = ref<ShareInfo | null>(null);
const scoreData = ref<ScoreData[]>([]);
const totalBeatTierPoints = ref(0);

/** flat な scores レスポンスを ScoreData[] に詰め替える（useScores と同じロジック）。 */
function groupScores(flat: any[]): ScoreData[] {
    const grouped = new Map<string, any>();
    const emptyDiff = (): DifficultyStats => ({
        difficulty: null,
        score: 0,
        pgreat: 0,
        great: 0,
        missCount: null,
        clearType: 'NO PLAY',
        djLevel: '---',
        options: undefined,
        id: undefined,
    });

    flat.forEach((s) => {
        const title = s.title;
        if (!grouped.has(title)) {
            grouped.set(title, {
                version: '0',
                title,
                genre: s.genre || '',
                artist: s.artist || '',
                playCount: s.playCount || 0,
                lastPlayTime: '',
                beginner: emptyDiff(),
                normal: emptyDiff(),
                hyper: emptyDiff(),
                another: emptyDiff(),
                leggendaria: emptyDiff(),
            });
        }
        const entry = grouped.get(title);
        const diffKey = String(s.difficultyName || '').toLowerCase() as keyof ScoreData;
        if (entry[diffKey]) {
            entry[diffKey] = {
                id: s.id,
                difficulty: s.difficultyLevel,
                score: s.score,
                pgreat: s.pgreat || 0,
                great: s.great || 0,
                missCount: s.missCount,
                clearType: s.clearType,
                djLevel: s.djLevel,
                options: Array.isArray(s.options) ? s.options : undefined,
            };
        }
    });

    return Array.from(grouped.values());
}

/** 共有情報＋スコアデータを取得する。 */
async function loadShare() {
    isLoading.value = true;
    errorState.value = 'none';
    info.value = null;
    scoreData.value = [];
    totalBeatTierPoints.value = 0;

    if (!token.value) {
        errorState.value = 'notfound';
        isLoading.value = false;
        return;
    }

    try {
        const infoRes = await fetch(`${API_BASE}/api/share/${encodeURIComponent(token.value)}/info`);
        if (infoRes.status === 404) {
            errorState.value = 'notfound';
            return;
        }
        if (infoRes.status === 410) {
            const body = await infoRes.json().catch(() => ({}));
            errorState.value = body?.error === 'revoked' ? 'revoked' : 'expired';
            return;
        }
        if (!infoRes.ok) {
            errorState.value = 'unknown';
            return;
        }
        info.value = await infoRes.json();

        // スコアデータが必要なのは「ダッシュボード／スコア一覧／プロフィール」のいずれかが ON のとき
        if (info.value && (info.value.scopeDashboard || info.value.scopeScores || info.value.scopeProfile)) {
            const scoresRes = await fetch(`${API_BASE}/api/share/${encodeURIComponent(token.value)}/scores`);
            if (scoresRes.ok) {
                const flat = await scoresRes.json();
                scoreData.value = groupScores(flat);
                if (scoreData.value.length > 0) {
                    totalBeatTierPoints.value = calculateTotalPoints(flattenScores(scoreData.value));
                }
            }
        }
    } catch {
        errorState.value = 'unknown';
    } finally {
        isLoading.value = false;
    }
}

/** 共有ページは検索インデックスから除外する。 */
let robotsMeta: HTMLMetaElement | null = null;
onMounted(() => {
    robotsMeta = document.createElement('meta');
    robotsMeta.name = 'robots';
    robotsMeta.content = 'noindex,nofollow';
    document.head.appendChild(robotsMeta);
    loadShare();
});
onBeforeUnmount(() => {
    if (robotsMeta && robotsMeta.parentNode) robotsMeta.parentNode.removeChild(robotsMeta);
});

const errorTitle = computed(() => {
    switch (errorState.value) {
        case 'notfound': return 'リンクが見つかりません';
        case 'expired':  return 'リンクの有効期限が切れています';
        case 'revoked':  return 'このリンクは失効されました';
        case 'unknown':  return 'リンクの読み込みに失敗しました';
        default: return '';
    }
});

/** scope フラグから利用可能なセクション一覧を導出。 */
const availableSections = computed<Array<{ key: ShareSection; label: string }>>(() => {
    if (!info.value) return [];
    const list: Array<{ key: ShareSection; label: string }> = [];
    if (info.value.scopeDashboard) list.push({ key: 'dashboard', label: 'ダッシュボード' });
    if (info.value.scopeScores) list.push({ key: 'scores', label: 'スコア一覧' });
    if (info.value.scopeHistory) list.push({ key: 'history', label: '成長記録' });
    if (info.value.scopeProfile) list.push({ key: 'profile', label: 'プロフィール' });
    return list;
});

// info 取得後、利用可能な最初のセクションを初期表示にする。
watch(availableSections, (list) => {
    if (list.length === 0) return;
    if (!list.find(s => s.key === activeSection.value)) {
        activeSection.value = list[0].key;
    }
});

const errorBody = computed(() => {
    switch (errorState.value) {
        case 'notfound': return 'URL が間違っているか、すでに削除された可能性があります。';
        case 'expired':  return 'リンクの発行者に新しい URL を発行してもらってください。';
        case 'revoked':  return 'リンクの発行者によって取り消されました。';
        case 'unknown':  return 'しばらく時間をおいて再度お試しください。';
        default: return '';
    }
});
</script>

<template>
  <div class="w-full max-w-6xl mx-auto px-3 py-6 flex flex-col gap-4">

    <!-- 共有元ユーザーのバナー -->
    <div v-if="info" class="w-full bg-white dark:bg-slate-800 border border-slate-200 dark:border-slate-700 rounded-2xl px-5 py-3 flex items-center gap-3 shadow-sm">
      <div class="w-9 h-9 rounded-full bg-blue-100 dark:bg-blue-900/40 flex items-center justify-center flex-shrink-0">
        <svg xmlns="http://www.w3.org/2000/svg" class="h-5 w-5 text-blue-600 dark:text-blue-400" fill="none" viewBox="0 0 24 24" stroke="currentColor" stroke-width="2">
          <path stroke-linecap="round" stroke-linejoin="round" d="M15 8a3 3 0 10-2.977-2.63l-4.94 2.47a3 3 0 100 4.319l4.94 2.47a3 3 0 10.895-1.789l-4.94-2.47a3.027 3.027 0 000-.74l4.94-2.47C13.456 7.68 14.19 8 15 8z" />
        </svg>
      </div>
      <div class="min-w-0 flex-1">
        <div class="text-xs text-slate-500 dark:text-slate-400 font-bold uppercase tracking-widest">共有リンク</div>
        <div class="font-bold text-slate-700 dark:text-slate-200 truncate">
          {{ info.user.displayName || info.user.iidxId }} さんのデータ
        </div>
      </div>
    </div>

    <!-- ローディング -->
    <div v-if="isLoading" class="py-16 flex flex-col items-center justify-center gap-3">
      <div class="w-10 h-10 border-4 border-blue-200 dark:border-blue-900 border-t-blue-600 rounded-full animate-spin"></div>
      <p class="text-slate-500 dark:text-slate-400 font-medium">読み込み中...</p>
    </div>

    <!-- エラー（404 / 410 / その他） -->
    <div v-else-if="errorState !== 'none'" class="py-16 flex flex-col items-center text-center gap-3 max-w-md mx-auto">
      <div class="w-16 h-16 rounded-full bg-amber-100 dark:bg-amber-900/30 flex items-center justify-center">
        <svg xmlns="http://www.w3.org/2000/svg" class="h-8 w-8 text-amber-500" fill="none" viewBox="0 0 24 24" stroke="currentColor" stroke-width="2">
          <path stroke-linecap="round" stroke-linejoin="round" d="M12 9v2m0 4h.01M5.07 19h13.86c1.54 0 2.5-1.67 1.73-3L13.73 4a2 2 0 00-3.46 0L3.34 16c-.77 1.33.19 3 1.73 3z" />
        </svg>
      </div>
      <h2 class="text-lg font-bold text-slate-800 dark:text-slate-100">{{ errorTitle }}</h2>
      <p class="text-sm text-slate-500 dark:text-slate-400">{{ errorBody }}</p>
      <button
        v-if="errorState === 'notfound' || errorState === 'unknown'"
        type="button"
        @click="loadShare"
        class="mt-2 px-4 py-2 rounded-xl bg-blue-600 hover:bg-blue-700 text-white text-sm font-bold transition-colors"
      >再読み込み</button>
    </div>

    <!-- 本体 -->
    <template v-else-if="info">
      <!-- 共有範囲のみのタブナビ -->
      <nav v-if="availableSections.length > 0" class="w-full overflow-x-auto no-scrollbar border-b border-slate-200 dark:border-slate-700 -mx-3 px-3 sm:mx-0 sm:px-0">
        <div class="flex items-center gap-1">
          <button
            v-for="s in availableSections"
            :key="s.key"
            type="button"
            @click="activeSection = s.key"
            class="flex items-center px-4 py-3 border-b-2 transition-all font-bold text-sm tracking-wide whitespace-nowrap"
            :class="activeSection === s.key
              ? 'border-blue-600 text-blue-600 dark:text-blue-400'
              : 'border-transparent text-slate-500 hover:text-slate-800 dark:text-slate-400 dark:hover:text-slate-200'"
          >
            {{ s.label }}
          </button>
        </div>
      </nav>

      <!-- ダッシュボード -->
      <ScoreDashboard
        v-if="info.scopeDashboard && activeSection === 'dashboard'"
        :scores="scoreData"
        :totalPoints="totalBeatTierPoints"
        :viewing-iidx-id="info.user.iidxId"
        :viewing-display-name="info.user.displayName"
        viewing-mode="public"
        class="w-full"
      />

      <!-- スコア一覧 -->
      <ScoreSummary
        v-if="info.scopeScores && activeSection === 'scores'"
        :scores="scoreData"
        viewing-mode="public"
        class="w-full"
      />

      <!-- 成長記録（アップロード履歴） -->
      <UploadHistory
        v-if="info.scopeHistory && activeSection === 'history'"
        :share-token="token"
        class="w-full"
      />

      <!-- プロフィール（成長軌跡＋スコア分析。URL共有・通知設定は非表示） -->
      <ProfileDashboard
        v-if="info.scopeProfile && activeSection === 'profile'"
        :share-token="token"
        class="w-full"
      />

      <!-- 全 OFF（理論上はサーバが弾くが念のため） -->
      <div v-if="availableSections.length === 0" class="py-12 text-center text-sm text-slate-500 dark:text-slate-400">
        このリンクで公開されている画面はありません。
      </div>
    </template>

  </div>
</template>
