
import fs from 'fs';
import path from 'path';

// For ESM, __dirname is not available, use process.cwd() since we run from frontend dir
const rootDir = process.cwd();
const logFile = path.join(rootDir, 'debug_log.txt');

function log(msg) {
    fs.appendFileSync(logFile, msg + '\r\n');
}

fs.writeFileSync(logFile, '--- Update Script Start (ESM) ---\r\n');

try {
    const dataDir = path.join(rootDir, 'src', 'data');
    log('Data dir: ' + dataDir);

    const sp11Path = path.join(dataDir, 'sp11.json');
    const sp12Path = path.join(dataDir, 'sp12.json');
    const diffTablePath = path.join(dataDir, 'difficulty_table.json');

    log('Loading sp11...');
    const sp11 = JSON.parse(fs.readFileSync(sp11Path, 'utf8'));
    log('Loading sp12...');
    const sp12 = JSON.parse(fs.readFileSync(sp12Path, 'utf8'));
    log('Loading difficulty_table...');
    const diffTable = JSON.parse(fs.readFileSync(diffTablePath, 'utf8'));

    // Build existing titles set for faster lookup
    const existingKeys = new Set();
    diffTable.ranks.forEach(rank => {
        if (!rank.songs) return;
        rank.songs.forEach(song => {
            if (song.endsWith('[L]')) {
                existingKeys.add(song.slice(0, -3).toLowerCase() + '_10');
            } else {
                existingKeys.add(song.toLowerCase() + '_4');
            }
        });
    });
    log('Existing keys count: ' + existingKeys.size);

    const missingLv12 = [];
    const missingLv11 = [];

    [...sp11, ...sp12].forEach(song => {
        if (song.difficultyLevel !== "11" && song.difficultyLevel !== "12") return;
        if (song.difficulty !== "4" && song.difficulty !== "10") return;

        const key = song.title.toLowerCase() + '_' + song.difficulty;
        if (!existingKeys.has(key)) {
            const entry = song.difficulty === "10" ? song.title + "[L]" : song.title;
            if (song.difficultyLevel === "12") {
                missingLv12.push(entry);
            } else {
                missingLv11.push(entry);
            }
            existingKeys.add(key);
        }
    });

    log(`Missing Lv12: ${missingLv12.length}`);
    log(`Missing Lv11: ${missingLv11.length}`);

    // Clean up old temp ranks
    diffTable.ranks = diffTable.ranks.filter(r =>
        !r.rank.startsWith('12.0 (IIDX 32)') &&
        !r.rank.startsWith('11.0 (IIDX 32)') &&
        !r.rank.includes('Uncategorized')
    );

    if (missingLv12.length > 0) {
        diffTable.ranks.push({
            rank: "Uncategorized (Lv12)",
            songs: missingLv12.sort()
        });
    }

    if (missingLv11.length > 0) {
        diffTable.ranks.push({
            rank: "Uncategorized (Lv11)",
            songs: missingLv11.sort()
        });
    }

    log('Saving updated difficulty_table.json...');
    fs.writeFileSync(diffTablePath, JSON.stringify(diffTable, null, 4));
    log('Successfully updated difficulty_table.json');

} catch (e) {
    log('ERROR: ' + e.message);
    log(e.stack);
    process.exit(1);
}

log('--- Update Script End ---');
