/**
 * build-random-ranking.mts
 *
 * 譜面分析ページの「当たり配置ランキング」（正規・MIRROR・R-RANDOM・自由入力）用のデータを作る。
 * 譜面ごとに RANDOM の配置評価（frontend/src/utils/randomEval.ts）で 5,040 通りを 1P として順位付けし、
 * 2P のデータは作らない（2P の評価は 1P の左右反転なので、2P で並び p の順位 = 1P で p を逆順にした並びの順位。ページが読み替える）。
 * 次の 2 種類を frontend/public/data/random-ranking/ に書く。
 *
 * - summary.json: 譜面ごとの正規・MIRROR・R-RANDOM 最良の順位（一覧・並べ替えに使う）
 * - ranks-12.bin / ranks-11.bin / ranks-low.bin: 自由入力用。譜面ごとに 5,040 バイト（並びの辞書順）で、
 *   1 バイト = floor((順位 - 1) × 256 / 5040)。順位は ±10 位ほどの精度（上位何 % かの表示には十分）。
 *   ページは自由入力を使ったときだけ、そのレベルのファイルを読む
 *
 * 入力は再生データの JSONL（1 行 = GET /api/analysis/chart-playback のレスポンスと同じ形）。
 * 評価のロジックを変えたら作り直す。1 譜面 0.3〜1 秒、全 A/L（約 2,150 譜面）で 1 本なら 30 分前後。
 *
 * 使い方（Node 24 以上。.ts をそのまま読み込む）:
 *   node scripts/build-random-ranking.mts <再生データ.jsonl>                 … 1 本で全部作る
 *   node scripts/build-random-ranking.mts <再生データ.jsonl> --shard=0/3     … 分割して並行に作る（行番号 % 3 == 0 の譜面だけ。
 *   node scripts/build-random-ranking.mts <再生データ.jsonl> --shard=1/3        結果は random-ranking/parts/part-0-of-3.json）
 *   node scripts/build-random-ranking.mts <再生データ.jsonl> --shard=2/3
 *   node scripts/build-random-ranking.mts --merge=3                          … 分割の結果をまとめて summary.json と ranks-*.bin を書く
 * まとめた結果は入力の行の順に並ぶので、1 本で作ったときと同じになる。
 */
import fs from 'node:fs';
import path from 'node:path';
import readline from 'node:readline';
import { fileURLToPath, pathToFileURL } from 'node:url';

const here = path.dirname(fileURLToPath(import.meta.url));
const root = path.resolve(here, '..');

const args = process.argv.slice(2);
const flag = (name: string) => args.find(a => a.startsWith(`--${name}=`))?.split('=')[1];
const input = args.find(a => !a.startsWith('--'));
const shardArg = flag('shard');
const mergeArg = flag('merge');
if (!input && !mergeArg) {
  console.error('usage: node scripts/build-random-ranking.mts <playback.jsonl> [--shard=i/n] | --merge=n');
  process.exit(1);
}
const outDir = path.join(root, 'frontend/public/data/random-ranking');
const partsDir = path.join(outDir, 'parts');
fs.mkdirSync(outDir, { recursive: true });

const TOTAL = 5040;
const groupOf = (level: number) => (level >= 12 ? '12' : level === 11 ? '11' : 'low');

type Row = {
  t: string; d: string; l: number; n: number; x: string;
  off: number; mir: number; rr: number; rrp: string; best: string;
  g: string; i: number;
};
/** 1 譜面分の結果（分割の途中ファイルにも書く）。seq = 入力の行番号、ranks = 5,040 バイトの base64 */
type Item = { seq: number; row: Omit<Row, 'i'>; ranks: string };

/** 結果を入力の行の順に並べ、summary.json と ranks-*.bin を書く。 */
function writeOutputs(items: Item[]) {
  items.sort((a, b) => a.seq - b.seq);
  const rows: Row[] = [];
  const bins: Record<string, Buffer[]> = { '12': [], '11': [], low: [] };
  for (const it of items) {
    rows.push({ ...it.row, i: bins[it.row.g].length });
    bins[it.row.g].push(Buffer.from(it.ranks, 'base64'));
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
  return rows.length;
}

if (mergeArg) {
  // ── 分割の結果をまとめる ──
  const n = Number(mergeArg);
  const items: Item[] = [];
  for (let k = 0; k < n; k++) {
    const file = path.join(partsDir, `part-${k}-of-${n}.json`);
    if (!fs.existsSync(file)) {
      console.error(`見つかりません: ${file}（--shard=${k}/${n} がまだ終わっていない）`);
      process.exit(1);
    }
    items.push(...(JSON.parse(fs.readFileSync(file, 'utf8')) as Item[]));
  }
  const count = writeOutputs(items);
  fs.rmSync(partsDir, { recursive: true, force: true });
  console.log(`MERGED ${count} charts from ${n} parts → ${outDir}`);
  process.exit(0);
}

const { buildChartTimeline } = await import(pathToFileURL(path.join(root, 'frontend/src/utils/chartPlayback.ts')).href);
const { evaluateRandom, allPatterns } = await import(pathToFileURL(path.join(root, 'frontend/src/utils/randomEval.ts')).href);
const PATTERNS: string[] = allPatterns();
const R_RANDOM: string[] = [];
for (const base of ['1234567', '7654321']) for (let s = 1; s <= 6; s++) R_RANDOM.push(base.slice(s) + base.slice(0, s));

let shard = 0;
let shards = 1;
if (shardArg) {
  [shard, shards] = shardArg.split('/').map(Number);
  if (!(shards >= 1 && shard >= 0 && shard < shards)) {
    console.error(`--shard は i/n（0 ≤ i < n）: ${shardArg}`);
    process.exit(1);
  }
}

const items: Item[] = [];
const rl = readline.createInterface({ input: fs.createReadStream(input!), crlfDelay: Infinity });
const t0 = Date.now();
let seq = -1;
for await (const line of rl) {
  if (!line.trim()) continue;
  seq++;
  if (seq % shards !== shard) continue;
  const d = JSON.parse(line);
  const ev = evaluateRandom(buildChartTimeline(d), 1);
  const rankOf = (p: string) => ev.byPattern.get(p).rank as number;
  const rr = R_RANDOM.map(p => ({ p, r: rankOf(p) })).sort((a, b) => a.r - b.r)[0];
  const buf = Buffer.alloc(TOTAL);
  PATTERNS.forEach((p, idx) => { buf[idx] = Math.min(255, Math.floor(((rankOf(p) - 1) * 256) / TOTAL)); });
  items.push({
    seq,
    row: { t: d.title, d: d.difficulty, l: d.level, n: d.notes, x: d.textage,
      off: rankOf('1234567'), mir: rankOf('7654321'), rr: rr.r, rrp: rr.p, best: ev.candidates[0].pattern, g: groupOf(d.level) },
    ranks: buf.toString('base64'),
  });
  if (items.length % 100 === 0) console.log(`${items.length} charts, ${((Date.now() - t0) / 1000).toFixed(0)}s`);
}

if (shards > 1) {
  fs.mkdirSync(partsDir, { recursive: true });
  const file = path.join(partsDir, `part-${shard}-of-${shards}.json`);
  fs.writeFileSync(file, JSON.stringify(items));
  console.log(`DONE shard ${shard}/${shards}: ${items.length} charts in ${((Date.now() - t0) / 1000).toFixed(0)}s → ${file}`);
} else {
  const count = writeOutputs(items);
  console.log(`DONE ${count} charts in ${((Date.now() - t0) / 1000).toFixed(0)}s → ${outDir}`);
}
