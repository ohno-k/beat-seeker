import type { FolderRankInfo } from '../utils/beatTier';

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
}

export interface UploadDiffResult {
    oldTotalBeatPt: number;
    newTotalBeatPt: number;
    totalBeatPtIncrease: number;
    oldTier: FolderRankInfo | null;
    newTier: FolderRankInfo | null;
    updatedSongs: UpdatedSong[];
}
