// TOP RANKER データの曲名 → 公式スコア CSV（= DB の song_definitions）表記への寄せ。
// 曲名は公式 CSV 表記が正なので、寄せておかないと BEAT-PT 集計で譜面が引けずに落ちる。
// eagate 公式（scrape-top-rankers-eagate.js）と masaoblue 版の過去作 CSV の両方に同じ揺れがあるので、
// 取得時と歴代合算時（merge-all-top-rankers.js）の両方で使う。
// 前後の空白（"Nyan Nyan University " / "Idola " など）は normalizeTitle で一律に落とす。

const TITLE_FIXES = {
  'Blind Justice ～Torn souls, Hurt Faiths ～': 'Blind Justice ～Torn souls， Hurt Faiths ～',
  'ROCK女 feat. 大山愛未, Ken': 'ROCK女 feat. 大山愛未， Ken',
  'Praludium': 'Präludium',
  'Geirskogul': 'Geirskögul',
  '!Viva!': '¡Viva!',
  'ZEИITH': 'ZENITH',
  'BLOSSOM': 'BLO§OM',
};

function normalizeTitle(title) {
  const t = String(title ?? '').trim();
  return TITLE_FIXES[t] ?? t;
}

module.exports = { TITLE_FIXES, normalizeTitle };
