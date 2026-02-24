
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

console.log('--- Diagnostic Start ---');

try {
    const tablePath = path.join(__dirname, 'src', 'data', 'difficulty_table.json');
    const sp12Path = path.join(__dirname, 'src', 'data', 'sp12.json');

    console.log('Checking files...');
    if (!fs.existsSync(tablePath)) throw new Error('Table not found: ' + tablePath);
    if (!fs.existsSync(sp12Path)) throw new Error('SP12 not found: ' + sp12Path);

    const table = JSON.parse(fs.readFileSync(tablePath, 'utf8'));
    const sp12 = JSON.parse(fs.readFileSync(sp12Path, 'utf8'));

    console.log('Files loaded successfully.');

    const targetRanks = ['12.9', '12.8'];
    const tableSongs = [];
    table.ranks.forEach(r => {
        if (targetRanks.includes(r.rank)) {
            r.songs.forEach(s => tableSongs.push({ title: s, rank: r.rank }));
        }
    });

    console.log(`Found ${tableSongs.length} songs in target ranks in table.`);

    const matches = [];
    const missing = [];

    tableSongs.forEach(ts => {
        const isLeg = ts.title.endsWith('[L]');
        const baseTitle = isLeg ? ts.title.slice(0, -3) : ts.title;
        const normBase = normalizeTitle(baseTitle);
        const diffLabel = isLeg ? '10' : '4'; // SP12.json uses "10" for Leggendaria in difficulty field? Wait, check.

        const found = sp12.find(s => {
            const normS = normalizeTitle(s.title);
            return normS === normBase && (s.difficulty === diffLabel);
        });

        if (found) {
            matches.push(ts.title);
        } else {
            missing.push({
                orig: ts.title,
                norm: normBase,
                diff: diffLabel,
                hex: Buffer.from(baseTitle).toString('hex')
            });
        }
    });

    console.log(`Matches: ${matches.length}`);
    console.log(`Missing: ${missing.length}`);

    if (missing.length > 0) {
        console.log('\n--- Missing Samples (Hex) ---');
        missing.slice(0, 10).forEach(m => {
            console.log(`${m.orig} (${m.norm}) [Diff: ${m.diff}]: ${m.hex}`);
        });
    }

    // Check one specific song like "惑星鉄道"
    const target = "惑星鉄道";
    console.log(`\nSpecific check for "${target}":`);
    const tNorm = normalizeTitle(target);
    const tHex = Buffer.from(target).toString('hex');
    console.log(`Table Norm: ${tNorm}, Hex: ${tHex}`);

    const spEntry = sp12.find(s => normalizeTitle(s.title).includes('惑星'));
    if (spEntry) {
        console.log(`SP12 Entry: "${spEntry.title}", Norm: ${normalizeTitle(spEntry.title)}, Hex: ${Buffer.from(spEntry.title).toString('hex')}, Diff: ${spEntry.difficulty}`);
    } else {
        console.log('No "惑星" entry found in SP12.');
    }

} catch (err) {
    console.error('Error during diagnostics:', err);
}

console.log('--- Diagnostic End ---');
