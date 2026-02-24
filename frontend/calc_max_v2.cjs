const fs = require('fs');
const diffTable = JSON.parse(fs.readFileSync('./src/data/difficulty_table.json', 'utf-8'));

let allWeights = [];

diffTable.ranks.forEach(r => {
    const rankName = r.rank;
    if (rankName && rankName.match(/^1[12]\.\d/)) {
        const val = parseFloat(rankName.match(/^1[12]\.\d/)[0]);
        if (val >= 11.0) {
            const i = Math.round((val - 11.0) * 10);
            const weight = 150 + i * 2;
            r.songs.forEach(() => {
                allWeights.push(weight);
            });
        }
    }
});

allWeights.sort((a, b) => b - a);
const top100 = allWeights.slice(0, 100);
const total = top100.reduce((sum, w) => sum + w, 0);

console.log('RESULT:' + total);
