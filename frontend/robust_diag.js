
const fs = require('fs');
const path = require('path');
const logFile = 'debug_out.txt';
fs.writeFileSync(logFile, 'Diagnostic started\n');

try {
    const dataDir = './src/data';
    fs.appendFileSync(logFile, `dataDir: ${dataDir}\n`);

    const files = ['sp11.json', 'sp12.json', 'difficulty_table.json'];
    files.forEach(f => {
        const p = path.join(dataDir, f);
        const exists = fs.existsSync(p);
        fs.appendFileSync(logFile, `File ${f}: ${exists ? 'EXISTS' : 'MISSING'} (${p})\n`);
        if (exists) {
            try {
                const text = fs.readFileSync(p, 'utf8');
                fs.appendFileSync(logFile, `  Read successful: ${text.length} chars\n`);
                JSON.parse(text);
                fs.appendFileSync(logFile, `  JSON parse successful\n`);
            } catch (e) {
                fs.appendFileSync(logFile, `  ERROR on ${f}: ${e.message}\n`);
            }
        }
    });
} catch (e) {
    fs.appendFileSync(logFile, `TOP LEVEL ERROR: ${e.message}\n${e.stack}\n`);
}
fs.appendFileSync(logFile, 'Diagnostic finished\n');
