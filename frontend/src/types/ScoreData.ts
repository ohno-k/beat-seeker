export interface DifficultyStats {
    difficulty: number | null;
    score: number;
    pgreat: number;
    great: number;
    missCount: number | null;
    clearType: string;
    djLevel: string;
    id?: number;
    /** iidx-memo 等から同期された譜面オプション。読み取り専用。 */
    options?: string[];
    djName?: string;
}

export interface ScoreData {
    version: string;
    title: string;
    genre: string;
    artist: string;
    playCount: number;
    beginner: DifficultyStats;
    normal: DifficultyStats;
    hyper: DifficultyStats;
    another: DifficultyStats;
    leggendaria: DifficultyStats;
    lastPlayTime: string;
}
