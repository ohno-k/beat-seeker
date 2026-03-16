<script setup lang="ts">
import { ref, onMounted, computed } from 'vue';
import { useAuth } from '../composables/useAuth';
import UnifiedImport from '../components/UnifiedImport.vue';
import { BOOKMARKLET_CODE } from '../utils/bookmarklet';
import songDataRaw from '../data/song_data.json';

// Difficulty text (from bookmarklet) → song_data.json difficulty code
const DIFF_TEXT_TO_CODE: Record<string, string> = {
  BEGINNER: '1',
  NORMAL: '2',
  HYPER: '3',
  ANOTHER: '4',
  LEGGENDARIA: '10',
};

// Build lookup: "title|diffCode" → notes
const songNotesMap = new Map<string, number>();
for (const entry of (songDataRaw as any).body) {
  songNotesMap.set(`${entry.title}|${entry.difficulty}`, entry.notes);
}

const getSongMaxScore = (titleRaw: string, diffText: string): number => {
  let title = titleRaw;
  let diff = diffText;
  // Fallback: if difficulty is empty, extract from title (e.g. "Silver Bullet / ANOTHER")
  if (!diff) {
    const sep = title.lastIndexOf(' / ');
    if (sep !== -1) {
      diff = title.substring(sep + 3).trim();
      title = title.substring(0, sep).trim();
    }
  }
  const code = DIFF_TEXT_TO_CODE[diff];
  if (!code) return 0;
  const notes = songNotesMap.get(`${title}|${code}`);
  return notes ? notes * 2 : 0;
};

const { isLoggedIn } = useAuth();

const API_BASE = import.meta.env.VITE_API_BASE_URL || 'http://localhost:8080';

// --- Matches state ---
const matches = ref<any[]>([]);
const isFetching = ref(false);
const expandedMatchId = ref<number | null>(null);
const activeTab = ref<'all' | 'online' | 'local'>('all');
const mainView = ref<'history' | 'opponents'>('history');
const showImportPanel = ref(false);

// --- Filtered matches by tab ---
const filteredMatches = computed(() => {
  if (activeTab.value === 'online') return matches.value.filter(m => m.battleType?.includes('オンライン'));
  if (activeTab.value === 'local') return matches.value.filter(m => m.battleType?.includes('ローカル'));
  return matches.value;
});

// --- Stats (based on filtered matches) ---
const stats = computed(() => {
  const ranks = [0, 0, 0, 0];
  for (const m of filteredMatches.value) {
    if (m.myRank >= 1 && m.myRank <= 4) ranks[m.myRank - 1]++;
  }
  return { total: filteredMatches.value.length, ranks };
});

// --- Opponent stats (song-level comparison) ---
const expandedOpponent = ref<string | null>(null);

// Filter out CPU opponents and names written entirely in Japanese characters (NPCs)
const isHumanOpponent = (djName: string): boolean => {
  if (/^CPU\d+$/i.test(djName)) return false;
  if (/^[\u3040-\u309F\u30A0-\u30FF\u4E00-\u9FFF\u3400-\u4DBF\s]+$/.test(djName)) return false;
  return true;
};

const opponentStats = computed(() => {
  const map = new Map<string, {
    djName: string;
    matches: number;
    songWins: number;
    songLosses: number;
    songTotal: number;
  }>();

  for (const m of matches.value) {
    const myPlayer = (m.players as any[]).find((p: any) => p.djName === m.myDjName);
    for (const p of (m.players as any[])) {
      if (p.djName === m.myDjName) continue;
      if (!isHumanOpponent(p.djName)) continue;
      if (!map.has(p.djName)) {
        map.set(p.djName, { djName: p.djName, matches: 0, songWins: 0, songLosses: 0, songTotal: 0 });
      }
      const entry = map.get(p.djName)!;
      entry.matches++;
      if (myPlayer) {
        const mySongs: any[] = myPlayer.songScores || [];
        const theirSongs: any[] = p.songScores || [];
        const count = Math.min(mySongs.length, theirSongs.length);
        for (let si = 0; si < count; si++) {
          const myScore = mySongs[si]?.score ?? 0;
          const theirScore = theirSongs[si]?.score ?? 0;
          entry.songTotal++;
          if (myScore > theirScore) entry.songWins++;
          else if (myScore < theirScore) entry.songLosses++;
        }
      }
    }
  }

  return Array.from(map.values()).sort((a, b) => b.matches - a.matches);
});

const matchesWithOpponent = (djName: string) =>
  matches.value.filter((m: any) =>
    (m.players as any[]).some((p: any) => p.djName !== m.myDjName && p.djName === djName)
  );

const fetchMatches = async () => {
  if (!isLoggedIn.value) return;
  isFetching.value = true;
  try {
    const token = localStorage.getItem('beat-seeker-token');
    const res = await fetch(`${API_BASE}/api/arena/matches`, {
      headers: { Authorization: `Bearer ${token}` },
    });
    if (res.ok) {
      matches.value = await res.json();
    }
  } catch (e) {
    console.error(e);
  } finally {
    isFetching.value = false;
  }
};

onMounted(fetchMatches);

const handleDelete = async (id: number) => {
  if (!confirm('この対戦記録を削除しますか？')) return;
  const token = localStorage.getItem('beat-seeker-token');
  await fetch(`${API_BASE}/api/arena/matches/${id}`, {
    method: 'DELETE',
    headers: { Authorization: `Bearer ${token}` },
  });
  matches.value = matches.value.filter(m => m.id !== id);
};

const rankLabel = (rank: number) => ['1位', '2位', '3位', '4位'][rank - 1] ?? '-';
const rankColor = (rank: number) => {
  return [
    'text-yellow-600 dark:text-yellow-400 font-black',
    'text-slate-500 dark:text-slate-300 font-bold',
    'text-orange-600 dark:text-orange-400 font-bold',
    'text-slate-400 dark:text-slate-500',
  ][rank - 1] ?? '';
};

// Per-song: returns [topScore, secondBestScore]
const songTopTwo = (players: any[], si: number): [number, number | undefined] => {
  const scores = players.map((p: any) => p.songScores?.[si]?.score ?? 0).sort((a, b) => b - a);
  const top = scores[0] ?? 0;
  const second = scores.find(s => s < top);
  return [top, second];
};

const scoreColorClass = (score: number, top: number, second: number | undefined): string => {
  if (score === top && top > 0) return 'text-yellow-500 dark:text-yellow-400 font-black';
  if (second !== undefined && score === second) return 'text-slate-400 dark:text-slate-200 font-bold';
  return 'text-slate-600 dark:text-slate-400';
};

// Grade based on actual MAX score from song_data.json
// Thresholds: A+(66.67%), AA-(72.23%), AA+(77.78%), AAA-(83.34%), AAA+(88.89%), MAX-(94.45%)
const scoreGrade = (score: number, maxScore: number): string => {
  if (maxScore === 0 || score <= 0) return '';
  if (score >= maxScore) return 'MAX';
  const pct = score / maxScore;
  if (pct >= 0.9445) return 'MAX-';
  if (pct >= 8 / 9) return 'AAA+';
  if (pct >= 0.8334) return 'AAA-';
  if (pct >= 7 / 9) return 'AA+';
  if (pct >= 0.7223) return 'AA-';
  if (pct >= 6 / 9) return 'A+';
  return '';
};

const scoreGradeDelta = (score: number, maxScore: number): string => {
  if (maxScore === 0 || score <= 0) return '';
  const grade = scoreGrade(score, maxScore);
  if (grade === 'A+')   return `${score - Math.ceil(maxScore * 6 / 9)}`;
  if (grade === 'AA-')  return `${Math.ceil(maxScore * 7 / 9) - score}`;
  if (grade === 'AA+')  return `${score - Math.ceil(maxScore * 7 / 9)}`;
  if (grade === 'AAA-') return `${Math.ceil(maxScore * 8 / 9) - score}`;
  if (grade === 'AAA+') return `${score - Math.ceil(maxScore * 8 / 9)}`;
  if (grade === 'MAX-') return `${maxScore - score}`;
  return '';
};

const gradeColor = (grade: string): string => {
  if (grade === 'MAX') return 'text-yellow-500 dark:text-yellow-400';
  if (grade === 'MAX-') return 'text-purple-500 dark:text-purple-400';
  if (grade === 'AAA+') return 'text-amber-500 dark:text-amber-400';
  return '';
};

const classColor = (cls: string) => {
  const map: Record<string, string> = {
    A1: 'bg-yellow-100 text-yellow-800 dark:bg-yellow-900/40 dark:text-yellow-300',
    A2: 'bg-blue-100 text-blue-800 dark:bg-blue-900/40 dark:text-blue-300',
    A3: 'bg-green-100 text-green-800 dark:bg-green-900/40 dark:text-green-300',
    A4: 'bg-purple-100 text-purple-800 dark:bg-purple-900/40 dark:text-purple-300',
    A5: 'bg-red-100 text-red-800 dark:bg-red-900/40 dark:text-red-300',
  };
  return map[cls] ?? 'bg-slate-100 text-slate-700 dark:bg-slate-700 dark:text-slate-300';
};

</script>

<template>
  <div class="w-full max-w-5xl mx-auto space-y-8 animate-fade-in">
    <!-- Header -->
    <div>
      <h1 class="text-2xl font-extrabold text-slate-900 dark:text-white tracking-tight">ARENAモード戦績</h1>
      <p class="text-sm text-slate-500 dark:text-slate-400 mt-1">ブックマークレットで取得した対戦データを管理します。</p>
    </div>

    <!-- Not logged in -->
    <div v-if="!isLoggedIn" class="p-8 text-center bg-white dark:bg-slate-800 rounded-2xl border border-slate-200 dark:border-slate-700">
      <p class="text-slate-500 dark:text-slate-400">ログインすると対戦記録を保存できます。</p>
    </div>

    <template v-else>
      <!-- Stats Bar -->
      <div v-if="matches.length > 0" class="grid grid-cols-5 gap-3">
        <div class="bg-white dark:bg-slate-800 rounded-xl border border-slate-200 dark:border-slate-700 p-4 text-center">
          <p class="text-2xl font-black text-slate-800 dark:text-white">{{ stats.total }}</p>
          <p class="text-xs text-slate-500 dark:text-slate-400 mt-1">総対戦数</p>
        </div>
        <div v-for="(cnt, i) in stats.ranks" :key="i" class="bg-white dark:bg-slate-800 rounded-xl border border-slate-200 dark:border-slate-700 p-4 text-center">
          <p class="text-2xl font-black" :class="rankColor(i + 1)">{{ cnt }}</p>
          <p class="text-xs text-slate-500 dark:text-slate-400 mt-1">{{ rankLabel(i + 1) }}</p>
        </div>
      </div>

      <!-- Import Panel (collapsible) -->
      <div class="bg-white dark:bg-slate-800 rounded-2xl border border-slate-200 dark:border-slate-700 overflow-hidden">
        <!-- Toggle row -->
        <button
          @click="showImportPanel = !showImportPanel"
          class="w-full flex items-center justify-between px-5 py-3.5 hover:bg-slate-50 dark:hover:bg-slate-700/50 transition-colors"
        >
          <span class="flex items-center gap-2 text-sm font-bold text-slate-700 dark:text-slate-200">
            <svg xmlns="http://www.w3.org/2000/svg" class="h-4 w-4" fill="none" viewBox="0 0 24 24" stroke="currentColor">
              <path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M4 16v1a3 3 0 003 3h10a3 3 0 003-3v-1m-4-8l-4-4m0 0L8 8m4-4v12" />
            </svg>
            データを取り込む
          </span>
          <svg xmlns="http://www.w3.org/2000/svg" class="h-4 w-4 text-slate-400 transition-transform" :class="showImportPanel ? 'rotate-180' : ''" fill="none" viewBox="0 0 24 24" stroke="currentColor">
            <path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M19 9l-7 7-7-7" />
          </svg>
        </button>

        <!-- Expanded content -->
        <div v-if="showImportPanel" class="border-t border-slate-100 dark:border-slate-700 p-5">
          <UnifiedImport :bookmarklet-code="BOOKMARKLET_CODE" @close="showImportPanel = false; fetchMatches()" />
        </div>
      </div>

      <!-- Match History / Opponents -->
      <div class="space-y-3">
        <div class="flex items-center justify-between gap-2 flex-wrap">
          <!-- Main view toggle -->
          <div class="flex gap-1 bg-slate-100 dark:bg-slate-800 rounded-xl p-1 border border-slate-200 dark:border-slate-700">
            <button
              v-for="v in [{ id: 'history', label: '対戦履歴' }, { id: 'opponents', label: '対戦相手' }]"
              :key="v.id"
              @click="mainView = v.id as 'history' | 'opponents'"
              class="px-3 py-1 text-xs font-bold rounded-lg transition-colors"
              :class="mainView === v.id
                ? 'bg-white dark:bg-slate-600 text-slate-800 dark:text-white shadow-sm'
                : 'text-slate-500 dark:text-slate-400 hover:text-slate-700 dark:hover:text-slate-200'"
            >{{ v.label }}</button>
          </div>
          <!-- History sub-tabs (only in history view) -->
          <div v-if="mainView === 'history'" class="flex gap-1 bg-slate-100 dark:bg-slate-800 rounded-xl p-1 border border-slate-200 dark:border-slate-700">
            <button
              v-for="tab in [{ id: 'all', label: 'すべて' }, { id: 'online', label: 'オンライン' }, { id: 'local', label: 'ローカル' }]"
              :key="tab.id"
              @click="activeTab = tab.id as 'all' | 'online' | 'local'; expandedMatchId = null"
              class="px-3 py-1 text-xs font-bold rounded-lg transition-colors"
              :class="activeTab === tab.id
                ? 'bg-white dark:bg-slate-600 text-slate-800 dark:text-white shadow-sm'
                : 'text-slate-500 dark:text-slate-400 hover:text-slate-700 dark:hover:text-slate-200'"
            >{{ tab.label }}</button>
          </div>
        </div>

        <div v-if="isFetching" class="flex justify-center py-8">
          <div class="w-8 h-8 border-4 border-blue-200 dark:border-blue-900 border-t-blue-600 rounded-full animate-spin"></div>
        </div>

        <div v-else-if="matches.length === 0" class="text-center py-10 text-slate-400 dark:text-slate-500">
          対戦記録がありません。ブックマークレットでデータを取得してください。
        </div>

        <!-- Opponent Summary View -->
        <template v-else-if="mainView === 'opponents'">
          <div v-if="opponentStats.length === 0" class="text-center py-10 text-slate-400 dark:text-slate-500">
            対戦相手のデータがありません。
          </div>
          <div v-else class="bg-white dark:bg-slate-800 rounded-2xl border border-slate-200 dark:border-slate-700 overflow-hidden">
            <table class="w-full text-sm">
              <thead>
                <tr class="bg-slate-50 dark:bg-slate-900/50 border-b border-slate-100 dark:border-slate-700">
                  <th class="px-4 py-3 text-left font-bold text-slate-600 dark:text-slate-400 text-xs">DJ NAME</th>
                  <th class="px-3 py-3 text-center font-bold text-slate-600 dark:text-slate-400 text-xs">対戦</th>
                  <th class="px-3 py-3 text-center font-bold text-slate-600 dark:text-slate-400 text-xs">曲勝</th>
                  <th class="px-3 py-3 text-center font-bold text-slate-600 dark:text-slate-400 text-xs">曲負</th>
                  <th class="px-4 py-3 text-left font-bold text-slate-600 dark:text-slate-400 text-xs">曲勝率</th>
                </tr>
              </thead>
              <tbody>
                <template v-for="opp in opponentStats" :key="opp.djName">
                  <!-- Opponent row -->
                  <tr
                    class="border-t border-slate-100 dark:border-slate-700 cursor-pointer hover:bg-slate-50 dark:hover:bg-slate-700/30 transition-colors"
                    @click="expandedOpponent = expandedOpponent === opp.djName ? null : opp.djName"
                  >
                    <td class="px-4 py-2.5">
                      <div class="flex items-center gap-2">
                        <svg xmlns="http://www.w3.org/2000/svg" class="h-3 w-3 text-slate-400 transition-transform shrink-0" :class="expandedOpponent === opp.djName ? 'rotate-180' : ''" fill="none" viewBox="0 0 24 24" stroke="currentColor">
                          <path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M19 9l-7 7-7-7" />
                        </svg>
                        <span class="font-bold text-slate-800 dark:text-slate-100 text-sm">{{ opp.djName }}</span>
                      </div>
                    </td>
                    <td class="px-3 py-2.5 text-center text-sm font-mono font-bold text-slate-700 dark:text-slate-300">{{ opp.matches }}</td>
                    <td class="px-3 py-2.5 text-center text-sm font-bold text-blue-600 dark:text-blue-400">{{ opp.songWins }}</td>
                    <td class="px-3 py-2.5 text-center text-sm font-bold text-rose-500 dark:text-rose-400">{{ opp.songLosses }}</td>
                    <td class="px-4 py-2.5">
                      <div class="flex items-center gap-2 min-w-[80px]">
                        <div class="flex-1 h-1.5 bg-slate-100 dark:bg-slate-700 rounded-full overflow-hidden">
                          <div
                            class="h-full bg-blue-500 rounded-full"
                            :style="{ width: opp.songTotal > 0 ? `${Math.round(opp.songWins / opp.songTotal * 100)}%` : '0%' }"
                          ></div>
                        </div>
                        <span class="text-xs font-mono text-slate-500 dark:text-slate-400 w-8 text-right">
                          {{ opp.songTotal > 0 ? Math.round(opp.songWins / opp.songTotal * 100) : 0 }}%
                        </span>
                      </div>
                    </td>
                  </tr>
                  <!-- Expanded: per-match song breakdown -->
                  <tr v-if="expandedOpponent === opp.djName">
                    <td colspan="5" class="p-0 bg-slate-50 dark:bg-slate-900/40">
                      <div class="divide-y divide-slate-100 dark:divide-slate-700/50">
                        <div v-for="m in matchesWithOpponent(opp.djName)" :key="m.id" class="px-4 py-3">
                          <!-- Match header -->
                          <div class="flex items-center gap-2 mb-2 flex-wrap">
                            <span class="text-[10px] font-bold px-2 py-0.5 bg-slate-200 dark:bg-slate-700 text-slate-600 dark:text-slate-300 rounded-full">{{ m.battleType }}</span>
                            <span class="text-xs font-mono text-slate-500 dark:text-slate-400">{{ m.matchDate }}</span>
                            <span class="text-xs font-bold" :class="rankColor(m.myRank)">自: {{ rankLabel(m.myRank) }}</span>
                            <span class="text-[10px] text-slate-400">vs</span>
                            <span class="text-xs font-bold" :class="rankColor((m.players as any[]).find((p: any) => p.djName === opp.djName)?.rank)">
                              相: {{ rankLabel((m.players as any[]).find((p: any) => p.djName === opp.djName)?.rank) }}
                            </span>
                          </div>
                          <!-- Song table -->
                          <table class="w-full text-xs">
                            <tbody>
                              <template v-for="(song, si) in m.songs" :key="si">
                                <tr
                                  v-if="(m.players as any[]).find((p: any) => p.djName === m.myDjName)?.songScores?.[si] !== undefined"
                                  class="border-t border-slate-100 dark:border-slate-700/30"
                                >
                                  <td class="py-1.5 pr-3 max-w-[160px]">
                                    <span class="block truncate text-slate-700 dark:text-slate-300 font-medium">{{ song.title }}</span>
                                    <span v-if="song.difficulty" class="text-[10px] text-slate-400 dark:text-slate-500">{{ song.difficulty }}</span>
                                  </td>
                                  <td class="py-1.5 px-2 text-right font-mono w-20"
                                    :class="(((m.players as any[]).find((p: any) => p.djName === m.myDjName)?.songScores?.[si]?.score ?? 0) > ((m.players as any[]).find((p: any) => p.djName === opp.djName)?.songScores?.[si]?.score ?? 0)) ? 'text-blue-600 dark:text-blue-400 font-bold' : (((m.players as any[]).find((p: any) => p.djName === m.myDjName)?.songScores?.[si]?.score ?? 0) < ((m.players as any[]).find((p: any) => p.djName === opp.djName)?.songScores?.[si]?.score ?? 0)) ? 'text-rose-500 dark:text-rose-400' : 'text-slate-600 dark:text-slate-400'"
                                  >{{ ((m.players as any[]).find((p: any) => p.djName === m.myDjName)?.songScores?.[si]?.score ?? 0).toLocaleString() }}</td>
                                  <td class="py-1.5 px-1 text-center text-slate-400 dark:text-slate-500 w-4">vs</td>
                                  <td class="py-1.5 px-2 font-mono w-20"
                                    :class="(((m.players as any[]).find((p: any) => p.djName === opp.djName)?.songScores?.[si]?.score ?? 0) > ((m.players as any[]).find((p: any) => p.djName === m.myDjName)?.songScores?.[si]?.score ?? 0)) ? 'text-blue-600 dark:text-blue-400 font-bold' : (((m.players as any[]).find((p: any) => p.djName === opp.djName)?.songScores?.[si]?.score ?? 0) < ((m.players as any[]).find((p: any) => p.djName === m.myDjName)?.songScores?.[si]?.score ?? 0)) ? 'text-rose-500 dark:text-rose-400' : 'text-slate-600 dark:text-slate-400'"
                                  >{{ ((m.players as any[]).find((p: any) => p.djName === opp.djName)?.songScores?.[si]?.score ?? 0).toLocaleString() }}</td>
                                  <td class="py-1.5 pl-2 font-mono text-right w-14">
                                    <span :class="(((m.players as any[]).find((p: any) => p.djName === m.myDjName)?.songScores?.[si]?.score ?? 0) - ((m.players as any[]).find((p: any) => p.djName === opp.djName)?.songScores?.[si]?.score ?? 0)) > 0 ? 'text-blue-600 dark:text-blue-400' : (((m.players as any[]).find((p: any) => p.djName === m.myDjName)?.songScores?.[si]?.score ?? 0) - ((m.players as any[]).find((p: any) => p.djName === opp.djName)?.songScores?.[si]?.score ?? 0)) < 0 ? 'text-rose-500 dark:text-rose-400' : 'text-slate-400'">
                                      {{ (((m.players as any[]).find((p: any) => p.djName === m.myDjName)?.songScores?.[si]?.score ?? 0) - ((m.players as any[]).find((p: any) => p.djName === opp.djName)?.songScores?.[si]?.score ?? 0)) > 0 ? '+' : '' }}{{ ((m.players as any[]).find((p: any) => p.djName === m.myDjName)?.songScores?.[si]?.score ?? 0) - ((m.players as any[]).find((p: any) => p.djName === opp.djName)?.songScores?.[si]?.score ?? 0) }}
                                    </span>
                                  </td>
                                </tr>
                              </template>
                            </tbody>
                          </table>
                        </div>
                      </div>
                    </td>
                  </tr>
                </template>
              </tbody>
            </table>
          </div>
        </template>

        <!-- Match History View -->
        <template v-else>
        <div v-if="filteredMatches.length === 0" class="text-center py-10 text-slate-400 dark:text-slate-500">
          該当する対戦記録がありません。
        </div>

        <div
          v-for="match in filteredMatches"
          :key="match.id"
          class="bg-white dark:bg-slate-800 rounded-2xl border border-slate-200 dark:border-slate-700 overflow-hidden"
        >
          <!-- Match Header -->
          <div
            class="flex items-center justify-between p-4 cursor-pointer hover:bg-slate-50 dark:hover:bg-slate-700/50 transition-colors"
            @click="expandedMatchId = expandedMatchId === match.id ? null : match.id"
          >
            <div class="flex items-center gap-3 flex-wrap">
              <span class="text-xs font-bold px-2 py-0.5 bg-slate-100 dark:bg-slate-700 text-slate-600 dark:text-slate-300 rounded-full">
                {{ match.battleType }}
              </span>
              <span class="text-sm font-mono text-slate-500 dark:text-slate-400">{{ match.matchDate }}</span>
              <span class="text-xs font-bold px-2 py-0.5 rounded-full" :class="classColor(match.myArenaClass)">
                {{ match.myArenaClass }}
              </span>
              <span class="text-sm font-bold" :class="rankColor(match.myRank)">
                {{ rankLabel(match.myRank) }}
              </span>
              <span class="text-xs text-slate-400 dark:text-slate-500">{{ match.myTotalPt }}pt</span>
            </div>
            <div class="flex items-center gap-2">
              <button
                @click.stop="handleDelete(match.id)"
                class="p-1.5 text-slate-300 hover:text-red-500 dark:text-slate-600 dark:hover:text-red-400 transition-colors rounded-lg hover:bg-red-50 dark:hover:bg-red-900/20"
              >
                <svg xmlns="http://www.w3.org/2000/svg" class="h-4 w-4" fill="none" viewBox="0 0 24 24" stroke="currentColor">
                  <path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M19 7l-.867 12.142A2 2 0 0116.138 21H7.862a2 2 0 01-1.995-1.858L5 7m5 4v6m4-6v6m1-10V4a1 1 0 00-1-1h-4a1 1 0 00-1 1v3M4 7h16" />
                </svg>
              </button>
              <svg xmlns="http://www.w3.org/2000/svg" class="h-4 w-4 text-slate-400 transition-transform" :class="expandedMatchId === match.id ? 'rotate-180' : ''" fill="none" viewBox="0 0 24 24" stroke="currentColor">
                <path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M19 9l-7 7-7-7" />
              </svg>
            </div>
          </div>

          <!-- Match Detail -->
          <div v-if="expandedMatchId === match.id" class="border-t border-slate-100 dark:border-slate-700 overflow-x-auto">
            <table class="w-full text-sm min-w-max">
              <thead>
                <tr class="bg-slate-50 dark:bg-slate-900/50">
                  <th class="px-4 py-2 text-left font-bold text-slate-600 dark:text-slate-400 text-xs">DJ NAME</th>
                  <th class="px-3 py-2 text-center font-bold text-slate-600 dark:text-slate-400 text-xs">CLS</th>
                  <th class="px-3 py-2 text-center font-bold text-slate-600 dark:text-slate-400 text-xs">合計</th>
                  <th class="px-3 py-2 text-center font-bold text-slate-600 dark:text-slate-400 text-xs">順位</th>
                  <th
                    v-for="(song, si) in match.songs"
                    :key="si"
                    class="px-3 py-2 text-center font-bold text-slate-600 dark:text-slate-400 text-xs max-w-[120px]"
                  >
                    <span class="block truncate">{{ song.title }}</span>
                    <span class="block text-[10px] text-slate-400 dark:text-slate-500 font-normal">{{ song.difficulty }}</span>
                  </th>
                </tr>
              </thead>
              <tbody>
                <tr
                  v-for="(player, pi) in match.players"
                  :key="pi"
                  class="border-t border-slate-100 dark:border-slate-700/50 transition-colors"
                  :class="player.djName === match.myDjName ? 'bg-blue-50 dark:bg-blue-950/30' : 'hover:bg-slate-50 dark:hover:bg-slate-700/30'"
                >
                  <td class="px-4 py-2 font-bold text-slate-800 dark:text-white text-xs">
                    {{ player.djName }}
                    <span v-if="player.djName === match.myDjName" class="ml-1 text-[10px] font-black text-blue-600 dark:text-blue-400">YOU</span>
                  </td>
                  <td class="px-3 py-2 text-center">
                    <span class="text-[10px] font-bold px-1.5 py-0.5 rounded-full" :class="classColor(player.arenaClass)">{{ player.arenaClass }}</span>
                  </td>
                  <td class="px-3 py-2 text-center text-xs font-mono text-slate-700 dark:text-slate-300">{{ player.totalPt }}pt</td>
                  <td class="px-3 py-2 text-center text-xs font-bold" :class="rankColor(player.rank)">{{ rankLabel(player.rank) }}</td>
                  <td
                    v-for="(ss, si) in player.songScores"
                    :key="si"
                    class="px-3 py-2 text-center"
                  >
                    <!-- Score: gold for top, silver for 2nd -->
                    <span
                      class="block text-xs font-mono"
                      :class="scoreColorClass(ss.score, songTopTwo(match.players, si)[0], songTopTwo(match.players, si)[1])"
                    >{{ ss.score.toLocaleString() }}</span>
                    <!-- Diff from top (non-top scorers) -->
                    <span
                      v-if="ss.score < songTopTwo(match.players, si)[0]"
                      class="block text-[10px] font-mono text-rose-400 dark:text-rose-500"
                    >-{{ (songTopTwo(match.players, si)[0] - ss.score).toLocaleString() }}</span>
                    <!-- Grade badge based on song_data.json actual MAX -->
                    <span
                      v-if="match.songs[si] && scoreGrade(ss.score, getSongMaxScore(match.songs[si].title, match.songs[si].difficulty))"
                      class="block text-[10px] font-bold"
                      :class="gradeColor(scoreGrade(ss.score, getSongMaxScore(match.songs[si].title, match.songs[si].difficulty)))"
                    >{{ scoreGrade(ss.score, getSongMaxScore(match.songs[si].title, match.songs[si].difficulty)) }}{{ scoreGradeDelta(ss.score, getSongMaxScore(match.songs[si].title, match.songs[si].difficulty)) }}</span>
                  </td>
                </tr>
              </tbody>
            </table>
          </div>
        </div>
        </template>
      </div>
    </template>
  </div>
</template>
