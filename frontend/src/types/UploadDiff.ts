import type { RankInfo } from '../utils/beatTier';

export interface FolderAnnouncement {
    folder: string; // e.g. "12.0"
    type: 'rank_assigned' | 'rank_up' | 'remaining';
    oldRankName?: string;
    newRankName?: string;
    remaining?: number; // songs remaining to complete the folder
}

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
    // Rate-Tier fields
    scoreRate: number;
    maxScore: number;
    newRatePt: number;
    ratePtIncrease: number;
    isInRateTop100?: boolean;
    songRank?: number;
    songRankTotal?: number;
}

export interface UploadDiffResult {
    oldTotalBeatPt: number;
    newTotalBeatPt: number;
    totalBeatPtIncrease: number;
    oldTier: RankInfo | null;
    newTier: RankInfo | null;
    updatedSongs: UpdatedSong[];
    // Rate-Tier totals
    oldTotalRatePt: number;
    newTotalRatePt: number;
    oldRateTier: RankInfo | null;
    newRateTier: RankInfo | null;
    folderAnnouncements?: FolderAnnouncement[];
}
