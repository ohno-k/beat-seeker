import type { ScoreData } from '../types/ScoreData';
import { useAuth } from './useAuth';

const API_BASE = import.meta.env.VITE_API_BASE ?? 'http://localhost:8080';

// Flatten ScoreData (one per song) into individual chart records for API
function flattenToUploadRecords(scores: ScoreData[]) {
    const difficulties = ['beginner', 'normal', 'hyper', 'another', 'leggendaria'] as const;
    const difficultyLabels: Record<string, string> = {
        beginner: 'BEGINNER',
        normal: 'NORMAL',
        hyper: 'HYPER',
        another: 'ANOTHER',
        leggendaria: 'LEGGENDARIA',
    };

    const records: object[] = [];

    scores.forEach(song => {
        difficulties.forEach(diff => {
            const stats = song[diff as keyof ScoreData] as any;
            if (!stats || stats.clearType === 'NO PLAY' || stats.clearType === '---') return;

            records.push({
                title: song.title,
                artist: song.artist,
                genre: song.genre,
                difficultyName: difficultyLabels[diff],
                difficultyLevel: stats.difficulty,
                score: stats.score,
                clearType: stats.clearType,
                djLevel: stats.djLevel,
                pgreat: stats.pgreat,
                great: stats.great,
                missCount: stats.missCount,
                playCount: song.playCount,
            });
        });
    });

    return records;
}

export function useScoreUpload() {
    const { authHeaders } = useAuth();

    const upload = async (scores: ScoreData[]): Promise<{ updatedCount: number; updatedSongs: any[]; message: string }> => {
        const records = flattenToUploadRecords(scores);
        const res = await fetch(`${API_BASE}/api/scores/upload`, {
            method: 'POST',
            headers: authHeaders({ 'Content-Type': 'application/json' }),
            body: JSON.stringify(records),
        });

        if (!res.ok) {
            throw new Error(`Upload failed: ${res.status}`);
        }

        return res.json();
    };

    return { upload };
}
