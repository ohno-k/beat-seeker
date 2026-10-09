/**
 * fit-random-weights.mts
 *
 * 当たり配置ランキング（frontend/src/utils/randomEval.ts）の「減点の形ごとの係数」を、正解データから学習する。
 * 学習した係数は frontend/src/utils/randomWeights.ts に書き、ランキングは scripts/build-random-ranking.mts で作り直す。
 *
 * 正解データは 2 種類:
 * - 「どっちが押しやすい？」の回答（サイドバーの「配置アンケート」。GET /api/admin/random-pairs/export）:
 *   同じ区間を 2 つの並びで見比べた答え。区間の減点の形ごとの数を数え直して使う（評価の数え方を変えても学び直せる）
 * - オプション投票（弱い正解。同じ export に入っている）: 正規と MIRROR の票の比を、譜面全体の正規と MIRROR の比較の答えとみなす。
 *   票の割合をそのまま「正規の方が押しやすい確率」として使い、重みは回答より軽くする（--weak-weight）
 *
 * モデル（Bradley-Terry / ロジスティック回帰）:
 *   P(左が押しやすい) = σ( Σ_k β_k × (右の減点_k − 左の減点_k) / 規模 )
 *   規模 = 左右の減点の合計の平均 + 1（長い区間・譜面全体の比較で確率が張り付かないように差を割合にする）。
 *   β_k ≥ 0。データが少ないうちに暴れないよう、「全部同じ係数」（今の数え方）に引き戻す罰則 λ Σ (β_k / c0 − 1)² を付ける
 *   （c0 = 全部同じ係数で当てはめたときの係数）。λ は交差検証で選ぶ。ランキングは係数の比だけで決まるので、
 *   書き出すときは平均 1 にそろえる。
 *
 * 適当に押した回答への対策（{@link reliabilityOf}）:
 * - 問題を出してから {@link MIN_RESPONSE_MS} ミリ秒未満の回答は、見ていないものとして使わない（管理者を除く）
 * - 人ごとの信頼度で回答の重みを決める（管理者の回答は 1 のまま）。確認問題（obvious = 上位 1% と下位 1%、
 *   repeat = 前の問題の左右を入れ替えた出し直し）の正解率、左右どちらかばかり選ぶ偏り、速すぎる回答の割合から決め、
 *   0 の人の回答は使わない。確認問題の obvious は今の評価から作った答えなので、学習には入れない
 *
 * 検証: 譜面ごとに 5 つに分けた交差検証で、学習に使っていない回答の正解率（同じくらいを除く）と対数損失を、
 * 「全部同じ係数」と比べる。オプション投票の一致率（正規と MIRROR の票の多い方を当てられるか）も出す。
 * --write のときも、回答が {@link MIN_HUMAN} 件以上あり、学習後の方が正解率・対数損失とも良いときだけ書く（--force で強制）。
 *
 * 使い方（Node 22.18 以上。.ts をそのまま読み込む）:
 *   node scripts/fit-random-weights.mts <export.json> [--playback=<再生データ.jsonl>] [--write]
 *   BEAT_SEEKER_TOKEN=<管理者の個人 API トークン> node scripts/fit-random-weights.mts --write
 *     … export を API から取る（毎晩などの自動実行用。トークンはプロフィールの外部連携で発行。ログインの JWT でもよいが 7 日で切れる）
 * オプション:
 *   --api=<URL>         再生データ（と export）を取りに行くサーバー（既定 https://beat-seeker.com）
 *   --playback=<jsonl>  手元の再生データ（build-random-ranking.mts の入力と同じ形）。無い譜面は --api から取って
 *                       data/random-learning/playback-cache.jsonl に貯める
 *   --weak-weight=<数>  オプション投票 1 譜面の重み（既定 0.3。0 で使わない）
 *   --write             条件を満たせば randomWeights.ts を書き換える
 *   --force             条件を満たさなくても書き換える
 * 結果の詳細は data/random-learning/report-<日時>.json に残す。
 */
import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath, pathToFileURL } from 'node:url';

const here = path.dirname(fileURLToPath(import.meta.url));
const root = path.resolve(here, '..');
const { buildChartTimeline } = await import(pathToFileURL(path.join(root, 'frontend/src/utils/chartPlayback.ts')).href);
const { makeWindowScorer, PENALTY_KEYS, PENALTY_LABELS } = await import(pathToFileURL(path.join(root, 'frontend/src/utils/randomEval.ts')).href);
const { RANDOM_WEIGHTS } = await import(pathToFileURL(path.join(root, 'frontend/src/utils/randomWeights.ts')).href);
const KEYS: string[] = [...PENALTY_KEYS];
const D = KEYS.length;

const args = process.argv.slice(2);
const flag = (name: string) => args.find(a => a.startsWith(`--${name}=`))?.slice(name.length + 3);
const has = (name: string) => args.includes(`--${name}`);
const exportFile = args.find(a => !a.startsWith('--'));
const api = (flag('api') ?? 'https://beat-seeker.com').replace(/\/$/, '');
const weakWeight = Number(flag('weak-weight') ?? 0.3);
const outDir = path.join(root, 'data/random-learning');
const cacheFile = path.join(outDir, 'playback-cache.jsonl');
const weightsFile = path.join(root, 'frontend/src/utils/randomWeights.ts');
fs.mkdirSync(outDir, { recursive: true });

/** 書き換えに必要な回答（同じくらい・スキップを除く）の数 */
const MIN_HUMAN = 100;
const FOLDS = 5;
const LAMBDAS = [0.1, 0.3, 1, 3, 10, 30];
const DIFF_NAME: Record<string, string> = { '4': 'ANOTHER', '10': 'LEGGENDARIA' };

// ── 入力 ────────────────────────────────────────────────
type Vote = {
  id: number; textage: string; title: string; difficulty: string; side: number;
  patternLeft: string; patternRight: string; startTime: number; endTime: number;
  choice: 'LEFT' | 'RIGHT' | 'SAME' | 'SKIP'; strategy?: string; modelLeft?: number; modelRight?: number;
  userId?: number; responseMs?: number | null; repeatOf?: number | null; trusted?: boolean;
};
type OptionVoteRow = { title: string; difficultyName: string; counts: Record<string, number> };
type Exported = { votes: Vote[]; optionVotes: OptionVoteRow[] };

async function loadExport(): Promise<Exported> {
  if (exportFile) return JSON.parse(fs.readFileSync(exportFile, 'utf8'));
  const token = process.env.BEAT_SEEKER_TOKEN;
  if (!token) {
    console.error('usage: node scripts/fit-random-weights.mts <export.json> [--write]  または  BEAT_SEEKER_TOKEN=... で --api から取る');
    process.exit(1);
  }
  // 個人 API トークン（/api/external/**。期限なしにできる）→ ログインの JWT の順に試す
  for (const p of ['/api/external/v1/random-pairs/export', '/api/admin/random-pairs/export']) {
    const res = await fetch(`${api}${p}`, { headers: { Authorization: `Bearer ${token}` } });
    if (res.ok) return res.json();
  }
  throw new Error('export を取れません（管理者の個人 API トークンか、期限内のログインの JWT を BEAT_SEEKER_TOKEN に入れる）');
}

const summary: { charts: { t: string; d: string; x: string; off: number; mir: number; rr: number }[]; total: number } =
  JSON.parse(fs.readFileSync(path.join(root, 'frontend/public/data/random-ranking/summary.json'), 'utf8'));

/** 再生データ: 手元の jsonl → キャッシュ → API の順に探す */
const playback = new Map<string, any>();
for (const file of [flag('playback'), cacheFile]) {
  if (!file || !fs.existsSync(file)) continue;
  for (const line of fs.readFileSync(file, 'utf8').split('\n')) {
    if (!line.trim()) continue;
    const d = JSON.parse(line);
    if (!playback.has(d.textage)) playback.set(d.textage, d);
  }
}
const missing = new Set<string>();
async function getPlayback(textage: string): Promise<any | null> {
  if (playback.has(textage)) return playback.get(textage);
  if (missing.has(textage)) return null;
  try {
    const res = await fetch(`${api}/api/analysis/chart-playback?textage=${encodeURIComponent(textage)}`);
    if (!res.ok) throw new Error(String(res.status));
    const d = await res.json();
    playback.set(textage, d);
    fs.appendFileSync(cacheFile, JSON.stringify(d) + '\n');
    return d;
  } catch {
    missing.add(textage);
    return null;
  }
}
const scorers = new Map<string, (p: string, w: [number, number][]) => Record<string, number>[]>();
async function scorerOf(textage: string, side: 1 | 2) {
  const key = `${side}:${textage}`;
  if (scorers.has(key)) return scorers.get(key)!;
  const d = await getPlayback(textage);
  if (!d) return null;
  const s = makeWindowScorer(buildChartTimeline(d), side);
  scorers.set(key, s);
  return s;
}

// ── 比較のデータ ────────────────────────────────────────
/** 1 つの比較。x = (右 − 左) / 規模、y = 左が押しやすい確率（同じくらい = 0.5、投票 = 正規の票の割合）、w = 重み */
type Pair = { x: Float64Array; y: number; w: number; group: string; human: boolean; strategy?: string };

function toX(left: Record<string, number>, right: Record<string, number>): Float64Array {
  let sl = 0, sr = 0;
  for (const k of KEYS) { sl += left[k]; sr += right[k]; }
  const scale = (sl + sr) / 2 + 1;
  return Float64Array.from(KEYS, k => (right[k] - left[k]) / scale);
}

// ── 適当に押した回答への対策 ────────────────────────────
/** これより速い回答（ミリ秒）は見ていないものとして使わない */
const MIN_RESPONSE_MS = 1500;
/** 確認問題の正解率の事前の見込み（正解 4・不正解 1 回ぶん。少ない確認問題で重みが振れないように） */
const CHECK_PRIOR_PASS = 4, CHECK_PRIOR_N = 5;
/** 左右の偏りを見始める回答数 */
const SIDE_MIN_ANSWERS = 20;

type Reliability = {
  userId: number; trusted: boolean; answers: number; fast: number; leftShare: number | null;
  checks: number; passed: number; weight: number; reasons: string[];
};
const isFast = (v: Vote) => !v.trusted && v.responseMs != null && v.responseMs < MIN_RESPONSE_MS;

/**
 * 人ごとの信頼度（0〜1）。次の 3 つを掛けたもの（管理者は 1）
 * - 確認問題: 正解率（事前の見込みつき）が 0.8 以上で 1、0.5（当てずっぽう）以下で 0、その間は比例
 *   （obvious は上位の並びを選べば正解、repeat は元の問題と同じ並びを選べば正解。同じくらいは半分）
 * - 左右の偏り: {@link SIDE_MIN_ANSWERS} 件以上で、片側が 70% 以下なら 1、95% 以上なら 0（左右はくじで決めているので普通は半々）
 * - 速すぎる回答の割合: 1 − 割合（速すぎる回答そのものは別に除く）
 */
function reliabilityOf(votes: Vote[]): Map<number, Reliability> {
  const byId = new Map(votes.map(v => [v.id, v]));
  const byUser = new Map<number, Vote[]>();
  for (const v of votes) {
    if (v.choice === 'SKIP') continue;
    const list = byUser.get(v.userId ?? -1) ?? [];
    list.push(v);
    byUser.set(v.userId ?? -1, list);
  }
  const out = new Map<number, Reliability>();
  for (const [userId, list] of byUser) {
    const trusted = list.some(v => v.trusted);
    const fast = list.filter(isFast).length;
    const ok = list.filter(v => !isFast(v));
    const decisive = ok.filter(v => v.choice === 'LEFT' || v.choice === 'RIGHT');
    const left = decisive.filter(v => v.choice === 'LEFT').length;
    let checks = 0, passed = 0;
    for (const v of ok) {
      if (v.strategy === 'obvious' && v.modelLeft != null && v.modelRight != null && v.modelLeft !== v.modelRight) {
        checks++;
        if (v.choice === 'SAME') passed += 0.5;
        else if ((v.choice === 'LEFT') === (v.modelLeft < v.modelRight)) passed++;
      } else if (v.strategy === 'repeat' && v.repeatOf != null) {
        const orig = byId.get(v.repeatOf);
        if (!orig || orig.choice === 'SKIP' || orig.userId !== v.userId) continue;
        checks++;
        // 左右を入れ替えて出しているので、同じ並びを選んでいれば左右は逆になる
        if (v.choice === 'SAME' || orig.choice === 'SAME') passed += 0.5;
        else if (v.choice !== orig.choice) passed++;
      }
    }
    const reasons: string[] = [];
    const passRate = (passed + CHECK_PRIOR_PASS) / (checks + CHECK_PRIOR_N);
    const checkFactor = Math.min(1, Math.max(0, (passRate - 0.5) / 0.3));
    if (checkFactor < 1) reasons.push(`確認問題 ${passed}/${checks}`);
    const leftShare = decisive.length ? left / decisive.length : null;
    let sideFactor = 1;
    if (leftShare != null && decisive.length >= SIDE_MIN_ANSWERS) {
      const bias = Math.abs(leftShare - 0.5);
      sideFactor = Math.min(1, Math.max(0, 1 - (bias - 0.2) / 0.25));
      if (sideFactor < 1) reasons.push(`${leftShare > 0.5 ? '左' : '右'}ばかり ${(Math.max(leftShare, 1 - leftShare) * 100).toFixed(0)}%`);
    }
    const fastFactor = 1 - fast / list.length;
    if (fast > 0) reasons.push(`速すぎ ${fast} 件`);
    const weight = trusted ? 1 : Math.round(checkFactor * sideFactor * fastFactor * 1000) / 1000;
    out.set(userId, { userId, trusted, answers: list.length, fast, leftShare, checks, passed, weight, reasons });
  }
  return out;
}

const exported = await loadExport();
const reliability = reliabilityOf(exported.votes);
const pairs: Pair[] = [];
let skippedVotes = 0, fastVotes = 0, zeroWeightVotes = 0;
for (const v of exported.votes) {
  if (v.choice === 'SKIP' || v.strategy === 'obvious') continue;
  if (isFast(v)) { fastVotes++; continue; }
  const userWeight = reliability.get(v.userId ?? -1)?.weight ?? 1;
  if (userWeight <= 0) { zeroWeightVotes++; continue; }
  const sc = await scorerOf(v.textage, v.side === 2 ? 2 : 1);
  if (!sc) { skippedVotes++; continue; }
  const win: [number, number][] = [[v.startTime, v.endTime]];
  const [l] = sc(v.patternLeft, win);
  const [r] = sc(v.patternRight, win);
  pairs.push({ x: toX(l, r), y: v.choice === 'LEFT' ? 1 : v.choice === 'RIGHT' ? 0 : 0.5, w: userWeight, group: v.textage, human: true, strategy: v.strategy });
}

// オプション投票（弱い正解）: 正規と MIRROR の票がある譜面
const chartByKey = new Map(summary.charts.map(c => [`${c.t}\u0000${DIFF_NAME[c.d] ?? c.d}`, c]));
type WeakChart = { row: (typeof summary.charts)[number]; reg: number; mir: number; fixed: number; random: number; rr: number };
const weakCharts: WeakChart[] = [];
for (const ov of exported.optionVotes) {
  const row = chartByKey.get(`${ov.title}\u0000${ov.difficultyName}`);
  if (!row) continue;
  const c = ov.counts;
  const reg = c.REGULAR ?? 0, mir = c.MIRROR ?? 0;
  weakCharts.push({ row, reg, mir, fixed: reg + mir, random: (c.RANDOM ?? 0) + (c['S-RANDOM'] ?? 0), rr: c['R-RANDOM'] ?? 0 });
}
let weakSkipped = 0;
if (weakWeight > 0) {
  for (const wc of weakCharts) {
    if (wc.reg + wc.mir < 2 || wc.reg === wc.mir) continue;
    const sc = await scorerOf(wc.row.x, 1);
    if (!sc) { weakSkipped++; continue; }
    const all: [number, number][] = [[-1e9, 1e9]];
    const [off] = sc('1234567', all);
    const [mr] = sc('7654321', all);
    pairs.push({ x: toX(off, mr), y: wc.reg / (wc.reg + wc.mir), w: weakWeight * Math.min(1, (wc.reg + wc.mir) / 5), group: wc.row.x, human: false });
  }
}

// ── 当てはめ ────────────────────────────────────────────
const sigmoid = (z: number) => 1 / (1 + Math.exp(-z));
const dot = (b: Float64Array, x: Float64Array) => { let s = 0; for (let k = 0; k < D; k++) s += b[k] * x[k]; return s; };

/** 全部同じ係数 c の当てはめ（1 変数のニュートン法。c ≥ 0） */
function fitScalar(data: Pair[]): number {
  let c = 1;
  for (let it = 0; it < 100; it++) {
    let g = 0, h = 1e-9;
    for (const p of data) {
      let s = 0; for (let k = 0; k < D; k++) s += p.x[k];
      const q = sigmoid(c * s);
      g += p.w * (q - p.y) * s;
      h += p.w * q * (1 - q) * s * s;
    }
    const next = Math.max(0, c - g / h);
    if (Math.abs(next - c) < 1e-9) break;
    c = next;
  }
  return c;
}

/**
 * 形ごとの係数の当てはめ（β ≥ 0 の制約つきニュートン法。全部同じ係数 c0 へ引き戻す罰則つき）。
 * 0 に張り付いて、さらに下げたい係数は固定し、残りでニュートン方向に進む（直線探索つき）
 */
function fitWeights(data: Pair[], lambda: number): Float64Array {
  const c0 = Math.max(fitScalar(data), 1e-3);
  const reg = (2 * lambda) / (c0 * c0);
  const loss = (b: Float64Array) => {
    let l = 0;
    for (const p of data) {
      const z = dot(b, p.x);
      // log(1 + e^z) − y z を桁あふれしないように
      l += p.w * ((z > 0 ? z + Math.log1p(Math.exp(-z)) : Math.log1p(Math.exp(z))) - p.y * z);
    }
    for (let k = 0; k < D; k++) l += (reg / 2) * (b[k] - c0) ** 2;
    return l;
  };
  let b = new Float64Array(D).fill(c0);
  let cur = loss(b);
  for (let it = 0; it < 100; it++) {
    const g = new Float64Array(D);
    const H = Array.from({ length: D }, () => new Float64Array(D));
    for (const p of data) {
      const q = sigmoid(dot(b, p.x));
      const r = p.w * (q - p.y), h = p.w * q * (1 - q);
      for (let i = 0; i < D; i++) {
        g[i] += r * p.x[i];
        for (let j = 0; j < D; j++) H[i][j] += h * p.x[i] * p.x[j];
      }
    }
    for (let k = 0; k < D; k++) { g[k] += reg * (b[k] - c0); H[k][k] += reg; }
    const free = [...Array(D).keys()].filter(k => b[k] > 0 || g[k] < 0);
    const step = solve(free.map(i => free.map(j => H[i][j])), free.map(i => -g[i]));
    let t = 1, next = b, nextLoss = cur;
    for (let ls = 0; ls < 30; ls++, t /= 2) {
      next = Float64Array.from(b);
      free.forEach((k, i) => { next[k] = Math.max(0, b[k] + t * step[i]); });
      nextLoss = loss(next);
      if (nextLoss <= cur) break;
    }
    if (!(nextLoss <= cur)) break;
    const moved = next.reduce((s, x, k) => s + Math.abs(x - b[k]), 0);
    b = next;
    const improved = cur - nextLoss;
    cur = nextLoss;
    if (moved < 1e-9 * c0 || improved < 1e-12) break;
  }
  return b;
}

/** 連立一次方程式 A x = y（ガウスの消去法。A は対称正定値の想定） */
function solve(A: Float64Array[], y: number[]): number[] {
  const n = y.length;
  const M = A.map((row, i) => [...row, y[i]]);
  for (let c = 0; c < n; c++) {
    let piv = c;
    for (let r = c + 1; r < n; r++) if (Math.abs(M[r][c]) > Math.abs(M[piv][c])) piv = r;
    [M[c], M[piv]] = [M[piv], M[c]];
    const d = M[c][c] || 1e-12;
    for (let r = 0; r < n; r++) {
      if (r === c) continue;
      const f = M[r][c] / d;
      if (f !== 0) for (let k = c; k <= n; k++) M[r][k] -= f * M[c][k];
    }
  }
  return M.map((row, i) => row[n] / (row[i] || 1e-12));
}

/** 正解率（はっきり答えた比較だけ。予測が同点なら 0.5）と対数損失 */
function evaluate(data: Pair[], beta: (p: Pair) => Float64Array) {
  let n = 0, hit = 0, ll = 0, lw = 0, aw = 0;
  for (const p of data) {
    const z = dot(beta(p), p.x);
    const q = Math.min(1 - 1e-9, Math.max(1e-9, sigmoid(z)));
    ll += -p.w * (p.y * Math.log(q) + (1 - p.y) * Math.log(1 - q));
    lw += p.w;
    if (p.y === 0.5) continue;
    // 正解率も信頼度の重みつき（n は問題の数）
    n++;
    aw += p.w;
    if (Math.abs(z) < 1e-12) hit += 0.5 * p.w; else if ((z > 0) === (p.y > 0.5)) hit += p.w;
  }
  return { n, accuracy: aw ? hit / aw : null, logLoss: lw ? ll / lw : null };
}

/** 譜面ごとに分けた交差検証。学習に使っていない比較での予測を集める */
function crossValidate(lambda: number) {
  const groups = [...new Set(pairs.map(p => p.group))];
  // 譜面の順を固定の擬似乱数で混ぜる（毎回同じ分け方になるように）
  let seed = 12345;
  const rnd = () => ((seed = (seed * 1664525 + 1013904223) >>> 0) / 2 ** 32);
  for (let i = groups.length - 1; i > 0; i--) { const j = Math.floor(rnd() * (i + 1)); [groups[i], groups[j]] = [groups[j], groups[i]]; }
  const foldOf = new Map(groups.map((g, i) => [g, i % FOLDS]));
  const base = new Map<Pair, Float64Array>();
  const learned = new Map<Pair, Float64Array>();
  for (let f = 0; f < FOLDS; f++) {
    const train = pairs.filter(p => foldOf.get(p.group) !== f);
    const test = pairs.filter(p => foldOf.get(p.group) === f);
    if (train.length === 0 || test.length === 0) continue;
    const c = fitScalar(train);
    const b = fitWeights(train, lambda);
    for (const p of test) { base.set(p, new Float64Array(D).fill(c)); learned.set(p, b); }
  }
  const tested = pairs.filter(p => base.has(p));
  const pick = (h: boolean) => tested.filter(p => p.human === h);
  return {
    human: { before: evaluate(pick(true), p => base.get(p)!), after: evaluate(pick(true), p => learned.get(p)!) },
    weak: { before: evaluate(pick(false), p => base.get(p)!), after: evaluate(pick(false), p => learned.get(p)!) },
  };
}

// ── オプション投票と、今のランキング（summary.json）の一致 ──
/** 2 群の AUC（a の値が b より小さい確率。同点 0.5） */
function aucLess(a: number[], b: number[]): number | null {
  if (a.length === 0 || b.length === 0) return null;
  let s = 0;
  for (const x of a) for (const y of b) s += x < y ? 1 : x === y ? 0.5 : 0;
  return s / (a.length * b.length);
}
function currentRankingVsVotes() {
  let n = 0, hit = 0;
  for (const wc of weakCharts) {
    if (wc.reg + wc.mir < 2 || wc.reg === wc.mir || wc.row.off === wc.row.mir) continue;
    n++;
    if ((wc.reg > wc.mir) === (wc.row.off < wc.row.mir)) hit++;
  }
  // 正規・MIRROR 派が多い譜面ほど、正規・MIRROR の良い方の順位が上のはず
  const voted = weakCharts.filter(wc => wc.fixed + wc.random + wc.rr >= 3);
  const share = (wc: WeakChart) => wc.fixed / (wc.fixed + wc.random + wc.rr);
  const bestFixed = (wc: WeakChart) => Math.min(wc.row.off, wc.row.mir);
  const fixedFans = voted.filter(wc => share(wc) >= 0.6).map(bestFixed);
  const randomFans = voted.filter(wc => share(wc) <= 0.4).map(bestFixed);
  return {
    offVsMirror: { n, accuracy: n ? hit / n : null },
    fixedVsRandomAuc: { fixedFans: fixedFans.length, randomFans: randomFans.length, auc: aucLess(fixedFans, randomFans) },
  };
}

// ── 実行 ────────────────────────────────────────────────
const humanDecisive = pairs.filter(p => p.human && p.y !== 0.5).length;
const weakCount = pairs.filter(p => !p.human).length;
console.log(`回答 ${exported.votes.length} 件（使う ${pairs.filter(p => p.human).length}、うち同じくらい以外 ${humanDecisive}、`
  + `速すぎて除外 ${fastVotes}、信頼度 0 の人で除外 ${zeroWeightVotes}、譜面を取れず除外 ${skippedVotes}。スキップと確認問題 obvious は使わない）`);
const users = [...reliability.values()].sort((a, b) => a.weight - b.weight || b.answers - a.answers);
if (users.length) {
  console.log('\n■ 人ごとの信頼度（低い順。重み 1 = そのまま使う、0 = 使わない）');
  for (const u of users.slice(0, 30)) {
    console.log(`  ユーザー ${String(u.userId).padStart(5)}${u.trusted ? '（管理者）' : ''}: 重み ${u.weight.toFixed(2)}  回答 ${u.answers}`
      + `  左 ${u.leftShare == null ? '—' : (u.leftShare * 100).toFixed(0) + '%'}  確認問題 ${u.passed}/${u.checks}${u.reasons.length ? '  ← ' + u.reasons.join('、') : ''}`);
  }
  if (users.length > 30) console.log(`  …ほか ${users.length - 30} 人`);
}
console.log(`オプション投票: 票のある譜面 ${weakCharts.length}、正規と MIRROR の比較に使う ${weakCount}（譜面を取れず除外 ${weakSkipped}）`);

const now = currentRankingVsVotes();
const pct = (x: number | null | undefined) => (x == null ? '—' : `${(x * 100).toFixed(1)}%`);
console.log('\n■ 今のランキング（summary.json）とオプション投票');
console.log(`  正規と MIRROR の票の多い方を、順位の良い方として当てた割合: ${pct(now.offVsMirror.accuracy)}（${now.offVsMirror.n} 譜面）`);
console.log(`  正規・MIRROR 派が多い譜面の方が、正規・MIRROR の良い方の順位が上である確率（AUC）: ${now.fixedVsRandomAuc.auc == null ? '—' : now.fixedVsRandomAuc.auc.toFixed(3)}`
  + `（正規・MIRROR 派 ${now.fixedVsRandomAuc.fixedFans} 譜面 / RANDOM 派 ${now.fixedVsRandomAuc.randomFans} 譜面。0.5 = 無関係、1 = 完全に一致）`);

// 今の係数（randomWeights.ts）での、回答との一致（出題したときの評価を使う）
const byStrategy: Record<string, { n: number; hit: number }> = {};
for (const v of exported.votes) {
  if ((v.choice !== 'LEFT' && v.choice !== 'RIGHT') || v.modelLeft == null || v.modelRight == null || v.modelLeft === v.modelRight) continue;
  const s = (byStrategy[v.strategy ?? '?'] ??= { n: 0, hit: 0 });
  s.n++;
  if ((v.choice === 'LEFT') === (v.modelLeft < v.modelRight)) s.hit++;
}
if (Object.keys(byStrategy).length) {
  console.log('\n■ 出題したときの評価と回答の一致（選び方別）');
  for (const [k, s] of Object.entries(byStrategy)) console.log(`  ${k}: ${pct(s.hit / s.n)}（${s.n} 問）`);
}

const report: Record<string, unknown> = {
  generatedAt: new Date(Date.now() + 9 * 3600 * 1000).toISOString().replace('T', ' ').slice(0, 16),
  votes: exported.votes.length, humanPairs: pairs.filter(p => p.human).length, humanDecisive, weakPairs: weakCount,
  currentRankingVsVotes: now, byStrategy, fastVotes, zeroWeightVotes, reliability: users,
};

if (pairs.length < FOLDS * 2) {
  console.log('\n比較のデータが少なすぎるので、係数の学習はしません。');
  writeReport();
  process.exit(0);
}

// λ を交差検証の対数損失（回答があれば回答、無ければ投票）で選ぶ
let best: { lambda: number; cv: ReturnType<typeof crossValidate> } | null = null;
for (const lambda of LAMBDAS) {
  const cv = crossValidate(lambda);
  const loss = (humanDecisive > 0 ? cv.human.after.logLoss : cv.weak.after.logLoss) ?? Infinity;
  const bestLoss = best ? ((humanDecisive > 0 ? best.cv.human.after.logLoss : best.cv.weak.after.logLoss) ?? Infinity) : Infinity;
  if (loss < bestLoss) best = { lambda, cv };
}
const cv = best!.cv;
const beta = fitWeights(pairs, best!.lambda);
const mean = beta.reduce((s, x) => s + x, 0) / D || 1;
const weights = Object.fromEntries(KEYS.map((k, i) => [k, Math.round((beta[i] / mean) * 1000) / 1000]));
/** 形ごとに、比較の中で左右の差があった数（0 の形は学習されず 1 のまま） */
const support = Object.fromEntries(KEYS.map((k, i) => [k, pairs.filter(p => Math.abs(p.x[i]) > 1e-9).length]));

console.log(`\n■ 交差検証（譜面ごとに ${FOLDS} 分割。λ = ${best!.lambda}）  全部同じ係数 → 学習後`);
console.log(`  回答の正解率: ${pct(cv.human.before.accuracy)} → ${pct(cv.human.after.accuracy)}（${cv.human.after.n} 問）`
  + `  対数損失: ${cv.human.before.logLoss?.toFixed(4) ?? '—'} → ${cv.human.after.logLoss?.toFixed(4) ?? '—'}`);
console.log(`  投票（正規 vs MIRROR）の正解率: ${pct(cv.weak.before.accuracy)} → ${pct(cv.weak.after.accuracy)}（${cv.weak.after.n} 譜面）`);
console.log('\n■ 学習した係数（平均 1。今の係数 → 学習後、比較に差があった数）');
for (const k of KEYS) {
  console.log(`  ${String(RANDOM_WEIGHTS[k]).padStart(6)} → ${weights[k].toFixed(3).padStart(6)}  (${String(support[k]).padStart(4)})  ${PENALTY_LABELS[k]}`);
}

const better = humanDecisive >= MIN_HUMAN
  && (cv.human.after.accuracy ?? 0) >= (cv.human.before.accuracy ?? 0)
  && (cv.human.after.logLoss ?? Infinity) < (cv.human.before.logLoss ?? Infinity);
Object.assign(report, { lambda: best!.lambda, cv, weights, support, adopted: false });

if (has('write') && (better || has('force'))) {
  writeWeights();
  report.adopted = true;
  console.log(`\n${path.relative(root, weightsFile)} を書き換えました。ランキングを作り直してください:`);
  console.log('  node scripts/build-random-ranking.mts <再生データ.jsonl>');
} else if (has('write')) {
  console.log(`\n書き換えませんでした（回答 ${humanDecisive} / ${MIN_HUMAN} 件以上、かつ学習後の方が正解率・対数損失とも良いときだけ。--force で強制）`);
}
writeReport();

function writeWeights() {
  const src = fs.readFileSync(weightsFile, 'utf8');
  const info = {
    fittedAt: report.generatedAt, humanPairs: humanDecisive, weakPairs: weakCount,
    cvAccuracyBefore: round4(cv.human.before.accuracy), cvAccuracyAfter: round4(cv.human.after.accuracy),
  };
  const infoSrc = `export const RANDOM_WEIGHTS_INFO = {\n`
    + `  /** 学習した日時（JST）。null = まだ学習していない */\n  fittedAt: ${JSON.stringify(info.fittedAt)} as string | null,\n`
    + `  /** 学習に使った回答の数（同じくらい・スキップを除く）と、オプション投票の弱い正解の数 */\n`
    + `  humanPairs: ${info.humanPairs},\n  weakPairs: ${info.weakPairs},\n`
    + `  /** 交差検証での「どっちが押しやすい？」の正解率（係数無し → 学習後） */\n`
    + `  cvAccuracyBefore: ${info.cvAccuracyBefore} as number | null,\n  cvAccuracyAfter: ${info.cvAccuracyAfter} as number | null,\n};`;
  const weightsSrc = `export const RANDOM_WEIGHTS: Record<PenaltyKey, number> = {\n${KEYS.map(k => `  ${k}: ${weights[k]},`).join('\n')}\n};`;
  const out = src
    .replace(/export const RANDOM_WEIGHTS_INFO = \{[\s\S]*?\n\};/, infoSrc)
    .replace(/export const RANDOM_WEIGHTS: Record<PenaltyKey, number> = \{[\s\S]*?\n\};/, weightsSrc);
  fs.writeFileSync(weightsFile, out);
}
function round4(x: number | null) { return x == null ? null : Math.round(x * 10000) / 10000; }
function writeReport() {
  const stamp = String(report.generatedAt).replace(/[-: ]/g, '').slice(0, 12);
  const file = path.join(outDir, `report-${stamp}.json`);
  fs.writeFileSync(file, JSON.stringify(report, null, 2));
  console.log(`\n詳細: ${path.relative(root, file)}`);
}
