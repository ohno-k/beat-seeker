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
    /**
     * このスコアの取得元。"arcade"（CSV）/ "infinitas"（画面取得）。
     * arcade と infinitas の両方がある場合、表示では EX SCORE が高い方の source が入る。
     * undefined は emptyDiff（未プレイプレースホルダ）の目印も兼ねる。
     */
    source?: string;
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
