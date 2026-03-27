import { ref, readonly } from 'vue';

/**
 * Global reactive store for song data and difficulty table.
 * Fetched from the backend API on app initialization.
 * Replaces static JSON imports.
 */

export interface SongDataEntry {
  title: string;
  artist: string;
  genre: string;
  notes: number;
  bpm: string;
  difficulty: string;
  level: number;
  wr?: number;
  avg?: number;
  textage?: string;
  coef?: number;
  difficultyLevel?: string;
  dpLevel?: string;
}

export interface DifficultyRankEntry {
  rank: string;
  songs: string[];
}

interface SongDataRoot {
  version: number;
  requireVersion: string;
  body: SongDataEntry[];
}

interface DifficultyTableRoot {
  ranks: DifficultyRankEntry[];
}

const API_BASE = import.meta.env.VITE_API_BASE ?? 'http://localhost:8080';

// ── Global reactive state ────────────────────────────────
const songDataBody = ref<SongDataEntry[]>([]);
const diffTableRanks = ref<DifficultyRankEntry[]>([]);
const isLoaded = ref(false);
const isLoading = ref(false);
const loadError = ref('');

// ── Fallback: import static JSON for initial load / offline ──
import songDataFallback from '../data/song_data.json';
import diffTableFallback from '../data/difficulty_table.json';

function applyFallback() {
  if (songDataFallback && Array.isArray(songDataFallback.body)) {
    songDataBody.value = songDataFallback.body as SongDataEntry[];
  }
  if (diffTableFallback && Array.isArray(diffTableFallback.ranks)) {
    diffTableRanks.value = diffTableFallback.ranks as DifficultyRankEntry[];
  }
}

// Initialize with fallback immediately so components have data before API fetch
applyFallback();

export function useGameData() {
  const fetchGameData = async () => {
    if (isLoading.value) return;
    isLoading.value = true;
    loadError.value = '';

    try {
      const [songRes, diffRes] = await Promise.all([
        fetch(`${API_BASE}/api/game-data/songs`),
        fetch(`${API_BASE}/api/game-data/difficulty-table`),
      ]);

      if (songRes.ok) {
        const songJson: SongDataRoot = await songRes.json();
        if (songJson.body && Array.isArray(songJson.body)) {
          songDataBody.value = songJson.body;
        }
      }

      if (diffRes.ok) {
        const diffJson: DifficultyTableRoot = await diffRes.json();
        if (diffJson.ranks && Array.isArray(diffJson.ranks)) {
          diffTableRanks.value = diffJson.ranks;
        }
      }

      isLoaded.value = true;
    } catch (e: any) {
      console.warn('Failed to fetch game data from API, using fallback:', e.message);
      loadError.value = e.message;
      // Keep fallback data
    } finally {
      isLoading.value = false;
    }
  };

  return {
    songDataBody: readonly(songDataBody),
    diffTableRanks: readonly(diffTableRanks),
    isLoaded: readonly(isLoaded),
    isLoading: readonly(isLoading),
    loadError: readonly(loadError),
    fetchGameData,
  };
}

// ── Direct exports for non-composable usage (e.g. utility functions) ──
export const songData = songDataBody;
export const diffTable = diffTableRanks;
