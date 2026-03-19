/**
 * Correctly calculate the average score rate needed for each folder rank tier.
 *
 * Key insight from UnofficialDifficultyTable.vue:
 *   maxBeatPoints = getLegendPtPerSong(rank) * totalCount
 *   legendThreshold = maxBeatPoints (i.e., Legend = 100% of maxBeatPoints)
 *   getFolderRankInfo(totalBeatPoints, maxBeatPoints, legendThreshold)
 *
 * So each song needs to average legendPtPerSong points for Legend.
 * For other tiers, ratio is applied to maxBeatPoints (= legendPtPerSong * totalCount).
 * So at the per-song level: need ratio * legendPtPerSong average points.
 *
 * We need to find: what score rate on a song of weight W gives ratio * legendPtPerSong?
 */

// Build weights
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

function getLegendPtPerSong(rank) {
    const weight = WEIGHTS[rank] || 0;
    if (weight <= 0) return 0;

    const LEGEND_PT_CAP = 180;
    const LEGEND_WEIGHT = 181;
    const BASE_WEIGHT = 145;
    const BASE_THRESHOLD = calculatePoints(99, '11.0');

    if (weight >= LEGEND_WEIGHT) return LEGEND_PT_CAP;
    return BASE_THRESHOLD + (LEGEND_PT_CAP - BASE_THRESHOLD) * (weight - BASE_WEIGHT) / (LEGEND_WEIGHT - BASE_WEIGHT);
}

const FOLDER_TIER_RATIOS = [
    { ratio: 1.0, name: 'Legend' },  // Legend = legendThreshold = maxBeatPoints = 100%
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

// Binary search: find scoreRate such that calculatePoints(scoreRate, rank) >= targetPt
function findScoreRate(rank, targetPt) {
    const weight = WEIGHTS[rank] || 0;
    if (weight === 0) return null;
    // Max possible points for this rank
    const maxPossible = calculatePoints(100, rank);
    if (targetPt > maxPossible) return null; // impossible

    let lo = 66.666, hi = 100.0;
    for (let iter = 0; iter < 100; iter++) {
        const mid = (lo + hi) / 2;
        const pts = calculatePoints(mid, rank);
        if (pts < targetPt) {
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

// Debug: show legendPtPerSong and max possible points per folder
console.log("=== Legend PT per Song & Max Possible Points ===");
for (const f of folders) {
    const lpt = getLegendPtPerSong(f);
    const maxPt = calculatePoints(100, f);
    console.log(`☆${f}: weight=${WEIGHTS[f]}, legendPtPerSong=${lpt.toFixed(2)}, maxPossiblePt=${maxPt.toFixed(2)}`);
}
console.log("");

// For each tier and folder, the target per-song pt = ratio * legendPtPerSong(folder)
// Find the scoreRate on that folder that gives those points.

// Print header
const header = ['Rank', ...folders.map(f => '☆'+f)];
console.log('| ' + header.join(' | ') + ' |');
console.log('| ' + header.map(() => '---').join(' | ') + ' |');

for (const tier of FOLDER_TIER_RATIOS) {
    const row = [tier.name.padEnd(16)];
    for (const folder of folders) {
        const legendPt = getLegendPtPerSong(folder);
        const targetPt = tier.ratio * legendPt;
        const scoreRate = findScoreRate(folder, targetPt);
        if (scoreRate !== null && scoreRate <= 100.0) {
            row.push(scoreRate.toFixed(2) + '%');
        } else {
            row.push('N/A');
        }
    }
    console.log('| ' + row.join(' | ') + ' |');
}
