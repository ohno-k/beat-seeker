const fs = require('fs');
const path = require('path');

const logFile = 'c:/Users/ohno/.gemini/antigravity/scratch/beat-seeker/frontend/debug_log.txt';
function log(msg) {
    fs.appendFileSync(logFile, msg + '\r\n');
}

try {
    fs.writeFileSync(logFile, 'Starting comparison script\r\n');
    const dataDir = 'c:/Users/ohno/.gemini/antigravity/scratch/beat-seeker/frontend/src/data';

    log('Reading sp11.json');
    const sp11 = JSON.parse(fs.readFileSync(path.join(dataDir, 'sp11.json'), 'utf8'));
    log('Reading sp12.json');
    const sp12 = JSON.parse(fs.readFileSync(path.join(dataDir, 'sp12.json'), 'utf8'));
    log('Reading difficulty_table.json');
    const diffTable = JSON.parse(fs.readFileSync(path.join(dataDir, 'difficulty_table.json'), 'utf8'));

    const titlesInTable = new Set();
    diffTable.ranks.forEach(rank => {
        rank.songs.forEach(song => titlesInTable.add(song));
    });
    log('Titles in table: ' + titlesInTable.size);

    const missingMap = new Map();
    [...sp11, ...sp12].forEach(song => {
        if (!titlesInTable.has(song.title)) {
            missingMap.set(song.title, song.difficultyLevel);
        }
    });

    const missingEntries = Array.from(missingMap.entries())
        .map(([title, level]) => `Lv${level}: ${title}`)
        .sort((a, b) => {
            const lvA = parseInt(a.match(/Lv(\d+)/)?.[1] || '0');
            const lvB = parseInt(b.match(/Lv(\d+)/)?.[1] || '0');
            if (lvA !== lvB) return lvB - lvA; // Higher level first
            return a.localeCompare(b);
        });

    log('Missing count: ' + missingEntries.length);

    fs.writeFileSync('c:/Users/ohno/.gemini/antigravity/scratch/beat-seeker/frontend/all_missing.txt', missingEntries.join('\r\n'));
    log('Successfully written all_missing.txt');
} catch (e) {
    log('ERROR: ' + e.message);
    log(e.stack);
}
