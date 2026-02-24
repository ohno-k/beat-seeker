const fs = require('fs');
const path = require('path');

const dataDir = 'c:/Users/ohno/.gemini/antigravity/scratch/beat-seeker/frontend/src/data';
const resultsFile = 'c:/Users/ohno/.gemini/antigravity/scratch/beat-seeker/frontend/results.txt';
const diffFile = path.join(dataDir, 'difficulty_table.json');
const logFile = 'c:/Users/ohno/.gemini/antigravity/scratch/beat-seeker/frontend/update_log.txt';

function log(msg) {
    fs.appendFileSync(logFile, msg + '\r\n');
}

try {
    fs.writeFileSync(logFile, 'Starting update_ranks.js\r\n');

    log('Reading results.txt');
    const resultsRaw = fs.readFileSync(resultsFile, 'utf8');
    const lines = resultsRaw.split(/\r?\n/).filter(l => l.trim());

    const missing = { 'Lv12': [], 'Lv11': [] };
    lines.forEach(line => {
        const match = line.match(/^(Lv\d+): (.+)$/);
        if (match) {
            const lv = match[1];
            const title = match[2];
            if (missing[lv]) {
                missing[lv].push(title);
            }
        }
    });

    log(`Found Lv12: ${missing.Lv12.length}, Lv11: ${missing.Lv11.length}`);

    log('Reading difficulty_table.json');
    const table = JSON.parse(fs.readFileSync(diffFile, 'utf8'));

    // Create new ranks
    const newRanks = [
        { rank: '12.0 (IIDX 32)', songs: missing.Lv12 },
        { rank: '11.0 (IIDX 32)', songs: missing.Lv11 }
    ];

    // Append to ranks array
    table.ranks.push(...newRanks);

    log('Writing updated difficulty_table.json');
    fs.writeFileSync(diffFile, JSON.stringify(table, null, 4));

    log('Update complete!');
} catch (e) {
    log('ERROR: ' + e.message);
    log(e.stack);
}
