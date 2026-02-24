
const fs = require('fs');

const sp11Path = 'c:/Users/ohno/.gemini/antigravity/scratch/beat-seeker/frontend/src/data/sp11.json';
const sp12Path = 'c:/Users/ohno/.gemini/antigravity/scratch/beat-seeker/frontend/src/data/sp12.json';
const diffTablePath = 'c:/Users/ohno/.gemini/antigravity/scratch/beat-seeker/frontend/src/data/difficulty_table.json';
const outputPath = 'c:/Users/ohno/.gemini/antigravity/scratch/beat-seeker/frontend/missing_songs.txt';

try {
    const sp11Text = fs.readFileSync(sp11Path, 'utf8');
    const sp12Text = fs.readFileSync(sp12Path, 'utf8');
    const diffTableText = fs.readFileSync(diffTablePath, 'utf8');

    // Regex to capture "title": "..." handling escaped quotes \"
    const titleRegex = /"title":\s*"((?:\\"|[^"])*)"/g;
    const spTitles = new Set();

    let match;
    while ((match = titleRegex.exec(sp11Text)) !== null) {
        // match[1] still has the backslashes for escaped quotes.
        // We want the unescaped title for set comparison
        spTitles.add(match[1].replace(/\\"/g, '"'));
    }
    while ((match = titleRegex.exec(sp12Text)) !== null) {
        spTitles.add(match[1].replace(/\\"/g, '"'));
    }

    const missingInTable = [];
    spTitles.forEach(title => {
        // Re-escape for searching in the JSON string
        const escapedForTable = title.replace(/"/g, '\\"');
        const searchStr = '"' + escapedForTable + '"';
        const searchStrL = '"' + escapedForTable + '[L]"';

        if (diffTableText.indexOf(searchStr) === -1 && diffTableText.indexOf(searchStrL) === -1) {
            missingInTable.push(title);
        }
    });

    let output = '--- sp11/sp12 にはあるが difficulty_table.json にない曲 ---\n';
    missingInTable.sort().forEach(t => {
        output += t + '\n';
    });
    output += `\n合計: ${missingInTable.length}\n`;

    fs.writeFileSync(outputPath, output);
    console.log('Processed ' + spTitles.size + ' songs. Found ' + missingInTable.length + ' missing.');
} catch (err) {
    fs.writeFileSync(outputPath, 'Fatal Error: ' + err.message + '\n' + err.stack);
}
