const https = require('https');

const BASE = 'https://masaoblue.github.io/iidx-top-rankers-viewer';

const cases = [
  { v: 32, vShort: 'Pinky', p: 0,  pName: '全国' },
  { v: 32, vShort: 'Pinky', p: 13, pName: '東京都' },
  { v: 32, vShort: 'Pinky', p: 59, pName: '海外' },
  { v: 21, vShort: 'SPA',   p: 56, pName: '米国', pNum: 51 },
  { v: 25, vShort: 'CB',    p: 51, pName: 'タイ', pNum: 51 },
  { v: 11, vShort: 'RED',   p: 1,  pName: '北海道' },
  { v: 11, vShort: 'RED',   p: 0,  pName: '全国' },
  { v: 1,  vShort: '1st',   p: 0,  pName: '全国' },
  { v: 20, vShort: 'tri',   p: 48, pName: '香港' },
  { v: 20, vShort: 'tri',   p: 1,  pName: '北海道' },
];

function head(url) {
  return new Promise((resolve) => {
    const req = https.request(url, { method: 'HEAD' }, (res) => {
      resolve(res.statusCode);
      res.resume();
    });
    req.on('error', () => resolve('ERR'));
    req.end();
  });
}

(async () => {
  for (const c of cases) {
    const pNum = c.pNum ?? c.p;
    const pref2 = String(pNum).padStart(2, '0');
    const file = `${c.v}_${c.vShort}_TOPRANKER_${pref2}_${c.pName}.csv`;
    const url = `${BASE}/versions/${c.v}/${encodeURIComponent(file)}`;
    const code = await head(url);
    console.log(`${code}  ${file}`);
  }
})();
