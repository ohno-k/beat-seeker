// UNUSED: 初期疎通確認用の手動APIテスト。test_req.js とほぼ同一。backend/SCRIPTS.md §2 参照。
const http = require('http');
const fs = require('fs');

const data = JSON.stringify({
    iidxId: '9999-9998',
    password: 'password123',
    displayName: 'TestNode',
    danRank: '皆伝',
    arenaRank: 'A1'
});

const options = {
    hostname: 'localhost',
    port: 8080,
    path: '/api/auth/register',
    method: 'POST',
    headers: {
        'Content-Type': 'application/json',
        'Content-Length': Buffer.byteLength(data)
    }
};

const req = http.request(options, (res) => {
    let body = '';
    res.setEncoding('utf8');
    res.on('data', (chunk) => body += chunk);
    res.on('end', () => {
        fs.writeFileSync('test_res.txt', `STATUS: ${res.statusCode}\nBODY: ${body}`, 'utf8');
    });
});

req.on('error', (e) => {
    fs.writeFileSync('test_res.txt', `ERROR: ${e.message}`, 'utf8');
});

req.write(data);
req.end();
