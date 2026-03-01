const puppeteer = require('puppeteer');
const fs = require('fs');

(async () => {
    console.log('Starting puppeteer...');
    const browser = await puppeteer.launch({
        headless: 'new',
        args: ['--no-sandbox', '--disable-setuid-sandbox']
    });
    const page = await browser.newPage();

    // Array to hold captured data
    let foundData = [];

    // Log all responses to find the data source
    page.on('response', async (response) => {
        const url = response.url();
        const type = response.request().resourceType();

        // The ranking data is likely fetched via fetch or xhr
        if (type === 'fetch' || type === 'xhr') {
            console.log(`Intercepted API Response: ${url}`);
            try {
                const json = await response.json();
                console.log(` -> Parsed JSON from ${url}. Length:`, JSON.stringify(json).length);
                foundData.push({ url, data: json });
            } catch (e) {
                // Not JSON, ignore
            }
        }
    });

    console.log('Navigating to target URL...');
    try {
        await page.goto('https://masaoblue.github.io/iidx-top-rankers-viewer/sp/ranking', {
            waitUntil: 'networkidle0',
            timeout: 60000
        });
        console.log('Page loaded. Waiting 5 seconds for any delayed requests...');
        await new Promise(r => setTimeout(r, 5000));

        // Save the results
        if (foundData.length > 0) {
            fs.writeFileSync('iidx_api_responses.json', JSON.stringify(foundData, null, 2));
            console.log('Saved all intercepted JSON responses to iidx_api_responses.json');
        } else {
            console.log('No JSON API responses found. The data might be embedded in a JS file or fetched differently.');
        }

    } catch (e) {
        console.error('Error during execution:', e);
    } finally {
        await browser.close();
    }
})();
