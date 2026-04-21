<script setup lang="ts">
import { ref } from 'vue';
import ResetPasswordView from './views/ResetPasswordView.vue';
import CsvDropzone from './components/CsvDropzone.vue';
import UnifiedImport from './components/UnifiedImport.vue';
import { BOOKMARKLET_CODE } from './utils/bookmarklet';
import ScoreSummary from './components/ScoreSummary.vue';
import ScoreDashboard from './components/ScoreDashboard.vue';
import ProfileDashboard from './components/ProfileDashboard.vue';
import LoginModal from './components/LoginModal.vue';
import ProfileEditModal from './components/ProfileEditModal.vue';
import UploadHistory from './components/UploadHistory.vue';
import Changelog from './components/Changelog.vue';
import UploadResultModal from './components/UploadResultModal.vue';
import RankingList from './components/RankingList.vue';
import AdminUserListModal from './components/AdminUserListModal.vue';
import AdminSongRanksView from './components/AdminSongRanksView.vue';
import Sidebar from './components/Sidebar.vue';
import Terms from './components/Terms.vue';
import About from './components/About.vue';
import ArenaView from './views/ArenaView.vue';
import TierVotingView from './views/TierVotingView.vue';
import ArcadeAssistView from './views/ArcadeView.vue';
import SongAverageView from './views/SongAverageView.vue';
import DifficultyTableView from './views/DifficultyTableView.vue';
import ScorePredictionView from './views/ScorePredictionView.vue';
import SkillTreeView from './views/SkillTreeView.vue';
import ChartListView from './views/ChartListView.vue';
import RankComparisonView from './views/RankComparisonView.vue';
import Friends from './components/Friends.vue';
import NotificationBox from './components/NotificationBox.vue';
import OnboardingModal from './components/OnboardingModal.vue';
import { parseScoreCsv } from './utils/csvParser';
import type { ScoreData } from './types/ScoreData';
import { flattenScores, getSongMaxScore } from './utils/scoreData';
import type { UploadDiffResult, UpdatedSong, FolderAnnouncement } from './types/UploadDiff';
import { getRankInfo, getRateTierRankInfo, calculateTotalPoints, calculatePoints, calculateScoreRateTierPoints, getFolderRankInfoByRate } from './utils/beatTier';
import { diffTable as diffTableRanksRef } from './composables/useGameData';
import { useAuth } from './composables/useAuth';
import { useScoreUpload } from './composables/useScoreUpload';
import { useAppUpdate } from './composables/useAppUpdate';
import { useScores } from './composables/useScores';
import { useDarkMode } from './composables/useDarkMode';
import { useFriends } from './composables/useFriends';
import { useI18n } from './composables/useI18n';
import { useGameData } from './composables/useGameData';
import { useAprilFools } from './composables/useAprilFools';
import AprilFoolsOverlay from './components/AprilFoolsOverlay.vue';
import { watch, watchEffect, onMounted } from 'vue';

const { t } = useI18n();
const { isAprilFools } = useAprilFools();

// Toggle af-mode class on <html> element for global CSS overrides
watchEffect(() => {
  if (isAprilFools.value) {
    document.documentElement.classList.add('af-mode');
  } else {
    document.documentElement.classList.remove('af-mode');
  }
});

// Fetch game data from API on initialization
const { fetchGameData } = useGameData();
fetchGameData();

const isResetPasswordPage = ref(window.location.pathname === '/reset-password');

const { hasUpdate } = useAppUpdate();
const reloadPage = () => window.location.reload();

const scoreData = ref<ScoreData[]>([]);
const isParsing = ref(false);
const errorMsg = ref('');
const activeTab = ref<'dashboard' | 'table' | 'profile' | 'history' | 'ranking' | 'changelog' | 'terms' | 'about' | 'friends' | 'admin-song-ranks' | 'arena' | 'tier-voting' | 'arcade-assist' | 'song-avg' | 'diff-table' | 'score-prediction' | 'skill-tree' | 'chart-list' | 'rank-comparison'>('dashboard')
const viewingMode = ref<'admin' | 'friend' | 'public' | 'topRanker' | 'private' | null>(null);
const viewingTopRanker = ref<{ versionNum: number; versionName: string; prefectureFileNum: number; prefectureName: string } | null>(null);
const totalBeatTierPoints = ref(0);
const privateRateTierPoints = ref<number | null>(null);

const diffResult = ref<UploadDiffResult | null>(null);
const isDiffModalOpen = ref(false);
const isLoginModalOpen = ref(false);
const isProfileModalOpen = ref(false);
const isAdminModalOpen = ref(false);
const isOnboardingOpen = ref(false);

const viewingUserId = ref<number | null>(null);
const viewingUserName = ref<string>('');
const viewingUserIidxId = ref<string>('');
const isSidebarOpen = ref(false);

const { user, isLoggedIn, logout, isLoading: authLoading, authHeaders } = useAuth();
const { upload, saveHistoryLog } = useScoreUpload();
const { fetchMyScores, fetchUserScores, fetchTopRankerProfile, isFetching } = useScores();
const { isDarkMode, toggleDarkMode } = useDarkMode();
const { pendingRequests, appUnreadCount, fetchPendingRequests, fetchAppNotifications, requestNotificationPermission, sendFriendRequest, fetchVirtualRivalStatus, addVirtualRival, removeVirtualRival } = useFriends();

const friendStatus = ref<'none' | 'friend' | 'requested' | 'incoming' | 'self' | null>(null);
const isFriendRequestModalOpen = ref(false);
const friendRequestMessage = ref('');
const friendRequestSending = ref(false);
const friendRequestError = ref('');

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

const openFriendRequestModal = () => {
  friendRequestMessage.value = '';
  friendRequestError.value = '';
  isFriendRequestModalOpen.value = true;
};

const virtualRivalRegistered = ref<boolean | null>(null);
const virtualRivalBusy = ref(false);

const refreshVirtualRivalStatus = async () => {
  virtualRivalRegistered.value = null;
  if (!isLoggedIn.value) return;
  const area = viewingTopRanker.value;
  if (!area) return;
  virtualRivalRegistered.value = await fetchVirtualRivalStatus(area.versionNum, area.prefectureFileNum);
};

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

const isNotificationOpen = ref(false);
const deferredPrompt = ref<any>(null);

// Ko-fi support modal
const showKofiModal = ref(false);
const kofiCopied = ref(false);

const handleKofiClick = () => {
  if (user.value?.supporterToken) {
    showKofiModal.value = true;
  } else {
    window.open('https://ko-fi.com/beat_seeker', '_blank');
  }
};

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
const showInstallBanner = ref(false);
const pendingImportOpen = ref(false);
const pendingFragmentData = ref<string | null>(null);

/** Decode bookmarklet data from URL fragment */
const decodeFragmentData = (): string | null => {
  const hash = window.location.hash;
  if (!hash.startsWith('#data=')) return null;
  try {
    const b64 = hash.slice(6); // strip '#data='
    return decodeURIComponent(escape(atob(b64)));
  } catch (e) {
    console.warn('Failed to decode fragment data:', e);
    return null;
  }
};

/** Process bookmarklet JSON data: handle ARENA import + score CSV */
const processBookmarkletData = async (jsonText: string) => {
  try {
    const parsed = JSON.parse(jsonText);
    if (!parsed || parsed.type !== 'beat-seeker-combined') return;

    // Handle ARENA battles
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

    // Handle score CSV
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

const API_BASE = import.meta.env.VITE_API_BASE ?? 'http://localhost:8080';

onMounted(() => {
  window.addEventListener('beforeinstallprompt', (e) => {
    e.preventDefault();
    deferredPrompt.value = e;
    showInstallBanner.value = true;
  });

  // Request notification permission if logged in
  if (isLoggedIn.value) {
    requestNotificationPermission();
  }

  // URLパスに応じてタブを設定（直接アクセス・クローラー対応）
  const pathToTab: Record<string, typeof activeTab.value> = {
    '/about': 'about',
    '/terms': 'terms',
    '/ranking': 'ranking',
    '/changelog': 'changelog',
    '/difficulty-table': 'diff-table',
  };
  const currentPath = window.location.pathname;
  if (pathToTab[currentPath]) {
    activeTab.value = pathToTab[currentPath];
  }

  // ブックマークレットからのリダイレクト時：URLフラグメントからデータを自動取り込み
  const urlParams = new URLSearchParams(window.location.search);
  if (urlParams.get('import') === 'open') {
    const fragmentData = decodeFragmentData();
    window.history.replaceState({}, document.title, window.location.pathname);

    if (fragmentData) {
      // URLフラグメントにデータあり → 自動取り込み
      if (isLoggedIn.value) {
        processBookmarkletData(fragmentData);
      } else {
        pendingFragmentData.value = fragmentData;
        pendingImportOpen.value = true;
      }
    } else {
      // フラグメントなし（従来のクリップボード方式フォールバック）
      if (isLoggedIn.value) {
        showUploadArea.value = true;
      } else {
        pendingImportOpen.value = true;
      }
    }
  }
});


// Notification permission is handled by useFriends

const installApp = async () => {
  if (!deferredPrompt.value) return;
  deferredPrompt.value.prompt();
  const { outcome } = await deferredPrompt.value.userChoice;
  if (outcome === 'accepted') {
    deferredPrompt.value = null;
    showInstallBanner.value = false;
  }
};

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

const handleSelectUser = async (selectedUser: any) => {
  isAdminModalOpen.value = false;
  viewingUserId.value = selectedUser.id;
  viewingUserName.value = selectedUser.displayName || selectedUser.iidxId;
  viewingUserIidxId.value = selectedUser.iidxId || '';
  viewingMode.value = 'admin';
  await loadSavedScores();
};

const handleViewFriend = async (friend: { id: number; displayName: string; iidxId?: string }) => {
  viewingUserId.value = friend.id;
  viewingUserName.value = friend.displayName;
  viewingUserIidxId.value = friend.iidxId || '';
  viewingMode.value = 'friend';
  viewingTopRanker.value = null;
  activeTab.value = 'dashboard';
  await loadSavedScores();
};

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
        // URLフラグメントデータがある場合は自動取り込み
        const data = pendingFragmentData.value;
        pendingFragmentData.value = null;
        processBookmarkletData(data);
      } else {
        showUploadArea.value = true;
      }
    }

    // Check if we just logged in via Google OAuth redirect
    const urlParams = new URLSearchParams(window.location.search);
    if (urlParams.get('login') === 'success') {
      activeTab.value = 'dashboard';

      // Clean up the URL without reloading the page
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
 * Compare old vs new scores to generate folder-level announcements.
 * - 'rank_assigned': all songs in a folder are now played and a rank was assigned
 * - 'rank_up': folder rank improved
 * - 'remaining': at every 5-song boundary, announce how many songs remain
 */
const computeFolderAnnouncements = (
  oldFlat: ReturnType<typeof flattenScores>,
  newFlat: ReturnType<typeof flattenScores>
): FolderAnnouncement[] => {
  const announcements: FolderAnnouncement[] = [];
  const ranks = diffTableRanksRef.value;
  if (!ranks || !Array.isArray(ranks)) return announcements;

  // Build play-count maps per folder: count songs with score > 0
  const buildPlayCounts = (flat: ReturnType<typeof flattenScores>) => {
    const counts = new Map<string, number>();
    flat.forEach(s => {
      if (s.informalRank && s.score > 0) {
        counts.set(s.informalRank, (counts.get(s.informalRank) || 0) + 1);
      }
    });
    return counts;
  };

  // Build average score rate per folder (played songs only)
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

  // Total song count per folder from the difficulty table
  const folderTotals = new Map<string, number>();
  ranks.forEach((r: any) => {
    if (!r.rank.includes('Uncategorized')) {
      folderTotals.set(r.rank, r.songs.length);
    }
  });

  folderTotals.forEach((totalCount, folder) => {
    const oldPlayed = oldPlayCounts.get(folder) || 0;
    const newPlayed = newPlayCounts.get(folder) || 0;
    if (newPlayed <= oldPlayed) return; // no new plays in this folder

    const remaining = totalCount - newPlayed;
    const oldRemaining = totalCount - oldPlayed;

    // Check: all songs now played (rank assigned for the first time)
    if (remaining === 0 && oldRemaining > 0) {
      const newRate = newAvgRates.get(folder) || 0;
      const newRank = getFolderRankInfoByRate(newRate, folder);
      announcements.push({
        folder,
        type: 'rank_assigned',
        newRankName: newRank.name + (newRank.tier ? ' ' + newRank.tier : ''),
      });
      return; // rank_assigned takes priority
    }

    // Check: rank up (both old and new have all songs played)
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

    // Check: remaining songs milestone (every 5 songs)
    if (remaining > 0) {
      // Crossed a 5-song boundary? e.g. old=17 remaining, new=14 remaining → crossed 15
      const oldBucket = Math.floor(oldRemaining / 5);
      const newBucket = Math.floor(remaining / 5);
      if (newBucket < oldBucket || remaining <= 5) {
        // Only announce if remaining is a multiple of 5, or <= 5
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

  // Sort by folder descending
  announcements.sort((a, b) => parseFloat(b.folder) - parseFloat(a.folder));
  return announcements;
};

const handleFileDropped = async (file: File) => {
  errorMsg.value = '';
  isParsing.value = true;
  
  try {
    const newData = await parseScoreCsv(file);
    console.log(`Successfully parsed ${newData.length} songs.`);
    
    // Calculate Diff (compare with current scoreData)
    const oldFlat = flattenScores(scoreData.value);
    const newFlat = flattenScores(newData);
    
    const oldScoreMap = new Map();
    oldFlat.forEach(r => oldScoreMap.set(`${r.title}_${r.difficultyName}`, r));
    
    const updatedSongs: UpdatedSong[] = [];

    const sortedNewFlatDesc = newFlat.filter(s => s.beatTierPoints > 0).sort((a, b) => b.beatTierPoints - a.beatTierPoints);
    const top100SetGuest = new Set(sortedNewFlatDesc.slice(0, 100).map(s => `${s.title}_${s.difficultyName}`));

    const sortedByRatePtDescGuest = newFlat
      .filter(s => ['ANOTHER', 'LEGGENDARIA'].includes(s.difficultyName) && s.scoreRate > 0)
      .map(s => ({ key: `${s.title}_${s.difficultyName}`, pt: calculateScoreRateTierPoints(s.scoreRate) }))
      .filter(s => s.pt > 0)
      .sort((a, b) => b.pt - a.pt);
    const rateTop100SetGuest = new Set(sortedByRatePtDescGuest.slice(0, 100).map(s => s.key));

    newFlat.forEach(newR => {
        // Report on level 11 and 12 improvements primarily, but you can see all in table
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

        const isRateEligible = ['ANOTHER', 'LEGGENDARIA'].includes(newR.difficultyName);
        const newRatePt = (isRateEligible && newR.scoreRate > 0) ? calculateScoreRateTierPoints(newR.scoreRate) : 0;
        const oldRateScore = oldR ? oldR.scoreRate : 0;
        const oldRatePt = (isRateEligible && oldRateScore > 0) ? calculateScoreRateTierPoints(oldRateScore) : 0;
        const ratePtIncrease = Math.max(0, newRatePt - oldRatePt);

        // Only report if there is an actual improvement in score or lamp
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
                isInRateTop100: rateTop100SetGuest.has(`${newR.title}_${newR.difficultyName}`)
            });
        }
    });

    // Sort updated songs by beatPtIncrease descending, then scoreIncrease
    updatedSongs.sort((a, b) => {
        if (b.beatPtIncrease !== a.beatPtIncrease) return b.beatPtIncrease - a.beatPtIncrease;
        return b.scoreIncrease - a.scoreIncrease;
    });

    const oldTotalBeatPt = calculateTotalPoints(oldFlat);
    const newTotalBeatPt = calculateTotalPoints(newFlat);
    const oldTier = getRankInfo(oldTotalBeatPt);
    const newTier = getRankInfo(newTotalBeatPt);

    // Rate-Tier totals helper
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
      // PRO-UPGRADE: Use backend-provided diff for accuracy against DB
      isParsing.value = true; // Keep loading state
      try {
        const result = await upload(newData);
        console.log("Scores persisted to database.");
        
        // Map the backend diff to our UploadDiffResult format, adding beat points
        const backendUpdates = result.updatedSongs.map(s => {
          // We need original chart data to calculate Beat Points (maxScore, informalRank)
          const chartData = newFlat.find(nf => nf.title === s.title && nf.difficultyName === s.difficulty);
          const informalRank = chartData?.informalRank || (chartData?.difficultyLevel ? chartData.difficultyLevel.toFixed(1) : '12.0');
          const maxScore = chartData?.maxScore || (s.newScore > 0 ? s.newScore : 3000); // Fallback

          // Helper to get points
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
            beatPtIncrease: Math.max(0, newBeatPt - oldBeatPt)
          };
        });

        // Update local state by fetching ALL scores from the server to get an accurate total
        await loadSavedScores();
        const accurateTotalBeatPt = totalBeatTierPoints.value;

        // Determine top-100 set for isInTop100 and isInRateTop100 flags
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

        // Enrich backendUpdates with rate-tier and scoreRate fields
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

        // Fetch user's song ranks for the report (with timeout to avoid blocking upload flow)
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
        } catch { /* silent - timeout or network error, proceed without ranks */ }

        // Filter and sort for the report
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

        if (reportSongs.length > 0 || (oldFlat.length === 0 && newFlat.length > 0)) {
            isDiffModalOpen.value = true;
            // Save the history log only when there are actual score improvements
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
                    accurateTotalRatePt
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
        // フォールバック: クライアント側差分でモーダルを表示
        // (サーバー側でスコアが保存されていた場合に備えて history log も試みる)
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
        // Guest mode - stay with frontend calculation
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
        
        scoreData.value = newData;
        totalBeatTierPoints.value = newTotalBeatPt;
    }
    
    // Always hide upload area and return to dashboard view after successful parse
    showUploadArea.value = false;
    activeTab.value = 'dashboard';

  } catch (err: any) {
    console.error('Failed to parse or save CSV:', err);
    errorMsg.value = err.message || t('app.error.parseFailed');
  } finally {
    isParsing.value = false;
  }
};

const showUploadArea = ref(false);

const pendingScoreFile = ref<File | null>(null);

const resetData = () => {
  if (isLoggedIn.value) {
    // If logged in, we shouldn't clear the data, just show the upload area
    showUploadArea.value = true;
  } else {
    // If guest, clear it to start over
    scoreData.value = [];
    totalBeatTierPoints.value = 0;
  }
  errorMsg.value = '';
};

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
  <ResetPasswordView v-if="isResetPasswordPage" />
  <div v-else class="min-h-screen bg-slate-50 dark:bg-slate-900 transition-colors duration-200 flex flex-row overflow-hidden" :class="{ 'af-mode': isAprilFools }">
    <!-- April Fools Overlay -->
    <AprilFoolsOverlay />
    <!-- Sidebar Component -->
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
    />

    <!-- Modals -->
    <LoginModal :is-open="isLoginModalOpen" @close="isLoginModalOpen = false" @registered="isOnboardingOpen = true" />
    <OnboardingModal :is-open="isOnboardingOpen" :deferred-prompt="deferredPrompt" @close="isOnboardingOpen = false" />
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

    <div class="flex-1 flex flex-col h-screen overflow-x-hidden overflow-y-auto relative custom-scrollbar lg:ml-72">
      <!-- Header -->
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
            
            <div class="hidden lg:flex items-center gap-4 overflow-x-auto no-scrollbar ml-4 h-full flex-1">
              <button 
                @click="activeTab = 'dashboard'"
                class="flex items-center h-full px-3 border-b-2 transition-all font-bold text-sm tracking-wide shrink-0 whitespace-nowrap"
                :class="activeTab === 'dashboard' ? 'border-blue-600 text-blue-600' : 'border-transparent text-slate-500 hover:text-slate-800 dark:hover:text-slate-200'"
              >
                {{ t('nav.dashboard') }}
              </button>
              <button 
                @click="activeTab = 'table'"
                class="flex items-center h-full px-3 border-b-2 transition-all font-bold text-sm tracking-wide shrink-0 whitespace-nowrap"
                :class="activeTab === 'table' ? 'border-blue-600 text-blue-600' : 'border-transparent text-slate-500 hover:text-slate-800 dark:hover:text-slate-200'"
              >
                {{ t('nav.scoreList') }}
              </button>
              <button
                v-if="!viewingUserId"
                @click="activeTab = 'ranking'"
                class="flex items-center h-full px-3 border-b-2 transition-all font-bold text-sm tracking-wide shrink-0 whitespace-nowrap"
                :class="activeTab === 'ranking' ? 'border-blue-600 text-blue-600' : 'border-transparent text-slate-500 hover:text-slate-800 dark:hover:text-slate-200'"
              >
                {{ t('nav.ranking') }}
              </button>
              <button 
                v-if="isLoggedIn && (!viewingUserId || viewingMode === 'admin')"
                @click="activeTab = 'history'"
                class="flex items-center h-full px-3 border-b-2 transition-all font-bold text-sm tracking-wide shrink-0 whitespace-nowrap"
                :class="activeTab === 'history' ? 'border-blue-600 text-blue-600' : 'border-transparent text-slate-500 hover:text-slate-800 dark:hover:text-slate-200'"
              >
                {{ t('nav.history') }}
              </button>
              <button 
                v-if="isLoggedIn && (!viewingUserId || viewingMode === 'admin')"
                @click="activeTab = 'profile'"
                class="flex items-center h-full px-3 border-b-2 transition-all font-bold text-sm tracking-wide shrink-0 whitespace-nowrap"
                :class="activeTab === 'profile' ? 'border-blue-600 text-blue-600' : 'border-transparent text-slate-500 hover:text-slate-800 dark:hover:text-slate-200'"
              >
                {{ t('nav.profile') }}
              </button>
              
              <button 
                v-if="isLoggedIn && (!viewingUserId || viewingMode === 'admin')"
                @click="activeTab = 'arena'"
                class="flex items-center h-full px-3 border-b-2 transition-all font-bold text-sm tracking-wide shrink-0 whitespace-nowrap"
                :class="activeTab === 'arena' ? 'border-blue-600 text-blue-600' : 'border-transparent text-slate-500 hover:text-slate-800 dark:hover:text-slate-200'"
              >
                {{ t('nav.arena') }}
              </button>

              <button
                v-if="isLoggedIn && !viewingUserId"
                @click="activeTab = 'arcade-assist'"
                class="flex items-center h-full px-3 border-b-2 transition-all font-bold text-sm tracking-wide shrink-0 whitespace-nowrap"
                :class="activeTab === 'arcade-assist' ? 'border-violet-600 text-violet-600' : 'border-transparent text-slate-500 hover:text-slate-800 dark:hover:text-slate-200'"
              >
                {{ t('nav.arcadeAssist') }}
              </button>

              <button
                v-if="!viewingUserId"
                @click="activeTab = 'tier-voting'"
                class="flex items-center h-full px-3 border-b-2 transition-all font-bold text-sm tracking-wide shrink-0 whitespace-nowrap"
                :class="activeTab === 'tier-voting' ? 'border-blue-600 text-blue-600' : 'border-transparent text-slate-500 hover:text-slate-800 dark:hover:text-slate-200'"
              >
                {{ t('nav.tierVoting') }}
              </button>

              <button
                v-if="!viewingUserId"
                @click="activeTab = 'song-avg'"
                class="flex items-center h-full px-3 border-b-2 transition-all font-bold text-sm tracking-wide shrink-0 whitespace-nowrap"
                :class="activeTab === 'song-avg' ? 'border-indigo-600 text-indigo-600' : 'border-transparent text-slate-500 hover:text-slate-800 dark:hover:text-slate-200'"
              >
                {{ t('nav.songAvg') }}
              </button>

              <button
                v-if="!viewingUserId"
                @click="activeTab = 'diff-table'"
                class="flex items-center h-full px-3 border-b-2 transition-all font-bold text-sm tracking-wide shrink-0 whitespace-nowrap"
                :class="activeTab === 'diff-table' ? 'border-blue-600 text-blue-600' : 'border-transparent text-slate-500 hover:text-slate-800 dark:hover:text-slate-200'"
              >
                {{ t('nav.diffTable') }}
              </button>

              <button
                v-if="!viewingUserId && user && [18, 23, 24].includes(user.id)"
                @click="activeTab = 'rank-comparison'"
                class="flex items-center h-full px-3 border-b-2 transition-all font-bold text-sm tracking-wide shrink-0 whitespace-nowrap"
                :class="activeTab === 'rank-comparison' ? 'border-teal-600 text-teal-600' : 'border-transparent text-slate-500 hover:text-slate-800 dark:hover:text-slate-200'"
              >
                {{ t('nav.rankComparison') }}
              </button>

              <!-- Special Titles for non-tab pages -->
              <span v-if="['changelog', 'terms', 'about'].includes(activeTab)" class="ml-4 px-3 py-1 bg-slate-100 dark:bg-slate-700 rounded text-xs font-bold text-slate-600 dark:text-slate-300 shrink-0 capitalize">
                {{ t(`nav.${activeTab}`) }}
              </span>
            </div>
          </div>
          
          <div class="flex items-center gap-4">
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

      <!-- Main Content -->
      <main class="flex-1 w-full mx-auto px-4 sm:px-6 lg:px-8 py-8 sm:py-12">
        <!-- Mobile Navigation Tabs (Body Portion) -->
        <nav class="lg:hidden sticky top-16 z-20 bg-slate-50/95 dark:bg-slate-900/95 backdrop-blur-sm border-b border-slate-200 dark:border-slate-700 -mx-4 px-4 mb-8 flex items-center gap-1 overflow-x-auto no-scrollbar">
          <button 
            @click="activeTab = 'dashboard'"
            class="py-3 px-3 border-b-2 transition-all font-bold text-sm whitespace-nowrap"
            :class="activeTab === 'dashboard' ? 'border-blue-600 text-blue-600' : 'border-transparent text-slate-500'"
          >
            {{ t('nav.dashboard') }}
          </button>
          <button 
            @click="activeTab = 'table'"
            class="py-3 px-3 border-b-2 transition-all font-bold text-sm whitespace-nowrap"
            :class="activeTab === 'table' ? 'border-blue-600 text-blue-600' : 'border-transparent text-slate-500'"
          >
            {{ t('nav.scoreList') }}
          </button>
          <button
            v-if="!viewingUserId"
            @click="activeTab = 'ranking'"
            class="py-3 px-3 border-b-2 transition-all font-bold text-sm whitespace-nowrap"
            :class="activeTab === 'ranking' ? 'border-blue-600 text-blue-600' : 'border-transparent text-slate-500'"
          >
            {{ t('nav.ranking') }}
          </button>
          <button 
            v-if="isLoggedIn && (!viewingUserId || viewingMode === 'admin')"
            @click="activeTab = 'history'"
            class="py-3 px-3 border-b-2 transition-all font-bold text-sm whitespace-nowrap"
            :class="activeTab === 'history' ? 'border-blue-600 text-blue-600' : 'border-transparent text-slate-500'"
          >
            {{ t('nav.history') }}
          </button>
          <button
            v-if="isLoggedIn && (!viewingUserId || viewingMode === 'admin')"
            @click="activeTab = 'profile'"
            class="py-3 px-3 border-b-2 transition-all font-bold text-sm whitespace-nowrap"
            :class="activeTab === 'profile' ? 'border-blue-600 text-blue-600' : 'border-transparent text-slate-500'"
          >
            {{ t('nav.profile') }}
          </button>
          <button
            v-if="isLoggedIn && (!viewingUserId || viewingMode === 'admin')"
            @click="activeTab = 'arena'"
            class="py-3 px-3 border-b-2 transition-all font-bold text-sm whitespace-nowrap"
            :class="activeTab === 'arena' ? 'border-blue-600 text-blue-600' : 'border-transparent text-slate-500'"
          >
            {{ t('nav.arena') }}
          </button>
          <button
            v-if="isLoggedIn && !viewingUserId"
            @click="activeTab = 'arcade-assist'"
            class="py-3 px-3 border-b-2 transition-all font-bold text-sm whitespace-nowrap"
            :class="activeTab === 'arcade-assist' ? 'border-violet-600 text-violet-600' : 'border-transparent text-slate-500'"
          >
            {{ t('nav.arcadeAssist') }}
          </button>
          <button
            v-if="!viewingUserId"
            @click="activeTab = 'tier-voting'"
            class="py-3 px-3 border-b-2 transition-all font-bold text-sm whitespace-nowrap"
            :class="activeTab === 'tier-voting' ? 'border-blue-600 text-blue-600' : 'border-transparent text-slate-500'"
          >
            {{ t('nav.tierVoting') }}
          </button>
          <button
            v-if="!viewingUserId"
            @click="activeTab = 'song-avg'"
            class="py-3 px-3 border-b-2 transition-all font-bold text-sm whitespace-nowrap"
            :class="activeTab === 'song-avg' ? 'border-indigo-600 text-indigo-600' : 'border-transparent text-slate-500'"
          >
            {{ t('nav.songAvg') }}
          </button>
          <button
            v-if="!viewingUserId"
            @click="activeTab = 'diff-table'"
            class="py-3 px-3 border-b-2 transition-all font-bold text-sm whitespace-nowrap"
            :class="activeTab === 'diff-table' ? 'border-blue-600 text-blue-600' : 'border-transparent text-slate-500'"
          >
            {{ t('nav.diffTable') }}
          </button>
          <button
            v-if="!viewingUserId && user && [18, 23, 24].includes(user.id)"
            @click="activeTab = 'rank-comparison'"
            class="py-3 px-3 border-b-2 transition-all font-bold text-sm whitespace-nowrap"
            :class="activeTab === 'rank-comparison' ? 'border-teal-600 text-teal-600' : 'border-transparent text-slate-500'"
          >
            {{ t('nav.rankComparison') }}
          </button>
        </nav>
        <!-- Admin Viewing Banner -->
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

        <!-- Friend Request Modal -->
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
        
        <!-- Import Modal -->
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

      <!-- Main Views -->
        <template v-if="activeTab === 'changelog'">
          <Changelog class="w-full max-w-5xl mx-auto animate-fade-in" />
        </template>

        <template v-else-if="activeTab === 'ranking'">
          <RankingList
            class="w-full max-w-5xl mx-auto animate-fade-in"
            @view-user="handleViewPublicUser"
            @view-private-user="handleViewPrivateUser"
            @view-top-ranker="handleViewTopRanker"
          />
        </template>

        <template v-else-if="activeTab === 'admin-song-ranks'">
          <AdminSongRanksView class="w-full max-w-5xl mx-auto" />
        </template>
        
        <template v-else-if="activeTab === 'terms'">
          <Terms class="w-full max-w-5xl mx-auto animate-fade-in" />
        </template>
        
        <template v-else-if="activeTab === 'about'">
          <About class="w-full max-w-5xl mx-auto animate-fade-in" />
        </template>

        <template v-else-if="activeTab === 'arena'">
          <ArenaView class="w-full max-w-5xl mx-auto animate-fade-in" :viewing-user-id="viewingUserId" />
        </template>

        <template v-else-if="activeTab === 'tier-voting'">
          <TierVotingView class="w-full max-w-5xl mx-auto animate-fade-in" />
        </template>

        <template v-else-if="activeTab === 'arcade-assist'">
          <ArcadeAssistView class="w-full max-w-lg mx-auto animate-fade-in" />
        </template>

        <template v-else-if="activeTab === 'song-avg'">
          <SongAverageView class="w-full max-w-7xl mx-auto animate-fade-in" />
        </template>

        <template v-else-if="activeTab === 'diff-table'">
          <DifficultyTableView class="w-full max-w-5xl mx-auto animate-fade-in" />
        </template>

        <template v-else-if="activeTab === 'rank-comparison'">
          <RankComparisonView class="w-full max-w-6xl mx-auto animate-fade-in" />
        </template>

        <template v-else-if="activeTab === 'score-prediction'">
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
          <ScorePredictionView
            v-else
            class="w-full max-w-6xl mx-auto animate-fade-in"
            :viewing-user-id="viewingUserId"
            :viewing-mode="viewingMode"
          />
        </template>

        <template v-else-if="activeTab === 'profile'">
          <ProfileDashboard
            class="w-full max-w-6xl"
            :viewing-user-id="viewingUserId"
          />
        </template>

        <template v-else-if="activeTab === 'history'">
          <UploadHistory
            class="w-full max-w-6xl animate-fade-in"
            :viewing-user-id="viewingUserId"
          />
        </template>

        <template v-else-if="activeTab === 'friends'">
          <Friends
            class="w-full max-w-6xl animate-fade-in"
            @view-user="handleViewFriend"
            @view-top-ranker="handleViewTopRanker"
          />
        </template>

        <template v-else>
          <!-- Hero Section (Visible only when no data) -->
          <div v-if="!scoreData.length && viewingMode !== 'private'" class="text-center mb-12 max-w-2xl mx-auto animate-fade-in">
            <h1 class="text-4xl font-extrabold text-slate-900 dark:text-white tracking-tight sm:text-5xl mb-4">
              {{ t('app.hero.title') }}
            </h1>
            <p class="text-lg text-slate-600 dark:text-slate-400 leading-relaxed">
              {{ t('app.hero.subtitle') }}
            </p>

            <!-- PWA Install Banner -->
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

          <!-- Loading State -->
          <div v-if="isParsing || isFetching || authLoading" class="w-full max-w-3xl mx-auto animate-fade-in flex flex-col items-center">
            <div class="w-full flex flex-col items-center justify-center p-12 bg-white dark:bg-slate-800 rounded-2xl shadow-sm border border-slate-200 dark:border-slate-700">
              <div class="w-10 h-10 border-4 border-blue-200 dark:border-blue-900 border-t-blue-600 dark:border-t-blue-400 rounded-full animate-spin mb-4"></div>
              <p class="text-slate-600 dark:text-slate-300 font-medium tracking-wide">{{ t('app.loading.data') }}</p>
            </div>
          </div>

          <!-- Empty State (no data yet) -->
          <div v-else-if="!scoreData.length && viewingMode !== 'private'" class="w-full max-w-3xl mx-auto animate-fade-in flex flex-col items-center">
            <CsvDropzone @file-dropped="handleFileDropped" class="w-full" />
            <!-- Error Message -->
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

          <!-- Score Results View -->
          <div v-if="scoreData.length > 0 || viewingMode === 'private'" class="w-full flex flex-col items-center animate-fade-in">
            <!-- Dashboard Tab -->
            <div v-show="activeTab === 'dashboard'" class="w-full max-w-6xl flex flex-col items-center">
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

            <!-- Table Tab -->
            <ScoreSummary
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

      <!-- Footer -->
      <footer class="bg-white dark:bg-slate-800 border-t border-slate-200 dark:border-slate-700 py-8 transition-colors duration-200 shrink-0">
        <div class="max-w-7xl mx-auto px-4 sm:px-6 lg:px-8 flex flex-col sm:flex-row items-center justify-between gap-4">
          <p class="text-sm text-slate-500 dark:text-slate-400">
            © 2026 beat-seeker.
          </p>
          <div class="flex items-center gap-4 flex-wrap justify-center">
            <button @click="activeTab = 'about'" class="text-sm font-medium text-slate-500 dark:text-slate-400 hover:text-slate-800 dark:hover:text-slate-200 transition-colors">{{ t('app.footer.desc') }}</button>
            <button @click="activeTab = 'terms'" class="text-sm font-medium text-slate-500 dark:text-slate-400 hover:text-slate-800 dark:hover:text-slate-200 transition-colors">{{ t('nav.terms') }}</button>
            <button v-if="!viewingUserId" @click="activeTab = 'ranking'" class="text-sm font-medium text-slate-500 dark:text-slate-400 hover:text-slate-800 dark:hover:text-slate-200 transition-colors">{{ t('nav.ranking') }}</button>
          </div>
        </div>
      </footer>
    </div>
  <!-- Update banner -->
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
  <!-- Ko-fi Confirmation Modal -->
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
  </div>
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
