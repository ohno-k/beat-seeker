const fs = require('fs');
const log = (msg) => {
    console.log(msg);
    fs.appendFileSync('debug_out.txt', msg + '\n');
};

function normalizeTitle(title) {
    if (!title) return '';
    return title
        .normalize('NFKC')
        .replace(/[“”〝〞]/g, '"')
        .replace(/[‘’'｀´]/g, "'")
        .toLowerCase()
        .trim();
}

fs.writeFileSync('debug_out.txt', '');

try {
    log('Reading files...');
    const diffTable = JSON.parse(fs.readFileSync('src/data/difficulty_table.json', 'utf8'));
    const sp12 = JSON.parse(fs.readFileSync('src/data/sp12.json', 'utf8'));
    log('Files read successfully.');

    const informalDict = new Map();
    diffTable.ranks.forEach(r => {
        r.songs.forEach(songTitle => {
            if (songTitle.endsWith('[L]')) {
                const baseTitle = songTitle.slice(0, -3);
                informalDict.set(`${normalizeTitle(baseTitle)}_LEGGENDARIA`, r.rank);
            } else {
                informalDict.set(`${normalizeTitle(songTitle)}_ANOTHER`, r.rank);
            }
        });
    });

    log('--- 12.9 Analysis ---');
    const rank129 = diffTable.ranks.find(r => r.rank === '12.9');
    if (!rank129) {
        log('Error: Rank 12.9 not found!');
    } else {
        const rank129Songs = rank129.songs;
        log('Songs in Table (12.9): ' + rank129Songs.length);

        rank129Songs.forEach(songTitle => {
            let base = songTitle;
            let diff = 'ANOTHER';
            if (songTitle.endsWith('[L]')) {
                base = songTitle.slice(0, -3);
                diff = 'LEGGENDARIA';
            }
            const normBase = normalizeTitle(base);
            const key = `${normBase}_${diff}`;

            // Search in sp12 using normalized title
            const match = sp12.find(s => normalizeTitle(s.title) === normBase);
            log(`[${songTitle}] Key: ${key} | Match in sp12: ${match ? 'YES (' + match.title + ')' : 'NO'}`);
        });
    }

} catch (err) {
    log('CRITICAL ERROR: ' + err.message);
    log(err.stack);
}
