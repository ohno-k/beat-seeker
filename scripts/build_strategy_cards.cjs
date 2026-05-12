#!/usr/bin/env node
/**
 * ストラテジーカード用の課題曲リストを TSV から JSON に変換するワンショットスクリプト。
 *
 * 入力: プロジェクトルート直下の `{genre}.txt` (TSV)
 *   フォーマット: 曲管理番号\tバージョン\t曲名\tDiff(A|L)\tLv
 *   1行目はヘッダ。
 *
 * 出力: frontend/src/data/strategy_card_songs.json
 *   構造: { [genre]: { [level]: [{ id, version, title, diff, level }, ...] } }
 *   genre: NOTES / PEAK / CHORD / CHARGE / SCRATCH / SOF-LAN / INSANE
 *   level: "8" | "9" | "10" | "11" | "12"
 */
const fs = require('fs');
const path = require('path');

const ROOT = path.resolve(__dirname, '..');
const OUT = path.resolve(ROOT, 'frontend/src/data/strategy_card_songs.json');

const GENRES = [
  { key: 'NOTES',   file: 'notes.txt'   },
  { key: 'PEAK',    file: 'peak.txt'    },
  { key: 'CHORD',   file: 'chord.txt'   },
  { key: 'CHARGE',  file: 'charge.txt'  },
  { key: 'SCRATCH', file: 'scratch.txt' },
  { key: 'SOF-LAN', file: 'sof-lan.txt' },
  { key: 'INSANE',  file: 'insane.txt'  },
];

const result = {};

for (const { key, file } of GENRES) {
  const fp = path.join(ROOT, file);
  const raw = fs.readFileSync(fp, 'utf8');
  const lines = raw.split(/\r?\n/).filter(l => l.trim().length > 0);
  const dataLines = lines.slice(1); // skip header

  const byLevel = {};
  for (const line of dataLines) {
    const cols = line.split('\t');
    if (cols.length < 5) continue;
    const [id, version, title, diff, lvStr] = cols;
    const level = String(parseInt(lvStr, 10));
    if (!byLevel[level]) byLevel[level] = [];
    byLevel[level].push({
      id: parseInt(id, 10),
      version: version.trim(),
      title: title.trim(),
      diff: diff.trim(),
      level: parseInt(lvStr, 10),
    });
  }
  result[key] = byLevel;
}

fs.writeFileSync(OUT, JSON.stringify(result, null, 2), 'utf8');

// 統計を出力
console.log(`Wrote ${OUT}`);
for (const [genre, levels] of Object.entries(result)) {
  const counts = Object.entries(levels).map(([lv, songs]) => `Lv${lv}:${songs.length}`).join(' ');
  const total = Object.values(levels).reduce((s, a) => s + a.length, 0);
  console.log(`  ${genre.padEnd(8)} total=${total}  (${counts})`);
}
