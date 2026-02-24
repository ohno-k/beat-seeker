
const fs = require('fs');
const path = require('path');

function normalizeTitle(title) {
    if (!title) return '';
    return title
        .normalize('NFKC')
        .replace(/[“”〝〞]/g, '"')
        .replace(/[‘’'｀´]/g, "'")
        .toLowerCase()
        .trim();
}

const dataDir = path.join(__dirname, 'src', 'data');
const logFile = path.join(__dirname, 'debug_update.txt');
fs.writeFileSync(logFile, 'Script started\n');
function log(msg) { fs.appendFileSync(logFile, msg + '\n'); }

log('Reading sp11.json');
const sp11 = JSON.parse(fs.readFileSync(path.join(dataDir, 'sp11.json'), 'utf8'));
log('Reading sp12.json');
const sp12 = JSON.parse(fs.readFileSync(path.join(dataDir, 'sp12.json'), 'utf8'));
log('Reading difficulty_table.json');
const diffTable = JSON.parse(fs.readFileSync(path.join(dataDir, 'difficulty_table.json'), 'utf8'));

try {

    // Build existing titles set for faster lookup
    const existingKeys = new Set();
    diffTable.ranks.forEach(rank => {
        // Only skip temporary ranks from previous runs
        if (rank.rank.includes('IIDX 32') || rank.rank.includes('IIDX 33') || rank.rank.includes('Uncategorized')) return;

        rank.songs.forEach(song => {
            if (song.endsWith('[L]')) {
                existingKeys.add(normalizeTitle(song.slice(0, -3)) + '_10');
            } else {
                existingKeys.add(normalizeTitle(song) + '_4');
            }
        });
    });

    const missingLv12 = [];
    const missingLv11 = [];

    [...sp11, ...sp12].forEach(song => {
        // Only care about Lv 11/12
        if (song.difficultyLevel !== "11" && song.difficultyLevel !== "12") return;

        // We only care about ANOTHER (4) and LEGGENDARIA (10) for informal ranking
        if (song.difficulty !== "4" && song.difficulty !== "10") return;

        const key = normalizeTitle(song.title) + '_' + song.difficulty;
        if (!existingKeys.has(key)) {
            const entry = song.difficulty === "10" ? song.title + "[L]" : song.title;
            if (song.difficultyLevel === "12") {
                missingLv12.push(entry);
            } else {
                missingLv11.push(entry);
            }
            existingKeys.add(key); // prevent duplicates in missing list
        }
    });

    log(`Missing Lv12: ${missingLv12.length}`);
    log(`Missing Lv11: ${missingLv11.length}`);

    // Remove any existing temporary ranks if they exist (to avoid duplicates)
    diffTable.ranks = diffTable.ranks.filter(r => !r.rank.includes('IIDX 32') && !r.rank.includes('IIDX 33') && !r.rank.includes('Uncategorized'));

    if (missingLv12.length > 0) {
        diffTable.ranks.push({
            rank: "Uncategorized (Lv12)",
            songs: missingLv12.sort((a, b) => a.localeCompare(b))
        });
    }

    if (missingLv11.length > 0) {
        diffTable.ranks.push({
            rank: "Uncategorized (Lv11)",
            songs: missingLv11.sort((a, b) => a.localeCompare(b))
        });
    }

    fs.writeFileSync(path.join(dataDir, 'difficulty_table.json'), JSON.stringify(diffTable, null, 4));
    log('Successfully updated difficulty_table.json with ' + (missingLv12.length + missingLv11.length) + ' new entries.');
} catch (e) {
    log('FATAL ERROR: ' + e.message + '\n' + e.stack);
}
