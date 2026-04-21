// Gzip all top-rankers CSVs into backend/src/main/resources/top-rankers-data/
// Also writes a manifest.json listing all (version, prefecture) combos with prefecture names.
// Run: node scripts/compress-top-rankers.js

const fs = require('fs');
const path = require('path');
const zlib = require('zlib');

const SRC = path.join(__dirname, 'top-rankers-data');
const DST = path.resolve(__dirname, '..', 'backend', 'src', 'main', 'resources', 'top-rankers-data');

// Version definitions mirror scrape-top-rankers.js. Version 0 is the
// virtual "歴代" version produced by merge-all-top-rankers.js.
const VERSIONS = [
  { num: 0,  name: '歴代' },
  { num: 1,  name: '1st&substream' },  { num: 2,  name: '2nd style' },     { num: 3,  name: '3rd style' },
  { num: 4,  name: '4th style' },       { num: 5,  name: '5th style' },     { num: 6,  name: '6th style' },
  { num: 7,  name: '7th style' },       { num: 8,  name: '8th style' },     { num: 9,  name: '9th style' },
  { num: 10, name: '10th style' },      { num: 11, name: 'IIDX RED' },      { num: 12, name: 'HAPPY SKY' },
  { num: 13, name: 'DistorteD' },       { num: 14, name: 'GOLD' },          { num: 15, name: 'DJ TROOPERS' },
  { num: 16, name: 'EMPRESS' },         { num: 17, name: 'SIRIUS' },        { num: 18, name: 'Resort Anthem' },
  { num: 19, name: 'Lincle' },          { num: 20, name: 'tricoro' },       { num: 21, name: 'SPADA' },
  { num: 22, name: 'PENDUAL' },         { num: 23, name: 'copula' },        { num: 24, name: 'SINOBUZ' },
  { num: 25, name: 'CANNON BALLERS' },  { num: 26, name: 'Rootage' },       { num: 27, name: 'HEROIC VERSE' },
  { num: 28, name: 'BISTROVER' },       { num: 29, name: 'CastHour' },      { num: 30, name: 'RESIDENT' },
  { num: 31, name: 'EPOLIS' },          { num: 32, name: 'Pinky Crush' },
];

if (!fs.existsSync(DST)) fs.mkdirSync(DST, { recursive: true });

const manifest = [];
let count = 0;
let origTotal = 0;
let gzTotal = 0;

for (const vDir of fs.readdirSync(SRC).sort((a, b) => Number(a) - Number(b))) {
  const vPath = path.join(SRC, vDir);
  if (!fs.statSync(vPath).isDirectory()) continue;
  const dstVDir = path.join(DST, vDir);
  if (!fs.existsSync(dstVDir)) fs.mkdirSync(dstVDir, { recursive: true });
  const versionNum = Number(vDir);
  const versionName = VERSIONS.find(v => v.num === versionNum)?.name ?? `v${versionNum}`;

  const files = fs.readdirSync(vPath).sort();
  for (const fname of files) {
    if (!fname.endsWith('.csv')) continue;
    const srcPath = path.join(vPath, fname);
    const dstPath = path.join(dstVDir, fname + '.gz');
    const raw = fs.readFileSync(srcPath);
    const gz = zlib.gzipSync(raw, { level: 9 });
    fs.writeFileSync(dstPath, gz);
    count++;
    origTotal += raw.length;
    gzTotal += gz.length;

    // Filename like "01_北海道.csv" → prefNum=1, prefName=北海道
    const m = fname.match(/^(\d{2})_(.+)\.csv$/);
    if (!m) throw new Error(`Unexpected filename ${fname}`);
    manifest.push({
      versionNum,
      versionName,
      prefectureFileNum: Number(m[1]),
      prefectureName: m[2],
      resourcePath: `top-rankers-data/${vDir}/${fname}.gz`,
    });
  }
}

fs.writeFileSync(path.join(DST, 'manifest.json'), JSON.stringify(manifest, null, 0));
console.log(`Compressed ${count} files: ${(origTotal / 1024 / 1024).toFixed(1)}MB -> ${(gzTotal / 1024 / 1024).toFixed(1)}MB`);
console.log(`Manifest: ${manifest.length} entries`);
