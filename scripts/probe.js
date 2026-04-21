const https = require('https');
const BASE = 'https://masaoblue.github.io/iidx-top-rankers-viewer';
const V = [
  [1,'1st'],[2,'2nd'],[3,'3rd'],[4,'4th'],[5,'5th'],[6,'6th'],[7,'7th'],[8,'8th'],[9,'9th'],[10,'10th'],
  [11,'RED'],[12,'HS'],[13,'DD'],[14,'GOLD'],[15,'DJT'],[16,'EMP'],[17,'SIR'],[18,'RA'],[19,'Linc'],[20,'tri'],
  [21,'SPA'],[22,'PEN'],[23,'cop'],[24,'SINO'],[25,'CB'],[26,'Root'],[27,'HERO'],[28,'BIST'],[29,'CH'],[30,'RESI'],[31,'EPO'],[32,'Pinky']
];

function head(url) {
  return new Promise((resolve) => {
    const req = https.request(url, { method: 'HEAD' }, (res) => { resolve(res.statusCode); res.resume(); });
    req.on('error', () => resolve('ERR'));
    req.end();
  });
}
(async () => {
  // Probe prefecture=0 (全国) for each version
  console.log('--- 全国 (prefecture=0) per version ---');
  for (const [v, s] of V) {
    const u = `${BASE}/versions/${v}/${encodeURIComponent(`${v}_${s}_TOPRANKER_00_全国.csv`)}`;
    console.log(`v${v}(${s}): ${await head(u)}`);
  }
  console.log('--- 北海道 (prefecture=1) per version ---');
  for (const [v, s] of V) {
    const u = `${BASE}/versions/${v}/${encodeURIComponent(`${v}_${s}_TOPRANKER_01_北海道.csv`)}`;
    console.log(`v${v}(${s}): ${await head(u)}`);
  }
})();
