// UNUSED: evaluate_oono.js（ESM 版）の旧 CommonJS 版。重複のため未使用。frontend/SCRIPTS.md §3 参照。
const fs = require('fs');

const diffTable = JSON.parse(fs.readFileSync('./src/data/difficulty_table.json', 'utf-8'));
const songData = JSON.parse(fs.readFileSync('./src/data/song_data.json', 'utf-8'));

const informalDict = new Map();
diffTable.ranks.forEach(r => {
    r.songs.forEach(songTitle => {
        if (songTitle.endsWith('[L]')) {
            const baseTitle = songTitle.slice(0, -3).trim();
            informalDict.set(`${baseTitle}_LEGGENDARIA`, r.rank);
        } else {
            informalDict.set(`${songTitle.trim()}_ANOTHER`, r.rank);
        }
    });
});

const songDict = new Map();
songData.body.forEach(s => {
    songDict.set(`${s.title.trim()}_${s.difficulty.trim()}`, s);
});

// NEW WEIGHT LOGIC
const getWeight = (rank) => {
    if (!rank) return 0;
    const match = rank.match(/^1[12]\.\d/);
    if (!match) return 0;

    const val = parseFloat(match[0]);
    if (val < 11.0) return 0;

    const i = Math.round((val - 11.0) * 10);
    return 150 + i * 2;
};

const lines = fs.readFileSync('./public/大野.csv', 'utf-8').split('\n');
const headers = lines[0].split(',');
let records = [];

const difficulties = ['BEGINNER', 'NORMAL', 'HYPER', 'ANOTHER', 'LEGGENDARIA'];

for (let i = 1; i < lines.length; i++) {
    const line = lines[i].trim();
    if (!line) continue;

    const fields = [];
    let currentField = '';
    let insideQuotes = false;
    for (let char of line) {
        if (char === '\"') {
            insideQuotes = !insideQuotes;
        } else if (char === ',' && !insideQuotes) {
            fields.push(currentField);
            currentField = '';
        } else {
            currentField += char;
        }
    }
    fields.push(currentField);

    if (fields.length < headers.length) continue;

    let title = fields[1];
    if (title && title.startsWith('"') && title.endsWith('"')) {
        title = title.substring(1, title.length - 1);
    }
    title = title ? title.trim() : '';
    if (!title) continue;

    difficulties.forEach(diff => {
        const dStr = diff;

        const statusIdx = headers.findIndex(h => h.includes(`${diff} クリアタイプ`));
        const scoreIdx = headers.findIndex(h => h.includes(`${diff} スコア`));
        if (statusIdx === -1 || scoreIdx === -1) return;

        const statusStr = fields[statusIdx];
        const scoreStr = fields[scoreIdx];

        if (statusStr && statusStr !== 'NO PLAY' && statusStr !== '---') {
            const score = parseInt(scoreStr) || 0;
            const defKey = `${title}_${dStr}`;
            const definition = songDict.get(defKey);

            if (definition && definition.notes) {
                const maxScore = definition.notes * 2;
                if (maxScore > 0) {
                    const scoreRate = (score / maxScore) * 100;

                    let informalRank = informalDict.get(defKey);
                    if (!informalRank && dStr === 'ANOTHER') {
                        informalRank = informalDict.get(`${title}_ANOTHER`);
                    }

                    const weight = getWeight(informalRank);
                    if (weight > 0) {
                        const points2 = Math.pow(scoreRate / 100, 2) * weight;
                        records.push(points2);
                    }
                }
            }
        }
    });
}
records.sort((a, b) => b - a);
const top100_2 = records.slice(0, 100);
const sum2 = top100_2.reduce((a, b) => a + b, 0);

console.log('Total Oono Points (150+i*2):', Math.floor(sum2));
