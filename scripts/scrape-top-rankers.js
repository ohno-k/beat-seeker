// Scrape all CSVs from https://masaoblue.github.io/iidx-top-rankers-viewer
// Data is stored as /versions/{N}/{N}_{shortName}_TOPRANKER_{pref2}_{prefName}.csv
// Supports version num 1..32 and prefecture num 0..59.
//
// Run: node scripts/scrape-top-rankers.js
// Stores downloaded CSVs under scripts/top-rankers-data/{version}/...
// Skips files already downloaded (resume-safe).

const fs = require('fs');
const path = require('path');
const https = require('https');

const OUT_DIR = path.join(__dirname, 'top-rankers-data');
const BASE = 'https://masaoblue.github.io/iidx-top-rankers-viewer';

const VERSIONS = [
  { num: 1,  name: '1st&substream',   shortName: '1st'   },
  { num: 2,  name: '2nd style',        shortName: '2nd'   },
  { num: 3,  name: '3rd style',        shortName: '3rd'   },
  { num: 4,  name: '4th style',        shortName: '4th'   },
  { num: 5,  name: '5th style',        shortName: '5th'   },
  { num: 6,  name: '6th style',        shortName: '6th'   },
  { num: 7,  name: '7th style',        shortName: '7th'   },
  { num: 8,  name: '8th style',        shortName: '8th'   },
  { num: 9,  name: '9th style',        shortName: '9th'   },
  { num: 10, name: '10th style',       shortName: '10th'  },
  { num: 11, name: 'IIDX RED',         shortName: 'RED'   },
  { num: 12, name: 'HAPPY SKY',        shortName: 'HS'    },
  { num: 13, name: 'DistorteD',        shortName: 'DD'    },
  { num: 14, name: 'GOLD',             shortName: 'GOLD'  },
  { num: 15, name: 'DJ TROOPERS',      shortName: 'DJT'   },
  { num: 16, name: 'EMPRESS',          shortName: 'EMP'   },
  { num: 17, name: 'SIRIUS',           shortName: 'SIR'   },
  { num: 18, name: 'Resort Anthem',    shortName: 'RA'    },
  { num: 19, name: 'Lincle',           shortName: 'Linc'  },
  { num: 20, name: 'tricoro',          shortName: 'tri'   },
  { num: 21, name: 'SPADA',            shortName: 'SPA'   },
  { num: 22, name: 'PENDUAL',          shortName: 'PEN'   },
  { num: 23, name: 'copula',           shortName: 'cop'   },
  { num: 24, name: 'SINOBUZ',          shortName: 'SINO'  },
  { num: 25, name: 'CANNON BALLERS',   shortName: 'CB'    },
  { num: 26, name: 'Rootage',          shortName: 'Root'  },
  { num: 27, name: 'HEROIC VERSE',     shortName: 'HERO'  },
  { num: 28, name: 'BISTROVER',        shortName: 'BIST'  },
  { num: 29, name: 'CastHour',         shortName: 'CH'    },
  { num: 30, name: 'RESIDENT',         shortName: 'RESI'  },
  { num: 31, name: 'EPOLIS',           shortName: 'EPO'   },
  { num: 32, name: 'Pinky Crush',      shortName: 'Pinky' },
];

// Prefecture names, index = prefecture num. Source: module 39399 v_ export.
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

// Prefecture name override per version (module 2811 `i` map): older versions used "米国" instead of "アメリカ" for idx 56.
const PREFECTURE_NAME_OVERRIDE = {
  24: { 56: '米国' },
  23: { 56: '米国' },
  22: { 56: '米国' },
  21: { 56: '米国' },
};

// Prefecture num override per version (module 2811 `o` map): in older versions, some prefecture numbers were different.
const PREFECTURE_NUM_OVERRIDE = {
  29: { 59: 57 },
  28: { 59: 57 },
  27: { 59: 57 },
  26: { 59: 57 },
  25: { 59: 57 },
  24: { 56: 51, 59: 53 },
  23: { 56: 51, 59: 53 },
  22: { 56: 51, 59: 53 },
  21: { 56: 51, 59: 53 },
};

// Prefecture-to-earliest-version map (module 39399 `a` map). Prefectures absent from this map use RED (11) as earliest.
const PREFECTURE_FIRST_VERSION = {
  51: 25, 52: 25, 53: 25, 54: 25, 55: 25, 56: 21, 57: 30, 58: 30, 59: 21,
};

function buildCsvUrl(versionNum, prefNum) {
  const v = VERSIONS[versionNum - 1];
  if (!v) throw new Error(`Unknown version num ${versionNum}`);
  const numMapped = PREFECTURE_NUM_OVERRIDE[versionNum]?.[prefNum] ?? prefNum;
  const pref2 = String(numMapped).padStart(2, '0');
  const prefName = PREFECTURE_NAME_OVERRIDE[versionNum]?.[prefNum] ?? PREFECTURE_NAMES[prefNum];
  const file = `${versionNum}_${v.shortName}_TOPRANKER_${pref2}_${prefName}.csv`;
  return `${BASE}/versions/${versionNum}/${encodeURIComponent(file)}`;
}

// Empirically-verified existence ranges. Older versions only have 全国 (prefecture=0), and
// 都道府県-level data starts from SPADA (v21). Some versions (v19 Lincle, v20 tricoro) lack
// even the 全国 file. The scraper still tolerates 404s, so these ranges are only for efficiency.
function versionsForPrefecture(prefNum) {
  if (prefNum === 0) {
    // 全国: v11 onward, with v19 and v20 missing.
    return VERSIONS.filter(v => v.num >= 11 && v.num !== 19 && v.num !== 20).map(v => v.num);
  }
  // Foreign prefectures: use explicit first-version map; Japanese prefectures start at v21.
  const first = PREFECTURE_FIRST_VERSION[prefNum] ?? 21;
  return VERSIONS.filter(v => v.num >= first).map(v => v.num);
}

function fetchBuffer(url) {
  return new Promise((resolve, reject) => {
    https.get(url, (res) => {
      if (res.statusCode === 302 || res.statusCode === 301) {
        return fetchBuffer(res.headers.location).then(resolve, reject);
      }
      if (res.statusCode !== 200) {
        res.resume();
        return reject(new Error(`HTTP ${res.statusCode} for ${url}`));
      }
      const chunks = [];
      res.on('data', (c) => chunks.push(c));
      res.on('end', () => resolve(Buffer.concat(chunks)));
      res.on('error', reject);
    }).on('error', reject);
  });
}

async function main() {
  if (!fs.existsSync(OUT_DIR)) fs.mkdirSync(OUT_DIR, { recursive: true });

  const tasks = [];
  for (let p = 0; p < PREFECTURE_NAMES.length; p++) {
    for (const vNum of versionsForPrefecture(p)) {
      tasks.push({ version: vNum, prefecture: p });
    }
  }
  console.log(`Total combinations to attempt: ${tasks.length}`);

  let success = 0, skipped = 0, missing = 0, failed = 0;
  const missingList = [];
  const concurrency = 8;
  let idx = 0;

  async function worker(id) {
    while (idx < tasks.length) {
      const cur = tasks[idx++];
      const versionDir = path.join(OUT_DIR, String(cur.version));
      if (!fs.existsSync(versionDir)) fs.mkdirSync(versionDir, { recursive: true });
      const prefName = PREFECTURE_NAME_OVERRIDE[cur.version]?.[cur.prefecture] ?? PREFECTURE_NAMES[cur.prefecture];
      const numMapped = PREFECTURE_NUM_OVERRIDE[cur.version]?.[cur.prefecture] ?? cur.prefecture;
      const fname = `${String(numMapped).padStart(2, '0')}_${prefName}.csv`;
      const outPath = path.join(versionDir, fname);
      if (fs.existsSync(outPath) && fs.statSync(outPath).size > 0) {
        skipped++;
        continue;
      }
      const url = buildCsvUrl(cur.version, cur.prefecture);
      try {
        const buf = await fetchBuffer(url);
        fs.writeFileSync(outPath, buf);
        success++;
        if ((success + missing) % 50 === 0) {
          console.log(`progress: ok=${success}, skipped=${skipped}, missing=${missing}, failed=${failed} / ${tasks.length}`);
        }
      } catch (e) {
        if (String(e.message).includes('HTTP 404')) {
          missing++;
          missingList.push({ ...cur, url });
        } else {
          failed++;
          console.warn(`FAIL ${url}: ${e.message}`);
        }
      }
    }
  }

  await Promise.all(Array.from({ length: concurrency }, (_, i) => worker(i)));

  fs.writeFileSync(path.join(OUT_DIR, 'missing.json'), JSON.stringify(missingList, null, 2));
  console.log(`DONE: ok=${success}, skipped=${skipped}, missing=${missing}, failed=${failed}, total=${tasks.length}`);
}

main().catch((e) => { console.error(e); process.exit(1); });
