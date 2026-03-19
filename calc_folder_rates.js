/**
 * Calculate the average score rate needed for each folder rank tier,
 * for each folder (11.0 to 13.0).
 *
 * Based on beatTier.ts logic:
 *   calculatePoints(scoreRate, rank) = (scoreRate/100)^1.3 * weight + bonus
 *   bonus: +weight*0.01 if >77.77, +weight*0.01 if >88.88, +weight*0.01 if >94.44
 *   maxPoints(rank) = weight * 1.03
 *   ratio = calculatePoints / maxPoints
 *
 * We need to find scoreRate such that ratio >= target for each tier.
 */

// Build weights (same as beatTier.ts)
const WEIGHTS = {};
let w = 145;
for (let i = 0; i <= 20; i++) {
    const rankValue = 11.0 + i * 0.1;
    const rank = rankValue.toFixed(1);
    WEIGHTS[rank] = w;
    w += (rankValue >= 12.49) ? 3 : 2;
}

function calculatePoints(scoreRate, rank) {
    const weight = WEIGHTS[rank] || 0;
    if (weight === 0 || scoreRate <= 66.666) return 0;

    let basePoints = Math.pow(scoreRate / 100, 1.3) * weight;
    let bonus = 0;
    if (scoreRate > 77.77) bonus += weight * 0.01;
    if (scoreRate > 88.88) bonus += weight * 0.01;
    if (scoreRate > 94.44) bonus += weight * 0.01;
    return basePoints + bonus;
}

function getMaxPoints(rank) {
    const weight = WEIGHTS[rank] || 0;
    return weight * 1.03;
}

// FOLDER_TIER_RATIOS
const FOLDER_TIER_RATIOS = [
    { ratio: 0.995, name: 'Legend' },
    { ratio: 0.99, name: 'Mythic 5' },
    { ratio: 0.985, name: 'Mythic 4' },
    { ratio: 0.98, name: 'Mythic 3' },
    { ratio: 0.975, name: 'Mythic 2' },
    { ratio: 0.97, name: 'Mythic 1' },
    { ratio: 0.96, name: 'Ancient 5' },
    { ratio: 0.955, name: 'Ancient 4' },
    { ratio: 0.95, name: 'Ancient 3' },
    { ratio: 0.945, name: 'Ancient 2' },
    { ratio: 0.94, name: 'Ancient 1' },
    { ratio: 0.935, name: 'Master 5' },
    { ratio: 0.93, name: 'Master 4' },
    { ratio: 0.925, name: 'Master 3' },
    { ratio: 0.92, name: 'Master 2' },
    { ratio: 0.915, name: 'Master 1' },
    { ratio: 0.91, name: 'Elite 5' },
    { ratio: 0.905, name: 'Elite 4' },
    { ratio: 0.90, name: 'Elite 3' },
    { ratio: 0.895, name: 'Elite 2' },
    { ratio: 0.89, name: 'Elite 1' },
    { ratio: 0.885, name: 'Commander 5' },
    { ratio: 0.88, name: 'Commander 4' },
    { ratio: 0.875, name: 'Commander 3' },
    { ratio: 0.87, name: 'Commander 2' },
    { ratio: 0.865, name: 'Commander 1' },
    { ratio: 0.86, name: 'Veteran 5' },
    { ratio: 0.855, name: 'Veteran 4' },
    { ratio: 0.85, name: 'Veteran 3' },
    { ratio: 0.845, name: 'Veteran 2' },
    { ratio: 0.84, name: 'Veteran 1' },
    { ratio: 0.835, name: 'Expert 5' },
    { ratio: 0.83, name: 'Expert 4' },
    { ratio: 0.825, name: 'Expert 3' },
    { ratio: 0.82, name: 'Expert 2' },
    { ratio: 0.815, name: 'Expert 1' },
    { ratio: 0.81, name: 'Advanced 5' },
    { ratio: 0.805, name: 'Advanced 4' },
    { ratio: 0.80, name: 'Advanced 3' },
    { ratio: 0.79, name: 'Advanced 2' },
    { ratio: 0.78, name: 'Advanced 1' },
    { ratio: 0.77, name: 'Intermediate 5' },
    { ratio: 0.76, name: 'Intermediate 4' },
    { ratio: 0.75, name: 'Intermediate 3' },
    { ratio: 0.74, name: 'Intermediate 2' },
    { ratio: 0.73, name: 'Intermediate 1' },
    { ratio: 0.715, name: 'Novice 5' },
    { ratio: 0.70, name: 'Novice 4' },
    { ratio: 0.685, name: 'Novice 3' },
    { ratio: 0.67, name: 'Novice 2' },
    { ratio: 0.665, name: 'Novice 1' },
];

// For a given rank (folder) and target ratio, binary search for the scoreRate
function findScoreRate(rank, targetRatio) {
    const maxPts = getMaxPoints(rank);
    if (maxPts <= 0) return null;

    let lo = 66.666, hi = 100.0;
    for (let iter = 0; iter < 100; iter++) {
        const mid = (lo + hi) / 2;
        const pts = calculatePoints(mid, rank);
        const ratio = pts / maxPts;
        if (ratio < targetRatio) {
            lo = mid;
        } else {
            hi = mid;
        }
    }
    return hi;
}

// Folders
const folders = [];
for (let i = 0; i <= 20; i++) {
    folders.push((11.0 + i * 0.1).toFixed(1));
}

// But Legend has special handling with legendMinPoints for some folders
// Let's also check if there's a legendMinPoints override – from getFolderRankInfo,
// if legendMinPoints is provided it's used. But in the summary, let's check how
// Legend is computed. getLegendPtPerSong provides a per-song legend threshold.
// For the summary view, Legend threshold = getLegendPtPerSong(rank) * songCount
// But we don't know songCount here. Let's just compute the ratio-based version first.

// Print header
const header = ['Rank', ...folders];
console.log('| ' + header.join(' | ') + ' |');
console.log('| ' + header.map(() => '---').join(' | ') + ' |');

// For each tier
for (const tier of FOLDER_TIER_RATIOS) {
    const row = [tier.name.padEnd(16)];
    for (const folder of folders) {
        const scoreRate = findScoreRate(folder, tier.ratio);
        if (scoreRate !== null && scoreRate <= 100.0) {
            row.push(scoreRate.toFixed(2) + '%');
        } else {
            row.push('N/A');
        }
    }
    console.log('| ' + row.join(' | ') + ' |');
}
