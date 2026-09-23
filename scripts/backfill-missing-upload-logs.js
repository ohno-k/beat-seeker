/**
 * 成長記録（score_history_logs）が欠落したアップロードを検出して補填するスクリプト。
 *
 * 背景:
 *   スコア本体は POST /api/scores/upload がサーバー側で保存するが、成長記録は
 *   そのレスポンスを受け取ったフロントが改めて POST /api/scores/save-history-log を
 *   呼ぶことで初めて作られる。upload のレスポンスがブラウザに届かなかった場合
 *   （60 秒タイムアウト・通信断・再読込）、スコアだけ保存されて成長記録が残らない。
 *   その結果、最新ログを参照する RATE-Tier ランキングが古い値のまま据え置かれる。
 *
 * 検出:
 *   scores.uploaded_at を「アップロード単位」にクラスタリング（既定 120 秒以上空いたら別回）し、
 *   その前後 [-2 分, +5 分] に score_history_logs が 1 件も無いクラスタを欠落とみなす。
 *
 * 再構成:
 *   現在のスコアから「欠落アップロードの直後の状態」を復元する。欠落回より後に
 *   成功したアップロードがある場合は、そのログの diffJson に入っている oldScore /
 *   oldClearType を使って逆適用（ロールバック）する。各種 PT は backend の
 *   ScoreRecalculationService / BeatPtCalculator と同じ式を JS で再現して算出する。
 *
 * 検証:
 *   補填の前に、各ユーザーの「最後に成功したアップロード」を同じ手順で復元し、
 *   実際に保存されているログの値と一致するかを必ず確認する（--skip-verify で省略可）。
 *
 * 使い方:
 *   node scripts/backfill-missing-upload-logs.js                 # dry-run（検出 + 検証のみ）
 *   node scripts/backfill-missing-upload-logs.js --apply         # INSERT を実行
 *   node scripts/backfill-missing-upload-logs.js --since=2026-09-01
 *   node scripts/backfill-missing-upload-logs.js --user=842
 */
const { Client, types } = require('pg');

// timestamp without time zone (OID 1114) を「そのままの壁時計」として扱う。
// 既定パーサはローカル時刻（JST）として Date 化するため、toISOString で 9 時間ずれる。
types.setTypeParser(1114, str => new Date(str.replace(' ', 'T') + 'Z'));

const ARGS = process.argv.slice(2);
const APPLY = ARGS.includes('--apply');
const SKIP_VERIFY = ARGS.includes('--skip-verify');
const SINCE = (ARGS.find(a => a.startsWith('--since=')) || '--since=2026-09-14').split('=')[1];
const ONLY_USER = (ARGS.find(a => a.startsWith('--user=')) || '=').split('=')[1] || null;
/** 同一アップロードとみなす scores.uploaded_at の最大間隔（秒）。 */
const CLUSTER_GAP_SEC = 120;

const client = new Client({
  connectionString: 'postgresql://postgress:kAw2xymPeLH4mOZuV76hsJCR4L9kFkgM@dpg-d6f68314tr6s73bnbhag-a.oregon-postgres.render.com:5432/beatseeker',
  ssl: { rejectUnauthorized: false },
});

// ---------------------------------------------------------------------------
// backend の計算式の再現（BeatPtCalculator / ScoreRecalculationService と同一）
// ---------------------------------------------------------------------------

/** 非公式難易度 "11.0"〜"13.1" → weight。11.0=145 から 12.49 未満は +2、以上は +3。 */
const WEIGHTS = (() => {
  const m = new Map();
  let weight = 145;
  for (let i = 0; i <= 21; i++) {
    const rankValue = 11.0 + i * 0.1;
    m.set(rankValue.toFixed(1), weight);
    weight += rankValue >= 12.49 ? 3 : 2;
  }
  return m;
})();

function getWeight(informalRank) {
  if (!informalRank) return 0;
  const m = /(\d+\.\d+)/.exec(informalRank);
  return WEIGHTS.get(m ? m[1] : informalRank) || 0;
}

/** BEAT-PT 単曲。base=(rate/100)^1.3*weight に 77.77/88.88/94.44 超えごとに weight*0.01。 */
function calculatePoints(scoreRate, informalRank) {
  if (informalRank == null) return 0;
  const weight = getWeight(informalRank);
  if (weight === 0 || scoreRate <= 66.666) return 0;
  let pts = Math.pow(scoreRate / 100, 1.3) * weight;
  if (scoreRate > 77.77) pts += weight * 0.01;
  if (scoreRate > 88.88) pts += weight * 0.01;
  if (scoreRate > 94.44) pts += weight * 0.01;
  return pts;
}

const SCORE_RATE_THRESHOLDS = [
  [77.77, 1], [88.89, 2], [94.44, 4], [97.22, 8], [98.61, 16],
  [99.31, 32], [99.65, 64], [99.83, 128], [99.91, 256], [100, 512],
];

/** RATE-PT 単曲。しきい値表のピースワイズ線形補間。 */
function calculateScoreRateTierPoints(scoreRate) {
  if (scoreRate <= 0 || scoreRate < SCORE_RATE_THRESHOLDS[0][0]) return 0;
  const last = SCORE_RATE_THRESHOLDS[SCORE_RATE_THRESHOLDS.length - 1];
  if (scoreRate >= last[0]) return last[1];
  for (let i = 0; i < SCORE_RATE_THRESHOLDS.length - 1; i++) {
    const [loRate, loPt] = SCORE_RATE_THRESHOLDS[i];
    const [hiRate, hiPt] = SCORE_RATE_THRESHOLDS[i + 1];
    if (scoreRate < hiRate) return loPt + ((scoreRate - loRate) / (hiRate - loRate)) * (hiPt - loPt);
  }
  return 0;
}

const DIFF_CODE = { BEGINNER: '1', NORMAL: '2', HYPER: '3', ANOTHER: '4', LEGGENDARIA: '10' };
const round1 = v => Math.round(v * 10) / 10;
const sumTop = (arr, n) => arr.slice(0, n).reduce((a, b) => a + b, 0);

/** EX スコア率から DJ LEVEL を導く（ロールバックで djLevel が判らない譜面用）。 */
function djLevelFromRate(rate) {
  if (rate >= (8 / 9) * 100) return 'AAA';
  if (rate >= (7 / 9) * 100) return 'AA';
  if (rate >= (6 / 9) * 100) return 'A';
  if (rate >= (5 / 9) * 100) return 'B';
  if (rate >= (4 / 9) * 100) return 'C';
  if (rate >= (3 / 9) * 100) return 'D';
  if (rate >= (2 / 9) * 100) return 'E';
  return 'F';
}

/**
 * 1 ユーザーのスコア集合から成長記録に入る全指標を算出する。
 *
 * 集計規則は保存経路ごとに異なるので、実際の save-history-log に合わせる:
 *  - totalScore / 各クリア種別・DJ ランクのカウント … scores 全行をそのまま数える
 *    （ScoreController.saveHistoryLog と同じ。重複排除も NO PLAY 除外もしない）
 *  - BEAT / RATE-PT … (曲,難易度) ごとに最高スコアの行だけ採用し上位 100
 */
function computeTotals(scores, masters) {
  const { maxScores, informalRanks } = masters;

  let totalScore = 0;
  let fcCount = 0, exhCount = 0, hCount = 0, clearCount = 0, easyCount = 0;
  let aaaCount = 0, aaCount = 0, aCount = 0;
  for (const s of scores) {
    totalScore += s.score || 0;
    if (s.clearType === 'FULLCOMBO CLEAR') fcCount++;
    if (s.clearType === 'EX HARD CLEAR') exhCount++;
    if (s.clearType === 'HARD CLEAR') hCount++;
    if (s.clearType === 'CLEAR') clearCount++;
    if (s.clearType === 'EASY CLEAR') easyCount++;
    if (s.djLevel === 'AAA') aaaCount++;
    if (s.djLevel === 'AA') aaCount++;
    if (s.djLevel === 'A') aCount++;
  }

  // (曲, 難易度) ごとに最高スコアの 1 行へ寄せる（同一譜面の重複行による二重計上防止）。
  const bestByChart = new Map();
  for (const s of scores) {
    const k = `${s.title} ${s.difficultyName}`;
    const cur = bestByChart.get(k);
    if (!cur || (s.score || 0) > (cur.score || -1)) bestByChart.set(k, s);
  }

  const beatPts = [], ratePts = [];
  let perfectRateCount = 0;
  const chartPt = new Map(); // key → { beatPt, ratePt, scoreRate, maxScore, informalRank }

  for (const s of bestByChart.values()) {
    if (s.clearType === '---' || s.clearType === 'NO PLAY') continue;
    const diffName = (s.difficultyName || 'UNKNOWN').toUpperCase();
    const code = DIFF_CODE[diffName];
    if (!code) continue;
    const maxScore = maxScores.get(`${s.title}_${code}`);
    if (!maxScore) continue;
    const scoreRate = ((s.score || 0) * 100) / maxScore;
    const informalRank = informalRanks.get(`${s.title}_${diffName}`) ?? null;

    const isHyperNonTarget = diffName === 'HYPER' && s.difficultyLevel != null && s.difficultyLevel >= 11;
    let beatPt = 0;
    if (!isHyperNonTarget) {
      beatPt = calculatePoints(scoreRate, informalRank);
      if (beatPt > 0) beatPts.push(beatPt);
    }
    let ratePt = 0;
    const isRateEligible = diffName === 'ANOTHER' || diffName === 'LEGGENDARIA';
    if (isRateEligible && scoreRate > 0) {
      ratePt = calculateScoreRateTierPoints(scoreRate);
      if (ratePt > 0) ratePts.push(ratePt);
      if (scoreRate >= 100) perfectRateCount++;
    }
    chartPt.set(`${s.title}_${diffName}`, { beatPt, ratePt, scoreRate, maxScore, informalRank });
  }

  const desc = (a, b) => b - a;
  beatPts.sort(desc); ratePts.sort(desc);

  let rateAcc = sumTop(ratePts, 100);
  if (perfectRateCount > 100) rateAcc += perfectRateCount - 100;

  return {
    totalScore, fcCount, exhCount, hCount, clearCount, easyCount, aaaCount, aaCount, aCount,
    totalBeatPt: round1(sumTop(beatPts, 100)),
    totalRatePt: round1(rateAcc),
    // 上位 100 判定用（diffJson の isInTop100 / isInRateTop100 に使う）
    beatTop100Threshold: beatPts.length >= 100 ? beatPts[99] : 0,
    rateTop100Threshold: ratePts.length >= 100 ? ratePts[99] : 0,
    chartPt,
  };
}

// ---------------------------------------------------------------------------
// データ取得
// ---------------------------------------------------------------------------

async function loadMasters() {
  const maxScores = new Map();
  for (const r of (await client.query(
    "select title, difficulty, notes from song_definitions where revision='active' and notes is not null and notes > 0")).rows) {
    maxScores.set(`${r.title}_${r.difficulty}`, r.notes * 2);
  }

  const informalRanks = new Map();
  for (const r of (await client.query(
    `select dr.rank_value, drs.song_title from difficulty_ranks dr
       join difficulty_rank_songs drs on drs.difficulty_rank_id = dr.id
      where dr.revision='active'`)).rows) {
    const t = (r.song_title || '').trim();
    if (!t) continue;
    if (t.endsWith('[L]')) informalRanks.set(`${t.slice(0, -3).trim()}_LEGGENDARIA`, r.rank_value);
    else informalRanks.set(`${t}_ANOTHER`, r.rank_value);
  }

  return { maxScores, informalRanks };
}

async function loadScores(userId) {
  const { rows } = await client.query(
    `select id, title, difficulty_name as "difficultyName", difficulty_level as "difficultyLevel",
            score, clear_type as "clearType", dj_level as "djLevel", uploaded_at as "uploadedAt"
       from scores where user_id = $1`, [userId]);
  return rows;
}

async function loadLogs(userId) {
  const { rows } = await client.query(
    `select id, uploaded_at as "uploadedAt", tag, version, total_beat_pt as "totalBeatPt",
            total_rate_pt as "totalRatePt", beat_pt_increase as "beatPtIncrease",
            updated_count as "updatedCount", diff_json as "diffJson"
       from score_history_logs where user_id = $1 order by uploaded_at asc`, [userId]);
  return rows;
}

function parseDiff(json) {
  if (!json || json === '[]') return [];
  try {
    const v = JSON.parse(json);
    return Array.isArray(v) ? v : [];
  } catch { return []; }
}

const diffKey = d => `${d.title}_${d.difficulty || d.difficultyName}`;
const chartKeyOf = s => `${s.title}_${s.difficultyName}`;

/**
 * 現在のスコアから「時刻 t の直後の状態」を復元する。
 * t より後のログの diffJson を新しい順に逆適用し、各譜面を oldScore / oldClearType に戻す。
 */
function rollbackTo(scores, logs, t, masters) {
  const state = scores.map(s => ({ ...s }));
  const byChart = new Map(state.map(s => [chartKeyOf(s), s]));
  const later = logs.filter(l => new Date(l.uploadedAt) > t).sort((a, b) => new Date(b.uploadedAt) - new Date(a.uploadedAt));
  for (const log of later) {
    for (const d of parseDiff(log.diffJson)) {
      const row = byChart.get(diffKey(d));
      if (!row) continue;
      const oldScore = Number(d.oldScore ?? 0);
      row.score = oldScore;
      row.clearType = d.oldClearType || 'NO PLAY';
      const code = DIFF_CODE[(row.difficultyName || '').toUpperCase()];
      const maxScore = code ? masters.maxScores.get(`${row.title}_${code}`) : null;
      row.djLevel = maxScore ? djLevelFromRate((oldScore * 100) / maxScore) : null;
    }
  }
  return state;
}

/**
 * 欠落アップロードの diffJson を復元する。
 * 更新された譜面 = そのクラスタの時刻に uploaded_at が入っている行。
 * 更新前の値 = その譜面が最後に登場する「欠落回より前のログ」の newScore / newClearType。
 * どのログにも無ければ、そのアップロードで新規に作られた譜面とみなし 0 / NO PLAY 扱い。
 */
function buildDiffJson(stateAfter, logs, cluster, totals, masters) {
  // 作品の切替（tag='version-transition'）をまたいで遡ってはいけない。切替時に scores は
  // 初期化されるため、前作のスコアを「更新前の値」に使うと 0 からの取り込みが差分 0 に化ける。
  const boundary = logs
    .filter(l => l.tag === 'version-transition' && new Date(l.uploadedAt) < cluster.start)
    .map(l => new Date(l.uploadedAt))
    .sort((a, b) => b - a)[0] || null;

  const prevValue = new Map();
  for (const log of logs.filter(l => {
    const t = new Date(l.uploadedAt);
    return t < cluster.start && (!boundary || t > boundary);
  })) {
    for (const d of parseDiff(log.diffJson)) {
      prevValue.set(diffKey(d), { score: Number(d.newScore ?? 0), clearType: d.newClearType || 'NO PLAY' });
    }
  }

  const updated = stateAfter.filter(s => {
    const u = new Date(s.uploadedAt);
    return u >= cluster.start && u <= cluster.end;
  });

  return updated.map(s => {
    const diffName = (s.difficultyName || '').toUpperCase();
    const key = `${s.title}_${diffName}`;
    const before = prevValue.get(key) || { score: 0, clearType: 'NO PLAY' };
    const pt = totals.chartPt.get(key);
    const maxScore = pt ? pt.maxScore : (masters.maxScores.get(`${s.title}_${DIFF_CODE[diffName]}`) || 0);
    const scoreRate = maxScore ? ((s.score || 0) * 100) / maxScore : 0;
    const oldRate = maxScore ? (before.score * 100) / maxScore : 0;
    const informalRank = pt ? pt.informalRank : (masters.informalRanks.get(key) ?? null);
    const oldBeatPt = calculatePoints(oldRate, informalRank);
    const newBeatPt = pt ? pt.beatPt : calculatePoints(scoreRate, informalRank);
    const isRateEligible = diffName === 'ANOTHER' || diffName === 'LEGGENDARIA';
    const oldRatePt = isRateEligible ? calculateScoreRateTierPoints(oldRate) : 0;
    const newRatePt = pt ? pt.ratePt : (isRateEligible ? calculateScoreRateTierPoints(scoreRate) : 0);
    return {
      title: s.title,
      difficulty: diffName,
      oldScore: before.score,
      newScore: s.score || 0,
      scoreIncrease: Math.max(0, (s.score || 0) - before.score),
      oldClearType: before.clearType,
      newClearType: s.clearType,
      clearTypeImproved: clearRank(s.clearType) > clearRank(before.clearType),
      maxScore,
      scoreRate,
      informalRank: informalRank ?? undefined,
      oldBeatPt,
      newBeatPt,
      beatPtIncrease: Math.max(0, newBeatPt - oldBeatPt),
      newRatePt,
      ratePtIncrease: Math.max(0, newRatePt - oldRatePt),
      isInTop100: newBeatPt > 0 && newBeatPt >= totals.beatTop100Threshold,
      isInRateTop100: newRatePt > 0 && newRatePt >= totals.rateTop100Threshold,
      // この行が後から機械的に復元されたものであることを残す（本来のレポートは失われている）
      backfilled: true,
    };
  });
}

function clearRank(ct) {
  return { 'FULLCOMBO CLEAR': 7, 'EX HARD CLEAR': 6, 'HARD CLEAR': 5, CLEAR: 4, 'EASY CLEAR': 3, 'ASSIST CLEAR': 2, FAILED: 1 }[ct] || 0;
}

/** scores.uploaded_at を「1 回のアップロード」単位にまとめる。 */
function clusterUploads(scores) {
  const times = [...new Set(scores.map(s => new Date(s.uploadedAt).getTime()))].sort((a, b) => a - b);
  const clusters = [];
  for (const t of times) {
    const last = clusters[clusters.length - 1];
    if (last && t - last.endMs <= CLUSTER_GAP_SEC * 1000) last.endMs = t;
    else clusters.push({ startMs: t, endMs: t });
  }
  return clusters.map(c => ({ start: new Date(c.startMs), end: new Date(c.endMs) }));
}

const fmt = d => new Date(d).toISOString().replace('T', ' ').slice(0, 19);

// ---------------------------------------------------------------------------
// メイン
// ---------------------------------------------------------------------------

async function main() {
  await client.connect();
  const masters = await loadMasters();
  console.log(`masters: maxScores=${masters.maxScores.size} informalRanks=${masters.informalRanks.size}`);

  // 欠落候補のユーザーを絞り込む（SINCE 以降にスコア更新があったユーザーのみ走査）
  const userRows = (await client.query(
    `select distinct s.user_id as id, u.display_name as name
       from scores s join users u on u.id = s.user_id
      where s.uploaded_at >= $1 ${ONLY_USER ? 'and s.user_id = ' + Number(ONLY_USER) : ''}
      order by 1`, [SINCE])).rows;
  console.log(`対象ユーザー: ${userRows.length} 人 (since ${SINCE})\n`);

  const pending = [];
  for (const u of userRows) {
    const scores = await loadScores(u.id);
    const logs = await loadLogs(u.id);
    const clusters = clusterUploads(scores.filter(s => new Date(s.uploadedAt) >= new Date(SINCE)));

    // 欠落クラスタは新しい順に復元する。復元した diff は「仮想ログ」として積み、
    // さらに古いクラスタや検証のロールバック時に実ログと同じように逆適用できるようにする。
    const missing = clusters.filter(c => !logs.some(l => {
      const t = new Date(l.uploadedAt).getTime();
      return t >= c.start.getTime() - 2 * 60000 && t <= c.end.getTime() + 5 * 60000;
    })).sort((a, b) => b.start - a.start);

    const virtual = [];
    for (const c of missing) {
      const known = logs.concat(virtual);
      const rowsWritten = scores.filter(s => {
        const t = new Date(s.uploadedAt);
        return t >= c.start && t <= c.end;
      }).length;

      // 復元 → 指標算出
      const stateAfter = rollbackTo(scores, known, c.end, masters);
      const totals = computeTotals(stateAfter, masters);
      const prevLog = [...logs].reverse().find(l => new Date(l.uploadedAt) < c.start) || null;
      const diff = buildDiffJson(stateAfter, known, c, totals, masters);
      virtual.push({ uploadedAt: c.start, tag: null, diffJson: JSON.stringify(diff) });

      pending.push({
        userId: u.id, name: u.name, cluster: c, rowsWritten, totals, prevLog, diff,
        version: prevLog?.version ?? 34,
        isLatest: !logs.some(l => new Date(l.uploadedAt) > c.end),
      });
    }

    // 検証: 直近の通常ログの時点を同じ手順（実ログ + 復元した仮想ログの逆適用）で復元し、
    // 実際に保存されている値と一致するか確かめる。ここが合えば復元手順と計算式の両方が正しい。
    if (!SKIP_VERIFY && missing.length) {
      // 比較対象は「補填する回と同じ作品の通常ログ」に限る。作品をまたいだログは
      // 切替時の初期化を逆適用できないため復元できない。
      const version = missing[0] && (pending.find(p => p.userId === u.id)?.version ?? 34);
      const target = [...logs].reverse().find(l => !l.tag && l.totalRatePt > 0 && (l.version ?? 33) === version);
      if (!target) {
        console.log(`  検証 user=${u.id} ${u.name}: 同じ作品の比較対象ログが無く検証不能`);
        for (const p of pending.filter(p => p.userId === u.id)) p.verifyUnavailable = true;
      } else {
        const state = rollbackTo(scores, logs.concat(virtual), new Date(target.uploadedAt), masters);
        const t = computeTotals(state, masters);
        // 曲マスタ（ノーツ数）や難易度表は日々更新されるため、過去ログとの比較は相対 1% まで許容する。
        const near = (a, b) => Math.abs(a - b) < 0.2 || Math.abs(a - b) / Math.max(1, Math.abs(b)) < 0.01;
        const okBeat = near(t.totalBeatPt, target.totalBeatPt);
        const okRate = near(t.totalRatePt, target.totalRatePt);
        console.log(
          `  検証 user=${u.id} ${u.name} log@${fmt(target.uploadedAt)}: ` +
          `BEAT ${t.totalBeatPt} vs ${target.totalBeatPt} ${okBeat ? 'OK' : '★NG'} / ` +
          `RATE ${t.totalRatePt} vs ${target.totalRatePt} ${okRate ? 'OK' : '★NG'}`);
        if (!okBeat || !okRate) {
          for (const p of pending.filter(p => p.userId === u.id)) p.verifyFailed = true;
        }
      }
    }
  }

  console.log(`\n=== 成長記録が欠落しているアップロード: ${pending.length} 件 ===`);
  for (const p of pending) {
    const prev = p.prevLog ? p.prevLog.totalRatePt : 0;
    console.log(
      `user=${p.userId} ${p.name} @${fmt(p.cluster.start)} UTC  更新${p.rowsWritten}譜面  ` +
      `BEAT=${p.totals.totalBeatPt} RATE=${p.totals.totalRatePt} (直前ログ RATE=${prev}) ` +
      `diff=${p.diff.length}件 ${p.isLatest ? '[最新イベント=ランキングに影響]' : ''}`);
  }

  if (!APPLY) {
    console.log('\ndry-run です。INSERT するには --apply を付けてください。');
    await client.end();
    return;
  }

  for (const p of pending) {
    if (p.verifyFailed && !ARGS.includes('--force')) {
      console.log(`SKIP user=${p.userId} @${fmt(p.cluster.start)}: 検証に失敗しているため挿入しません`);
      continue;
    }
    const t = p.totals;
    const base = p.prevLog?.totalBeatPt ?? 0;
    const { rows } = await client.query(
      `insert into score_history_logs
         (user_id, uploaded_at, tag, version, total_score, fc_count, exh_count, h_count,
          clear_count, easy_count, aaa_count, aa_count, a_count, total_beat_pt, beat_pt_increase,
          updated_count, total_precision_pt, total_rate_pt, diff_json)
       values ($1,$2,null,$3,$4,$5,$6,$7,$8,$9,$10,$11,$12,$13,$14,$15,0,$16,$17)
       returning id`,
      [p.userId, fmt(p.cluster.start), p.version, t.totalScore, t.fcCount, t.exhCount, t.hCount,
       t.clearCount, t.easyCount, t.aaaCount, t.aaCount, t.aCount, t.totalBeatPt,
       round1(Math.max(0, t.totalBeatPt - base)), p.diff.length, t.totalRatePt,
       JSON.stringify(p.diff)]);
    console.log(`INSERT log id=${rows[0].id} user=${p.userId} @${fmt(p.cluster.start)}`);

    // 最新イベントだった場合は users 側のキャッシュも追従させる（ランキングの整合性）。
    if (p.isLatest) {
      await client.query(
        'update users set total_beat_pt=$1 where id=$2',
        [t.totalBeatPt, p.userId]);
      console.log(`  users キャッシュ更新 user=${p.userId} beat=${t.totalBeatPt}`);
    }
  }

  await client.end();
}

main().catch(e => { console.error(e); process.exit(1); });
