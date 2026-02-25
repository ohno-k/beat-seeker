/**
 * Beat-Tier System Logic
 * 
 * 1. Single Song Points = (ScoreRate / 100)^2 * Weight
 *    Weight: 11.0 -> 150, 11.1 -> 152, ..., 12.9 -> 188
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

// Weights configuration (can be easily adjusted)
export const WEIGHTS: Record<string, number> = {};
for (let i = 0; i <= 19; i++) {
    const rank = (11.0 + i * 0.1).toFixed(1);
    WEIGHTS[rank] = 150 + i * 2;
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
 * Calculate points for a single song
 */
export function calculatePoints(scoreRate: number, informalRank: string | undefined): number {
    const weight = getWeight(informalRank);
    if (weight === 0 || scoreRate <= 66.666) return 0;

    // Linear curve: (ScoreRate/100) * Weight
    return (scoreRate / 100) * weight;
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
    ...generateTieredRanks('Elite', 16000, 16500, 'text-orange-600'),  // 500
    ...generateTieredRanks('Veteran', 15500, 16000, 'text-emerald-600'), // 500
    ...generateTieredRanks('Expert', 14500, 15500, 'text-teal-600'),    // 1000
    ...generateTieredRanks('Advanced', 13500, 14500, 'text-cyan-600'),    // 1000
    ...generateTieredRanks('Intermediate', 12000, 13500, 'text-blue-600'),    // 1500
    ...generateTieredRanks('Novice', 10000, 12000, 'text-slate-600'),   // 2000

    { name: 'Beginner', minPoints: 0, color: 'text-slate-400' },
];

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
