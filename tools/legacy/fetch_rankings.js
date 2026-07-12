const puppeteer = require('puppeteer');
const fs = require('fs');

(async () => {
    console.log('Starting headless browser...');
    const browser = await puppeteer.launch({ headless: 'new' });
    const page = await browser.newPage();

    let targetData = null;

    // Intercept network requests
    page.on('response', async (response) => {
        const url = response.url();
        // The data is likely a JSON file (e.g., scores.json, rankings.json, or loaded via an API)
        if (url.includes('.json') && !url.includes('manifest')) {
            console.log(`Intercepted JSON: ${url}`);
            try {
                const data = await response.json();

                // Let's check if this JSON has the shape we want (contains 'title', 'diff', 'average')
                // The structure might be an array or an object
                let isTarget = false;
                if (Array.isArray(data) && data.length > 0) {
                    if (data[0].title || data[0].average || data[0].chart) isTarget = true;
                } else if (typeof data === 'object' && data !== null) {
                    // Sometimes it's nested
                    const sample = Object.values(data)[0];
                    if (sample && (sample.title || sample.avg || sample.average)) isTarget = true;
                    // Or just save anything that looks big enough
                    if (JSON.stringify(data).length > 10000) isTarget = true;
                }

                if (isTarget && !targetData) {
                    console.log('Target data structure found! Saving...');
                    targetData = data;
                }
            } catch (e) {
                // Ignore parsing errors for non-JSON or malformed responses
            }
        }
    });

    console.log('Navigating to IIDX Top Rankers Viewer...');
    await page.goto('https://masaoblue.github.io/iidx-top-rankers-viewer/sp/ranking', { waitUntil: 'networkidle0' });

    // Wait a bit more just in case it loads async after idle
    await new Promise(r => setTimeout(r, 3000));

    if (targetData) {
        fs.writeFileSync('iidx_raw_rankings.json', JSON.stringify(targetData, null, 2));
        console.log('Successfully saved raw data to iidx_raw_rankings.json');
    } else {
        console.log('Could not automatically identify the JSON endpoint.');

        // Let's dump the window obj or try to find where it stored state
        const state = await page.evaluate(() => {
            // Look for common React or Vue state injections
            return window.__INITIAL_STATE__ || window.__NUXT__ || null;
        });

        if (state) {
            fs.writeFileSync('iidx_raw_rankings.json', JSON.stringify(state, null, 2));
            console.log('Saved window state to iidx_raw_rankings.json');
        }
    }

    await browser.close();
    console.log('Done.');
})();
