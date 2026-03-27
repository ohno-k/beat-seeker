import type { ScoreData } from '../types/ScoreData';
import { songData as songDataBody, diffTable as diffTableRanks } from '../composables/useGameData';
import { calculatePoints, getMaxPoints } from './beatTier';

export interface ScoreRecord {
    id?: number;
    title: string;
    artist: string;
    genre: string;
    difficultyName: string;
    difficultyColor: string;
    difficultyLevel: number | null;
    clearType: string;
    score: number;
    scoreRate: number; // calculated percentage
    maxScore: number;
    informalRank: string | undefined;
    djLevel: string;
    pgreat: number;
    great: number;
    missCount: number | null;
    playCount: number;
    lastPlayTime: string;
    beatTierPoints: number;
    maxBeatTierPoints: number;
    memo?: string;
}

const difficulties = ['beginner', 'normal', 'hyper', 'another', 'leggendaria'] as const;
const difficultyLabels: Record<string, string> = {
    beginner: 'BEGINNER',
    normal: 'NORMAL',
    hyper: 'HYPER',
    another: 'ANOTHER',
    leggendaria: 'LEGGENDARIA'
};

const spIidxDiffMap: Record<string, string> = {
    beginner: '1',
    normal: '2',
    hyper: '3',
    another: '4',
    leggendaria: '10'
};

const diffColors: Record<string, string> = {
    beginner: 'text-emerald-700 bg-emerald-100 border border-emerald-300',
    normal: 'text-blue-700 bg-blue-100 border border-blue-300',
    hyper: 'text-amber-700 bg-amber-100 border border-amber-300',
    another: 'text-red-700 bg-red-100 border border-red-300',
    leggendaria: 'text-purple-700 bg-purple-100 border border-purple-300'
};

// Map from uppercase difficulty label to difficulty code
const diffLabelToCode: Record<string, string> = {
    BEGINNER: '1', NORMAL: '2', HYPER: '3', ANOTHER: '4', LEGGENDARIA: '10'
};

/** Helper to build a song dictionary from current reactive data */
function buildSongDict(): Map<string, any> {
    const dict = new Map<string, any>();
    const body = songDataBody.value;
    if (body && Array.isArray(body)) {
        body.forEach((s: any) => {
            dict.set(`${s.title}_${s.difficulty}`, s);
        });
    }
    return dict;
}

/** Helper to build an informal rank dictionary from current reactive data */
function buildInformalDict(): Map<string, string> {
    const dict = new Map<string, string>();
    const ranks = diffTableRanks.value;
    if (ranks && Array.isArray(ranks)) {
        ranks.forEach((r: any) => {
            r.songs.forEach((songTitle: string) => {
                if (songTitle.endsWith('[L]')) {
                    const baseTitle = songTitle.slice(0, -3);
                    dict.set(`${baseTitle}_LEGGENDARIA`, r.rank);
                } else {
                    dict.set(`${songTitle}_ANOTHER`, r.rank);
                }
            });
        });
    }
    return dict;
}

/**
 * Look up maxScore (= notes * 2) from song data given a title and
 * the uppercase difficulty label (e.g. "ANOTHER", "LEGGENDARIA").
 * Returns 0 when the song/chart is not found.
 */
export function getSongMaxScore(title: string, difficultyName: string): number {
    const code = diffLabelToCode[difficultyName];
    if (!code) return 0;
    const songDict = buildSongDict();
    const definition = songDict.get(`${title}_${code}`);
    return definition?.notes ? definition.notes * 2 : 0;
}

export function flattenScores(scores: ScoreData[]): ScoreRecord[] {
    const records: ScoreRecord[] = [];

    const songDict = buildSongDict();
    const informalDict = buildInformalDict();

    scores.forEach(song => {
        difficulties.forEach(diff => {
            const stats = song[diff as keyof ScoreData] as any;
            if (stats && stats.clearType !== 'NO PLAY' && stats.clearType !== '---') {

                let scoreRate = -1;
                let maxScore = 0;

                // Exact match via title and internal difficulty code
                const defKey = `${song.title}_${spIidxDiffMap[diff]}`;
                const definition = songDict.get(defKey);

                if (definition && definition.notes) {
                    maxScore = definition.notes * 2;
                    if (maxScore > 0) {
                        scoreRate = (stats.score / maxScore) * 100;
                    }
                }

                const diffLabel = difficultyLabels[diff];
                const isHyperNonTarget = diffLabel === 'HYPER' && stats.difficulty >= 11;

                // Get informal rank
                const informalKey = `${song.title}_${diffLabel}`;
                let informalRank = informalDict.get(informalKey) || undefined;

                // Fallback for non-LEGGENDARIA songs in the table
                if (!informalRank && diffLabel === 'ANOTHER') {
                    informalRank = informalDict.get(`${song.title}_ANOTHER`) || undefined;
                }

                const beatTierPoints = isHyperNonTarget ? 0 : calculatePoints(scoreRate, informalRank);

                records.push({
                    id: stats.id,
                    title: song.title,
                    artist: song.artist,
                    genre: song.genre,
                    difficultyName: diffLabel,
                    difficultyColor: diffColors[diff],
                    difficultyLevel: stats.difficulty,
                    clearType: stats.clearType,
                    score: stats.score,
                    scoreRate: scoreRate,
                    maxScore: maxScore,
                    informalRank: informalRank,
                    djLevel: stats.djLevel,
                    pgreat: stats.pgreat,
                    great: stats.great,
                    missCount: stats.missCount,
                    playCount: song.playCount,
                    lastPlayTime: song.lastPlayTime,
                    beatTierPoints: beatTierPoints,
                    maxBeatTierPoints: getMaxPoints(informalRank),
                    memo: stats.memo
                });
            }
        });
    });

    return records;
}
