import type { RankInfo } from '../utils/beatTier';

export interface UpdatedSong {
    title: string;
    difficulty: string;
    oldScore: number;
    newScore: number;
    scoreIncrease: number;
    oldClearType: string;
    newClearType: string;
    clearTypeImproved: boolean;
    oldBeatPt: number;
    newBeatPt: number;
    beatPtIncrease: number;
    isInTop100?: boolean;
}

export interface UploadDiffResult {
    oldTotalBeatPt: number;
    newTotalBeatPt: number;
    totalBeatPtIncrease: number;
    oldTier: RankInfo | null;
    newTier: RankInfo | null;
    updatedSongs: UpdatedSong[];
}
