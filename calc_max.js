// UNUSED: ワンショット検証用。他から参照されていない。詳細は UNUSED.md 参照。
const fs = require('fs');
const path = require('path');

const diffFile = 'frontend/src/data/difficulty_table.json';
const content = fs.readFileSync(diffFile, 'utf8');
const diffTable = JSON.parse(content);

const WEIGHTS = {};
for (let i = 0; i <= 19; i++) {
    const rank = (11.0 + i * 0.1).toFixed(1);
    WEIGHTS[rank] = 150 + i * 2;
}

let allSongs = [];
diffTable.ranks.forEach(r => {
    const weight = WEIGHTS[r.rank] || 0;
    r.songs.forEach(s => {
        allSongs.push({ name: s, rank: r.rank, weight: weight });
    });
});

allSongs.sort((a, b) => parseFloat(b.rank) - parseFloat(a.rank));

const top100 = allSongs.slice(0, 100);
const totalPoints = top100.reduce((sum, s) => sum + s.weight, 0);

console.log('Total Songs in Table:', allSongs.length);
console.log('Theoretical Max (Top 100):', totalPoints.toFixed(2), 'pt');
console.log('--- Top 10 Songs ---');
allSongs.slice(0, 10).forEach((s, i) => {
    console.log(`${i + 1}. ${s.name} (${s.rank}) -> ${s.weight}pt`);
});
console.log('--- 100th Song ---');
console.log(`100. ${top100[99].name} (${top100[99].rank}) -> ${top100[99].weight}pt`);
