<script setup lang="ts">
/**
 * 【コンポーネントの役割】 beat-seeker アプリのルートコンポーネント。
 *
 * 画面全体構造:
 *  - ルーティング: `activeTab` という文字列 ref で SPA 的に画面を切り替える（Vue Router は未使用）。
 *    例: 'dashboard' / 'table' / 'ranking' / 'history' / 'profile' / 'arena' / ...
 *  - サイドバー: `Sidebar` コンポーネントに `isSidebarOpen` と `activeTab` を双方向バインド。
 *  - グローバルモーダル: ログイン / プロフィール編集 / アップロード結果 / 管理ユーザー一覧 /
 *    Ko-fi 確認 / フレンド申請 / 取り込みエリア などを一括で保持する。
 *  - ユーザー視点の切替: 自分のデータ / フレンドデータ / 公開ユーザー / TOPランカー / 管理者閲覧
 *    を `viewingMode` で管理し、`loadSavedScores` が閲覧対象に応じて fetch を切り替える。
 *
 * 依存 Composable:
 *  - `useAuth`: ログイン状態・ユーザー情報
 *  - `useScoreUpload`: CSV アップロード / 履歴ログ保存
 *  - `useScores`: スコアフェッチ（自分 / 他ユーザー / TOPランカー）
 *  - `useDarkMode`: ライト/ダーク切替
 *  - `useFriends`: フレンド申請・通知・バーチャルライバル
 *  - `useGameData`: song_data.json / 難易度表などのマスターデータ
 *  - `useAppUpdate`: Service Worker からの更新通知
 *  - `useI18n`: 多言語化
 *  - `useAprilFools`: エイプリルフール演出フラグ
 */
import { ref, nextTick, provide } from 'vue';
import ResetPasswordView from './views/ResetPasswordView.vue';
import CsvDropzone from './components/CsvDropzone.vue';
import UnifiedImport from './components/UnifiedImport.vue';
import { BOOKMARKLET_CODE } from './utils/bookmarklet';
import ScoreSummary from './components/ScoreSummary.vue';
import ScoreDashboard from './components/ScoreDashboard.vue';
import WrappedBanner from './components/WrappedBanner.vue';
import ProfileDashboard from './components/ProfileDashboard.vue';
import LoginModal from './components/LoginModal.vue';
import ProfileEditModal from './components/ProfileEditModal.vue';
import UploadHistory from './components/UploadHistory.vue';
import Changelog from './components/Changelog.vue';
import UploadResultModal from './components/UploadResultModal.vue';
import RankingList from './components/RankingList.vue';
import AdminUserListModal from './components/AdminUserListModal.vue';
import SongRankingList from './components/SongRankingList.vue';
import Sidebar from './components/Sidebar.vue';
import RankQuizModal from './components/RankQuizModal.vue';
import Terms from './components/Terms.vue';
import About from './components/About.vue';
import Manual from './components/Manual.vue';
import Landing from './components/Landing.vue';
import PrivacyPolicy from './components/PrivacyPolicy.vue';
import Contact from './components/Contact.vue';
import Guide from './components/Guide.vue';
import Friends from './components/Friends.vue';
import FriendTimeline from './components/FriendTimeline.vue';
import NotificationBox from './components/NotificationBox.vue';
import OnboardingModal from './components/OnboardingModal.vue';
import WhatsNewModal from './components/WhatsNewModal.vue';
import ShareImportModal from './components/ShareImportModal.vue';
import { defineAsyncComponent } from 'vue';
// 重いサブビュー / モーダルは遅延ロード。各タブが選択された時に初めて該当チャンクがフェッチされる。
// chart.js / html2canvas / tesseract.js などの大きな依存をユーザーが触るタイミングまで遅らせる効果がある。
const ArenaView = defineAsyncComponent(() => import('./views/ArenaView.vue'));
const TierVotingView = defineAsyncComponent(() => import('./views/TierVotingView.vue'));
const ArcadeAssistView = defineAsyncComponent(() => import('./views/ArcadeView.vue'));
const SongAverageView = defineAsyncComponent(() => import('./views/SongAverageView.vue'));
const DifficultyTableView = defineAsyncComponent(() => import('./views/DifficultyTableView.vue'));
const ScorePredictionView = defineAsyncComponent(() => import('./views/ScorePredictionView.vue'));
const ScoreScatterView = defineAsyncComponent(() => import('./views/ScoreScatterView.vue'));
const SkillTreeView = defineAsyncComponent(() => import('./views/SkillTreeView.vue'));
const ChartListView = defineAsyncComponent(() => import('./views/ChartListView.vue'));
const RankComparisonView = defineAsyncComponent(() => import('./views/RankComparisonView.vue'));
const ShareView = defineAsyncComponent(() => import('./views/ShareView.vue'));
// ストラテジーカード: 大会主催者 + 当該管理者のみ到達可能な隠し機能 (Sidebar.vue 側で出し分け)。
const StrategyCardView = defineAsyncComponent(() => import('./views/StrategyCardView.vue'));
// 選曲発表 (SONG REVEAL): 大会主催 + 運営担当のみ到達可能。`/song-reveal` のスタンドアロン URL で OBS から読み込む。
const SongRevealView = defineAsyncComponent(() => import('./views/SongRevealView.vue'));
// 個人戦順位表 (OBS): `/obs/individual/:token` のスタンドアロン URL。透過背景で OBS のブラウザソースに重ねる。
const ObsIndividualStandingsView = defineAsyncComponent(() => import('./views/ObsIndividualStandingsView.vue'));
// 月末振り返り (Spotify Wrapped 風)。/wrapped/:year/:month 系のパスで全画面オーバーレイ表示する。
const WrappedView = defineAsyncComponent(() => import('./views/WrappedView.vue'));
// 大会管理画面: Competition セクションの 4 ID 限定。サイドバーから activeTab 経由で遷移する通常タブ。
const CompetitionAdminView = defineAsyncComponent(() => import('./views/CompetitionAdminView.vue'));
// 管理者用 2 ユーザー比較画面: URL `/admin/user-comparison` での直接アクセス専用 (サイドバー導線なし)。
const AdminUserComparisonView = defineAsyncComponent(() => import('./views/AdminUserComparisonView.vue'));
// 大会参加者画面 (招待 URL 専用): `/competition/player/{token}` で直接アクセス。
// ログイン不要・サイドバーなしのスタンドアロン描画。
const CompetitionPlayerView = defineAsyncComponent(() => import('./views/CompetitionPlayerView.vue'));
// 大会 TL (チームリーダー) 管理画面: `/competition/tl/{token}` で直接アクセス。
// 自チームのラインアップ (4 matchup × 3 試合 = 12 試合) のアサインを編集する。
const CompetitionTlView = defineAsyncComponent(() => import('./views/CompetitionTlView.vue'));
// 観戦客向け対戦表: `/competition/spectator/{token}` で直接アクセス。
// ログイン不要・読み取り専用。公開済みのラインアップ・指定ジャンル・結果だけを一覧表示する。
const CompetitionSpectatorView = defineAsyncComponent(() => import('./views/CompetitionSpectatorView.vue'));
// きんじょー杯 特設ページ: `/kinjocup` のスタンドアロン URL。参加者一覧を公開閲覧する。
// 追加・削除 UI は View 内で管理者ログイン時のみ表示する。
const KinjoCupView = defineAsyncComponent(() => import('./views/KinjoCupView.vue'));
// OCR モーダルは tesseract.js (大きな wasm) を含むため遅延ロード。
const OcrSearchModal = defineAsyncComponent(() => import('./components/OcrSearchModal.vue'));
import type { SongDataEntry } from './composables/useGameData';
import { parseScoreCsv } from './utils/csvParser';
import type { ScoreData } from './types/ScoreData';
import { flattenScores, getSongMaxScore } from './utils/scoreData';
import type { UploadDiffResult, UpdatedSong, FolderAnnouncement } from './types/UploadDiff';
import { getRankInfo, getRateTierRankInfo, calculateTotalPoints, calculatePoints, calculateScoreRateTierPoints, getFolderRankInfoByRate } from './utils/beatTier';
import { diffTable as diffTableRanksRef } from './composables/useGameData';
import { useAuth } from './composables/useAuth';
import { TOKEN_KEY } from './composables/constants';
import { useScoreUpload } from './composables/useScoreUpload';
import { useAppUpdate } from './composables/useAppUpdate';
import { useScores } from './composables/useScores';
import { useDarkMode } from './composables/useDarkMode';
import { useFriends } from './composables/useFriends';
import { useI18n } from './composables/useI18n';
import { useGameData } from './composables/useGameData';
import { useAprilFools } from './composables/useAprilFools';
import AprilFoolsOverlay from './components/AprilFoolsOverlay.vue';
import ToastContainer from './components/ToastContainer.vue';
import CommandPalette from './components/CommandPalette.vue';
import BackToTop from './components/BackToTop.vue';
import { computed, watch, watchEffect, onMounted, onBeforeUnmount } from 'vue';

const { t } = useI18n();
const { isAprilFools } = useAprilFools();

// エイプリルフール演出: <html> に af-mode class を付け外しし、全体 CSS オーバーライドを有効化する。
watchEffect(() => {
  if (isAprilFools.value) {
    document.documentElement.classList.add('af-mode');
  } else {
    document.documentElement.classList.remove('af-mode');
  }
});

// 起動直後にゲームデータ（曲一覧・難易度表）を API から取得する。
// コンポーネント外から呼んでも問題ないように、composable 側で多重呼び出しはガードされている。
const { fetchGameData } = useGameData();
fetchGameData();

/** 現在 URL が `/reset-password` かどうか。パスワード再設定画面はルートコンポーネントを丸ごと差し替える。 */
const isResetPasswordPage = ref(window.location.pathname === '/reset-password');

/**
 * 現在 URL が `/strategy-card` かどうか。
 *
 * OBS のブラウザソースから直接読み込ませる用途のためのスタンドアロン URL。
 * サイドバー / ヘッダ / モーダル群を一切描画せず、StrategyCardView だけを画面に出す。
 * 認証も不要 (大会本番中に OBS から繋ぐため)。
 */
const isStrategyCardObsPage = ref(window.location.pathname === '/strategy-card');

/**
 * 現在 URL が `/song-reveal` かどうか。
 * OBS ブラウザソース用のスタンドアロン URL。SongRevealView だけを単独描画する。
 * 認証不要。OBS の Interact 機能で曲を選んでから REVEAL する想定。
 */
const isSongRevealPage = ref(window.location.pathname === '/song-reveal');

/**
 * 現在 URL が `/obs/individual/:token` かどうか。
 * OBS ブラウザソース用に個人戦の順位表だけを透過背景で描画する。認証不要。
 * トークンは ObsIndividualStandingsView 側で route から拾うのでここでは何も持たない。
 */
const isObsIndividualStandingsPage = ref(window.location.pathname.startsWith('/obs/individual/'));

/**
 * 現在 URL が `/competition/player/{token}` かどうかと、抽出した招待トークン。
 *
 * このページは大会参加者が招待 URL からアクセスする「自選曲提出 + StrategyCard 決定」用の
 * スタンドアロン画面。サイドバー・グローバルヘッダなしで CompetitionPlayerView だけ描画する。
 * トークン文字列自体が認証材料 (beat-seeker アカウント不要)。
 */
const isCompetitionPlayerPage = ref(window.location.pathname.startsWith('/competition/player/'));
const competitionPlayerToken = ref<string>(
  isCompetitionPlayerPage.value
    ? (window.location.pathname.replace(/^\/competition\/player\//, '').replace(/\/.*$/, '') || '')
    : ''
);

/**
 * 現在 URL が `/competition/tl/{token}` かどうかと、抽出した TL トークン。
 * TL (チームリーダー) 専用 URL で、自チームの試合へのアサインを管理する。
 * 同じくログイン不要のスタンドアロン画面。
 */
const isCompetitionTlPage = ref(window.location.pathname.startsWith('/competition/tl/'));
const competitionTlToken = ref<string>(
  isCompetitionTlPage.value
    ? (window.location.pathname.replace(/^\/competition\/tl\//, '').replace(/\/.*$/, '') || '')
    : ''
);

/**
 * 現在 URL が `/competition/spectator/{token}` かどうかと、抽出した観戦トークン。
 * 一般観戦客がログイン不要で対戦表 (読み取り専用) を閲覧するスタンドアロン画面。
 */
const isCompetitionSpectatorPage = ref(window.location.pathname.startsWith('/competition/spectator/'));
const competitionSpectatorToken = ref<string>(
  isCompetitionSpectatorPage.value
    ? (window.location.pathname.replace(/^\/competition\/spectator\//, '').replace(/\/.*$/, '') || '')
    : ''
);

/**
 * 現在 URL が `/kinjocup` かどうか。
 * きんじょー杯の特設ページ。参加者一覧を公開閲覧でき、管理者ログイン時のみ追加/削除 UI が出る。
 * サイドバー等を描画しないスタンドアロン。トークンは不要（参加者は DB 名簿から取得）。
 */
const isKinjoCupPage = ref(window.location.pathname.replace(/\/$/, '') === '/kinjocup');

const { hasUpdate } = useAppUpdate();
/** Service Worker による新バージョン通知を受けた際の更新ボタン。単純にページ再読込を行う。 */
const reloadPage = () => window.location.reload();

/**
 * 【関数の役割】 OCR で曲がマッチしたときの処理。
 *  - スコア一覧タブに切替し、ScoreSummary の詳細モーダルを曲名で開く。
 *  - ScoreSummary は ☆11/☆12 ANOTHER/LEGGENDARIA しか扱わないため、範囲外の曲は
 *    `openSongByTitle` が false を返す。その場合は何も開かない（仕様）。
 */
const scoreSummaryRef = ref<InstanceType<typeof ScoreSummary> | null>(null);
const handleOcrMatched = async (song: SongDataEntry) => {
  isOcrSearchModalOpen.value = false;
  activeTab.value = 'table';
  // v-show で常にマウントされているはずだが、初回描画タイミングを保険で待つ
  await nextTick();
  scoreSummaryRef.value?.openSongByTitle(song.title);
};

// ---------- Command Palette (Cmd/Ctrl + K) ----------

/** コマンドパレットの開閉。Cmd/Ctrl + K でトグル。 */
const isCmdkOpen = ref(false);

/**
 * 【computed の役割】 コマンドパレットに渡す利用可能タブ ID 一覧。
 *
 * Sidebar.vue の filteredNavItems と同じ条件（requiresAuth / hideOnViewing / score-prediction の admin 例外）
 * を再現する。サイドバーで隠れているタブはコマンドパレットからも開けないようにして UI の一貫性を保つ。
 */
const availableCmdkTabIds = computed<string[]>(() => {
  const ALL = [
    'dashboard', 'table', 'profile', 'ranking', 'friends', 'history', 'arena',
    'arcade-assist', 'tier-voting', 'song-avg', 'diff-table', 'rank-comparison',
    'score-prediction', 'score-scatter', 'changelog', 'about',
  ];
  // Sidebar.vue navigationItems と同じ判定
  const REQUIRES_AUTH = new Set(['profile', 'friends', 'history', 'arena', 'arcade-assist', 'score-prediction', 'score-scatter']);
  const HIDE_ON_VIEWING = new Set(['friends', 'history', 'arena', 'arcade-assist', 'score-prediction', 'score-scatter']);
  const RANK_COMPARISON_ALLOWED_IDS = [18, 23, 24];

  return ALL.filter((id) => {
    if (REQUIRES_AUTH.has(id) && !isLoggedIn.value) return false;
    if (HIDE_ON_VIEWING.has(id) && viewingUserId.value) {
      // admin モード閲覧中は score-prediction / history を例外的に許可（Sidebar.vue と同じ判定）
      if ((id === 'score-prediction' || id === 'history') && viewingMode.value === 'admin') return true;
      return false;
    }
    if (id === 'rank-comparison' && (!user.value || !RANK_COMPARISON_ALLOWED_IDS.includes(user.value.id))) return false;
    return true;
  });
});

/** 【関数の役割】 パレットからタブ選択時のハンドラ。activeTab を更新して閉じる。 */
const handleCmdkSelectTab = (tabId: string) => {
  activeTab.value = tabId as any;
};

/** 【関数の役割】 パレットから曲選択時のハンドラ。スコア一覧タブへ移動して詳細モーダルを開く。 */
const handleCmdkSelectSong = async (title: string) => {
  activeTab.value = 'table';
  await nextTick();
  scoreSummaryRef.value?.openSongByTitle(title);
};

/** 【関数の役割】 パレットからクイックアクション選択時のハンドラ。 */
const handleCmdkAction = (name: 'upload' | 'profile-edit' | 'toggle-dark' | 'logout') => {
  if (name === 'upload') showUploadArea.value = true;
  else if (name === 'profile-edit') isProfileModalOpen.value = true;
  else if (name === 'toggle-dark') toggleDarkMode();
  else if (name === 'logout') logout();
};

/**
 * 【関数の役割】 グローバル keydown ハンドラ。Cmd/Ctrl + K でコマンドパレットをトグル。
 *
 * input/textarea/contentEditable にフォーカス中でも反応させる（コマンドパレットの開閉は IME 入力中でなければ常に有効）。
 * IME 確定中は e.isComposing で除外する。
 */
const onGlobalKeydown = (e: KeyboardEvent) => {
  if (e.isComposing) return;
  const isCmdK = (e.metaKey || e.ctrlKey) && e.key.toLowerCase() === 'k';
  if (isCmdK) {
    e.preventDefault();
    isCmdkOpen.value = !isCmdkOpen.value;
  }
};

onMounted(() => {
  document.addEventListener('keydown', onGlobalKeydown);
});
onBeforeUnmount(() => {
  document.removeEventListener('keydown', onGlobalKeydown);
});

/** ログイン中ユーザーまたは閲覧対象ユーザーのスコアデータ（曲単位）。 */
const scoreData = ref<ScoreData[]>([]);
// 子孫コンポーネント（クイズモーダルなど）から inject で参照できるよう公開する。
provide('scoreData', scoreData);
/** CSV 解析中フラグ。ローディング表示用。 */
const isParsing = ref(false);
/** エラーメッセージ表示用。空文字で非表示。 */
const errorMsg = ref('');
/**
 * 現在アクティブなタブ（= SPA 的な現在ルート）。
 * 文字列リテラルユニオンで厳密にタイピングし、どこか一箇所からでもタブ切替できるようにしている。
 */
const activeTab = ref<'dashboard' | 'table' | 'profile' | 'history' | 'ranking' | 'changelog' | 'terms' | 'about' | 'manual' | 'friends' | 'timeline' | 'popular-songs' | 'arena' | 'tier-voting' | 'arcade-assist' | 'song-avg' | 'diff-table' | 'score-prediction' | 'skill-tree' | 'chart-list' | 'rank-comparison' | 'score-scatter' | 'landing' | 'privacy-policy' | 'contact' | 'guide' | 'share' | 'competition-admin' | 'admin-user-comparison'>('dashboard')

/**
 * 現在のタブ ID から表示用ラベル（ヘッダーのパンくず・タイトルで使う）への解決を行う computed。
 *
 * - サイドバーと完全に整合する nav.* キーを使うので翻訳ファイルを増やさずに済む。
 * - マップに無い ID（dashboard など）は空文字を返す → パンくずを 1 階層に省略する用途。
 */
const activeTabLabel = computed<string>(() => {
  const labels: Record<string, string> = {
    table: t('nav.scoreList'),
    timeline: t('nav.timeline'),
    ranking: t('nav.ranking'),
    profile: t('nav.profile'),
    friends: t('nav.friends'),
    history: t('nav.history'),
    arena: t('nav.arena'),
    'arcade-assist': t('nav.arcadeAssist'),
    'tier-voting': t('nav.tierVoting'),
    'song-avg': t('nav.songAvg'),
    'diff-table': t('nav.diffTable'),
    'rank-comparison': t('nav.rankComparison'),
    'score-prediction': t('nav.scorePrediction'),
    'score-scatter': t('nav.scoreScatter'),
    'popular-songs': t('nav.popularSongs'),
    'skill-tree': t('nav.skillTree'),
    'chart-list': t('nav.chartList'),
    changelog: t('nav.changelog'),
    terms: t('nav.terms'),
    about: t('nav.about'),
    manual: t('nav.manual'),
    'admin-user-comparison': 'ユーザー間スコア比較',
  };
  return labels[activeTab.value] ?? '';
});
/** /guide/:slug アクセス時のスラッグ。Guide コンポーネントが記事を絞り込む。 */
const currentGuideSlug = ref<string | null>(null);
/**
 * 閲覧モード。自分のデータを見る場合は null。
 *  - 'admin': 管理者が他ユーザーのデータを閲覧中
 *  - 'friend': フレンドのデータを閲覧中
 *  - 'public': スコア公開ユーザーを閲覧中（自分が非フレンドでも閲覧可）
 *  - 'topRanker': 県別・バージョン別の TOP ランカー（仮想ユーザー）を閲覧中
 *  - 'private': 非公開ユーザーの BEAT-PT/RATE-PT 総合値のみ閲覧（曲別データは持たない）
 */
const viewingMode = ref<'admin' | 'friend' | 'public' | 'topRanker' | 'private' | null>(null);
/** TOP ランカー閲覧時のエリア情報（バージョン+都道府県）。それ以外は null。 */
const viewingTopRanker = ref<{ versionNum: number; versionName: string; prefectureFileNum: number; prefectureName: string } | null>(null);
/** 現在表示中のユーザーの総 BEAT-PT（= TOP100 合計）。 */
const totalBeatTierPoints = ref(0);
/** 非公開ユーザー閲覧時に外部から渡される RATE-PT。通常閲覧時は null。 */
const privateRateTierPoints = ref<number | null>(null);

/** アップロード差分結果。モーダル内で新旧スコア比較を表示するために保持する。 */
const diffResult = ref<UploadDiffResult | null>(null);
/** アップロード結果モーダル（スコア差分）の開閉。 */
const isDiffModalOpen = ref(false);
/** ログイン/登録モーダルの開閉。 */
const isLoginModalOpen = ref(false);
/** プロフィール編集モーダルの開閉。 */
const isProfileModalOpen = ref(false);
/** 管理者用ユーザー一覧モーダルの開閉。 */
const isAdminModalOpen = ref(false);
/** 新規登録直後に出すオンボーディングモーダルの開閉。 */
const isOnboardingOpen = ref(false);
/** カメラ OCR 曲検索モーダルの開閉。サイドバーの「カメラで曲検索」ボタンから起動。 */
const isOcrSearchModalOpen = ref(false);
/** 非公式難易度クイズモーダルの開閉。サイドバーの Lv ウィジェットから起動。 */
const isRankQuizOpen = ref(false);

/** 現在閲覧中のユーザー ID（自分閲覧時は null）。 */
const viewingUserId = ref<number | null>(null);
/** 現在閲覧中のユーザー表示名。バナー等に表示。 */
const viewingUserName = ref<string>('');
/** 現在閲覧中のユーザーの IIDX ID。 */
const viewingUserIidxId = ref<string>('');
/** モバイル用サイドバーの開閉状態。 */
const isSidebarOpen = ref(false);

const { user, isLoggedIn, logout, isLoading: authLoading, authHeaders } = useAuth();
const { upload, saveHistoryLog } = useScoreUpload();
const { fetchMyScores, fetchUserScores, fetchTopRankerProfile, isFetching } = useScores();
const { isDarkMode, toggleDarkMode } = useDarkMode();

/**
 * 【computed の役割】 Competition セクション (大会管理 / Strategy Card / Song Reveal) を
 * ヘッダーに表示してよいかの判定。サイドバー側の canAccessCompetition と同じ 4 ID。
 * 他人ダッシュボード閲覧中 (viewingUserId) は隠す。
 */
const canAccessCompetition = computed(() => {
  const id = user.value?.id;
  return (id === 18 || id === 19 || id === 23 || id === 210) && !viewingUserId.value;
});

/** ヘッダー「beat-seeker for competition」ドロップダウンの開閉。 */
const isCompetitionMenuOpen = ref(false);

/** ドロップダウンから大会管理タブへ遷移。 */
const goCompetitionAdmin = () => {
  activeTab.value = 'competition-admin';
  window.history.replaceState({}, '', '/competition-admin');
  isCompetitionMenuOpen.value = false;
};
const { pendingRequests, appUnreadCount, fetchPendingRequests, fetchAppNotifications, requestNotificationPermission, sendFriendRequest, fetchVirtualRivalStatus, addVirtualRival, removeVirtualRival } = useFriends();

/** 閲覧中ユーザーとのフレンド関係。null はログイン前 or 取得前。 */
const friendStatus = ref<'none' | 'friend' | 'requested' | 'incoming' | 'self' | null>(null);
/** フレンド申請モーダルの開閉。 */
const isFriendRequestModalOpen = ref(false);
/** フレンド申請時に添えるメッセージ（任意、100 文字以内）。 */
const friendRequestMessage = ref('');
/** フレンド申請送信中フラグ。ボタン二重押下防止。 */
const friendRequestSending = ref(false);
/** フレンド申請エラーメッセージ。 */
const friendRequestError = ref('');

/**
 * 【関数の役割】 対象ユーザーと自分のフレンド状態を API から取得する。
 *  - 未ログインなら即 null のままで何もしない。
 *  - ネットワークエラーは握り潰す（フレンド状態は補助情報なので UI を壊さない方針）。
 * @param userId 対象ユーザーの ID
 */
const fetchFriendStatus = async (userId: number) => {
  friendStatus.value = null;
  if (!isLoggedIn.value) return;
  try {
    const res = await fetch(`${API_BASE}/api/users/${userId}/friend-status`, { headers: authHeaders() });
    if (res.ok) {
      const data = await res.json();
      friendStatus.value = data.status;
    }
  } catch {}
};

/**
 * 【関数の役割】 フレンド申請モーダルを開く前に、入力欄とエラーをリセットする。
 */
const openFriendRequestModal = () => {
  friendRequestMessage.value = '';
  friendRequestError.value = '';
  isFriendRequestModalOpen.value = true;
};

/** 仮想ライバル（TOPランカー）が既に登録済みかどうか。null は取得前または未ログイン。 */
const virtualRivalRegistered = ref<boolean | null>(null);
/** 仮想ライバル追加/削除 API 実行中フラグ。ボタン連打防止。 */
const virtualRivalBusy = ref(false);

/**
 * 【関数の役割】 現在閲覧中の TOP ランカーが自分の仮想ライバル登録済みか再取得する。
 * TOP ランカー閲覧画面を開いた直後に呼び出される。
 */
const refreshVirtualRivalStatus = async () => {
  virtualRivalRegistered.value = null;
  if (!isLoggedIn.value) return;
  const area = viewingTopRanker.value;
  if (!area) return;
  virtualRivalRegistered.value = await fetchVirtualRivalStatus(area.versionNum, area.prefectureFileNum);
};

/**
 * 【関数の役割】 仮想ライバル（TOPランカー）の登録/解除をトグルする。
 * 処理の流れ:
 *  手順1: 閲覧中エリアがなければ何もしない。
 *  手順2: 登録済みなら API で削除、未登録なら API で追加。
 *  手順3: ローカルフラグも即時更新して UI に反映する。
 */
const toggleVirtualRival = async () => {
  const area = viewingTopRanker.value;
  if (!area || !isLoggedIn.value) return;
  virtualRivalBusy.value = true;
  try {
    if (virtualRivalRegistered.value) {
      await removeVirtualRival(area.versionNum, area.prefectureFileNum);
      virtualRivalRegistered.value = false;
    } else {
      await addVirtualRival({
        versionNum: area.versionNum,
        prefectureFileNum: area.prefectureFileNum,
        versionName: area.versionName,
        prefectureName: area.prefectureName,
      });
      virtualRivalRegistered.value = true;
    }
  } catch (e) {
    console.error(e);
  } finally {
    virtualRivalBusy.value = false;
  }
};

/**
 * 【関数の役割】 フレンド申請を送信する。
 * 処理の流れ:
 *  手順1: 閲覧ユーザー ID が無ければ早期 return。
 *  手順2: `sendFriendRequest` で POST。メッセージが空なら undefined を渡す。
 *  手順3: 成功したら `friendStatus` を 'requested' に切り替え、モーダルを閉じる。
 *  手順4: 失敗したらエラーメッセージを表示しモーダルは開いたまま維持する。
 */
const submitFriendRequest = async () => {
  if (viewingUserId.value == null) return;
  friendRequestSending.value = true;
  friendRequestError.value = '';
  try {
    await sendFriendRequest(viewingUserId.value, friendRequestMessage.value.trim() || undefined);
    friendStatus.value = 'requested';
    isFriendRequestModalOpen.value = false;
  } catch (e: any) {
    friendRequestError.value = e.message || '申請に失敗しました';
  } finally {
    friendRequestSending.value = false;
  }
};

/** 通知ベルのドロップダウン表示状態。 */
const isNotificationOpen = ref(false);
/** PWA インストール用のプロンプトイベント（`beforeinstallprompt` で受け取る）。 */
const deferredPrompt = ref<any>(null);

// Ko-fi（サポーター機能）用のモーダル状態
/** Ko-fi 確認モーダルの開閉。サポータートークンを案内する。 */
const showKofiModal = ref(false);
/** Ko-fi トークンのコピー完了トースト用フラグ。 */
const kofiCopied = ref(false);

/**
 * 【関数の役割】 Ko-fi ボタン押下時の挙動を分岐する。
 *  - すでにサポータートークンを持つユーザー → 確認モーダルを表示
 *  - 未サポーター → 直接 Ko-fi ページを新タブで開く
 */
const handleKofiClick = () => {
  if (user.value?.supporterToken) {
    showKofiModal.value = true;
  } else {
    window.open('https://ko-fi.com/beat_seeker', '_blank');
  }
};

/**
 * 【関数の役割】 Ko-fi 確認モーダルで「確認して開く」を押した際の処理。
 *  - サポータートークンをクリップボードにコピー（5 秒後にトースト消滅）。
 *  - モーダルを閉じて Ko-fi ページを新タブで開く。
 */
const confirmKofiOpen = () => {
  const token = user.value?.supporterToken;
  if (token) {
    navigator.clipboard.writeText(token).then(() => {
      kofiCopied.value = true;
      setTimeout(() => { kofiCopied.value = false; }, 5000);
    }).catch(() => {});
  }
  showKofiModal.value = false;
  window.open('https://ko-fi.com/beat_seeker', '_blank');
};
/** PWA インストールバナーの表示フラグ。`beforeinstallprompt` 受領時に true。 */
const showInstallBanner = ref(false);
/** 未ログイン状態で取り込みが要求された場合に「ログイン後に開く」ための保留フラグ。 */
const pendingImportOpen = ref(false);
/** 未ログイン状態で URL フラグメント経由のデータ取り込みが要求された場合の保留データ。 */
const pendingFragmentData = ref<string | null>(null);

/**
 * 【関数の役割】 ブックマークレット経由の URL フラグメント (#data=...) を base64 デコードする。
 * 戻り値: デコード済み JSON テキスト。フォーマット不正時は null。
 */
const decodeFragmentData = (): string | null => {
  const hash = window.location.hash;
  if (!hash.startsWith('#data=')) return null;
  try {
    const b64 = hash.slice(6); // '#data=' の 6 文字を取り除く
    // escape + decodeURIComponent の順で日本語含む UTF-8 を安全に復元する
    return decodeURIComponent(escape(atob(b64)));
  } catch (e) {
    console.warn('Failed to decode fragment data:', e);
    return null;
  }
};

/**
 * 【関数の役割】 ブックマークレットから送り込まれた JSON を解釈し、
 *   ARENA 対戦ログの取り込み＋スコア CSV の取り込みをまとめて処理する。
 * 処理の流れ:
 *  手順1: type が 'beat-seeker-combined' でなければ無視。
 *  手順2: battles 配列があれば /api/arena/import へ POST（失敗は握り潰し）。
 *  手順3: scoresCsv があれば Blob→File 化して通常の取り込みフローへ流す。
 * @param jsonText ブックマークレットから受け渡された JSON 文字列
 */
const processBookmarkletData = async (jsonText: string) => {
  try {
    const parsed = JSON.parse(jsonText);
    if (!parsed || parsed.type !== 'beat-seeker-combined') return;

    // Handle ARENA battles
    // ARENA 対戦データ: あれば専用エンドポイントへ POST。成否に関わらず後続のスコア取り込みは続行。
    if (parsed.battles && Array.isArray(parsed.battles) && parsed.battles.length > 0) {
      const token = localStorage.getItem('beat-seeker-token');
      try {
        const res = await fetch(`${API_BASE}/api/arena/import`, {
          method: 'POST',
          headers: { 'Content-Type': 'application/json', Authorization: `Bearer ${token}` },
          body: JSON.stringify(parsed),
        });
        if (res.ok) {
          const data = await res.json();
          console.log('ARENA import:', data.message);
        }
      } catch (e) {
        console.warn('ARENA import failed:', e);
      }
    }

    // スコア CSV: 先頭に BOM を付けて Excel 等で正しく UTF-8 と認識される File に変換し、通常ドロップと同じ処理に流す。
    if (parsed.scoresCsv) {
      const bom = new Uint8Array([0xEF, 0xBB, 0xBF]);
      const blob = new Blob([bom, parsed.scoresCsv], { type: 'text/csv;charset=utf-8;' });
      const file = new File([blob], 'scores.csv', { type: 'text/csv' });
      await handleFileDropped(file);
    }
  } catch (e) {
    console.warn('Failed to process bookmarklet data:', e);
  }
};

/** API ベース URL。Vite の環境変数から取得し、未設定時はローカル開発用のデフォルト。 */
const API_BASE = import.meta.env.VITE_API_BASE ?? 'http://localhost:8080';

// 【Share Target】 OS の「共有」から送られた画像を受け取り、リザルト登録モーダルを開く。
// Service Worker が POST /share-target を横取りして画像を Cache に保存し /?sharetarget=1 へ遷移。
// ここではその画像を取り出して ShareImportModal を開く。
const isShareImportOpen = ref(false);
const shareImageBlob = ref<Blob | null>(null);

async function initShareTarget() {
  try {
    const params = new URLSearchParams(window.location.search);
    if (params.get('sharetarget') !== '1') return;
    // クエリを除去（リロード/戻るで再発火しないように）。
    history.replaceState({}, '', window.location.pathname);
    if (!('caches' in window)) return;
    const cache = await caches.open('shared-images');
    const resp = await cache.match('/__shared-image');
    if (resp) {
      shareImageBlob.value = await resp.blob();
      await cache.delete('/__shared-image');
      isShareImportOpen.value = true;
    }
  } catch {
    /* 失敗時は何もしない */
  }
}
onMounted(initShareTarget);

function closeShareImport() {
  isShareImportOpen.value = false;
  shareImageBlob.value = null;
}

// 【onMounted】 ルートコンポーネント初期化時の一括セットアップ。
// タイミング: アプリが DOM に載った直後に 1 回だけ実行。
// 目的:
//  1) PWA のインストールプロンプト捕捉
//  2) ログイン済みなら通知権限を要求
//  3) URL パスから初期タブを決定（クローラー向けの静的 URL 対応）
//  4) ブックマークレット経由のデータ取り込み
onMounted(() => {
  // PWA インストールプロンプト: ブラウザが表示できるタイミングで発火。
  // 既定の挙動を preventDefault して、アプリ側の独自 UI で後から発火させるために保存しておく。
  window.addEventListener('beforeinstallprompt', (e) => {
    e.preventDefault();
    deferredPrompt.value = e;
    showInstallBanner.value = true;
  });

  // ログイン済みのセッション復元時は通知権限をリクエストする。
  if (isLoggedIn.value) {
    requestNotificationPermission();
  }

  // URLパスに応じてタブを設定（直接アクセス・クローラー対応）。
  // Vue Router ではなく純粋なパス判定で初期タブを決める。
  const pathToTab: Record<string, typeof activeTab.value> = {
    '/about': 'about',
    '/manual': 'manual',
    '/terms': 'terms',
    '/privacy-policy': 'privacy-policy',
    '/contact': 'contact',
    '/ranking': 'ranking',
    '/changelog': 'changelog',
    '/difficulty-table': 'diff-table',
    '/guide': 'guide',
    '/competition-admin': 'competition-admin',
    '/admin/user-comparison': 'admin-user-comparison',
  };
  const currentPath = window.location.pathname;
  if (pathToTab[currentPath]) {
    activeTab.value = pathToTab[currentPath];
  } else if (currentPath.startsWith('/guide/')) {
    // /guide/:slug 形式のパスを Guide ビューに振り分け、スラッグを保持する。
    const slug = currentPath.slice('/guide/'.length).replace(/\/$/, '');
    if (slug) {
      activeTab.value = 'guide';
      currentGuideSlug.value = slug;
    }
  } else if (currentPath.startsWith('/chart/')) {
    // /chart/:version/:slug/:diff 形式の譜面分析ディープリンク。
    // ScorePredictionView 自身が useRoute() でパラメータを読み取り曲を自動選択する。
    activeTab.value = 'score-prediction';
  } else if (currentPath.startsWith('/share/')) {
    // /share/:token 形式の URL 共有ページ。ShareView がトークンを読み取って描画する。
    activeTab.value = 'share';
  } else if (currentPath === '/') {
    // ルートに来たログイン前ユーザーには公開ランディングを見せる。
    // ログイン済みユーザーは /dashboard に遷移させる（URL も書き換え、再アクセス時もダッシュボードに直接戻れるようにする）。
    // 認証復元 (/api/auth/me) は非同期なので、JWT の有無を同期的に確認して仮判定する。
    // 復元後にトークンが無効と判明した場合は下の watch 側で landing に戻す。
    const hasToken = !!localStorage.getItem(TOKEN_KEY);
    if (hasToken) {
      activeTab.value = 'dashboard';
      window.history.replaceState({}, document.title, '/dashboard');
    } else {
      activeTab.value = 'landing';
    }
  }

  // ブックマークレットからのリダイレクト時: URL フラグメントからデータを自動取り込み。
  // ?import=open が付いていたらフラグメント確認 → 未ログインなら保留 → ログイン後に実行。
  const urlParams = new URLSearchParams(window.location.search);
  if (urlParams.get('import') === 'open') {
    const fragmentData = decodeFragmentData();
    // URL に個人データを残さないよう、クエリもフラグメントもこの時点で除去する。
    window.history.replaceState({}, document.title, window.location.pathname);

    if (fragmentData) {
      // URL フラグメントにデータあり → 自動取り込み
      if (isLoggedIn.value) {
        processBookmarkletData(fragmentData);
      } else {
        // 未ログインなら保留し、ログイン完了の watch で実行する。
        pendingFragmentData.value = fragmentData;
        pendingImportOpen.value = true;
      }
    } else {
      // フラグメントなし（従来のクリップボード方式フォールバック）。アップロードエリアを開くだけ。
      if (isLoggedIn.value) {
        showUploadArea.value = true;
      } else {
        pendingImportOpen.value = true;
      }
    }
  }
});


// 通知権限の要求自体は useFriends で扱う。

/**
 * 【関数の役割】 `beforeinstallprompt` で取得しておいたプロンプトを発火し、PWA としてインストールさせる。
 * ユーザーが accept したらバナーを閉じ、deferredPrompt は使い捨てなので破棄する。
 */
const installApp = async () => {
  if (!deferredPrompt.value) return;
  deferredPrompt.value.prompt();
  const { outcome } = await deferredPrompt.value.userChoice;
  if (outcome === 'accepted') {
    deferredPrompt.value = null;
    showInstallBanner.value = false;
  }
};

/**
 * 【関数の役割】 閲覧モードに応じたスコアデータを取得し、ローカル状態に反映する。
 *
 * 処理の流れ:
 *  手順1: フェッチ失敗時に古いデータが残ってフレンド画面に見えてしまう問題を防ぐため、先にクリア。
 *  手順2: `viewingMode` に応じて API を切替:
 *         - topRanker: 仮想ユーザーのプロファイル＋スコア
 *         - admin/friend/public: 特定ユーザーのスコア
 *         - 自分: ログインユーザーのスコア
 *  手順3: 取得した曲データから BEAT-PT 合計を計算し、`totalBeatTierPoints` を更新する。
 */
const loadSavedScores = async () => {
  // フェッチ失敗時に古い（自分の）データが残ってフレンド画面に表示されてしまう問題を防ぐため
  // フェッチ前に必ずクリアする
  scoreData.value = [];
  totalBeatTierPoints.value = 0;

  try {
    let data;
    if (viewingMode.value === 'topRanker' && viewingTopRanker.value) {
      const { profile, scores } = await fetchTopRankerProfile(
        viewingTopRanker.value.versionNum,
        viewingTopRanker.value.prefectureFileNum
      );
      if (profile) {
        viewingTopRanker.value = profile;
      }
      data = scores;
    } else if (viewingUserId.value !== null) {
      const fetchMode =
        viewingMode.value === 'friend' ? 'friend'
        : viewingMode.value === 'public' ? 'public'
        : 'admin';
      data = await fetchUserScores(viewingUserId.value, fetchMode);
    } else {
      data = await fetchMyScores();
    }

    if (data && data.length > 0) {
      scoreData.value = data;
      const flat = flattenScores(data);
      totalBeatTierPoints.value = calculateTotalPoints(flat);
    }
  } catch (e) {
    console.error("Failed to load saved scores", e);
  }
};

/**
 * 【関数の役割】 管理者用ユーザー一覧モーダルから特定ユーザーを選択したときのハンドラ。
 * 閲覧モードを 'admin' に切り替え、ユーザー情報をセットしてスコアを再フェッチする。
 */
const handleSelectUser = async (selectedUser: any) => {
  isAdminModalOpen.value = false;
  viewingUserId.value = selectedUser.id;
  viewingUserName.value = selectedUser.displayName || selectedUser.iidxId;
  viewingUserIidxId.value = selectedUser.iidxId || '';
  viewingMode.value = 'admin';
  await loadSavedScores();
};

/**
 * 【関数の役割】 フレンド一覧画面からフレンドのデータを閲覧開始する。
 * ダッシュボードタブに移動し、TOP ランカー情報はクリアする。
 */
const handleViewFriend = async (friend: { id: number; displayName: string; iidxId?: string }) => {
  viewingUserId.value = friend.id;
  viewingUserName.value = friend.displayName;
  viewingUserIidxId.value = friend.iidxId || '';
  viewingMode.value = 'friend';
  viewingTopRanker.value = null;
  activeTab.value = 'dashboard';
  await loadSavedScores();
};

/**
 * 【関数の役割】 ランキング一覧から「スコア公開ユーザー」を閲覧開始する。
 * フレンド申請ボタンを出すために `fetchFriendStatus` も並行実行する。
 */
const handleViewPublicUser = async (u: { id: number; displayName: string; iidxId?: string }) => {
  viewingUserId.value = u.id;
  viewingUserName.value = u.displayName;
  viewingUserIidxId.value = u.iidxId || '';
  viewingMode.value = 'public';
  viewingTopRanker.value = null;
  activeTab.value = 'dashboard';
  fetchFriendStatus(u.id);
  await loadSavedScores();
};

/**
 * 【関数の役割】 TOP ランカー（県別/バージョン別の仮想ユーザー）を閲覧開始する。
 * バーチャルライバル登録状況を同時取得し、バナーのボタン表示を即時適切にする。
 */
const handleViewTopRanker = async (area: {
  versionNum: number;
  versionName: string;
  prefectureFileNum: number;
  prefectureName: string;
}) => {
  viewingUserId.value = null;
  viewingUserName.value = `${area.versionName} ${area.prefectureName} TOP`;
  viewingUserIidxId.value = '';
  viewingMode.value = 'topRanker';
  viewingTopRanker.value = { ...area };
  activeTab.value = 'dashboard';
  refreshVirtualRivalStatus();
  await loadSavedScores();
};

/**
 * 【関数の役割】 非公開ユーザーのダッシュボードを閲覧開始する。
 * 非公開ユーザーは曲別データを持たないため `scoreData` は空にし、
 * ランキング側から渡された BEAT-PT / RATE-PT 総合値のみを表示する。
 */
const handleViewPrivateUser = (u: { id: number; displayName: string; iidxId: string; totalBeatPt: number; totalRatePt: number }) => {
  viewingUserId.value = u.id;
  viewingUserName.value = u.displayName;
  viewingUserIidxId.value = u.iidxId || '';
  viewingMode.value = 'private';
  viewingTopRanker.value = null;
  activeTab.value = 'dashboard';
  scoreData.value = [];
  totalBeatTierPoints.value = u.totalBeatPt;
  privateRateTierPoints.value = u.totalRatePt;
  fetchFriendStatus(u.id);
};

/**
 * 【関数の役割】 閲覧モード（他人データ閲覧）を解除し、自分のデータに戻る。
 * 全閲覧系 ref を初期化してから、自分のスコアを再取得する。
 */
const returnToMyData = async () => {
  viewingUserId.value = null;
  viewingUserName.value = '';
  viewingUserIidxId.value = '';
  viewingMode.value = null;
  viewingTopRanker.value = null;
  privateRateTierPoints.value = null;
  friendStatus.value = null;
  isFriendRequestModalOpen.value = false;
  virtualRivalRegistered.value = null;
  await loadSavedScores();
};

// 【watch】 ログイン状態の変化を監視し、状態遷移に応じた画面リセットとフェッチを行う。
// タイミング: ログイン成功時 / ログアウト時 / セッション復元直後。
// 目的:
//  - ログイン成功時: 閲覧対象を自分に戻し、自分のスコア・申請・通知を一括取得。
//    保留していたブックマークレットデータがあればこのタイミングで取り込み。
//    Google OAuth リダイレクト直後は URL をクリーンにしてダッシュボードに飛ばす。
//  - ログアウト時: すべてのユーザー情報をクリアし、グラフやテーブルをゲスト状態に戻す。
watch(isLoggedIn, (newVal) => {
  if (newVal) {
    viewingUserId.value = null;
    viewingUserName.value = '';
    viewingUserIidxId.value = '';
    viewingTopRanker.value = null;
    loadSavedScores();
    fetchPendingRequests();
    fetchAppNotifications();
    requestNotificationPermission();

    if (pendingImportOpen.value) {
      pendingImportOpen.value = false;
      if (pendingFragmentData.value) {
        // URLフラグメントデータがある場合は自動取り込み（未ログインで保留していた分）
        const data = pendingFragmentData.value;
        pendingFragmentData.value = null;
        processBookmarkletData(data);
      } else {
        showUploadArea.value = true;
      }
    }

    // Google OAuth リダイレクト経由のログインをクエリで判定し、URL を綺麗にする。
    const urlParams = new URLSearchParams(window.location.search);
    if (urlParams.get('login') === 'success') {
      activeTab.value = 'dashboard';

      // ページをリロードせず URL だけ差し替える（履歴は残さない）
      window.history.replaceState({}, document.title, window.location.pathname);
    }
  } else {
    viewingUserId.value = null;
    viewingUserName.value = '';
    viewingUserIidxId.value = '';
    viewingTopRanker.value = null;
    scoreData.value = [];
    totalBeatTierPoints.value = 0;
  }
});

/**
 * クリアタイプの強弱を数値化したテーブル。
 * スコア差分算出時に「ランプが上がったか」判定するための順序比較に使う。
 */
const clearTypeRankings: Record<string, number> = {
  'FULLCOMBO CLEAR': 7,
  'EX HARD CLEAR': 6,
  'HARD CLEAR': 5,
  'CLEAR': 4,
  'EASY CLEAR': 3,
  'ASSIST CLEAR': 2,
  'FAILED': 1,
  'NO PLAY': 0,
  '---': 0
};

/**
 * 【関数の役割】 旧スコアと新スコアを比較し、Folder-Rank レベルの発表イベントを生成する。
 *
 * 発表タイプ:
 *  - 'rank_assigned': フォルダ内の全曲を今回初めて埋めきり、Folder-Rank が初認定された
 *  - 'rank_up': フォルダ内全曲埋め済みの状態で、平均スコアレートが上がりランクアップした
 *  - 'remaining': 残り曲数が 5 曲単位の閾値を跨いだ場合の進捗告知
 *
 * 処理の流れ:
 *  手順1: 難易度表マスターが無い場合は空配列で早期 return。
 *  手順2: 旧/新それぞれについてフォルダ別のプレイ曲数＆平均スコアレートを集計。
 *  手順3: フォルダ毎に比較して、rank_assigned > rank_up > remaining の優先順で発表を積む。
 *  手順4: フォルダ番号（☆12.0 など）降順に並べ替えて返す。
 *
 * @param oldFlat アップロード前の全譜面フラット配列
 * @param newFlat アップロード後の全譜面フラット配列
 * @returns フォルダ単位の発表イベント配列
 */
const computeFolderAnnouncements = (
  oldFlat: ReturnType<typeof flattenScores>,
  newFlat: ReturnType<typeof flattenScores>
): FolderAnnouncement[] => {
  const announcements: FolderAnnouncement[] = [];
  const ranks = diffTableRanksRef.value;
  if (!ranks || !Array.isArray(ranks)) return announcements;

  // フォルダ毎の「プレイ済み曲数」を集計するヘルパー。score > 0 を「プレイ済み」と定義。
  const buildPlayCounts = (flat: ReturnType<typeof flattenScores>) => {
    const counts = new Map<string, number>();
    flat.forEach(s => {
      if (s.informalRank && s.score > 0) {
        counts.set(s.informalRank, (counts.get(s.informalRank) || 0) + 1);
      }
    });
    return counts;
  };

  // フォルダ毎の「平均スコアレート（%）」を集計するヘルパー。プレイ済みの曲のみを分母に含める。
  const buildAvgRates = (flat: ReturnType<typeof flattenScores>) => {
    const sums = new Map<string, { totalScore: number; totalMax: number }>();
    flat.forEach(s => {
      if (s.informalRank && s.score > 0 && s.maxScore > 0) {
        const entry = sums.get(s.informalRank) || { totalScore: 0, totalMax: 0 };
        entry.totalScore += s.score;
        entry.totalMax += s.maxScore;
        sums.set(s.informalRank, entry);
      }
    });
    const rates = new Map<string, number>();
    sums.forEach((v, k) => {
      if (v.totalMax > 0) rates.set(k, (v.totalScore / v.totalMax) * 100);
    });
    return rates;
  };

  const oldPlayCounts = buildPlayCounts(oldFlat);
  const newPlayCounts = buildPlayCounts(newFlat);
  const oldAvgRates = buildAvgRates(oldFlat);
  const newAvgRates = buildAvgRates(newFlat);

  // フォルダ毎の全体曲数を難易度表から取得。'Uncategorized' フォルダは除外する。
  const folderTotals = new Map<string, number>();
  ranks.forEach((r: any) => {
    if (!r.rank.includes('Uncategorized')) {
      folderTotals.set(r.rank, r.songs.length);
    }
  });

  folderTotals.forEach((totalCount, folder) => {
    const oldPlayed = oldPlayCounts.get(folder) || 0;
    const newPlayed = newPlayCounts.get(folder) || 0;
    if (newPlayed <= oldPlayed) return; // このフォルダに新たなプレイは無い

    const remaining = totalCount - newPlayed;
    const oldRemaining = totalCount - oldPlayed;

    // 判定1: 全曲埋まって初めてランクが認定されたケース
    if (remaining === 0 && oldRemaining > 0) {
      const newRate = newAvgRates.get(folder) || 0;
      const newRank = getFolderRankInfoByRate(newRate, folder);
      announcements.push({
        folder,
        type: 'rank_assigned',
        newRankName: newRank.name + (newRank.tier ? ' ' + newRank.tier : ''),
      });
      return; // rank_assigned が最優先。他の判定は行わない。
    }

    // 判定2: 既に全曲埋まっていた状態で、平均スコアレートが上がってランクアップしたケース
    if (remaining === 0 && oldRemaining === 0) {
      const oldRate = oldAvgRates.get(folder) || 0;
      const newRate = newAvgRates.get(folder) || 0;
      const oldRank = getFolderRankInfoByRate(oldRate, folder);
      const newRank = getFolderRankInfoByRate(newRate, folder);
      if (newRank.minPoints > oldRank.minPoints) {
        announcements.push({
          folder,
          type: 'rank_up',
          oldRankName: oldRank.name + (oldRank.tier ? ' ' + oldRank.tier : ''),
          newRankName: newRank.name + (newRank.tier ? ' ' + newRank.tier : ''),
        });
      }
      return;
    }

    // 判定3: 残曲数が 5 の倍数境界を跨いだ（例: 17 → 14 なら「15」を跨いだ）。
    if (remaining > 0) {
      const oldBucket = Math.floor(oldRemaining / 5);
      const newBucket = Math.floor(remaining / 5);
      if (newBucket < oldBucket || remaining <= 5) {
        // 残曲が 5 の倍数、または残 5 曲以下のときに発表する（終盤は細かく通知）。
        if (remaining % 5 === 0 || remaining <= 5) {
          announcements.push({
            folder,
            type: 'remaining',
            remaining,
          });
        }
      }
    }
  });

  // フォルダ番号（☆12.0 等）を数値として降順に並べる（難しい順に先に表示）。
  announcements.sort((a, b) => parseFloat(b.folder) - parseFloat(a.folder));
  return announcements;
};

/**
 * 【関数の役割】 ドロップされた CSV ファイルを読み取り、解析→差分計算→サーバー保存→差分モーダル表示までを一括で行う。
 *
 * 処理の流れ（大ブロック）:
 *  手順1: CSV をパースし曲単位の配列に変換。
 *  手順2: 現在のスコア（旧）と新スコアを flatten し、タイトル+難易度で突き合わせ。
 *  手順3: TOP100 判定（BEAT-PT 上位 / RATE-PT 上位）用の Set を構築。
 *  手順4: 譜面ごとにスコア・ランプが向上したら updatedSongs に積む。
 *  手順5: ログイン済みならサーバーにアップロードし、バックエンドの差分で上書き。
 *         アップロード直後に全スコア再取得 → 正確な合計 BEAT-PT / RATE-PT を再計算。
 *         成功時はアップロード履歴にログを保存。
 *  手順6: 未ログイン（ゲストモード）ならクライアント計算のみで差分モーダルを表示。
 *  手順7: 完了後はアップロードエリアを閉じ、ダッシュボードに遷移する。
 */
const handleFileDropped = async (file: File) => {
  errorMsg.value = '';
  isParsing.value = true;
  
  try {
    const newData = await parseScoreCsv(file);
    console.log(`Successfully parsed ${newData.length} songs.`);

    // 差分計算: 現在表示中の scoreData（旧）と今回アップロードした newData（新）を突き合わせる。
    const oldFlat = flattenScores(scoreData.value);
    const newFlat = flattenScores(newData);

    // title_difficultyName をキーに旧スコアを O(1) 検索できる Map を構築。
    const oldScoreMap = new Map();
    oldFlat.forEach(r => oldScoreMap.set(`${r.title}_${r.difficultyName}`, r));

    const updatedSongs: UpdatedSong[] = [];

    // ゲスト向け BEAT-PT TOP100 判定セット。アップロード済みデータの上位 100 譜面をキー化。
    const sortedNewFlatDesc = newFlat.filter(s => s.beatTierPoints > 0).sort((a, b) => b.beatTierPoints - a.beatTierPoints);
    const top100SetGuest = new Set(sortedNewFlatDesc.slice(0, 100).map(s => `${s.title}_${s.difficultyName}`));

    // ゲスト向け RATE-PT TOP100 判定セット。ANOTHER/LEGGENDARIA のみを対象にレート由来 PT で上位 100 抽出。
    const sortedByRatePtDescGuest = newFlat
      .filter(s => ['ANOTHER', 'LEGGENDARIA'].includes(s.difficultyName) && s.scoreRate > 0)
      .map(s => ({ key: `${s.title}_${s.difficultyName}`, pt: calculateScoreRateTierPoints(s.scoreRate) }))
      .filter(s => s.pt > 0)
      .sort((a, b) => b.pt - a.pt);
    const rateTop100SetGuest = new Set(sortedByRatePtDescGuest.slice(0, 100).map(s => s.key));

    // 全譜面について旧→新の変化を突き合わせ、スコアアップ/ランプアップがあれば報告対象に積む。
    newFlat.forEach(newR => {
        // レベル 11 / 12 の向上を中心にレポートする（それ以外のレベルはスコア一覧で確認可能）。
        const oldR = oldScoreMap.get(`${newR.title}_${newR.difficultyName}`);

        const oldScore = oldR ? oldR.score : 0;
        const oldClearType = oldR ? oldR.clearType : 'NO PLAY';
        const oldBeatPt = oldR ? oldR.beatTierPoints : 0;

        const newScore = newR.score;
        const newClearType = newR.clearType;
        const newBeatPt = newR.beatTierPoints;

        const oldClearRank = clearTypeRankings[oldClearType] || 0;
        const newClearRank = clearTypeRankings[newClearType] || 0;
        const clearTypeImproved = newClearRank > oldClearRank;
        const scoreImproved = newScore > oldScore;

        const scoreIncrease = scoreImproved ? newScore - oldScore : 0;
        const beatPtIncrease = newBeatPt > oldBeatPt ? newBeatPt - oldBeatPt : 0;

        // RATE-PT 対象は ANOTHER / LEGGENDARIA のみ。旧新両方を計算して差分を取る。
        const isRateEligible = ['ANOTHER', 'LEGGENDARIA'].includes(newR.difficultyName);
        const newRatePt = (isRateEligible && newR.scoreRate > 0) ? calculateScoreRateTierPoints(newR.scoreRate) : 0;
        const oldRateScore = oldR ? oldR.scoreRate : 0;
        const oldRatePt = (isRateEligible && oldRateScore > 0) ? calculateScoreRateTierPoints(oldRateScore) : 0;
        const ratePtIncrease = Math.max(0, newRatePt - oldRatePt);

        // スコアかランプの少なくとも一方が向上していれば報告対象。
        if (scoreImproved || clearTypeImproved) {
            updatedSongs.push({
                title: newR.title,
                difficulty: newR.difficultyName,
                oldScore,
                newScore,
                scoreIncrease,
                oldClearType,
                newClearType,
                clearTypeImproved,
                oldBeatPt,
                newBeatPt,
                beatPtIncrease,
                isInTop100: top100SetGuest.has(`${newR.title}_${newR.difficultyName}`),
                scoreRate: newR.scoreRate,
                maxScore: newR.maxScore,
                newRatePt,
                ratePtIncrease,
                isInRateTop100: rateTop100SetGuest.has(`${newR.title}_${newR.difficultyName}`),
                informalRank: newR.informalRank,
            });
        }
    });

    // 更新曲を BEAT-PT 増加量降順、次点で素スコア増加量降順で並べ替える。
    updatedSongs.sort((a, b) => {
        if (b.beatPtIncrease !== a.beatPtIncrease) return b.beatPtIncrease - a.beatPtIncrease;
        return b.scoreIncrease - a.scoreIncrease;
    });

    const oldTotalBeatPt = calculateTotalPoints(oldFlat);
    const newTotalBeatPt = calculateTotalPoints(newFlat);
    const oldTier = getRankInfo(oldTotalBeatPt);
    const newTier = getRankInfo(newTotalBeatPt);

    /**
     * RATE-Tier 合計 PT を算出するローカル関数。
     * 仕様: ANOTHER/LEGGENDARIA のうち RATE-PT > 0 の譜面を PT 降順で並べ、上位 100 譜面を加算。
     *       小数第 1 位に丸める（`* 10` して `Math.round` して `/ 10`）。
     */
    const calcFlatRatePt = (flat: ReturnType<typeof flattenScores>) => {
      const top100 = flat
        .filter(s => ['ANOTHER', 'LEGGENDARIA'].includes(s.difficultyName) && s.scoreRate > 0)
        .map(s => calculateScoreRateTierPoints(s.scoreRate))
        .filter(pt => pt > 0)
        .sort((a, b) => b - a)
        .slice(0, 100);
      return Math.round(top100.reduce((acc, pt) => acc + pt, 0) * 10) / 10;
    };
    const oldTotalRatePt = calcFlatRatePt(oldFlat);

    if (isLoggedIn.value && newData.length > 0) {
      // ログイン時の正規ルート: DB との差分をバックエンドから受け取り、これを正解として採用する。
      isParsing.value = true; // ローディング表示を維持したまま次の処理へ
      try {
        const result = await upload(newData);
        console.log("Scores persisted to database.");
        
        // バックエンド差分を UploadDiffResult 形式に変換し、BEAT-PT を補って返す。
        // （バックエンドは score だけ返すので、クライアント側で PT を再算出する必要がある）
        const backendUpdates = result.updatedSongs.map(s => {
          // PT 算出には maxScore と informalRank が要るので、新フラットから該当譜面を引く。
          const chartData = newFlat.find(nf => nf.title === s.title && nf.difficultyName === s.difficulty);
          const informalRank = chartData?.informalRank || (chartData?.difficultyLevel ? chartData.difficultyLevel.toFixed(1) : '12.0');
          const maxScore = chartData?.maxScore || (s.newScore > 0 ? s.newScore : 3000); // 最低限のフォールバック

          // ローカル関数: 指定スコア → BEAT-PT
          const getPoints = (score: number) => {
             const scoreRate = (score / maxScore) * 100;
             return calculatePoints(scoreRate, informalRank);
          };

          const oldBeatPt = getPoints(s.oldScore);
          const newBeatPt = getPoints(s.newScore);

          return {
            ...s,
            oldBeatPt,
            newBeatPt,
            beatPtIncrease: Math.max(0, newBeatPt - oldBeatPt),
            informalRank,
          };
        });

        // アップロード成功後、全スコアをサーバーから再取得して正確な合計値を得る。
        // （1 ファイルだけでは見えない、過去に登録済みのスコアも合算するため）
        await loadSavedScores();
        const accurateTotalBeatPt = totalBeatTierPoints.value;

        // アップロード後の全譜面データから isInTop100 / isInRateTop100 を判定するセットを構築。
        const allFlatAfterUpload = flattenScores(scoreData.value);
        const sortedByPtDesc = allFlatAfterUpload
          .filter(s => s.beatTierPoints > 0)
          .sort((a, b) => b.beatTierPoints - a.beatTierPoints);
        const top100Set = new Set(sortedByPtDesc.slice(0, 100).map(s => `${s.title}_${s.difficultyName}`));

        const sortedByRatePtDesc = allFlatAfterUpload
          .filter(s => ['ANOTHER', 'LEGGENDARIA'].includes(s.difficultyName) && s.scoreRate > 0)
          .map(s => ({ key: `${s.title}_${s.difficultyName}`, pt: calculateScoreRateTierPoints(s.scoreRate) }))
          .filter(s => s.pt > 0)
          .sort((a, b) => b.pt - a.pt);
        const rateTop100Set = new Set(sortedByRatePtDesc.slice(0, 100).map(s => s.key));
        const accurateTotalRatePt = calcFlatRatePt(allFlatAfterUpload);

        // ランキング行の INF バッジ用: 集計対象（BEAT/RATE の上位100曲）に INFINITAS 由来ベストが
        // 含まれるか。flattenScores の record.source は arcade/infinitas のうち「採用された高い方」を指す。
        const beatTop100HasInf = sortedByPtDesc.slice(0, 100).some(s => s.source === 'infinitas');
        const rateTop100HasInf = allFlatAfterUpload
          .filter(s => ['ANOTHER', 'LEGGENDARIA'].includes(s.difficultyName) && s.scoreRate > 0)
          .map(s => ({ rec: s, pt: calculateScoreRateTierPoints(s.scoreRate) }))
          .filter(x => x.pt > 0)
          .sort((a, b) => b.pt - a.pt)
          .slice(0, 100)
          .some(x => x.rec.source === 'infinitas');
        const includesInfinitas = beatTop100HasInf || rateTop100HasInf;

        // backendUpdates に scoreRate / maxScore / RATE-PT 関連フィールドを追加補完する。
        const enrichedUpdates = backendUpdates.map(s => {
          const maxScore = getSongMaxScore(s.title, s.difficulty);
          const scoreRate = maxScore > 0 ? (s.newScore / maxScore) * 100 : 0;
          const isRateEligible = ['ANOTHER', 'LEGGENDARIA'].includes(s.difficulty);
          const newRatePt = (isRateEligible && scoreRate > 0) ? calculateScoreRateTierPoints(scoreRate) : 0;
          const oldRateScore = maxScore > 0 ? (s.oldScore / maxScore) * 100 : 0;
          const oldRatePt = (isRateEligible && oldRateScore > 0) ? calculateScoreRateTierPoints(oldRateScore) : 0;
          return {
            ...s,
            scoreRate,
            maxScore,
            newRatePt,
            ratePtIncrease: Math.max(0, newRatePt - oldRatePt),
          };
        });

        // 譜面ランキング（自分がその曲で何位か）を取得。15 秒タイムアウトでアップロード画面を長時間止めない。
        const songRankMap = new Map<string, { rank: number; total: number }>();
        try {
          const token = localStorage.getItem('beat-seeker-token');
          const rankController = new AbortController();
          const rankTimeout = setTimeout(() => rankController.abort(), 15000);
          try {
            const rankRes = await fetch(`${API_BASE}/api/scores/my-song-ranks`, {
              headers: { Authorization: `Bearer ${token}` },
              signal: rankController.signal
            });
            clearTimeout(rankTimeout);
            if (rankRes.ok) {
              const rankData: Array<{ title: string; difficultyName: string; rank: number; total: number }> = await rankRes.json();
              rankData.forEach(r => songRankMap.set(`${r.title}_${r.difficultyName}`, { rank: r.rank, total: r.total }));
            }
          } finally {
            clearTimeout(rankTimeout);
          }
        } catch { /* 握り潰し: タイムアウトやネットワーク断でも順位情報なしでレポートを続行 */ }

        // レポート対象をフィルタ（スコア or ランプが上がっただけ）、並べ替え、順位等を合成。
        const reportSongs = enrichedUpdates
          .filter(s => s.scoreIncrease > 0 || s.clearTypeImproved)
          .map(s => {
            const rankEntry = songRankMap.get(`${s.title}_${s.difficulty}`);
            return {
              ...s,
              isInTop100: top100Set.has(`${s.title}_${s.difficulty}`),
              isInRateTop100: rateTop100Set.has(`${s.title}_${s.difficulty}`),
              songRank: rankEntry?.rank,
              songRankTotal: rankEntry?.total,
            };
          })
          .sort((a, b) => b.beatPtIncrease - a.beatPtIncrease || b.scoreIncrease - a.scoreIncrease);

        diffResult.value = {
            oldTotalBeatPt,
            newTotalBeatPt: accurateTotalBeatPt,
            totalBeatPtIncrease: Math.max(0, accurateTotalBeatPt - oldTotalBeatPt),
            oldTier,
            newTier: getRankInfo(accurateTotalBeatPt),
            updatedSongs: reportSongs,
            oldTotalRatePt,
            newTotalRatePt: accurateTotalRatePt,
            oldRateTier: getRateTierRankInfo(oldTotalRatePt),
            newRateTier: getRateTierRankInfo(accurateTotalRatePt),
            folderAnnouncements: computeFolderAnnouncements(oldFlat, allFlatAfterUpload),
        };

        // 実際にスコアが上がった譜面がある、または初回アップロード（旧データ空 → 新データあり）なら
        // 差分モーダルを開き、履歴ログも保存する。
        if (reportSongs.length > 0 || (oldFlat.length === 0 && newFlat.length > 0)) {
            isDiffModalOpen.value = true;
            // 実際に更新があったときだけ履歴ログを残す（NO-OP アップロードでは履歴を増やさない）。
            try {
                const newTierInfo = getRankInfo(accurateTotalBeatPt);
                const newTierLabel = newTierInfo.name + (newTierInfo.tier ? ' ' + newTierInfo.tier : '');
                const oldTierLabel = oldTier.name + (oldTier.tier ? ' ' + oldTier.tier : '');
                await saveHistoryLog(
                    accurateTotalBeatPt,
                    Math.max(0, accurateTotalBeatPt - oldTotalBeatPt),
                    reportSongs.length,
                    JSON.stringify(reportSongs),
                    newTierLabel,
                    oldTierLabel,
                    accurateTotalRatePt,
                    includesInfinitas
                );
                console.log("History log saved successfully.");
            } catch (err) {
                console.error("Failed to save history log", err);
                errorMsg.value = t('app.error.historySaveFailed');
            }
        } else {
            errorMsg.value = t('app.error.noUpdate');
        }
      } catch (err) {
        console.error("Auto upload failed", err);
        errorMsg.value = t('app.error.uploadFailed');
        // フォールバック: サーバー通信に失敗してもクライアント側差分でモーダル表示を諦めない。
        // 稀に「サーバーには保存されたがレスポンス取得だけ失敗」という状況があり得るため、
        // そのケースでユーザー体験を損なわないよう history log 保存も念のため試行する。
        const guestNewTotalRatePt = calcFlatRatePt(newFlat);
        diffResult.value = {
            oldTotalBeatPt,
            newTotalBeatPt,
            totalBeatPtIncrease: Math.max(0, newTotalBeatPt - oldTotalBeatPt),
            oldTier,
            newTier,
            updatedSongs,
            oldTotalRatePt,
            newTotalRatePt: guestNewTotalRatePt,
            oldRateTier: getRateTierRankInfo(oldTotalRatePt),
            newRateTier: getRateTierRankInfo(guestNewTotalRatePt),
            folderAnnouncements: computeFolderAnnouncements(oldFlat, newFlat),
        };
        if (updatedSongs.length > 0 || (oldFlat.length === 0 && newFlat.length > 0)) {
            isDiffModalOpen.value = true;
            if (updatedSongs.length > 0 || newTotalBeatPt > 0) {
                try {
                    const newTierLabel = newTier.name + (newTier.tier ? ' ' + newTier.tier : '');
                    const oldTierLabel = oldTier.name + (oldTier.tier ? ' ' + oldTier.tier : '');
                    await saveHistoryLog(
                        newTotalBeatPt,
                        Math.max(0, newTotalBeatPt - oldTotalBeatPt),
                        updatedSongs.length,
                        JSON.stringify(updatedSongs),
                        newTierLabel,
                        oldTierLabel,
                        guestNewTotalRatePt
                    );
                    console.log("History log saved (fallback).");
                } catch (histErr) {
                    console.error("History log fallback save failed:", histErr);
                }
            }
        }
      }
    } else {
        // ゲストモード（未ログイン or 空データ）: クライアント計算の差分だけでモーダルを出す。
        // スコアはサーバーに保存されないので履歴ログも作らない。
        const guestNewTotalRatePt = calcFlatRatePt(newFlat);
        diffResult.value = {
            oldTotalBeatPt,
            newTotalBeatPt,
            totalBeatPtIncrease: Math.max(0, newTotalBeatPt - oldTotalBeatPt),
            oldTier,
            newTier,
            updatedSongs,
            oldTotalRatePt,
            newTotalRatePt: guestNewTotalRatePt,
            oldRateTier: getRateTierRankInfo(oldTotalRatePt),
            newRateTier: getRateTierRankInfo(guestNewTotalRatePt),
            folderAnnouncements: computeFolderAnnouncements(oldFlat, newFlat),
        };

        if (updatedSongs.length > 0 || (oldFlat.length === 0 && newFlat.length > 0)) {
            isDiffModalOpen.value = true;
        }
        
        // ゲストは DB 非永続化のため、クライアント上の scoreData を直接差し替える。
        scoreData.value = newData;
        totalBeatTierPoints.value = newTotalBeatPt;
    }

    // パース成功後は常にアップロードエリアを閉じ、ダッシュボードタブに戻す。
    showUploadArea.value = false;
    activeTab.value = 'dashboard';

  } catch (err: any) {
    console.error('Failed to parse or save CSV:', err);
    errorMsg.value = err.message || t('app.error.parseFailed');
  } finally {
    isParsing.value = false;
  }
};

/** アップロードモーダル（UnifiedImport）の表示フラグ。 */
const showUploadArea = ref(false);

/** UnifiedImport で選ばれたがまだ送信していないファイル。モーダルを閉じる瞬間にアップロードする用。 */
const pendingScoreFile = ref<File | null>(null);

/**
 * 【関数の役割】 サイドバーの「アップロード/リセット」ボタン押下時のハンドラ。
 *  - ログイン中: スコアは消さず、取り込みモーダルだけ開く
 *  - ゲスト: スコア表示を丸ごとクリアしてやり直す
 */
const resetData = () => {
  if (isLoggedIn.value) {
    // ログイン中はサーバーにデータがあるのでクリアせず、取り込みモーダルだけ出す。
    showUploadArea.value = true;
  } else {
    // ゲストはローカルのみなのでクリアしてゼロからやり直せるようにする。
    scoreData.value = [];
    totalBeatTierPoints.value = 0;
  }
  errorMsg.value = '';
};

/**
 * 【関数の役割】 UnifiedImport モーダルを閉じるときのハンドラ。
 *  - pendingScoreFile があれば閉じると同時にそのファイルを取り込みに回す。
 *  - なければ保存済みスコアを再取得して画面を最新化する。
 */
const handleUnifiedClose = async () => {
  showUploadArea.value = false;
  errorMsg.value = '';
  const fileToProcess = pendingScoreFile.value;
  pendingScoreFile.value = null;
  if (fileToProcess) {
    await handleFileDropped(fileToProcess);
  } else {
    await loadSavedScores();
  }
};
</script>

<template>
  <!-- パスワード再設定画面は独立ビューとして丸ごと差し替え、通常 UI は描画しない -->
  <ResetPasswordView v-if="isResetPasswordPage" />
  <!-- OBS ブラウザソース用: ストラテジーカードだけを単独描画。サイドバー等は全部省略。 -->
  <StrategyCardView v-else-if="isStrategyCardObsPage" class="w-full min-h-screen" />
  <!-- OBS ブラウザソース用: 選曲発表 (SONG REVEAL) も同様に単独描画。 -->
  <SongRevealView v-else-if="isSongRevealPage" />
  <!-- OBS ブラウザソース用: 個人戦順位表。透過背景で重ねる前提のためサイドバー等は一切描画しない。 -->
  <ObsIndividualStandingsView v-else-if="isObsIndividualStandingsPage" />
  <!-- 大会参加者用招待ページ: token を抽出して View に渡す。ログイン不要のスタンドアロン。 -->
  <CompetitionPlayerView v-else-if="isCompetitionPlayerPage" :token="competitionPlayerToken" />
  <!-- 大会 TL 管理ページ: token を抽出してラインアップ管理 View を表示。ログイン不要のスタンドアロン。 -->
  <CompetitionTlView v-else-if="isCompetitionTlPage" :token="competitionTlToken" />
  <!-- 観戦客向け対戦表ページ: token を抽出して読み取り専用 View を表示。ログイン不要のスタンドアロン。 -->
  <CompetitionSpectatorView v-else-if="isCompetitionSpectatorPage" :token="competitionSpectatorToken" />
  <!-- きんじょー杯 特設ページ: 参加者一覧を公開閲覧。追加/削除 UI は View 内で管理者ログイン時のみ表示。 -->
  <KinjoCupView v-else-if="isKinjoCupPage" />
  <div v-else class="min-h-screen bg-slate-50 dark:bg-slate-900 transition-colors duration-200 flex flex-row overflow-hidden" :class="{ 'af-mode': isAprilFools }">
    <!-- ============================================================ -->
    <!-- エイプリルフール限定オーバーレイ（常時マウントだが中身は日付判定） -->
    <!-- ============================================================ -->
    <AprilFoolsOverlay />
    <!-- ============================================================ -->
    <!-- サイドバー（モバイル時はオフキャンバス、PC 時は常時 lg:ml-72） -->
    <!-- ============================================================ -->
    <Sidebar 
      v-model:is-open="isSidebarOpen"
      v-model:active-tab="activeTab"
      :is-logged-in="isLoggedIn"
      :user="user"
      :viewing-user-id="viewingUserId"
      :viewing-mode="viewingMode"
      :auth-loading="authLoading"
      @login="isLoginModalOpen = true"
      @logout="logout"
      @edit-profile="isProfileModalOpen = true"
      @open-admin="isAdminModalOpen = true"
      @upload="resetData"
      @open-ocr-search="isOcrSearchModalOpen = true"
      @open-rank-quiz="isRankQuizOpen = true"
    />

    <!-- カメラ OCR 曲検索モーダル: 一致時は譜面一覧タブに切替して検索語を引き継ぐ -->
    <OcrSearchModal
      v-if="isOcrSearchModalOpen"
      @close="isOcrSearchModalOpen = false"
      @matched="handleOcrMatched"
    />

    <!-- 非公式難易度クイズモーダル: サイドバーの Lv ウィジェットから起動 -->
    <RankQuizModal :open="isRankQuizOpen" @close="isRankQuizOpen = false" />

    <!-- ============================================================ -->
    <!-- グローバルモーダル群（アプリ全体から開閉される共有モーダル）        -->
    <!-- ログイン / オンボーディング / プロフィール編集 / アップロード結果 / 管理者一覧 -->
    <!-- ============================================================ -->
    <LoginModal :is-open="isLoginModalOpen" @close="isLoginModalOpen = false" @registered="isOnboardingOpen = true" />
    <OnboardingModal
      :is-open="isOnboardingOpen"
      :deferred-prompt="deferredPrompt"
      @close="isOnboardingOpen = false"
      @open-upload="isOnboardingOpen = false; showUploadArea = true"
    />
    <ProfileEditModal :is-open="isProfileModalOpen" @close="isProfileModalOpen = false" />
    <UploadResultModal
      :is-open="isDiffModalOpen"
      :diff-data="diffResult"
      @close="isDiffModalOpen = false"
      @navigate="(tab) => { isDiffModalOpen = false; activeTab = tab as any; }"
    />
    <AdminUserListModal
      :is-open="isAdminModalOpen"
      @close="isAdminModalOpen = false"
      @select="handleSelectUser"
    />

    <!-- アップデート告知モーダル（ログイン後・未読の告知があれば1回だけ表示） -->
    <WhatsNewModal />

    <!-- 共有/選択した画像を曲名検索して保存するモーダル（PWA Share Target の受け皿） -->
    <ShareImportModal
      :open="isShareImportOpen"
      :image-blob="shareImageBlob"
      @close="closeShareImport"
      @login="isLoginModalOpen = true"
    />

    <!-- ============================================================ -->
    <!-- メインコンテナ（サイドバー右側の本文領域）                        -->
    <!-- lg:ml-72 でサイドバーぶんのオフセットを確保                      -->
    <!-- ============================================================ -->
    <div class="flex-1 flex flex-col h-screen overflow-x-hidden overflow-y-auto relative custom-scrollbar lg:ml-72">
      <!-- ========== ヘッダー: ロゴ / PC タブナビ / ダーク切替 / 通知 / アバター ========== -->
      <header class="bg-white dark:bg-slate-800 border-b border-slate-200 dark:border-slate-700 sticky top-0 z-30 shadow-sm transition-colors duration-200 h-16 shrink-0">
        <div class="max-w-7xl lg:max-w-none mx-auto lg:mx-0 px-4 sm:px-6 lg:px-8 h-full flex items-center justify-between">
          <div class="flex items-center gap-4">
            <!-- Hamburger Button -->
            <button 
              @click="isSidebarOpen = true" 
              class="p-2 -ml-2 text-slate-500 hover:text-slate-700 dark:text-slate-400 dark:hover:text-slate-200 transition-colors rounded-lg hover:bg-slate-100 dark:hover:bg-slate-700 focus:outline-none lg:hidden"
            >
              <svg xmlns="http://www.w3.org/2000/svg" class="h-6 w-6" fill="none" viewBox="0 0 24 24" stroke="currentColor">
                <path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M4 6h16M4 12h16M4 18h16" />
              </svg>
            </button>

            <div class="flex lg:hidden items-center gap-2 cursor-pointer group" @click="activeTab = 'dashboard'">
              <div class="relative w-8 h-8 bg-blue-600 rounded-lg flex items-center justify-center text-white font-bold text-xl shadow-sm group-hover:bg-blue-700 transition-colors overflow-hidden">
                B
                <div 
                  class="absolute bg-red-500 text-white text-[9px] font-black py-[2px] w-[46px] text-center transform -rotate-45 shadow-sm leading-none tracking-wider"
                  style="bottom: 3px; right: -14px;"
                >
                  BETA
                </div>
              </div>
            </div>
            
            <!-- パンくず: 現在地のみを示す。ナビゲーション操作はサイドバーに一本化。 -->
            <nav v-if="activeTab !== 'share'" class="hidden lg:flex items-center gap-2 ml-4 text-sm" aria-label="現在地">
              <button
                type="button"
                @click="activeTab = 'dashboard'"
                class="font-medium transition-colors"
                :class="activeTab === 'dashboard'
                  ? 'text-slate-800 dark:text-slate-100'
                  : 'text-slate-500 dark:text-slate-400 hover:text-slate-800 dark:hover:text-slate-100'"
              >
                {{ t('nav.dashboard') }}
              </button>
              <template v-if="activeTabLabel">
                <svg xmlns="http://www.w3.org/2000/svg" class="h-3.5 w-3.5 text-slate-300 dark:text-slate-600" fill="none" viewBox="0 0 24 24" stroke="currentColor" stroke-width="2.5">
                  <path stroke-linecap="round" stroke-linejoin="round" d="M9 5l7 7-7 7" />
                </svg>
                <span class="font-bold text-slate-800 dark:text-slate-100 truncate max-w-[16rem]">{{ activeTabLabel }}</span>
              </template>
            </nav>
          </div>
          
          <div class="flex items-center gap-4">
            <!--
              beat-seeker for competition: 大会主催 4 ID 限定の機能群。
              クリックで大会管理 / Strategy Card / Song Reveal の 3 リンクをドロップダウン表示する。
              ダークモード切替の左隣に常時表示 (権限のあるユーザーのみ)。
            -->
            <div v-if="canAccessCompetition" class="relative">
              <button
                type="button"
                @click="isCompetitionMenuOpen = !isCompetitionMenuOpen"
                class="flex items-center gap-2 px-3 py-1.5 rounded-full text-xs sm:text-sm font-black tracking-wider bg-gradient-to-r from-violet-600 via-fuchsia-600 to-amber-500 text-white shadow-sm hover:shadow-md hover:-translate-y-0.5 transition-all"
                :class="isCompetitionMenuOpen ? 'ring-2 ring-violet-300 dark:ring-violet-400' : ''"
                title="beat-seeker for competition"
              >
                <svg xmlns="http://www.w3.org/2000/svg" class="h-4 w-4" fill="none" viewBox="0 0 24 24" stroke="currentColor" stroke-width="2.2">
                  <path stroke-linecap="round" stroke-linejoin="round" d="M16 11V7a4 4 0 00-8 0v4M5 9h14l1 12H4L5 9z" />
                </svg>
                <span class="hidden sm:inline whitespace-nowrap">beat-seeker for competition</span>
                <span class="sm:hidden">Competition</span>
                <svg xmlns="http://www.w3.org/2000/svg" class="h-3 w-3 opacity-80" fill="none" viewBox="0 0 24 24" stroke="currentColor" stroke-width="3">
                  <path stroke-linecap="round" stroke-linejoin="round" d="M19 9l-7 7-7-7" />
                </svg>
              </button>

              <!-- ドロップダウン本体: 外側クリックで閉じる用のオーバーレイ + 浮動メニュー -->
              <template v-if="isCompetitionMenuOpen">
                <div class="fixed inset-0 z-40" @click="isCompetitionMenuOpen = false"></div>
                <div class="absolute right-0 mt-2 w-72 z-50 rounded-2xl border border-slate-200 dark:border-slate-700 bg-white dark:bg-slate-800 shadow-xl overflow-hidden">
                  <div class="px-4 py-2 text-[10px] font-black uppercase tracking-[0.25em] text-slate-400 dark:text-slate-500 border-b border-slate-100 dark:border-slate-700/60">
                    Competition Tools
                  </div>
                  <!-- 大会管理 (内部タブ) -->
                  <button
                    type="button"
                    @click="goCompetitionAdmin"
                    class="w-full flex items-center gap-3 px-4 py-3 text-left text-sm font-bold text-violet-700 dark:text-violet-300 hover:bg-violet-50 dark:hover:bg-violet-900/30 transition-colors"
                  >
                    <svg xmlns="http://www.w3.org/2000/svg" class="h-5 w-5" fill="none" viewBox="0 0 24 24" stroke="currentColor">
                      <path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M17 20h5v-2a3 3 0 00-5.356-1.857M17 20H7m10 0v-2c0-.656-.126-1.283-.356-1.857M7 20H2v-2a3 3 0 015.356-1.857M7 20v-2c0-.656.126-1.283.356-1.857m0 0a5.002 5.002 0 019.288 0M15 7a3 3 0 11-6 0 3 3 0 016 0zm6 3a2 2 0 11-4 0 2 2 0 014 0zM7 10a2 2 0 11-4 0 2 2 0 014 0z" />
                    </svg>
                    <div class="flex-1 min-w-0">
                      <p>大会管理</p>
                      <p class="text-[10px] font-mono text-slate-400 dark:text-slate-500">5チーム×4人 総当たり編成</p>
                    </div>
                  </button>
                  <!-- Strategy Card (スタンドアロン URL) -->
                  <a
                    href="/strategy-card"
                    @click="isCompetitionMenuOpen = false"
                    class="flex items-center gap-3 px-4 py-3 text-sm font-bold text-fuchsia-700 dark:text-fuchsia-300 hover:bg-fuchsia-50 dark:hover:bg-fuchsia-900/30 transition-colors"
                  >
                    <svg xmlns="http://www.w3.org/2000/svg" class="h-5 w-5" fill="none" viewBox="0 0 24 24" stroke="currentColor">
                      <path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M9.663 17h4.673M12 3v1m6.364 1.636l-.707.707M21 12h-1M4 12H3m3.343-5.657l-.707-.707m2.828 9.9a5 5 0 117.072 0l-.548.547A3.374 3.374 0 0014 18.469V19a2 2 0 11-4 0v-.531c0-.895-.356-1.754-.988-2.386l-.548-.547z" />
                    </svg>
                    <div class="flex-1 min-w-0">
                      <p>Strategy Card</p>
                      <p class="text-[10px] font-mono text-slate-400 dark:text-slate-500">課題曲ランダム抽選 (OBS用)</p>
                    </div>
                  </a>
                  <!-- Song Reveal (スタンドアロン URL) -->
                  <a
                    href="/song-reveal"
                    @click="isCompetitionMenuOpen = false"
                    class="flex items-center gap-3 px-4 py-3 text-sm font-bold text-cyan-700 dark:text-cyan-300 hover:bg-cyan-50 dark:hover:bg-cyan-900/30 transition-colors"
                  >
                    <svg xmlns="http://www.w3.org/2000/svg" class="h-5 w-5" fill="none" viewBox="0 0 24 24" stroke="currentColor">
                      <path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M9 19V5l12-2v14M9 9l12-2M5 21a2 2 0 100-4 2 2 0 000 4zm12-2a2 2 0 100-4 2 2 0 000 4z" />
                    </svg>
                    <div class="flex-1 min-w-0">
                      <p>Song Reveal</p>
                      <p class="text-[10px] font-mono text-slate-400 dark:text-slate-500">選曲発表演出 (OBS用)</p>
                    </div>
                  </a>
                </div>
              </template>
            </div>

            <!-- Dark Mode Toggle -->
            <button @click="toggleDarkMode" class="p-2 text-slate-500 hover:text-slate-700 dark:text-slate-400 dark:hover:text-slate-200 transition-colors rounded-full hover:bg-slate-100 dark:hover:bg-slate-700 focus:outline-none">
              <svg v-if="isDarkMode" xmlns="http://www.w3.org/2000/svg" viewBox="0 0 24 24" fill="currentColor" class="h-5 w-5">
                <path fill-rule="evenodd" d="M9.528 1.718a.75.75 0 01.162.819A8.97 8.97 0 009 6a9 9 0 009 9 8.97 8.97 0 003.463-.69.75.75 0 01.981.98 10.503 10.503 0 01-9.694 6.46c-5.799 0-10.5-4.701-10.5-10.5 0-4.368 2.667-8.112 6.46-9.694a.75.75 0 01.818.162z" clip-rule="evenodd" />
              </svg>
              <svg v-else xmlns="http://www.w3.org/2000/svg" viewBox="0 0 24 24" fill="currentColor" class="h-5 w-5">
                <path d="M12 2.25a.75.75 0 01.75.75v2.25a.75.75 0 01-1.5 0V3a.75.75 0 01.75-.75zM7.5 12a4.5 4.5 0 119 0 4.5 4.5 0 01-9 0zM18.894 6.166a.75.75 0 00-1.06-1.06l-1.591 1.59a.75.75 0 101.06 1.061l1.591-1.59zM21.75 12a.75.75 0 01-.75.75h-2.25a.75.75 0 010-1.5H21a.75.75 0 01.75.75zM17.834 18.894a.75.75 0 001.06-1.06l-1.59-1.591a.75.75 0 10-1.061 1.06l1.59 1.591zM12 18a.75.75 0 01.75.75V21a.75.75 0 01-1.5 0v-2.25A.75.75 0 0112 18zM7.758 17.303a.75.75 0 00-1.061-1.06l-1.591 1.59a.75.75 0 001.06 1.061l1.591-1.59zM6 12a.75.75 0 01-.75.75H3a.75.75 0 010-1.5h2.25A.75.75 0 016 12zM6.697 7.757a.75.75 0 001.06-1.06l-1.59-1.591a.75.75 0 00-1.061 1.06l1.59 1.591z" />
              </svg>
            </button>
            
            <!-- Notification Bell -->
            <div v-if="isLoggedIn" class="relative">
              <button 
                @click="isNotificationOpen = !isNotificationOpen"
                class="p-2 text-slate-500 hover:text-slate-700 dark:text-slate-400 dark:hover:text-slate-200 transition-colors rounded-full hover:bg-slate-100 dark:hover:bg-slate-700 focus:outline-none relative"
              >
                <svg xmlns="http://www.w3.org/2000/svg" class="h-6 w-6" fill="none" viewBox="0 0 24 24" stroke="currentColor">
                  <path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M15 17h5l-1.405-1.405A2.032 2.032 0 0118 14.158V11a6.002 6.002 0 00-4-5.659V5a2 2 0 10-4 0v.341C7.67 6.165 6 8.388 6 11v3.159c0 .538-.214 1.055-.595 1.436L4 17h5m6 0v1a3 3 0 11-6 0v-1m6 0H9" />
                </svg>
                <span v-if="pendingRequests.length + appUnreadCount > 0" class="absolute top-1.5 right-1.5 w-4 h-4 bg-red-500 text-white text-[10px] font-bold rounded-full flex items-center justify-center border-2 border-white dark:border-slate-800">
                  {{ pendingRequests.length + appUnreadCount }}
                </span>
              </button>
              <NotificationBox :is-open="isNotificationOpen" @close="isNotificationOpen = false" />
            </div>
            
            <template v-if="!isLoggedIn && !authLoading">
              <button class="text-sm font-medium text-slate-600 hover:text-slate-900 dark:text-slate-300 dark:hover:text-white transition-colors" @click="isLoginModalOpen = true">
                {{ t('nav.loginRegister') }}
              </button>
            </template>
            <template v-if="isLoggedIn">
              <div @click="isSidebarOpen = true" class="w-8 h-8 bg-gradient-to-br from-blue-500 to-indigo-600 rounded-full flex items-center justify-center text-white text-xs font-bold shadow-sm cursor-pointer hover:shadow-md transition-all lg:hidden">
                {{ user?.displayName?.charAt(0) || user?.iidxId?.charAt(0) || 'U' }}
              </div>
            </template>
          </div>
        </div>
      </header>

      <!-- ========== メインコンテンツ（タブ別のビューをここに描画） ========== -->
      <main class="flex-1 w-full mx-auto px-4 sm:px-6 lg:px-8 py-8 sm:py-12">
        <!-- モバイル用パンくず: ハンバーガーで開くサイドバーがあるので、ここでは現在地のみ示す。 -->
        <nav v-if="activeTab !== 'share' && activeTabLabel" class="lg:hidden sticky top-16 z-20 bg-slate-50/95 dark:bg-slate-900/95 backdrop-blur-sm border-b border-slate-200 dark:border-slate-700 -mx-4 px-4 mb-6 py-2.5 flex items-center gap-2 text-sm" aria-label="現在地">
          <button
            type="button"
            @click="activeTab = 'dashboard'"
            class="font-medium text-slate-500 dark:text-slate-400 hover:text-slate-800 dark:hover:text-slate-100 transition-colors"
          >
            {{ t('nav.dashboard') }}
          </button>
          <svg xmlns="http://www.w3.org/2000/svg" class="h-3.5 w-3.5 text-slate-300 dark:text-slate-600 shrink-0" fill="none" viewBox="0 0 24 24" stroke="currentColor" stroke-width="2.5">
            <path stroke-linecap="round" stroke-linejoin="round" d="M9 5l7 7-7 7" />
          </svg>
          <span class="font-bold text-slate-800 dark:text-slate-100 truncate">{{ activeTabLabel }}</span>
        </nav>
        <!-- ========== 閲覧中バナー: 他ユーザー/TOPランカー閲覧時に最上部へ固定表示 ========== -->
        <!-- 「自分のデータに戻る」「フレンド申請」「仮想ライバル登録」などの操作ボタンを配置 -->
        <div v-if="viewingUserId || viewingMode === 'topRanker'" class="w-full max-w-6xl mx-auto mb-6 flex flex-col sm:flex-row items-center justify-between gap-4 bg-gradient-to-r from-indigo-500 to-purple-600 p-4 rounded-xl shadow-md text-white border border-indigo-400 dark:border-indigo-700 animate-fade-in relative overflow-hidden shrink-0">
          <div class="absolute right-0 top-0 bottom-0 w-32 bg-[radial-gradient(ellipse_at_center,_var(--tw-gradient-stops))] from-white/20 to-transparent pointer-events-none"></div>
          <div class="flex items-center gap-3 relative z-10 w-full justify-center sm:justify-start">
            <svg xmlns="http://www.w3.org/2000/svg" class="h-6 w-6 text-indigo-200 shrink-0" fill="none" viewBox="0 0 24 24" stroke="currentColor" stroke-width="2">
              <path stroke-linecap="round" stroke-linejoin="round" d="M15 12a3 3 0 11-6 0 3 3 0 016 0z" />
              <path stroke-linecap="round" stroke-linejoin="round" d="M2.458 12C3.732 7.943 7.523 5 12 5c4.478 0 8.268 2.943 9.542 7-1.274 4.057-5.064 7-9.542 7-4.477 0-8.268-2.943-9.542-7z" />
            </svg>
            <div class="flex flex-col">
              <span class="text-xs font-bold text-indigo-200 uppercase tracking-widest leading-none mb-1">{{ viewingMode === 'admin' ? t('app.banner.adminMode') : viewingMode === 'public' ? t('app.banner.publicMode') : viewingMode === 'topRanker' ? t('app.banner.topRankerMode') : viewingMode === 'private' ? t('app.banner.privateMode') : t('app.banner.friendMode') }}</span>
              <span class="text-base sm:text-lg font-bold">{{ viewingMode === 'topRanker' ? t('app.banner.viewingTopRanker', { name: viewingUserName }) : t('app.banner.viewingUser', { name: viewingUserName }) }}</span>
            </div>
          </div>
          <div class="flex items-center gap-2 shrink-0 relative z-10">
            <button
              @click="returnToMyData"
              class="px-4 py-2 bg-white/10 hover:bg-white/20 backdrop-blur-md text-white font-bold rounded-lg border border-white/30 transition-all shadow-sm flex items-center gap-2 whitespace-nowrap"
            >
              <svg xmlns="http://www.w3.org/2000/svg" class="h-4 w-4" fill="none" viewBox="0 0 24 24" stroke="currentColor" stroke-width="2">
                <path stroke-linecap="round" stroke-linejoin="round" d="M10 19l-7-7m0 0l7-7m-7 7h18" />
              </svg>
              {{ t('app.banner.returnButton') }}
            </button>
            <template v-if="viewingMode === 'topRanker' && isLoggedIn">
              <button
                v-if="virtualRivalRegistered === false"
                @click="toggleVirtualRival"
                :disabled="virtualRivalBusy"
                class="px-4 py-2 bg-emerald-500/90 hover:bg-emerald-500 disabled:bg-emerald-400 text-white font-bold rounded-lg border border-emerald-300/50 transition-all shadow-sm flex items-center gap-2 whitespace-nowrap"
              >
                <svg xmlns="http://www.w3.org/2000/svg" class="h-4 w-4" fill="none" viewBox="0 0 24 24" stroke="currentColor" stroke-width="2">
                  <path stroke-linecap="round" stroke-linejoin="round" d="M18 9v3m0 0v3m0-3h3m-3 0h-3m-2-5a4 4 0 11-8 0 4 4 0 018 0zM3 20a6 6 0 0112 0v1H3v-1z" />
                </svg>
                フレンド申請
              </button>
              <button
                v-else-if="virtualRivalRegistered === true"
                @click="toggleVirtualRival"
                :disabled="virtualRivalBusy"
                class="px-4 py-2 bg-emerald-500/20 hover:bg-red-500/40 text-emerald-100 hover:text-white font-bold rounded-lg border border-emerald-300/40 transition-all whitespace-nowrap text-sm"
                title="クリックで解除"
              >ライバル登録済み</button>
            </template>
            <template v-if="(viewingMode === 'private' || viewingMode === 'public') && isLoggedIn">
              <button
                v-if="friendStatus === 'none'"
                @click="openFriendRequestModal"
                class="px-4 py-2 bg-emerald-500/90 hover:bg-emerald-500 text-white font-bold rounded-lg border border-emerald-300/50 transition-all shadow-sm flex items-center gap-2 whitespace-nowrap"
              >
                <svg xmlns="http://www.w3.org/2000/svg" class="h-4 w-4" fill="none" viewBox="0 0 24 24" stroke="currentColor" stroke-width="2">
                  <path stroke-linecap="round" stroke-linejoin="round" d="M18 9v3m0 0v3m0-3h3m-3 0h-3m-2-5a4 4 0 11-8 0 4 4 0 018 0zM3 20a6 6 0 0112 0v1H3v-1z" />
                </svg>
                フレンド申請
              </button>
              <span
                v-else-if="friendStatus === 'requested'"
                class="px-4 py-2 bg-amber-500/20 text-amber-100 font-bold rounded-lg border border-amber-300/40 whitespace-nowrap text-sm"
              >申請済み</span>
              <span
                v-else-if="friendStatus === 'friend'"
                class="px-4 py-2 bg-emerald-500/20 text-emerald-100 font-bold rounded-lg border border-emerald-300/40 whitespace-nowrap text-sm"
              >フレンド</span>
              <span
                v-else-if="friendStatus === 'incoming'"
                class="px-4 py-2 bg-blue-500/20 text-blue-100 font-bold rounded-lg border border-blue-300/40 whitespace-nowrap text-sm"
              >申請受信中</span>
            </template>
          </div>
        </div>

        <!-- ========== フレンド申請モーダル: メッセージを添えて申請を送信する ========== -->
        <div v-if="isFriendRequestModalOpen" class="fixed inset-0 z-[100] flex items-center justify-center p-4 bg-slate-900/60 backdrop-blur-sm animate-fade-in" @click.self="isFriendRequestModalOpen = false">
          <div class="bg-white dark:bg-slate-800 rounded-2xl shadow-2xl w-full max-w-md overflow-hidden border border-slate-200 dark:border-slate-700">
            <div class="p-4 sm:p-6 border-b border-slate-200 dark:border-slate-700 flex justify-between items-center bg-slate-50 dark:bg-slate-900/50">
              <h3 class="text-lg font-black text-slate-800 dark:text-white tracking-tight">{{ viewingUserName }} さんにフレンド申請</h3>
              <button @click="isFriendRequestModalOpen = false" class="p-2 text-slate-400 hover:text-slate-600 dark:hover:text-slate-200 transition-colors">
                <svg xmlns="http://www.w3.org/2000/svg" class="h-5 w-5" fill="none" viewBox="0 0 24 24" stroke="currentColor" stroke-width="2">
                  <path stroke-linecap="round" stroke-linejoin="round" d="M6 18L18 6M6 6l12 12" />
                </svg>
              </button>
            </div>
            <div class="p-6 space-y-4">
              <label class="block">
                <span class="text-sm font-bold text-slate-700 dark:text-slate-300">申請メッセージ (任意)</span>
                <textarea
                  v-model="friendRequestMessage"
                  maxlength="100"
                  rows="3"
                  placeholder="よろしくお願いします！"
                  class="mt-2 w-full px-3 py-2 bg-slate-50 dark:bg-slate-900 border border-slate-200 dark:border-slate-700 rounded-lg focus:ring-2 focus:ring-emerald-500 text-slate-800 dark:text-slate-200 resize-none"
                ></textarea>
                <span class="text-xs text-slate-400 mt-1 block text-right">{{ friendRequestMessage.length }} / 100</span>
              </label>
              <div v-if="friendRequestError" class="p-3 bg-red-50 dark:bg-red-900/30 text-red-700 dark:text-red-400 rounded-lg text-sm">
                {{ friendRequestError }}
              </div>
              <div class="flex gap-2 justify-end">
                <button
                  @click="isFriendRequestModalOpen = false"
                  :disabled="friendRequestSending"
                  class="px-4 py-2 bg-slate-100 dark:bg-slate-700 hover:bg-slate-200 dark:hover:bg-slate-600 text-slate-700 dark:text-slate-200 font-bold rounded-lg transition-all"
                >キャンセル</button>
                <button
                  @click="submitFriendRequest"
                  :disabled="friendRequestSending"
                  class="px-4 py-2 bg-emerald-600 hover:bg-emerald-700 disabled:bg-emerald-400 text-white font-bold rounded-lg transition-all flex items-center gap-2"
                >
                  <span v-if="friendRequestSending" class="w-4 h-4 border-2 border-white/30 border-t-white rounded-full animate-spin"></span>
                  申請を送る
                </button>
              </div>
            </div>
          </div>
        </div>
        
        <!-- ========== スコア取り込みモーダル: ブックマークレットコード案内＋ CSV ドロップ ========== -->
      <div v-if="showUploadArea && isLoggedIn" class="fixed inset-0 z-50 flex items-center justify-center p-4 bg-black/60 backdrop-blur-sm" @click.self="handleUnifiedClose">
        <div class="w-full max-w-xl bg-white dark:bg-slate-800 rounded-2xl shadow-2xl border border-slate-200 dark:border-slate-700 p-6 animate-fade-in">
          <div class="flex justify-between items-center mb-4">
            <h2 class="text-lg font-bold text-slate-800 dark:text-white">{{ t('app.import.title') }}</h2>
            <button @click="handleUnifiedClose" class="p-1 text-slate-400 hover:text-slate-600 dark:hover:text-slate-200 transition-colors rounded-lg hover:bg-slate-100 dark:hover:bg-slate-700">
              <svg xmlns="http://www.w3.org/2000/svg" class="h-5 w-5" fill="none" viewBox="0 0 24 24" stroke="currentColor" stroke-width="2">
                <path stroke-linecap="round" stroke-linejoin="round" d="M6 18L18 6M6 6l12 12" />
              </svg>
            </button>
          </div>
          <UnifiedImport :bookmarklet-code="BOOKMARKLET_CODE" @score-file="pendingScoreFile = $event" @close="handleUnifiedClose" />
        </div>
      </div>

      <!-- ============================================================ -->
      <!-- ルータービュー相当: activeTab による条件付き描画ブロック        -->
      <!-- 各タブが独立したビューコンポーネントを呼び出す                 -->
      <!-- ============================================================ -->
        <!-- 更新履歴 -->
        <template v-if="activeTab === 'changelog'">
          <Changelog class="w-full max-w-5xl mx-auto animate-fade-in" />
        </template>

        <!-- ランキング: 他ユーザーへの導線を 3 種類（公開/非公開/TOPランカー）発火する -->
        <template v-else-if="activeTab === 'ranking'">
          <RankingList
            class="w-full max-w-5xl mx-auto animate-fade-in"
            @view-user="handleViewPublicUser"
            @view-private-user="handleViewPrivateUser"
            @view-top-ranker="handleViewTopRanker"
          />
        </template>

        <!-- 管理者専用: 人気曲ランキング（BEAT-PT TOP100 集計） -->
        <template v-else-if="activeTab === 'popular-songs'">
          <SongRankingList class="w-full max-w-5xl mx-auto animate-fade-in" />
        </template>

        <!-- 大会主催専用: Competition 管理画面 (4 ID ホワイトリスト)。
             サイドバーの Competition セクションから遷移する通常タブ。 -->
        <template v-else-if="activeTab === 'competition-admin'">
          <CompetitionAdminView class="w-full animate-fade-in" />
        </template>

        <!-- 管理者専用: 任意の 2 ユーザー間スコア比較。
             サイドバー導線なし。URL `/admin/user-comparison` 直叩き専用。 -->
        <template v-else-if="activeTab === 'admin-user-comparison'">
          <AdminUserComparisonView class="w-full animate-fade-in" />
        </template>

        <!-- 利用規約 -->
        <template v-else-if="activeTab === 'terms'">
          <Terms class="w-full max-w-5xl mx-auto animate-fade-in" />
        </template>

        <!-- プライバシーポリシー -->
        <template v-else-if="activeTab === 'privacy-policy'">
          <PrivacyPolicy class="w-full max-w-5xl mx-auto animate-fade-in" />
        </template>

        <!-- お問い合わせ -->
        <template v-else-if="activeTab === 'contact'">
          <Contact class="w-full max-w-5xl mx-auto animate-fade-in" />
        </template>

        <!-- 攻略ガイド -->
        <template v-else-if="activeTab === 'guide'">
          <Guide
            class="w-full max-w-4xl mx-auto animate-fade-in"
            :slug="currentGuideSlug"
            @navigate-guide="(slug) => {
              currentGuideSlug = slug;
              window.history.pushState({}, '', `/guide/${slug}`);
            }"
          />
        </template>

        <!-- 公開ランディング (未ログイン /) -->
        <template v-else-if="activeTab === 'landing'">
          <Landing
            class="w-full max-w-6xl mx-auto"
            @navigate="(tab) => activeTab = tab as any"
            @open-login="isLoginModalOpen = true"
          />
        </template>

        <!-- アプリについて -->
        <template v-else-if="activeTab === 'about'">
          <About class="w-full max-w-5xl mx-auto animate-fade-in" />
        </template>

        <!-- 使い方ガイド: 各機能の操作手順をまとめたページ -->
        <template v-else-if="activeTab === 'manual'">
          <Manual class="w-full max-w-5xl mx-auto" />
        </template>

        <!-- URL 共有ビュー（ログイン不要） -->
        <template v-else-if="activeTab === 'share'">
          <ShareView class="w-full" />
        </template>

        <!-- ARENA: 対戦ログ -->
        <template v-else-if="activeTab === 'arena'">
          <ArenaView class="w-full max-w-5xl mx-auto animate-fade-in" :viewing-user-id="viewingUserId" />
        </template>

        <!-- Tier Voting: 難易度投票 -->
        <template v-else-if="activeTab === 'tier-voting'">
          <TierVotingView class="w-full max-w-5xl mx-auto animate-fade-in" />
        </template>

        <!-- ARCADE アシスト（ログイン必須） -->
        <template v-else-if="activeTab === 'arcade-assist'">
          <ArcadeAssistView class="w-full max-w-lg mx-auto animate-fade-in" />
        </template>

        <!-- 曲別平均スコア閲覧 -->
        <template v-else-if="activeTab === 'song-avg'">
          <SongAverageView class="w-full max-w-7xl mx-auto animate-fade-in" />
        </template>

        <!-- 非公式難易度表 -->
        <template v-else-if="activeTab === 'diff-table'">
          <DifficultyTableView class="w-full max-w-5xl mx-auto animate-fade-in" />
        </template>

        <!-- 譜面一覧 -->
        <template v-else-if="activeTab === 'chart-list'">
          <ChartListView class="w-full max-w-6xl mx-auto animate-fade-in" />
        </template>

        <!-- ランク比較（特定ユーザーのみ表示） -->
        <template v-else-if="activeTab === 'rank-comparison'">
          <RankComparisonView class="w-full max-w-6xl mx-auto animate-fade-in" />
        </template>

        <!-- スコアペア散布図: サポーター限定機能。非サポーターには課金誘導カードを表示 -->
        <template v-else-if="activeTab === 'score-scatter'">
          <div v-if="!user?.isSupporter" class="w-full max-w-2xl mx-auto animate-fade-in">
            <div class="bg-white dark:bg-slate-800 rounded-2xl border border-slate-200 dark:border-slate-700 p-12 text-center shadow-sm">
              <div class="w-20 h-20 mx-auto mb-6 bg-amber-50 dark:bg-amber-900/30 rounded-2xl flex items-center justify-center">
                <svg xmlns="http://www.w3.org/2000/svg" class="h-10 w-10 text-amber-500" fill="none" viewBox="0 0 24 24" stroke="currentColor">
                  <path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M12 15v2m-6 4h12a2 2 0 002-2v-6a2 2 0 00-2-2H6a2 2 0 00-2 2v6a2 2 0 002 2zm10-10V7a4 4 0 00-8 0v4h8z" />
                </svg>
              </div>
              <h2 class="text-2xl font-black text-slate-900 dark:text-white mb-3">{{ t('supporter.lockedTitle') }}</h2>
              <p class="text-slate-500 dark:text-slate-400 font-medium mb-6 leading-relaxed">{{ t('supporter.lockedDesc') }}</p>
              <button
                @click="handleKofiClick"
                class="inline-flex items-center gap-2 px-6 py-3 bg-gradient-to-r from-amber-500 to-yellow-500 text-white font-bold rounded-xl shadow-lg shadow-amber-500/20 hover:shadow-amber-500/40 hover:-translate-y-0.5 transition-all"
              >
                <svg xmlns="http://www.w3.org/2000/svg" class="h-5 w-5" fill="none" viewBox="0 0 24 24" stroke="currentColor">
                  <path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M4.318 6.318a4.5 4.5 0 000 6.364L12 20.364l7.682-7.682a4.5 4.5 0 00-6.364-6.364L12 7.636l-1.318-1.318a4.5 4.5 0 00-6.364 0z" />
                </svg>
                {{ t('supporter.kofiButton') }}
              </button>
            </div>
          </div>
          <ScoreScatterView v-else />
        </template>

        <!-- 譜面分析（スコア予測）: 全ユーザー利用可 -->
        <template v-else-if="activeTab === 'score-prediction'">
          <ScorePredictionView
            class="w-full max-w-6xl mx-auto animate-fade-in"
            :viewing-user-id="viewingUserId"
            :viewing-mode="viewingMode"
          />
        </template>

        <!-- プロフィール: 統計ダッシュボード（グラフ多数） -->
        <template v-else-if="activeTab === 'profile'">
          <ProfileDashboard
            class="w-full max-w-6xl"
            :viewing-user-id="viewingUserId"
          />
        </template>

        <!-- アップロード履歴: 過去の差分ログ一覧 -->
        <template v-else-if="activeTab === 'history'">
          <UploadHistory
            class="w-full max-w-6xl animate-fade-in"
            :viewing-user-id="viewingUserId"
          />
        </template>

        <!-- フレンド一覧 + 申請管理 -->
        <template v-else-if="activeTab === 'friends'">
          <Friends
            class="w-full max-w-6xl animate-fade-in"
            @view-user="handleViewFriend"
            @view-top-ranker="handleViewTopRanker"
          />
        </template>

        <!-- タイムライン: 自分 + フレンドのアップロード活動 -->
        <template v-else-if="(activeTab as string) === 'timeline'">
          <FriendTimeline class="w-full max-w-6xl animate-fade-in" />
        </template>

        <!-- デフォルト（dashboard / table）: ヒーロー → CSV ドロップ → スコア結果 の 3 段構え -->
        <template v-else>
          <!-- ヒーローセクション: スコア未登録時のみ表示する導入文 -->
          <div v-if="!scoreData.length && viewingMode !== 'private'" class="text-center mb-12 max-w-2xl mx-auto animate-fade-in">
            <h1 class="text-4xl font-extrabold text-slate-900 dark:text-white tracking-tight sm:text-5xl mb-4">
              {{ t('app.hero.title') }}
            </h1>
            <p class="text-lg text-slate-600 dark:text-slate-400 leading-relaxed">
              {{ t('app.hero.subtitle') }}
            </p>

            <!-- PWA インストールバナー: beforeinstallprompt を受けたときだけ出現 -->
            <div v-if="showInstallBanner" class="mt-8 p-6 bg-blue-600 rounded-2xl shadow-xl text-white flex flex-col sm:flex-row items-center gap-4 animate-in zoom-in duration-300">
              <div class="w-12 h-12 bg-white/20 rounded-xl flex items-center justify-center shrink-0">
                <svg xmlns="http://www.w3.org/2000/svg" class="h-8 w-8 text-white" fill="none" viewBox="0 0 24 24" stroke="currentColor">
                  <path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M12 18h.01M8 21h8a2 2 0 002-2V5a2 2 0 00-2-2H8a2 2 0 00-2 2v14a2 2 0 002 2z" />
                </svg>
              </div>
              <div class="text-center sm:text-left flex-1">
                <h3 class="font-bold text-lg">{{ t('app.pwa.title') }}</h3>
                <p class="text-blue-100 text-sm">{{ t('app.pwa.desc') }}</p>
              </div>
              <div class="flex gap-2">
                <button @click="showInstallBanner = false" class="px-4 py-2 bg-white/10 hover:bg-white/20 rounded-lg text-sm font-bold transition-all">{{ t('app.pwa.later') }}</button>
                <button @click="installApp" class="px-4 py-2 bg-white text-blue-600 hover:bg-blue-50 rounded-lg text-sm font-bold transition-all shadow-md">{{ t('app.pwa.install') }}</button>
              </div>
            </div>
          </div>

          <!-- ローディング表示: CSV 解析中 / スコア取得中 / 認証中のいずれかで表示 -->
          <div v-if="isParsing || isFetching || authLoading" class="w-full max-w-3xl mx-auto animate-fade-in flex flex-col items-center">
            <div class="w-full flex flex-col items-center justify-center p-12 bg-white dark:bg-slate-800 rounded-2xl shadow-sm border border-slate-200 dark:border-slate-700">
              <div class="w-10 h-10 border-4 border-blue-200 dark:border-blue-900 border-t-blue-600 dark:border-t-blue-400 rounded-full animate-spin mb-4"></div>
              <p class="text-slate-600 dark:text-slate-300 font-medium tracking-wide">{{ t('app.loading.data') }}</p>
            </div>
          </div>

          <!-- エンプティ状態: CSV ドロップエリアを中央に表示（非公開ユーザー閲覧時は出さない） -->
          <div v-else-if="!scoreData.length && viewingMode !== 'private'" class="w-full max-w-3xl mx-auto animate-fade-in flex flex-col items-center">
            <CsvDropzone @file-dropped="handleFileDropped" class="w-full" />
            <!-- エラーメッセージバナー -->
            <div
              v-if="errorMsg"
              class="w-full mt-6 p-4 bg-red-50 dark:bg-red-900/30 text-red-700 dark:text-red-400 border border-red-200 dark:border-red-800 rounded-xl flex items-center gap-3 animate-fade-in"
            >
              <svg xmlns="http://www.w3.org/2000/svg" class="h-5 w-5 text-red-500 shrink-0" viewBox="0 0 20 20" fill="currentColor">
                <path fill-rule="evenodd" d="M10 18a8 8 0 100-16 8 8 0 000 16zM8.707 7.293a1 1 0 00-1.414 1.414L8.586 10l-1.293 1.293a1 1 0 101.414 1.414L10 11.414l1.293 1.293a1 1 0 001.414-1.414L11.414 10l1.293-1.293a1 1 0 00-1.414-1.414L10 8.586 8.707 7.293z" clip-rule="evenodd" />
              </svg>
              <span class="font-medium text-sm sm:text-base">{{ errorMsg }}</span>
            </div>
          </div>

          <!-- エンプティステート: ログイン済みでまだスコアが 1 件もない時の案内 -->
          <!-- dashboard / table タブのときだけ出し、ranking や about など他のタブは邪魔しない -->
          <div
            v-if="isLoggedIn && !viewingUserId && scoreData.length === 0 && (activeTab === 'dashboard' || activeTab === 'table')"
            class="w-full max-w-2xl mx-auto animate-fade-in"
          >
            <div class="bg-white dark:bg-slate-800 rounded-2xl shadow-sm border border-slate-200 dark:border-slate-700 p-8 sm:p-12 text-center">
              <div class="w-16 h-16 mx-auto bg-blue-100 dark:bg-blue-900/40 rounded-2xl flex items-center justify-center mb-6 text-blue-600 dark:text-blue-400">
                <svg aria-hidden="true" xmlns="http://www.w3.org/2000/svg" class="h-8 w-8" fill="none" viewBox="0 0 24 24" stroke="currentColor">
                  <path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M7 16a4 4 0 01-.88-7.903A5 5 0 1115.9 6L16 6a5 5 0 011 9.9M15 13l-3-3m0 0l-3 3m3-3v12" />
                </svg>
              </div>
              <h2 class="text-2xl sm:text-3xl font-black text-slate-900 dark:text-white mb-3 tracking-tight">{{ t('empty.title') }}</h2>
              <p class="text-sm sm:text-base text-slate-600 dark:text-slate-300 leading-relaxed whitespace-pre-line mb-8">{{ t('empty.desc') }}</p>
              <div class="flex flex-col sm:flex-row items-center justify-center gap-3">
                <button
                  type="button"
                  @click="showUploadArea = true"
                  class="w-full sm:w-auto inline-flex items-center justify-center gap-2 px-6 py-3 bg-gradient-to-r from-blue-600 to-indigo-600 text-white font-bold rounded-xl shadow-lg shadow-blue-500/20 hover:shadow-blue-500/40 hover:-translate-y-0.5 transition-all"
                >
                  <svg aria-hidden="true" xmlns="http://www.w3.org/2000/svg" class="h-5 w-5" fill="none" viewBox="0 0 24 24" stroke="currentColor">
                    <path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M7 16a4 4 0 01-.88-7.903A5 5 0 1115.9 6L16 6a5 5 0 011 9.9M15 13l-3-3m0 0l-3 3m3-3v12" />
                  </svg>
                  {{ t('empty.uploadCta') }}
                </button>
                <button
                  type="button"
                  @click="activeTab = 'guide'"
                  class="w-full sm:w-auto inline-flex items-center justify-center gap-2 px-6 py-3 bg-slate-100 dark:bg-slate-700 text-slate-700 dark:text-slate-200 font-bold rounded-xl hover:bg-slate-200 dark:hover:bg-slate-600 transition-colors"
                >
                  {{ t('empty.guideLink') }}
                </button>
              </div>
            </div>
          </div>

          <!-- スコア結果表示: dashboard / table タブを v-show で切り替える（マウント状態を維持） -->
          <div v-else-if="scoreData.length > 0 || viewingMode === 'private'" class="w-full flex flex-col items-center animate-fade-in">
            <!-- ダッシュボードタブ: グラフ中心の概観表示 -->
            <div v-show="activeTab === 'dashboard'" class="w-full max-w-6xl flex flex-col items-center gap-4">
              <!-- 月末振り返りバナー: 自分のダッシュボード閲覧時かつ表示ウィンドウ内のみ -->
              <WrappedBanner v-if="!viewingUserId && isLoggedIn" />
              <ScoreDashboard
                :scores="scoreData"
                :totalPoints="totalBeatTierPoints"
                :viewing-iidx-id="viewingUserIidxId"
                :viewing-display-name="viewingUserName"
                :viewing-mode="viewingMode"
                :rate-tier-points-override="privateRateTierPoints"
                class="w-full"
                @open-profile-edit="isProfileModalOpen = true"
              />
            </div>

            <!-- スコア一覧タブ: ScoreSummary が BEAT-TIER / RATE-TIER モード切替と詳細モーダルを担当 -->
            <ScoreSummary
              ref="scoreSummaryRef"
              v-show="activeTab === 'table'"
              :scores="scoreData"
              :viewing-mode="viewingMode"
              @reset="resetData"
              @update:totalPoints="points => { if (viewingMode !== 'private') totalBeatTierPoints = points; }"
              @view-user="handleViewPublicUser"
              @view-top-ranker="handleViewTopRanker"
              class="w-full"
            />
          </div>
        </template>
      </main>

      <!-- ========== フッター: コピーライト / 主要ページへの導線 ========== -->
      <footer class="bg-white dark:bg-slate-800 border-t border-slate-200 dark:border-slate-700 py-8 transition-colors duration-200 shrink-0">
        <div class="max-w-7xl mx-auto px-4 sm:px-6 lg:px-8 flex flex-col sm:flex-row items-center justify-between gap-4">
          <div class="text-sm text-slate-500 dark:text-slate-400 text-center sm:text-left">
            <p>© 2026 beat-seeker.</p>
            <p class="text-xs mt-1 max-w-md">{{ t('landing.copyrightDisclaimer') }}</p>
          </div>
          <div class="flex items-center gap-4 flex-wrap justify-center">
            <button @click="activeTab = 'about'" class="text-sm font-medium text-slate-500 dark:text-slate-400 hover:text-slate-800 dark:hover:text-slate-200 transition-colors">{{ t('app.footer.desc') }}</button>
            <button @click="activeTab = 'terms'" class="text-sm font-medium text-slate-500 dark:text-slate-400 hover:text-slate-800 dark:hover:text-slate-200 transition-colors">{{ t('nav.terms') }}</button>
            <button @click="activeTab = 'privacy-policy'" class="text-sm font-medium text-slate-500 dark:text-slate-400 hover:text-slate-800 dark:hover:text-slate-200 transition-colors">{{ t('privacyPolicy.title') }}</button>
            <button @click="activeTab = 'contact'" class="text-sm font-medium text-slate-500 dark:text-slate-400 hover:text-slate-800 dark:hover:text-slate-200 transition-colors">{{ t('contactPage.title') }}</button>
            <button @click="() => { activeTab = 'guide'; currentGuideSlug = null; }" class="text-sm font-medium text-slate-500 dark:text-slate-400 hover:text-slate-800 dark:hover:text-slate-200 transition-colors">{{ t('guide.indexTitle') }}</button>
            <button v-if="!viewingUserId" @click="activeTab = 'ranking'" class="text-sm font-medium text-slate-500 dark:text-slate-400 hover:text-slate-800 dark:hover:text-slate-200 transition-colors">{{ t('nav.ranking') }}</button>
          </div>
        </div>
      </footer>
    </div>
  <!-- ============================================================ -->
  <!-- グローバルバナー/モーダル（Teleport で body 直下に描画）         -->
  <!-- ============================================================ -->
  <!-- アプリ更新バナー: Service Worker が新バージョン検知時に下部に固定表示 -->
  <Teleport to="body">
    <div
      v-if="hasUpdate"
      class="fixed bottom-0 inset-x-0 z-[100] flex items-center justify-between gap-4 px-4 py-3 bg-slate-800 dark:bg-slate-900 text-white shadow-2xl border-t border-slate-700"
    >
      <div class="flex items-center gap-2.5 text-sm">
        <svg xmlns="http://www.w3.org/2000/svg" class="h-4 w-4 text-blue-400 shrink-0" fill="none" viewBox="0 0 24 24" stroke="currentColor" stroke-width="2">
          <path stroke-linecap="round" stroke-linejoin="round" d="M4 4v5h.582m15.356 2A8.001 8.001 0 004.582 9m0 0H9m11 11v-5h-.581m0 0a8.003 8.003 0 01-15.357-2m15.357 2H15" />
        </svg>
        <span>{{ t('app.update.available') }}</span>
      </div>
      <button
        @click="reloadPage"
        class="shrink-0 px-4 py-1.5 bg-blue-500 hover:bg-blue-400 text-white text-sm font-bold rounded-lg transition-colors"
      >
        {{ t('app.update.reload') }}
      </button>
    </div>
  </Teleport>
  <!-- Ko-fi 確認モーダル: サポーター向けに限定トークンをコピーしてから Ko-fi を開く導線 -->
  <Teleport to="body">
    <Transition
      enter-active-class="transition-opacity duration-200"
      enter-from-class="opacity-0"
      enter-to-class="opacity-100"
      leave-active-class="transition-opacity duration-150"
      leave-from-class="opacity-100"
      leave-to-class="opacity-0"
    >
      <div v-if="showKofiModal" class="fixed inset-0 z-[100] flex items-center justify-center p-4">
        <div class="absolute inset-0 bg-slate-900/50 backdrop-blur-sm" @click="showKofiModal = false"></div>
        <div class="relative bg-white dark:bg-slate-800 rounded-2xl border border-slate-200 dark:border-slate-700 shadow-2xl max-w-sm w-full p-6 space-y-4">
          <div class="flex items-center gap-3">
            <div class="w-10 h-10 bg-amber-100 dark:bg-amber-900/30 rounded-xl flex items-center justify-center">
              <svg xmlns="http://www.w3.org/2000/svg" class="h-5 w-5 text-amber-500" fill="none" viewBox="0 0 24 24" stroke="currentColor">
                <path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M13 16h-1v-4h-1m1-4h.01M21 12a9 9 0 11-18 0 9 9 0 0118 0z" />
              </svg>
            </div>
            <h3 class="text-lg font-bold text-slate-900 dark:text-white">{{ t('supporter.modalTitle') }}</h3>
          </div>
          <p class="text-sm text-slate-600 dark:text-slate-300 leading-relaxed">{{ t('supporter.modalDesc') }}</p>
          <div class="bg-amber-50 dark:bg-amber-900/20 border border-amber-200 dark:border-amber-800/50 rounded-xl p-3 text-center">
            <p class="text-[10px] font-bold text-amber-600 dark:text-amber-400 uppercase tracking-wider mb-1">{{ t('supporter.modalTokenLabel') }}</p>
            <p class="text-lg font-mono font-black text-amber-700 dark:text-amber-300 select-all">{{ user?.supporterToken }}</p>
          </div>
          <div class="flex gap-2">
            <button @click="showKofiModal = false"
              class="flex-1 px-4 py-2.5 text-sm font-bold text-slate-600 dark:text-slate-400 bg-slate-100 dark:bg-slate-700 rounded-xl hover:bg-slate-200 dark:hover:bg-slate-600 transition-colors">
              {{ t('supporter.modalCancel') }}
            </button>
            <button @click="confirmKofiOpen"
              class="flex-1 px-4 py-2.5 text-sm font-bold text-white bg-gradient-to-r from-amber-500 to-yellow-500 rounded-xl hover:shadow-lg hover:shadow-amber-500/20 transition-all">
              {{ t('supporter.modalConfirm') }}
            </button>
          </div>
        </div>
      </div>
    </Transition>
  </Teleport>

  <!-- ToastContainer は template 末尾 (v-else の外) に移動済。
       スタンドアロン画面 (Player/TL/StrategyCard 等) でも通知を表示するため。 -->

  <!-- グローバルコマンドパレット（Cmd/Ctrl + K で開く全画面検索） -->
  <CommandPalette
    v-model:isOpen="isCmdkOpen"
    :active-tab="activeTab"
    :is-logged-in="isLoggedIn"
    :is-dark="isDarkMode"
    :score-data="scoreData"
    :available-tab-ids="availableCmdkTabIds"
    :is-viewing-other="!!viewingUserId"
    @select-tab="handleCmdkSelectTab"
    @select-song="handleCmdkSelectSong"
    @action="handleCmdkAction"
  />

  <!-- ページ上部へ戻る FAB（スクロール量がしきい値超で出現） -->
  <BackToTop />
  </div>

  <!--
    トースト通知のグローバルレイヤ。
    上記の <div v-else> の中ではなく外側に置くことで、
    CompetitionPlayerView / CompetitionTlView / StrategyCardView 等のスタンドアロン画面でも
    useToast() の通知が表示できるようにする。
  -->
  <ToastContainer />

  <!--
    月末振り返り (Spotify Wrapped 風) の全画面オーバーレイ。
    /wrapped/:year/:month あるいは /user/:userId/wrapped/:year/:month のパスでのみ表示する。
    fixed inset-0 z-50 で App.vue 本体の上に被せ、サイドバー/ヘッダーをスキップした没入レイアウトにする。
  -->
  <WrappedView v-if="$route.path.includes('/wrapped/')" />
</template>

<style scoped>
.animate-fade-in {
  animation: fadeIn 0.4s ease-out forwards;
}

@keyframes fadeIn {
  from {
    opacity: 0;
    transform: translateY(10px);
  }
  to {
    opacity: 1;
    transform: translateY(0);
  }
}
</style>
