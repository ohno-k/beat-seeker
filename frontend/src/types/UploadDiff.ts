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
}
