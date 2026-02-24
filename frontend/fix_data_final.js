const fs = require('fs');
const path = require('path');

const dataDir = path.join(__dirname, 'src', 'data');
const tablePath = path.join(dataDir, 'difficulty_table.json');
const resultsPath = path.join(__dirname, 'results.txt');
const logFile = path.join(__dirname, 'debug_fix.txt');

function readAsAny(filePath) {
    const buffer = fs.readFileSync(filePath);
    if (buffer[0] === 0xFF && buffer[1] === 0xFE) return buffer.toString('utf16le');
    if (buffer[0] === 0xFE && buffer[1] === 0xFF) return buffer.toString('utf16be');
    if (buffer[0] === 0xEF && buffer[1] === 0xBB && buffer[2] === 0xBF) return buffer.toString('utf8').slice(1);
    return buffer.toString('utf8');
}

try {
    fs.writeFileSync(logFile, 'Script started\n');
    const tableText = readAsAny(tablePath);
    const table = JSON.parse(tableText);
    fs.appendFileSync(logFile, 'Table loaded\n');

    const resultsText = readAsAny(resultsPath);
    const results = resultsText.split('\n')
        .map(line => line.trim())
        .filter(line => line.includes('Lv11:') || line.includes('Lv12:'));
    fs.appendFileSync(logFile, `Results loaded: ${results.length} lines\n`);

    const missingLv11 = results.filter(r => r.startsWith('Lv11:')).map(r => r.replace('Lv11: ', ''));
    const missingLv12 = results.filter(r => r.startsWith('Lv12:')).map(r => r.replace('Lv12: ', ''));

    // Filter out existing temp ranks
    table.ranks = table.ranks.filter(r => !r.rank.includes('IIDX 32') && !r.rank.includes('IIDX 33') && !r.rank.includes('Uncategorized'));

    // Handle ANOTHER/LEGGENDARIA properly for missing songs
    // (results.txt doesn't have [L] but the table should if it's LEGGENDARIA)
    // Actually, I'll check sp12.json to see if a song should have [L]
    const sp12 = JSON.parse(fs.readFileSync(path.join(dataDir, 'sp12.json'), 'utf8'));
    const legSongs = new Set(sp12.filter(s => s.difficulty === "10").map(s => s.title));

    const finalLv12 = missingLv12.map(s => legSongs.has(s) ? s + "[L]" : s);
    const finalLv11 = missingLv11; // sp11.json doesn't usually use [L] for ranked charts in this context, or we handle it later.

    table.ranks.push({
        rank: "Uncategorized (Lv12)",
        songs: [...new Set(finalLv12)].sort()
    });
    table.ranks.push({
        rank: "Uncategorized (Lv11)",
        songs: [...new Set(finalLv11)].sort()
    });

    // Write back as CLEAN UTF-8
    fs.writeFileSync(tablePath, JSON.stringify(table, null, 4), 'utf8');
    fs.writeFileSync(path.join(__dirname, 'debug_fix.txt'), 'Successfully fixed table encoding and added ' + (finalLv12.length + finalLv11.length) + ' songs.\n');
} catch (e) {
    fs.writeFileSync(path.join(__dirname, 'debug_fix.txt'), 'ERROR: ' + e.message + '\n' + e.stack + '\n');
}
