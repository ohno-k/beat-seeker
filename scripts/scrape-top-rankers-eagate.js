// Scrape the official TOP RANKER data from e-amusement (eagate) for versions that
// masaoblue.github.io/iidx-top-rankers-viewer does not provide (33 Sparkle Shower onward).
//
// Source page: https://p.eagate.573.jp/game/2dx/{ver}/ranking/topranker.html
// The page POSTs to /game/2dx/{ver}/ranking/json/topranker.html (no login required) with
//   pref_id (0=全国..59=海外), play_style (0=SP), series_id (0=1st&substream..), page, limit
// and gets one series' songs per call. We call every series for each prefecture and
// write the same CSV layout as scrape-top-rankers.js:
//   scripts/top-rankers-data/{ver}/{NN}_{prefName}.csv
//
// Run: node scripts/scrape-top-rankers-eagate.js [version=33]
// Skips files already downloaded (resume-safe). Delete a file to re-fetch it.

const fs = require('fs');
const path = require('path');
const https = require('https');

const VERSION = Number(process.argv[2] ?? 33);
const OUT_DIR = path.join(__dirname, 'top-rankers-data', String(VERSION));
const API = `https://p.eagate.573.jp/game/2dx/${VERSION}/ranking/json/topranker.html`;
const PLAY_STYLE_SP = 0;
const CONCURRENCY = 4;
const REQUEST_INTERVAL_MS = 300;

const HEADER =
  'バージョン,タイトル,' +
  'BEGINNER EXスコア,BEGINNER DJName,BEGINNER 都道府県,' +
  'NORMAL EXスコア,NORMAL DJName,NORMAL 都道府県,' +
  'HYPER EXスコア,HYPER DJName,HYPER 都道府県,' +
  'ANOTHER EXスコア,ANOTHER DJName,ANOTHER 都道府県,' +
  'LEGGENDARIA EXスコア,LEGGENDARIA DJName,LEGGENDARIA 都道府県';

// eagate の TOP RANKER 表記 → 公式スコア CSV（= DB の song_definitions）表記。
// 曲名は公式 CSV 表記が正なので、ここで寄せておかないと BEAT-PT 集計で譜面が引けずに落ちる。
// 前後の空白（"Nyan Nyan University " など）は normalizeTitle で一律に落とす。
const TITLE_FIXES = {
  'Blind Justice ～Torn souls, Hurt Faiths ～': 'Blind Justice ～Torn souls， Hurt Faiths ～',
  'ROCK女 feat. 大山愛未, Ken': 'ROCK女 feat. 大山愛未， Ken',
  'Praludium': 'Präludium',
  'Geirskogul': 'Geirskögul',
  '!Viva!': '¡Viva!',
  'ZEИITH': 'ZENITH',
};

function normalizeTitle(raw) {
  const t = decodeEntities(raw).trim();
  return TITLE_FIXES[t] ?? t;
}

const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

function decodeEntities(s) {
  if (s == null) return '';
  return String(s)
    .replace(/&#(\d+);/g, (_, n) => String.fromCodePoint(Number(n)))
    .replace(/&#x([0-9a-f]+);/gi, (_, n) => String.fromCodePoint(parseInt(n, 16)))
    .replace(/&quot;/g, '"')
    .replace(/&#039;|&apos;/g, "'")
    .replace(/&lt;/g, '<')
    .replace(/&gt;/g, '>')
    .replace(/&amp;/g, '&');
}

function csvEscape(s) {
  const str = s == null ? '' : String(s);
  if (str.includes(',') || str.includes('"') || str.includes('\n')) {
    return '"' + str.replace(/"/g, '""') + '"';
  }
  return str;
}

function postJson(prefId, seriesId) {
  const body = `pref_id=${prefId}&play_style=${PLAY_STYLE_SP}&page=0&limit=5000&series_id=${seriesId}`;
  return new Promise((resolve, reject) => {
    const req = https.request(API, {
      method: 'POST',
      headers: {
        'Content-Type': 'application/x-www-form-urlencoded; charset=UTF-8',
        'Content-Length': Buffer.byteLength(body),
        'User-Agent': 'Mozilla/5.0',
        'X-Requested-With': 'XMLHttpRequest',
      },
    }, (res) => {
      const chunks = [];
      res.on('data', (c) => chunks.push(c));
      res.on('end', () => {
        const text = Buffer.concat(chunks).toString('utf8');
        if (res.statusCode !== 200) return reject(new Error(`HTTP ${res.statusCode}`));
        try {
          resolve(JSON.parse(text));
        } catch (e) {
          reject(new Error(`non-JSON response (${text.slice(0, 80)})`));
        }
      });
      res.on('error', reject);
    });
    req.on('error', reject);
    req.end(body);
  });
}

async function fetchWithRetry(prefId, seriesId) {
  for (let attempt = 1; ; attempt++) {
    try {
      const j = await postJson(prefId, seriesId);
      if (j.status !== 0) throw new Error(`status=${j.status}`);
      return j;
    } catch (e) {
      if (attempt >= 5) throw new Error(`pref=${prefId} series=${seriesId}: ${e.message}`);
      await sleep(2000 * attempt);
    }
  }
}

function rowsToCsv(seriesName, list) {
  const lines = [];
  for (const r of list) {
    const cells = [csvEscape(seriesName), csvEscape(normalizeTitle(r.music))];
    for (let d = 0; d < 5; d++) {
      const score = Number(r[`score_${d}`]) || 0;
      cells.push(String(score));
      cells.push(csvEscape(score > 0 ? decodeEntities(r[`name_${d}`]) : ''));
      cells.push(csvEscape(score > 0 ? decodeEntities(r[`area_${d}`]) : '-'));
    }
    lines.push(cells.join(','));
  }
  return lines;
}

async function main() {
  fs.mkdirSync(OUT_DIR, { recursive: true });

  // 1 回目の呼び出しで地域一覧とシリーズ一覧を得る（ページのセレクトと同じ内容）。
  const first = await fetchWithRetry(0, 0);
  const prefs = first.pref_list.map((p) => ({ id: p.id, name: decodeEntities(p.name) }));
  const series = first.series_list.map((s) => ({ id: s.id, name: decodeEntities(s.name) }));
  console.log(`version ${VERSION}: ${prefs.length} areas x ${series.length} series`);

  let written = 0, skipped = 0, empty = 0;
  let next = 0;

  async function worker() {
    while (next < prefs.length) {
      const pref = prefs[next++];
      const fname = `${String(pref.id).padStart(2, '0')}_${pref.name}.csv`;
      const outPath = path.join(OUT_DIR, fname);
      if (fs.existsSync(outPath) && fs.statSync(outPath).size > 0) { skipped++; continue; }

      const lines = [HEADER];
      let songCount = 0;
      for (const s of series) {
        const j = await fetchWithRetry(pref.id, s.id);
        await sleep(REQUEST_INTERVAL_MS);
        // 全譜面 0 点の行（その地域で誰もプレーしていない曲）は masaoblue 版でも入っていないので落とす。
        const played = j.list.filter((r) => [0, 1, 2, 3, 4].some((d) => Number(r[`score_${d}`]) > 0));
        lines.push(...rowsToCsv(s.name, played));
        songCount += played.length;
      }
      if (songCount === 0) { empty++; console.log(`  ${fname}: no data`); continue; }
      fs.writeFileSync(outPath, lines.join('\n') + '\n');
      written++;
      console.log(`  ${fname}: ${songCount} songs`);
    }
  }

  await Promise.all(Array.from({ length: CONCURRENCY }, () => worker()));
  console.log(`DONE: written=${written}, skipped=${skipped}, empty=${empty}`);
}

main().catch((e) => { console.error(e); process.exit(1); });
