// UNUSED: 初期疎通確認用の手動APIテスト。backend/SCRIPTS.md §2 参照。
const http = require('http');
const fs = require('fs');

const options = {
    hostname: 'localhost',
    port: 8080,
    path: '/api/auth/me',
    method: 'GET'
};

const req = http.request(options, (res) => {
    let body = '';
    res.setEncoding('utf8');
    res.on('data', (chunk) => body += chunk);
    res.on('end', () => fs.writeFileSync('test_res_me.txt', `STATUS: ${res.statusCode}\nBODY: ${body}`, 'utf8'));
});
req.on('error', (e) => fs.writeFileSync('test_res_me.txt', `ERROR: ${e.message}`, 'utf8'));
req.end();
