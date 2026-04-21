// Merge per-version TOPRANKER CSVs into "all-time best" CSVs per prefecture.
// For each (title, difficulty), picks the max EXスコア across every version's
// CSV for that prefecture. Output: scripts/top-rankers-data/0/{NN}_{name}.csv
//
// Run: node scripts/merge-all-top-rankers.js

const fs = require('fs');
const path = require('path');

const SRC = path.join(__dirname, 'top-rankers-data');
const OUT_DIR = path.join(SRC, '0');

const PREFECTURE_NAMES = [
  '全国','北海道','青森県','岩手県','宮城県','秋田県','山形県','福島県',
  '茨城県','栃木県','群馬県','埼玉県','千葉県','東京都','神奈川県','新潟県',
  '富山県','石川県','福井県','山梨県','長野県','岐阜県','静岡県','愛知県',
  '三重県','滋賀県','京都府','大阪府','兵庫県','奈良県','和歌山県','鳥取県',
  '島根県','岡山県','広島県','山口県','徳島県','香川県','愛媛県','高知県',
  '福岡県','佐賀県','長崎県','熊本県','大分県','宮崎県','鹿児島県','沖縄県',
  '香港','韓国','台湾','タイ','インドネシア','シンガポール','フィリピン',
  'マカオ','アメリカ','オーストラリア','ニュージーランド','海外',
];

const PREFECTURE_NAME_OVERRIDE = {
  24: { 56: '米国' }, 23: { 56: '米国' }, 22: { 56: '米国' }, 21: { 56: '米国' },
};
const PREFECTURE_NUM_OVERRIDE = {
  29: { 59: 57 }, 28: { 59: 57 }, 27: { 59: 57 }, 26: { 59: 57 }, 25: { 59: 57 },
  24: { 56: 51, 59: 53 }, 23: { 56: 51, 59: 53 },
  22: { 56: 51, 59: 53 }, 21: { 56: 51, 59: 53 },
};

const DIFF_COUNT = 5; // BEGINNER, NORMAL, HYPER, ANOTHER, LEGGENDARIA
const HEADER =
  'バージョン,タイトル,' +
  'BEGINNER EXスコア,BEGINNER DJName,BEGINNER 都道府県,' +
  'NORMAL EXスコア,NORMAL DJName,NORMAL 都道府県,' +
  'HYPER EXスコア,HYPER DJName,HYPER 都道府県,' +
  'ANOTHER EXスコア,ANOTHER DJName,ANOTHER 都道府県,' +
  'LEGGENDARIA EXスコア,LEGGENDARIA DJName,LEGGENDARIA 都道府県';

function splitCsvLine(line) {
  const out = [];
  let cur = '';
  let inQuotes = false;
  for (let i = 0; i < line.length; i++) {
    const c = line[i];
    if (inQuotes) {
      if (c === '"') {
        if (line[i + 1] === '"') { cur += '"'; i++; } else { inQuotes = false; }
      } else cur += c;
    } else {
      if (c === ',') { out.push(cur); cur = ''; }
      else if (c === '"' && cur.length === 0) inQuotes = true;
      else cur += c;
    }
  }
  out.push(cur);
  return out;
}

function csvEscape(s) {
  if (s == null) return '';
  const str = String(s);
  if (str.includes(',') || str.includes('"') || str.includes('\n')) {
    return '"' + str.replace(/"/g, '""') + '"';
  }
  return str;
}

function csvFilenameFor(versionNum, prefNum) {
  const mappedNum = PREFECTURE_NUM_OVERRIDE[versionNum]?.[prefNum] ?? prefNum;
  const mappedName = PREFECTURE_NAME_OVERRIDE[versionNum]?.[prefNum] ?? PREFECTURE_NAMES[prefNum];
  return `${String(mappedNum).padStart(2, '0')}_${mappedName}.csv`;
}

function mergePrefecture(prefNum) {
  // title -> { version: string, diffs: [{score, dj, region}, ...] }
  const songs = new Map();

  for (let v = 1; v <= 32; v++) {
    const fname = csvFilenameFor(v, prefNum);
    const filePath = path.join(SRC, String(v), fname);
    if (!fs.existsSync(filePath)) continue;
    const text = fs.readFileSync(filePath, 'utf8');
    const lines = text.split(/\r?\n/);
    for (let li = 1; li < lines.length; li++) {
      const line = lines[li];
      if (!line) continue;
      const cols = splitCsvLine(line);
      if (cols.length < 2 + DIFF_COUNT * 3) continue;
      const version = cols[0];
      const title = cols[1];
      if (!title) continue;
      let entry = songs.get(title);
      if (!entry) {
        entry = { version, diffs: Array.from({ length: DIFF_COUNT }, () => ({ score: 0, dj: '', region: '-' })) };
        songs.set(title, entry);
      } else if (!entry.version && version) {
        entry.version = version;
      }
      for (let d = 0; d < DIFF_COUNT; d++) {
        const sRaw = cols[2 + d * 3];
        const dj = cols[3 + d * 3] ?? '';
        const region = cols[4 + d * 3] ?? '-';
        const score = Number(sRaw);
        if (!Number.isFinite(score) || score <= 0) continue;
        if (score > entry.diffs[d].score) {
          entry.diffs[d] = { score, dj, region };
        }
      }
    }
  }

  if (songs.size === 0) return null;

  const rows = [HEADER];
  for (const [title, entry] of songs) {
    const cells = [csvEscape(entry.version), csvEscape(title)];
    for (const d of entry.diffs) {
      cells.push(String(d.score));
      cells.push(csvEscape(d.dj));
      cells.push(csvEscape(d.region));
    }
    rows.push(cells.join(','));
  }
  return rows.join('\n') + '\n';
}

function main() {
  if (!fs.existsSync(OUT_DIR)) fs.mkdirSync(OUT_DIR, { recursive: true });
  let written = 0;
  for (let p = 0; p < PREFECTURE_NAMES.length; p++) {
    const csv = mergePrefecture(p);
    if (!csv) continue;
    const outName = `${String(p).padStart(2, '0')}_${PREFECTURE_NAMES[p]}.csv`;
    fs.writeFileSync(path.join(OUT_DIR, outName), csv);
    written++;
  }
  console.log(`Merged ${written} prefectures into ${OUT_DIR}`);
}

main();
