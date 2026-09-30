/**
 * build-random-ranking.mts
 *
 * 譜面分析ページの「当たり配置ランキング」（正規・MIRROR・R-RANDOM・自由入力）用のデータを作る。
 * 譜面ごとに RANDOM の配置評価（frontend/src/utils/randomEval.ts、1P 基準）で 5,040 通りを順位付けし、
 * 次の 2 種類を frontend/public/data/random-ranking/ に書く。
 *
 * - summary.json: 譜面ごとの正規・MIRROR・R-RANDOM 最良の順位（一覧・並べ替えに使う）
 * - ranks-12.bin / ranks-11.bin / ranks-low.bin: 自由入力用。譜面ごとに 5,040 バイト（並びの辞書順）で、
 *   1 バイト = floor((順位 - 1) × 256 / 5040)。順位は ±10 位ほどの精度（上位何 % かの表示には十分）。
 *   ページは自由入力を使ったときだけ、そのレベルのファイルを読む
 *
 * 入力は再生データの JSONL（1 行 = GET /api/analysis/chart-playback のレスポンスと同じ形）。
 * 評価のロジックを変えたら作り直す。1 譜面 0.3〜1 秒、全 A/L（約 1,300 譜面）で 20 分前後。
 *
 * 使い方（Node 24 以上。.ts をそのまま読み込む）:
 *   node scripts/build-random-ranking.mts <再生データ.jsonl>
 */
import fs from 'node:fs';
import path from 'node:path';
import readline from 'node:readline';
import { fileURLToPath, pathToFileURL } from 'node:url';

const here = path.dirname(fileURLToPath(import.meta.url));
const root = path.resolve(here, '..');
const { buildChartTimeline } = await import(pathToFileURL(path.join(root, 'frontend/src/utils/chartPlayback.ts')).href);
const { evaluateRandom, allPatterns } = await import(pathToFileURL(path.join(root, 'frontend/src/utils/randomEval.ts')).href);

const input = process.argv[2];
if (!input) {
  console.error('usage: node scripts/build-random-ranking.mts <playback.jsonl>');
  process.exit(1);
}
const outDir = path.join(root, 'frontend/public/data/random-ranking');
fs.mkdirSync(outDir, { recursive: true });

const PATTERNS: string[] = allPatterns();
const TOTAL = PATTERNS.length; // 5040
const R_RANDOM: string[] = [];
for (const base of ['1234567', '7654321']) for (let s = 1; s <= 6; s++) R_RANDOM.push(base.slice(s) + base.slice(0, s));
const groupOf = (level: number) => (level >= 12 ? '12' : level === 11 ? '11' : 'low');

type Row = {
  t: string; d: string; l: number; n: number; x: string;
  off: number; mir: number; rr: number; rrp: string; best: string;
  g: string; i: number;
};
const rows: Row[] = [];
const bins: Record<string, Buffer[]> = { '12': [], '11': [], low: [] };

const rl = readline.createInterface({ input: fs.createReadStream(input), crlfDelay: Infinity });
const t0 = Date.now();
for await (const line of rl) {
  if (!line.trim()) continue;
  const d = JSON.parse(line);
  const ev = evaluateRandom(buildChartTimeline(d), 1);
  const rankOf = (p: string) => ev.byPattern.get(p).rank as number;
  const rr = R_RANDOM.map(p => ({ p, r: rankOf(p) })).sort((a, b) => a.r - b.r)[0];
  const g = groupOf(d.level);
  const buf = Buffer.alloc(TOTAL);
  PATTERNS.forEach((p, idx) => { buf[idx] = Math.min(255, Math.floor(((rankOf(p) - 1) * 256) / TOTAL)); });
  rows.push({ t: d.title, d: d.difficulty, l: d.level, n: d.notes, x: d.textage,
    off: rankOf('1234567'), mir: rankOf('7654321'), rr: rr.r, rrp: rr.p, best: ev.candidates[0].pattern,
    g, i: bins[g].length });
  bins[g].push(buf);
  if (rows.length % 100 === 0) console.log(`${rows.length} charts, ${((Date.now() - t0) / 1000).toFixed(0)}s`);
}

for (const [g, list] of Object.entries(bins)) fs.writeFileSync(path.join(outDir, `ranks-${g}.bin`), Buffer.concat(list));
const jst = new Date(Date.now() + 9 * 3600 * 1000).toISOString().replace('T', ' ').slice(0, 16);
fs.writeFileSync(path.join(outDir, 'summary.json'), JSON.stringify({
  generatedAt: jst,
  side: 1,
  total: TOTAL,
  bucketsPerRank: 256,
  charts: rows,
}));
console.log(`DONE ${rows.length} charts in ${((Date.now() - t0) / 1000).toFixed(0)}s → ${outDir}`);
