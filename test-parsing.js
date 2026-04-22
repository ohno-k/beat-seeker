// UNUSED: パーサ単体テスト用のワンショットスクリプト。詳細は UNUSED.md 参照。
const fs = require('fs');
const songData = JSON.parse(fs.readFileSync('frontend/src/data/song_data.json', 'utf8'));
const diffTable = JSON.parse(fs.readFileSync('frontend/src/data/difficulty_table.json', 'utf8'));

const informalRanks = new Map();
diffTable.ranks.forEach(r => {
    r.songs.forEach(songTitle => {
        if (songTitle.endsWith("[L]")) {
            const baseTitle = songTitle.substring(0, songTitle.length - 3);
            informalRanks.set(baseTitle + "_LEGGENDARIA", r.rank);
        } else {
            informalRanks.set(songTitle + "_ANOTHER", r.rank);
        }
    });
});

console.log("Total mapped informalRanks:", informalRanks.size);
console.log("Sample of 13.0:", Array.from(informalRanks.entries()).slice(0, 5));

const songMaxScores = new Map();
songData.body.forEach(s => {
    if (s.notes > 0) {
        songMaxScores.set(s.title + "_" + s.difficulty, s.notes * 2);
    }
});
console.log("Total mapped songMaxScores:", songMaxScores.size);
console.log("Sample of scores:", Array.from(songMaxScores.entries()).slice(0, 5));

// Simulate one score
const testTitle = "惑星鉄道"; // A known 13.0
const informalKey = testTitle + "_ANOTHER";
console.log("Looking up:", informalKey, "Found:", informalRanks.get(informalKey));

const testTitle2 = "B4U(BEMANI FOR YOU MIX)";
const informalKey2 = testTitle2 + "_LEGGENDARIA";
console.log("Looking up:", informalKey2, "Found:", informalRanks.get(informalKey2));
