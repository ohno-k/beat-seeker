import type { ScoreData } from '../types/ScoreData';
import songDataRaw from '../data/song_data.json';
import diffTableRaw from '../data/difficulty_table.json';
import sp11Raw from '../data/sp11.json';
import sp12Raw from '../data/sp12.json';
import { calculatePoints, getWeight } from './beatTier';

/**
 * Normalize song titles for consistent matching across different data sources.
 * Handles full-width/half-width variations and case sensitivity.
 */
function normalizeTitle(title: string): string {
    if (!title) return '';
    return title
        .normalize('NFKC')
        .replace(/[“”〝〞]/g, '"') // Normalize various double quotes
        .replace(/[‘’'｀´]/g, "'")   // Normalize various single quotes
        .toLowerCase()
        .trim();
}

export interface ScoreRecord {
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

export function flattenScores(scores: ScoreData[]): ScoreRecord[] {
    const records: ScoreRecord[] = [];

    // Index song definitions for faster lookup
    const songDict = new Map<string, any>();
    if (songDataRaw && Array.isArray(songDataRaw.body)) {
        songDataRaw.body.forEach(s => {
            const normalizedTitle = normalizeTitle(s.title);
            songDict.set(`${normalizedTitle}_${s.difficulty}`, s);
        });
    }

    // Index newer SP11/SP12 data for more accurate notes counts
    const notesDict = new Map<string, number>();
    const processRawData = (data: any) => {
        if (Array.isArray(data)) {
            data.forEach(s => {
                if (s.title && s.notes) {
                    notesDict.set(normalizeTitle(s.title), s.notes);
                }
            });
        }
    };
    processRawData(sp11Raw);
    processRawData(sp12Raw);

    // Index informal difficulty table
    const informalDict = new Map<string, string>();
    if (diffTableRaw && Array.isArray((diffTableRaw as any).ranks)) {
        (diffTableRaw as any).ranks.forEach((r: any) => {
            r.songs.forEach((songTitle: string) => {
                informalDict.set(normalizeTitle(songTitle), r.rank);
            });
        });
    }

    scores.forEach(song => {
        difficulties.forEach(diff => {
            const stats = song[diff as keyof ScoreData] as any;
            if (stats && stats.clearType !== 'NO PLAY' && stats.clearType !== '---') {

                let scoreRate = -1;
                let maxScore = 0;

                const normalizedTitle = normalizeTitle(song.title);

                // Exact match via title and internal difficulty code
                const defKey = `${normalizedTitle}_${spIidxDiffMap[diff]}`;
                const definition = songDict.get(defKey);

                // Prioritize sp11/sp12 data for notes count if available
                const notes = notesDict.get(normalizedTitle) || (definition ? definition.notes : 0);

                if (notes > 0) {
                    maxScore = notes * 2;
                    if (maxScore > 0) {
                        scoreRate = (stats.score / maxScore) * 100;
                    }
                }

                const diffLabel = difficultyLabels[diff];
                const isHyperNonTarget = diffLabel === 'HYPER' && stats.difficulty >= 11;

                // Get informal rank
                const informalRank = informalDict.get(normalizedTitle) || undefined;

                const beatTierPoints = isHyperNonTarget ? 0 : calculatePoints(scoreRate, informalRank);

                records.push({
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
                    maxBeatTierPoints: getWeight(informalRank)
                });
            }
        });
    });

    return records;
}
