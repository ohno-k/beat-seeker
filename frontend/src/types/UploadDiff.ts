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
    /** 非公式難易度ランク（例: '12.0'）。レポート上で単曲ティアを表示するために保持。 */
    informalRank?: string;
    /**
     * 歴代自己ベスト（過去作を含めたベストスコア）を今回のプレーで塗り替えたか。
     *
     * 「これまでのベストが過去作のもので、今回それを超えた」場合にだけ true になる。
     * 元々現行作がベストだった譜面の単なる自己ベスト更新では立たない。
     * 過去作 CSV を取り込んでいないユーザーでは常に undefined。
     */
    allTimeBestUpdated?: boolean;
    /** 上記が true のとき、それまで歴代ベストを保持していた作品のバージョン番号。 */
    allTimeBeatenVersion?: number;
    /**
     * 元々現行作のスコアが歴代自己ベストだった譜面を、今回さらに更新したか。
     *
     * 「過去作にもスコアがあり、既に現行作がそれ以上（同値含む）だった」かつ
     * 「今回 EX スコアが伸びた」場合に true になる。{@link allTimeBestUpdated} とは排他で、
     * こちらは *歴代ベストを維持したまま伸ばした* 弱い達成として、控えめに強調表示する。
     * 過去作にスコアが無い譜面（新曲・過去作未プレー）や、過去作 CSV 未取り込みのユーザーでは立たない。
     */
    allTimeBestExtended?: boolean;
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
