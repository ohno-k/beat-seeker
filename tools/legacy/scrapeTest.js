// UNUSED: スクレイピング動作確認用のワンショットスクリプト。詳細は UNUSED.md 参照。
const https = require('https');

https.get('https://masaoblue.github.io/iidx-top-rankers-viewer/sp/ranking', (res) => {
    let rawData = '';
    res.on('data', (chunk) => { rawData += chunk; });
    res.on('end', () => {
        try {
            const scriptMatches = rawData.match(/src="([^"]+\.js)"/g);
            console.log("Scripts found:", scriptMatches);

            // Look for JSON or data file patterns
            const jsonMatches = rawData.match(/[^"]+\.json/g);
            console.log("JSON mentions found:", jsonMatches);
        } catch (e) {
            console.error(e.message);
        }
    });
}).on('error', (e) => {
    console.error(`Got error: ${e.message}`);
});
