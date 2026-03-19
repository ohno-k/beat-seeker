/**
 * Beat-Tier System Logic
 *
 * 1. Single Song Points = (ScoreRate / 100)^1.5 * Weight
 *    Weight: 11.0 -> 150, 11.1 -> 152, ..., 12.4 -> 178, 12.5 -> 182, ..., 13.0 -> 202
 * 2. Total Points = Sum of top 100 songs
 * 3. Rank = Based on Total Points (52 tiers + Beginner)
 *    Novice 1 (= entry point) starts at 10,000pt
 *    Note: Expert and above exceed current theoretical max (~18,464pt)
 */

export interface RankInfo {
    name: string;
    tier?: number; // 1-5
    minPoints: number;
    color: string;
}

export interface FolderRankInfo {
    name: string;
    tier: number;
    color: string;
    description: string;
}

// Weights configuration (can be easily adjusted)
export const WEIGHTS: Record<string, number> = {};
let weight = 145;
for (let i = 0; i <= 20; i++) {
    const rankValue = 11.0 + i * 0.1;
    const rank = rankValue.toFixed(1);
    WEIGHTS[rank] = weight;
    weight += (rankValue >= 12.49) ? 3 : 2; // Step becomes 3 starting from the jump to 12.5
}

/**
 * Get weight for a given informal rank
 */
export function getWeight(informalRank: string | undefined): number {
    if (!informalRank) return 0;

    // Attempt to extract numeric part (e.g., "12.0 (IIDX 32)" -> "12.0")
    const match = informalRank.match(/(\d+\.\d+)/);
    const key = match ? match[1] : informalRank;

    return WEIGHTS[key] || 0;
}

/**
 * Get maximum possible points for a given informal rank (includes AA/AAA/MAX- bonuses)
 */
export function getMaxPoints(informalRank: string | undefined): number {
    const weight = getWeight(informalRank);
    return weight * 1.03; // Max is basic weight + 3% (AA, AAA, MAX- bonuses)
}

/**
 * Get the Legend score rate threshold for a folder.
 * Power curve (t^1.7): ☆11.0 = 99.6%, ☆13.0 = 94.44%
 * t^1.7 keeps the rate flat near ☆11.0 and drops toward ☆13.0.
 */
export function getFolderLegendRate(informalRank: string | undefined): number {
    if (!informalRank) return 0;
    const match = informalRank.match(/(\d+\.\d+)/);
    const rankValue = match ? parseFloat(match[1]) : 0;
    if (rankValue < 11.0 || rankValue > 13.0) return 0;

    const LEGEND_RATE_LOW = 99.6;   // ☆11.0: almost all MAX- single digits
    const LEGEND_RATE_HIGH = 94.44; // ☆13.0: MAX- notation threshold
    const t = (rankValue - 11.0) / (13.0 - 11.0);
    return LEGEND_RATE_LOW - Math.pow(t, 1.7) * (LEGEND_RATE_LOW - LEGEND_RATE_HIGH);
}

/**
 * Get the per-song Legend point threshold for a folder.
 * Converts the Legend score rate to BEAT-PT using calculatePoints.
 */
export function getLegendPtPerSong(informalRank: string | undefined): number {
    const legendRate = getFolderLegendRate(informalRank);
    if (legendRate <= 0) return 0;
    return calculatePoints(legendRate, informalRank);
}

/**
 * Calculate points for a single song
 */
export function calculatePoints(scoreRate: number, informalRank: string | undefined): number {
    const weight = getWeight(informalRank);
    if (weight === 0 || scoreRate <= 66.666) return 0;

    let basePoints = Math.pow(scoreRate / 100, 1.3) * weight;

    let bonus = 0;
    if (scoreRate > 77.77) bonus += weight * 0.01; // AA bonus
    if (scoreRate > 88.88) bonus += weight * 0.01; // AAA bonus
    if (scoreRate > 94.44) bonus += weight * 0.01; // MAX- bonus

    return basePoints + bonus;
}

/**
 * Rank definitions (52 levels + Beginner)
 * Novice 1 starts at 10,000pt (= entry point for this tier system).
 * Legend is at 18,000pt (theoretical max ~18,464pt).
 * Ranges narrow as you ascend.
 */
export const RANKS: RankInfo[] = [
    { name: 'Legend', minPoints: 18000, color: 'text-amber-500 font-black' },

    ...generateTieredRanks('Mythic', 17500, 18000, 'text-purple-600'),  // 500
    ...generateTieredRanks('Ancient', 17000, 17500, 'text-indigo-600'),  // 500
    ...generateTieredRanks('Master', 16500, 17000, 'text-red-600'),     // 500
    ...generateTieredRanks('Elite', 16000, 16500, 'text-orange-600'),    // 500
    ...generateTieredRanks('Commander', 15500, 16000, 'text-yellow-700'), // 500
    ...generateTieredRanks('Veteran', 15000, 15500, 'text-emerald-600'), // 500
    ...generateTieredRanks('Expert', 14000, 15000, 'text-teal-600'),    // 1000
    ...generateTieredRanks('Advanced', 13000, 14000, 'text-cyan-600'),    // 1000
    ...generateTieredRanks('Intermediate', 12000, 13000, 'text-blue-600'),    // 1500
    ...generateTieredRanks('Novice', 10000, 12000, 'text-slate-600'),   // 2000

    { name: 'Beginner', minPoints: 0, color: 'text-slate-400' },
];

export const getFolderColorClass = (rankName: string): string => {
    switch (rankName.toLowerCase()) {
        case 'legend': return 'bg-gradient-to-r from-amber-200 to-yellow-400 border-amber-400 text-amber-900 font-bold';
        case 'mythic': return 'bg-purple-100 border-purple-300 text-purple-800 font-bold';
        case 'ancient': return 'bg-indigo-100 border-indigo-300 text-indigo-800 font-bold';
        case 'master': return 'bg-red-50 border-red-200 text-red-700';
        case 'elite': return 'bg-orange-50 border-orange-200 text-orange-700';
        case 'commander': return 'bg-yellow-50 border-yellow-200 text-yellow-700';
        case 'veteran': return 'bg-emerald-50 border-emerald-200 text-emerald-700';
        case 'expert': return 'bg-teal-50 border-teal-200 text-teal-700';
        case 'advanced': return 'bg-cyan-50 border-cyan-200 text-cyan-700';
        case 'intermediate': return 'bg-blue-50 border-blue-200 text-blue-700';
        case 'novice': return 'bg-slate-100 border-slate-300 text-slate-700';
        default: return 'bg-slate-50 border-slate-200 text-slate-800';
    }
};

/**
 * Calculates total BEAT-TIER points for a given set of flat scores
 */
export const calculateTotalPoints = (scores: { beatTierPoints: number }[]): number => {
    const validScores = scores.filter(s => s.beatTierPoints && s.beatTierPoints > 0);
    validScores.sort((a, b) => b.beatTierPoints - a.beatTierPoints);
    const top100 = validScores.slice(0, 100);
    const sum = top100.reduce((acc, score) => acc + score.beatTierPoints, 0);
    return Math.round(sum * 10) / 10;
};

export const getOverallRankInfo = (totalPoints: number): FolderRankInfo => {
    if (totalPoints >= 100000) return { name: 'Legend', tier: 5, color: 'text-amber-500', description: '神話の領域' };
    if (totalPoints >= 80000) return { name: 'Mythic', tier: 4, color: 'text-purple-500', description: '伝説のプレイヤー' };
    if (totalPoints >= 60000) return { name: 'Ancient', tier: 4, color: 'text-indigo-500', description: '古都の猛者' };
    if (totalPoints >= 45000) return { name: 'Master', tier: 3, color: 'text-red-500', description: '達人' };
    if (totalPoints >= 30000) return { name: 'Elite', tier: 3, color: 'text-orange-500', description: '熟練者' };
    if (totalPoints >= 25000) return { name: 'Commander', tier: 2, color: 'text-yellow-600', description: '指揮官' };
    if (totalPoints >= 20000) return { name: 'Veteran', tier: 2, color: 'text-emerald-500', description: '歴戦の勇者' };
    if (totalPoints >= 10000) return { name: 'Expert', tier: 2, color: 'text-teal-500', description: '上級者' };
    if (totalPoints >= 5000) return { name: 'Advanced', tier: 1, color: 'text-cyan-500', description: '中級者' };
    if (totalPoints >= 2000) return { name: 'Intermediate', tier: 1, color: 'text-blue-500', description: '初級者' };
    if (totalPoints >= 500) return { name: 'Novice', tier: 0, color: 'text-slate-600', description: '見習い' };
    return { name: 'Beginner', tier: 0, color: 'text-slate-500', description: '駆け出し' };
};

function generateTieredRanks(name: string, start: number, end: number, color: string): RankInfo[] {
    const tiers: RankInfo[] = [];
    const step = (end - start) / 5;
    for (let i = 5; i >= 1; i--) {
        tiers.push({
            name,
            tier: i,
            minPoints: start + (i - 1) * step,
            color
        });
    }
    return tiers;
}

/**
 * Get current rank info based on total points
 */
export function getRankInfo(totalPoints: number): RankInfo {
    // Sort by minPoints descending to find the highest match
    const sortedRanks = [...RANKS].sort((a, b) => b.minPoints - a.minPoints);
    return sortedRanks.find(r => totalPoints >= r.minPoints) || RANKS[RANKS.length - 1];
}

/**
 * Get progress to next rank
 */
export function getNextRankInfo(totalPoints: number): { nextRank?: RankInfo; progress: number } {
    // Sort ranks by minPoints in ascending order
    const sortedRanksAsc = [...RANKS].sort((a, b) => a.minPoints - b.minPoints);
    // Reverse the ascending sorted array to get descending order for findIndex
    const reversedRanks = [...sortedRanksAsc].reverse();

    // Find the first rank in the descending list that the totalPoints meets or exceeds.
    // This effectively finds the highest rank achieved.
    const currentRankIndexInReversed = reversedRanks.findIndex(r => totalPoints >= r.minPoints);

    // If no rank is found (shouldn't happen if Beginner is 0) or it's the highest rank (Legend),
    // then progress is 100% (no next rank or already at max).
    if (currentRankIndexInReversed === -1 || currentRankIndexInReversed === 0) { // 0 because the first element in reversedRanks is the highest rank
        return { progress: 100 };
    }

    // The current rank is the one found.
    const currentRank = reversedRanks[currentRankIndexInReversed];
    // The next rank is the one immediately before it in the reversed (descending) list,
    // which corresponds to the next higher rank in the ascending list.
    const nextRank = reversedRanks[currentRankIndexInReversed - 1];

    const range = nextRank.minPoints - currentRank.minPoints;
    const currentProgress = totalPoints - currentRank.minPoints;

    return {
        nextRank,
        progress: Math.min(100, Math.max(0, (currentProgress / range) * 100))
    };
}

/**
 * Group ranks by name for UI board
 */
export function getGroupedRanks() {
    const groups: Record<string, RankInfo[]> = {};
    RANKS.forEach(r => {
        if (!groups[r.name]) groups[r.name] = [];
        groups[r.name].push(r);
    });
    // Ensure tiers are sorted within groups (usually they are already)
    Object.keys(groups).forEach(name => {
        groups[name].sort((a, b) => (a.tier || 0) - (b.tier || 0));
    });
    return groups;
}

/**
 * Folder Rank definitions: each rank is 0.5% score rate below Legend.
 * Legend rate varies per folder (linear interpolation).
 */
export const FOLDER_RANK_DEFS: { offset: number; name: string; tier?: number; color: string }[] = [
    { offset: 0, name: 'Legend', color: 'text-amber-500 font-black' },

    { offset: 0.5, name: 'Mythic', tier: 5, color: 'text-purple-600' },
    { offset: 1.0, name: 'Mythic', tier: 4, color: 'text-purple-600' },
    { offset: 1.5, name: 'Mythic', tier: 3, color: 'text-purple-600' },
    { offset: 2.0, name: 'Mythic', tier: 2, color: 'text-purple-600' },
    { offset: 2.5, name: 'Mythic', tier: 1, color: 'text-purple-600' },

    { offset: 3.0, name: 'Ancient', tier: 5, color: 'text-indigo-600' },
    { offset: 3.5, name: 'Ancient', tier: 4, color: 'text-indigo-600' },
    { offset: 4.0, name: 'Ancient', tier: 3, color: 'text-indigo-600' },
    { offset: 4.5, name: 'Ancient', tier: 2, color: 'text-indigo-600' },
    { offset: 5.0, name: 'Ancient', tier: 1, color: 'text-indigo-600' },

    { offset: 5.5, name: 'Master', tier: 5, color: 'text-red-600' },
    { offset: 6.0, name: 'Master', tier: 4, color: 'text-red-600' },
    { offset: 6.5, name: 'Master', tier: 3, color: 'text-red-600' },
    { offset: 7.0, name: 'Master', tier: 2, color: 'text-red-600' },
    { offset: 7.5, name: 'Master', tier: 1, color: 'text-red-600' },

    { offset: 8.0, name: 'Elite', tier: 5, color: 'text-orange-600' },
    { offset: 8.5, name: 'Elite', tier: 4, color: 'text-orange-600' },
    { offset: 9.0, name: 'Elite', tier: 3, color: 'text-orange-600' },
    { offset: 9.5, name: 'Elite', tier: 2, color: 'text-orange-600' },
    { offset: 10.0, name: 'Elite', tier: 1, color: 'text-orange-600' },

    { offset: 10.5, name: 'Commander', tier: 5, color: 'text-yellow-700' },
    { offset: 11.0, name: 'Commander', tier: 4, color: 'text-yellow-700' },
    { offset: 11.5, name: 'Commander', tier: 3, color: 'text-yellow-700' },
    { offset: 12.0, name: 'Commander', tier: 2, color: 'text-yellow-700' },
    { offset: 12.5, name: 'Commander', tier: 1, color: 'text-yellow-700' },

    { offset: 13.0, name: 'Veteran', tier: 5, color: 'text-emerald-600' },
    { offset: 13.5, name: 'Veteran', tier: 4, color: 'text-emerald-600' },
    { offset: 14.0, name: 'Veteran', tier: 3, color: 'text-emerald-600' },
    { offset: 14.5, name: 'Veteran', tier: 2, color: 'text-emerald-600' },
    { offset: 15.0, name: 'Veteran', tier: 1, color: 'text-emerald-600' },

    { offset: 15.5, name: 'Expert', tier: 5, color: 'text-teal-600' },
    { offset: 16.0, name: 'Expert', tier: 4, color: 'text-teal-600' },
    { offset: 16.5, name: 'Expert', tier: 3, color: 'text-teal-600' },
    { offset: 17.0, name: 'Expert', tier: 2, color: 'text-teal-600' },
    { offset: 17.5, name: 'Expert', tier: 1, color: 'text-teal-600' },

    { offset: 18.0, name: 'Advanced', tier: 5, color: 'text-cyan-600' },
    { offset: 18.5, name: 'Advanced', tier: 4, color: 'text-cyan-600' },
    { offset: 19.0, name: 'Advanced', tier: 3, color: 'text-cyan-600' },
    { offset: 19.5, name: 'Advanced', tier: 2, color: 'text-cyan-600' },
    { offset: 20.0, name: 'Advanced', tier: 1, color: 'text-cyan-600' },

    { offset: 20.5, name: 'Intermediate', tier: 5, color: 'text-blue-600' },
    { offset: 21.0, name: 'Intermediate', tier: 4, color: 'text-blue-600' },
    { offset: 21.5, name: 'Intermediate', tier: 3, color: 'text-blue-600' },
    { offset: 22.0, name: 'Intermediate', tier: 2, color: 'text-blue-600' },
    { offset: 22.5, name: 'Intermediate', tier: 1, color: 'text-blue-600' },

    { offset: 23.0, name: 'Novice', tier: 5, color: 'text-slate-600' },
    { offset: 23.5, name: 'Novice', tier: 4, color: 'text-slate-600' },
    { offset: 24.0, name: 'Novice', tier: 3, color: 'text-slate-600' },
    { offset: 24.5, name: 'Novice', tier: 2, color: 'text-slate-600' },
    { offset: 25.0, name: 'Novice', tier: 1, color: 'text-slate-600' },
];

/**
 * Calculate folder rank info based on score rate thresholds.
 * Legend rate = getFolderLegendRate(informalRank), each rank 0.5% below.
 * Threshold points = calculatePoints(thresholdRate, informalRank) × songCount.
 */
export function getFolderRankInfo(totalPoints: number, informalRank: string | undefined, songCount: number): RankInfo {
    if (!informalRank || songCount <= 0) return { name: 'Beginner', minPoints: 0, color: 'text-slate-400' };

    const legendRate = getFolderLegendRate(informalRank);
    if (legendRate <= 0) return { name: 'Beginner', minPoints: 0, color: 'text-slate-400' };

    for (const def of FOLDER_RANK_DEFS) {
        const thresholdRate = legendRate - def.offset;
        if (thresholdRate <= 66.666) break;
        const thresholdPoints = calculatePoints(thresholdRate, informalRank) * songCount;
        if (totalPoints >= thresholdPoints) {
            return {
                name: def.name,
                tier: def.tier,
                minPoints: thresholdPoints,
                color: def.color
            };
        }
    }

    return { name: 'Beginner', minPoints: 0, color: 'text-slate-400' };
}

/**
 * Get progress to next folder rank.
 * Uses score-rate-based thresholds (Legend rate - 0.5% per rank).
 */
export function getNextFolderRankInfo(totalPoints: number, informalRank: string | undefined, songCount: number): { nextRank?: RankInfo; progress: number } {
    if (!informalRank || songCount <= 0) return { progress: 0 };

    const legendRate = getFolderLegendRate(informalRank);
    if (legendRate <= 0) return { progress: 0 };

    // Build thresholds array
    const thresholds: { def: typeof FOLDER_RANK_DEFS[0]; points: number }[] = [];
    for (const def of FOLDER_RANK_DEFS) {
        const thresholdRate = legendRate - def.offset;
        if (thresholdRate <= 66.666) break;
        thresholds.push({ def, points: calculatePoints(thresholdRate, informalRank) * songCount });
    }

    if (thresholds.length === 0) return { progress: 0 };

    // Find current rank
    let currentIdx = -1;
    for (let i = 0; i < thresholds.length; i++) {
        if (totalPoints >= thresholds[i].points) {
            currentIdx = i;
            break;
        }
    }

    // Already at Legend
    if (currentIdx === 0) return { progress: 100 };

    // Determine next rank and current threshold
    const nextIdx = currentIdx === -1 ? thresholds.length - 1 : currentIdx - 1;
    const currentThreshold = currentIdx === -1 ? 0 : thresholds[currentIdx].points;
    const nextThreshold = thresholds[nextIdx].points;
    const nextDef = thresholds[nextIdx].def;

    const nextRank: RankInfo = {
        name: nextDef.name,
        tier: nextDef.tier,
        minPoints: nextThreshold,
        color: nextDef.color
    };

    const range = nextThreshold - currentThreshold;
    const progress = range > 0
        ? Math.min(100, Math.max(0, (totalPoints - currentThreshold) / range * 100))
        : 0;

    return { nextRank, progress };
}
