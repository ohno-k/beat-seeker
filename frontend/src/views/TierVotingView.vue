<script setup lang="ts">
import { ref, computed, onMounted, watch } from 'vue';
import { useGameData } from '../composables/useGameData';
import { useAuth } from '../composables/useAuth';
import { useI18n } from '../composables/useI18n';
import { API_BASE } from '../composables/useAuth';

const { t } = useI18n();
const { diffTableRanks } = useGameData();
const { isLoggedIn, authHeaders } = useAuth();

// Tier select options: 11.0 ~ 13.0 in 0.1 steps
const TIER_OPTIONS: string[] = [];
for (let i = 110; i <= 130; i++) {
  TIER_OPTIONS.push((i / 10).toFixed(1));
}

// Vote counts map: "title|difficultyName" -> { voteType: count, ... }
const allVotes = ref<Map<string, Record<string, number>>>(new Map());
// User's own votes: "title|difficultyName" -> vote string
const myVotes = ref<Map<string, string>>(new Map());

const isLoadingVotes = ref(false);
const searchQuery = ref('');

const fetchAllVotes = async () => {
  isLoadingVotes.value = true;
  try {
    const res = await fetch(`${API_BASE}/api/tier-votes/all`);
    if (res.ok) {
      const data: Array<Record<string, any>> = await res.json();
      const map = new Map<string, Record<string, number>>();
      for (const item of data) {
        const { title, difficultyName, ...rest } = item;
        const counts: Record<string, number> = {};
        for (const [k, v] of Object.entries(rest)) {
          counts[k] = Number(v) || 0;
        }
        map.set(`${title}|${difficultyName}`, counts);
      }
      allVotes.value = map;
    }
  } finally {
    isLoadingVotes.value = false;
  }
};

const fetchMyVotes = async () => {
  if (!isLoggedIn.value) return;
  try {
    const res = await fetch(`${API_BASE}/api/tier-votes/mine`, {
      headers: authHeaders(),
    });
    if (res.ok) {
      const data: Array<{ title: string; difficultyName: string; vote: string }> = await res.json();
      const map = new Map<string, string>();
      for (const item of data) {
        map.set(`${item.title}|${item.difficultyName}`, item.vote);
      }
      myVotes.value = map;
    }
  } catch {
    // ignore
  }
};

onMounted(async () => {
  await fetchAllVotes();
  await fetchMyVotes();
});

watch(isLoggedIn, (val) => {
  if (val) fetchMyVotes();
  else myVotes.value = new Map();
});

// Parse song entry: "[L]" suffix = LEGGENDARIA
const parseSong = (songTitle: string): { title: string; difficultyName: 'ANOTHER' | 'LEGGENDARIA' } => {
  if (songTitle.endsWith('[L]')) {
    return { title: songTitle.slice(0, -3), difficultyName: 'LEGGENDARIA' };
  }
  return { title: songTitle, difficultyName: 'ANOTHER' };
};

const isUncategorized = (rankName: string) => rankName.toLowerCase().includes('uncategorized');

const getVotes = (title: string, difficultyName: string): Record<string, number> => {
  return allVotes.value.get(`${title}|${difficultyName}`) ?? {};
};

const getMyVote = (title: string, difficultyName: string): string | null => {
  return myVotes.value.get(`${title}|${difficultyName}`) ?? null;
};

// Returns the top-voted tier for uncategorized songs: { tier, count } | null
const getTopTier = (title: string, difficultyName: string): { tier: string; count: number } | null => {
  const counts = getVotes(title, difficultyName);
  let best: { tier: string; count: number } | null = null;
  for (const [k, v] of Object.entries(counts)) {
    if (TIER_OPTIONS.includes(k) && v > 0) {
      if (!best || v > best.count) best = { tier: k, count: v };
    }
  }
  return best;
};

const getTotalTierVotes = (title: string, difficultyName: string): number => {
  const counts = getVotes(title, difficultyName);
  return TIER_OPTIONS.reduce((sum, t) => sum + (counts[t] ?? 0), 0);
};

// Cast or toggle a PROMOTE/STAY/DEMOTE vote
const castVote = async (title: string, difficultyName: string, voteType: string) => {
  if (!isLoggedIn.value) return;
  const key = `${title}|${difficultyName}`;
  const currentVote = myVotes.value.get(key) ?? null;
  const counts = { ...(allVotes.value.get(key) ?? {}) };

  if (currentVote === voteType) {
    const res = await fetch(
      `${API_BASE}/api/tier-votes?title=${encodeURIComponent(title)}&difficultyName=${encodeURIComponent(difficultyName)}`,
      { method: 'DELETE', headers: authHeaders() }
    );
    if (res.ok) {
      counts[currentVote] = Math.max(0, (counts[currentVote] ?? 0) - 1);
      allVotes.value.set(key, counts);
      myVotes.value.delete(key);
    }
  } else {
    const res = await fetch(`${API_BASE}/api/tier-votes`, {
      method: 'POST',
      headers: authHeaders({ 'Content-Type': 'application/json' }),
      body: JSON.stringify({ title, difficultyName, vote: voteType }),
    });
    if (res.ok) {
      if (currentVote) counts[currentVote] = Math.max(0, (counts[currentVote] ?? 0) - 1);
      counts[voteType] = (counts[voteType] ?? 0) + 1;
      allVotes.value.set(key, counts);
      myVotes.value.set(key, voteType);
    }
  }
};

// Cast a tier placement vote for uncategorized songs ('' = delete)
const castTierVote = async (title: string, difficultyName: string, tier: string) => {
  if (!isLoggedIn.value) return;
  const key = `${title}|${difficultyName}`;
  const currentVote = myVotes.value.get(key) ?? null;
  const counts = { ...(allVotes.value.get(key) ?? {}) };

  if (!tier || tier === currentVote) {
    // Delete vote
    const res = await fetch(
      `${API_BASE}/api/tier-votes?title=${encodeURIComponent(title)}&difficultyName=${encodeURIComponent(difficultyName)}`,
      { method: 'DELETE', headers: authHeaders() }
    );
    if (res.ok) {
      if (currentVote) counts[currentVote] = Math.max(0, (counts[currentVote] ?? 0) - 1);
      allVotes.value.set(key, counts);
      myVotes.value.delete(key);
    }
  } else {
    const res = await fetch(`${API_BASE}/api/tier-votes`, {
      method: 'POST',
      headers: authHeaders({ 'Content-Type': 'application/json' }),
      body: JSON.stringify({ title, difficultyName, vote: tier }),
    });
    if (res.ok) {
      if (currentVote) counts[currentVote] = Math.max(0, (counts[currentVote] ?? 0) - 1);
      counts[tier] = (counts[tier] ?? 0) + 1;
      allVotes.value.set(key, counts);
      myVotes.value.set(key, tier);
    }
  }
};

const filteredRanks = computed(() => {
  if (!searchQuery.value.trim()) return diffTableRanks.value;
  const q = searchQuery.value.trim().toLowerCase();
  return diffTableRanks.value
    .map(rank => ({
      ...rank,
      songs: rank.songs.filter(s => s.toLowerCase().includes(q)),
    }))
    .filter(rank => rank.songs.length > 0);
});

const totalVotedCount = computed(() => myVotes.value.size);
</script>

<template>
  <div class="w-full">
    <!-- Page Header -->
    <div class="mb-6">
      <h1 class="text-2xl font-extrabold text-slate-900 dark:text-white mb-1">
        {{ t('nav.tierVoting') }}
      </h1>
      <p class="text-sm text-slate-500 dark:text-slate-400">
        {{ t('tierVoting.subtitle') }}
      </p>
    </div>

    <!-- Criteria -->
    <div class="mb-5 p-4 bg-slate-50 dark:bg-slate-800/60 border border-slate-200 dark:border-slate-700 rounded-xl">
      <p class="text-xs font-black text-slate-500 dark:text-slate-400 uppercase tracking-widest mb-2">{{ t('tierVoting.criteriaTitle') }}</p>
      <ul class="space-y-1 text-sm text-slate-700 dark:text-slate-300">
        <li class="flex items-start gap-2">
          <span class="mt-0.5 shrink-0 text-slate-400">•</span>
          {{ t('tierVoting.criteria1') }}
        </li>
        <li class="flex items-start gap-2">
          <span class="mt-0.5 shrink-0 text-slate-400">•</span>
          {{ t('tierVoting.criteria2') }}
        </li>
      </ul>
    </div>

    <!-- Legend (ranked songs only) -->
    <div class="mb-4 flex flex-wrap gap-2 text-xs font-bold">
      <span class="flex items-center gap-1 px-2.5 py-1 bg-green-100 dark:bg-green-900/30 text-green-700 dark:text-green-400 rounded-lg">
        ↑ {{ t('tierVoting.promote') }}
      </span>
      <span class="flex items-center gap-1 px-2.5 py-1 bg-blue-100 dark:bg-blue-900/30 text-blue-700 dark:text-blue-400 rounded-lg">
        → {{ t('tierVoting.stay') }}
      </span>
      <span class="flex items-center gap-1 px-2.5 py-1 bg-red-100 dark:bg-red-900/30 text-red-700 dark:text-red-400 rounded-lg">
        ↓ {{ t('tierVoting.demote') }}
      </span>
    </div>

    <!-- Login hint -->
    <div v-if="!isLoggedIn" class="mb-5 p-3 bg-amber-50 dark:bg-amber-900/20 border border-amber-200 dark:border-amber-700 rounded-xl text-sm text-amber-700 dark:text-amber-400">
      {{ t('tierVoting.loginHint') }}
    </div>

    <!-- Voted count -->
    <div v-if="isLoggedIn && totalVotedCount > 0" class="mb-5 p-3 bg-blue-50 dark:bg-blue-900/20 border border-blue-200 dark:border-blue-700 rounded-xl text-sm text-blue-700 dark:text-blue-400">
      {{ t('tierVoting.votedCount', { n: totalVotedCount }) }}
    </div>

    <!-- Search -->
    <div class="mb-6">
      <input
        v-model="searchQuery"
        type="text"
        :placeholder="t('tierVoting.searchPlaceholder')"
        class="w-full max-w-sm px-4 py-2 rounded-xl border border-slate-200 dark:border-slate-600 bg-white dark:bg-slate-800 text-slate-900 dark:text-white text-sm focus:outline-none focus:ring-2 focus:ring-blue-500"
      />
    </div>

    <!-- Loading -->
    <div v-if="isLoadingVotes" class="flex justify-center py-16">
      <div class="w-8 h-8 border-4 border-blue-200 dark:border-blue-900 border-t-blue-600 dark:border-t-blue-400 rounded-full animate-spin"></div>
    </div>

    <!-- Rank List -->
    <div v-else class="space-y-6">
      <div
        v-for="rank in filteredRanks"
        :key="rank.rank"
        class="bg-white dark:bg-slate-800 rounded-2xl border border-slate-200 dark:border-slate-700 shadow-sm overflow-hidden"
      >
        <!-- Rank Header -->
        <div class="px-5 py-3 bg-slate-50 dark:bg-slate-700/50 border-b border-slate-200 dark:border-slate-600 flex items-center gap-3">
          <span class="text-base font-black text-slate-800 dark:text-white tracking-tight">{{ rank.rank }}</span>
          <span class="text-xs text-slate-400 dark:text-slate-500">{{ rank.songs.length }}{{ t('tierVoting.songs') }}</span>
          <span v-if="isUncategorized(rank.rank)" class="text-[10px] px-2 py-0.5 bg-slate-200 dark:bg-slate-600 text-slate-500 dark:text-slate-300 rounded font-bold uppercase tracking-wide">
            {{ t('tierVoting.selectTierHint') }}
          </span>
        </div>

        <!-- Song Rows -->
        <div class="divide-y divide-slate-100 dark:divide-slate-700/60">
          <div
            v-for="songEntry in rank.songs"
            :key="songEntry"
            class="px-4 py-3 flex flex-col sm:flex-row sm:items-center gap-2 sm:gap-4"
          >
            <!-- Song Info -->
            <div class="flex items-center gap-2 flex-1 min-w-0">
              <span
                class="shrink-0 text-[10px] px-1.5 py-0.5 rounded font-bold"
                :class="parseSong(songEntry).difficultyName === 'LEGGENDARIA'
                  ? 'bg-purple-100 dark:bg-purple-900/30 text-purple-700 dark:text-purple-300'
                  : 'bg-orange-100 dark:bg-orange-900/30 text-orange-700 dark:text-orange-300'"
              >
                {{ parseSong(songEntry).difficultyName === 'LEGGENDARIA' ? 'LEG' : 'ANO' }}
              </span>
              <span class="font-semibold text-slate-900 dark:text-white text-sm truncate">
                {{ parseSong(songEntry).title }}
              </span>
            </div>

            <!-- Uncategorized: Tier Select -->
            <template v-if="isUncategorized(rank.rank)">
              <div class="flex items-center gap-3 shrink-0">
                <select
                  :value="getMyVote(parseSong(songEntry).title, parseSong(songEntry).difficultyName) ?? ''"
                  :disabled="!isLoggedIn"
                  @change="castTierVote(parseSong(songEntry).title, parseSong(songEntry).difficultyName, ($event.target as HTMLSelectElement).value)"
                  class="px-2.5 py-1.5 rounded-lg text-xs font-bold border transition-all focus:outline-none focus:ring-2 focus:ring-blue-500"
                  :class="getMyVote(parseSong(songEntry).title, parseSong(songEntry).difficultyName)
                    ? 'bg-blue-500 text-white border-blue-500'
                    : 'bg-white dark:bg-slate-800 text-slate-600 dark:text-slate-300 border-slate-200 dark:border-slate-600',
                    !isLoggedIn ? 'opacity-40 cursor-not-allowed' : 'cursor-pointer'"
                >
                  <option value="">{{ t('tierVoting.noVote') }}</option>
                  <option v-for="tier in TIER_OPTIONS" :key="tier" :value="tier">{{ tier }}</option>
                </select>
                <!-- Top voted tier display -->
                <span v-if="getTopTier(parseSong(songEntry).title, parseSong(songEntry).difficultyName)" class="text-xs text-slate-500 dark:text-slate-400 whitespace-nowrap">
                  {{ t('tierVoting.topVoted') }}: <span class="font-black text-slate-700 dark:text-slate-200">{{ getTopTier(parseSong(songEntry).title, parseSong(songEntry).difficultyName)!.tier }}</span>
                  <span class="text-slate-400"> ({{ getTotalTierVotes(parseSong(songEntry).title, parseSong(songEntry).difficultyName) }})</span>
                </span>
              </div>
            </template>

            <!-- Ranked: PROMOTE / STAY / DEMOTE buttons -->
            <template v-else>
              <div class="flex items-center gap-1.5 shrink-0">
                <!-- PROMOTE -->
                <button
                  @click="castVote(parseSong(songEntry).title, parseSong(songEntry).difficultyName, 'PROMOTE')"
                  :disabled="!isLoggedIn"
                  :title="t('tierVoting.promote')"
                  class="flex items-center gap-1 px-2.5 py-1.5 rounded-lg text-xs font-bold transition-all border"
                  :class="getMyVote(parseSong(songEntry).title, parseSong(songEntry).difficultyName) === 'PROMOTE'
                    ? 'bg-green-500 text-white border-green-500 shadow-sm'
                    : 'bg-white dark:bg-slate-800 text-slate-500 dark:text-slate-400 border-slate-200 dark:border-slate-600 hover:border-green-400 hover:text-green-600',
                    !isLoggedIn ? 'opacity-40 cursor-not-allowed' : 'cursor-pointer'"
                >
                  <span>↑</span>
                  <span class="font-black">{{ getVotes(parseSong(songEntry).title, parseSong(songEntry).difficultyName)['PROMOTE'] ?? 0 }}</span>
                </button>
                <!-- STAY -->
                <button
                  @click="castVote(parseSong(songEntry).title, parseSong(songEntry).difficultyName, 'STAY')"
                  :disabled="!isLoggedIn"
                  :title="t('tierVoting.stay')"
                  class="flex items-center gap-1 px-2.5 py-1.5 rounded-lg text-xs font-bold transition-all border"
                  :class="getMyVote(parseSong(songEntry).title, parseSong(songEntry).difficultyName) === 'STAY'
                    ? 'bg-blue-500 text-white border-blue-500 shadow-sm'
                    : 'bg-white dark:bg-slate-800 text-slate-500 dark:text-slate-400 border-slate-200 dark:border-slate-600 hover:border-blue-400 hover:text-blue-600',
                    !isLoggedIn ? 'opacity-40 cursor-not-allowed' : 'cursor-pointer'"
                >
                  <span>→</span>
                  <span class="font-black">{{ getVotes(parseSong(songEntry).title, parseSong(songEntry).difficultyName)['STAY'] ?? 0 }}</span>
                </button>
                <!-- DEMOTE -->
                <button
                  @click="castVote(parseSong(songEntry).title, parseSong(songEntry).difficultyName, 'DEMOTE')"
                  :disabled="!isLoggedIn"
                  :title="t('tierVoting.demote')"
                  class="flex items-center gap-1 px-2.5 py-1.5 rounded-lg text-xs font-bold transition-all border"
                  :class="getMyVote(parseSong(songEntry).title, parseSong(songEntry).difficultyName) === 'DEMOTE'
                    ? 'bg-red-500 text-white border-red-500 shadow-sm'
                    : 'bg-white dark:bg-slate-800 text-slate-500 dark:text-slate-400 border-slate-200 dark:border-slate-600 hover:border-red-400 hover:text-red-600',
                    !isLoggedIn ? 'opacity-40 cursor-not-allowed' : 'cursor-pointer'"
                >
                  <span>↓</span>
                  <span class="font-black">{{ getVotes(parseSong(songEntry).title, parseSong(songEntry).difficultyName)['DEMOTE'] ?? 0 }}</span>
                </button>
              </div>
            </template>
          </div>
        </div>
      </div>

      <!-- Empty search result -->
      <div v-if="filteredRanks.length === 0" class="text-center py-16 text-slate-400 dark:text-slate-500 text-sm">
        {{ t('tierVoting.noResults') }}
      </div>
    </div>
  </div>
</template>
