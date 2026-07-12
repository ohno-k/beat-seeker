const fs = require('fs');
function getFolderLegendRate(rank) {
    const rankValue = parseFloat(rank);
    const t = (rankValue - 11.0) / (13.0 - 11.0);
    return 99.6 - Math.pow(t, 1.7) * (99.6 - 94.44);
}
const RANK_NAMES = [
    'Legend',
    'Mythic 5', 'Mythic 4', 'Mythic 3', 'Mythic 2', 'Mythic 1',
    'Ancient 5', 'Ancient 4', 'Ancient 3', 'Ancient 2', 'Ancient 1',
    'Master 5', 'Master 4', 'Master 3', 'Master 2', 'Master 1',
    'Elite 5', 'Elite 4', 'Elite 3', 'Elite 2', 'Elite 1',
    'Commander 5', 'Commander 4', 'Commander 3', 'Commander 2', 'Commander 1',
    'Veteran 5', 'Veteran 4', 'Veteran 3', 'Veteran 2', 'Veteran 1',
    'Expert 5', 'Expert 4', 'Expert 3', 'Expert 2', 'Expert 1',
    'Advanced 5', 'Advanced 4', 'Advanced 3', 'Advanced 2', 'Advanced 1',
    'Intermediate 5', 'Intermediate 4', 'Intermediate 3', 'Intermediate 2', 'Intermediate 1',
    'Novice 5', 'Novice 4', 'Novice 3', 'Novice 2', 'Novice 1',
];
const folders = [];
for (let i = 0; i <= 20; i++) folders.push((11.0 + i * 0.1).toFixed(1));
// Just output the legend rates for reference
let legend = '';
for (const f of folders) legend += `☆${f}: ${getFolderLegendRate(f).toFixed(2)}%\n`;
fs.writeFileSync('C:/tmp/legend_rates_17.txt', legend, 'utf8');
