const puppeteer = require('puppeteer');
const fs = require('fs');

(async () => {
    console.log('Starting log browser...');
    // Add arguments to avoid some sandbox issues on Windows
    const browser = await puppeteer.launch({
        headless: 'new',
        args: ['--no-sandbox', '--disable-setuid-sandbox']
    });
    const page = await browser.newPage();

    // We want to see all XHR or fetch requests
    page.on('request', request => {
        const type = request.resourceType();
        if (type === 'xhr' || type === 'fetch') {
            console.log('Intercepted API URL:', request.url());
        }
    });

    console.log('Navigating...');
    try {
        await page.goto('https://masaoblue.github.io/iidx-top-rankers-viewer/sp/ranking', { waitUntil: 'networkidle2', timeout: 60000 });
        console.log('Page loaded.');
    } catch (e) {
        console.error('Navigation error:', e.message);
    }

    await browser.close();
    console.log('Done.');
})();
