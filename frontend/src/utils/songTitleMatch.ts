/**
 * 【モジュールの役割】 大会側の楽曲プール ({@code strategy_card_songs.json}) のタイトルと、
 * アプリ内の楽曲一覧 ({@code song_data.json} = {@code useGameData().songDataBody}) のタイトルを
 * 表記ゆれを吸収して突き合わせるためのユーティリティ。
 *
 * <p>2 つのデータは出自が別 (プールは大会運営が作ったジャンル別 TXT、song_data は譜面 DB) で、
 * 曲数は揃っているのに文字表記だけが食い違うケースが 40 件強ある。完全一致だけで引くと
 * SongReveal が「SongData に該当曲が見つかりません」で再生できなくなるため、
 * 次の 3 段階でフォールバックする:
 *
 * <ol>
 *   <li>完全一致 (タイトル + 難易度)</li>
 *   <li>{@link TITLE_ALIASES} による明示的な読み替え (機械的な畳み込みでは届かない差分)</li>
 *   <li>{@link foldTitleVariants} による畳み込み一致 (発音区別符号・装飾記号・字形が似た別文字)</li>
 * </ol>
 *
 * <p>実際に吸収している差分の例:
 * <pre>
 *   Pārvatī         / Parvati                 … 発音区別符号
 *   Punch Love♡仮面  / Punch Love 仮面          … 装飾記号
 *   ROCK女 feat. 大山愛未, Ken / …， Ken          … 全角/半角の読点
 *   ACTØ            / ACT0                    … Ø を O とも 0 とも書く
 *   POLꞰAMAИIA      / POLKAMANIA              … 字形だけ似せた別文字 (Ʞ, И)
 *   ÆTHER           / ATHER                   … 合字を Æ→A と潰した表記
 *   Ignis†Iræ       / Ignis†Irae              … 同じ合字を Æ→AE と開いた表記
 * </pre>
 *
 * <p>畳み込みは情報を捨てる方向の操作なので、別曲どうしが同じキーに落ちることがある
 * (例: {@code SHOOTING STAR} と {@code Shooting Star})。そのため索引を作るときに
 * 衝突したキーは「曖昧」として捨て、完全一致でしか引けないようにしている
 * ({@link buildChartIndex} 参照)。誤った曲を発表してしまうより引けない方が安全なため。
 */

/**
 * 字形が似ているだけの別文字 → song_data 側で使われている文字。
 *
 * <p>キーは小文字化後の 1 文字だが、U+A7B0 のように小文字マッピングの有無が実装依存の文字も
 * あるため大文字側も併記し、引くときは元の文字と小文字の両方を試す。
 */
const HOMOGLYPHS: Record<string, string> = {
  'ø': 'o',   // ø LATIN SMALL LETTER O WITH STROKE
  'Ø': 'o',   // Ø LATIN CAPITAL LETTER O WITH STROKE
  'œ': 'oe',  // œ LATIN SMALL LIGATURE OE
  'и': 'n',   // и CYRILLIC SMALL LETTER I (N の鏡文字として使われる)
  'И': 'n',   // И CYRILLIC CAPITAL LETTER I
  'ʞ': 'k',   // ʞ LATIN SMALL LETTER TURNED K
  'Ʞ': 'k',   // Ʞ LATIN CAPITAL LETTER TURNED K
  'ə': 'e',   // ə LATIN SMALL LETTER SCHWA
  'Ǝ': 'e',   // Ǝ LATIN CAPITAL LETTER REVERSED E
  'я': 'r',   // я CYRILLIC SMALL LETTER YA (R の鏡文字として使われる)
  'Я': 'r',   // Я CYRILLIC CAPITAL LETTER YA
  'đ': 'd',   // đ LATIN SMALL LETTER D WITH STROKE
  'ł': 'l',   // ł LATIN SMALL LETTER L WITH STROKE
  'ß': 'ss',  // ß LATIN SMALL LETTER SHARP S
  '0': 'o',        // 数字のゼロを字母 O の代用に使う表記 (ACTØ / ACT0, PØT!OИ / POT!0N)
};

/**
 * 合字は song_data 側の表記が一定しない (ÆTHER→ATHER と Ignis†Iræ→Ignis†Irae で
 * Æ→A と Æ→AE に割れている) ので、両方の読みを候補として展開する。
 */
const LIGATURE_VARIANTS: Record<string, string[]> = {
  'æ': ['ae', 'a'],
};

/** 展開の組合せ爆発を防ぐ上限。曲名に合字が 3 つ以上入ることは無いので実質的に効かない。 */
const MAX_VARIANTS = 8;

/**
 * 畳み込みでは届かない、タイトルそのものが違う組。
 * キー = 楽曲プール側のタイトル / 値 = song_data 側のタイトル。
 */
const TITLE_ALIASES: Record<string, string> = {
  // プール側が略称。song_data は正式タイトル (IIDX 28 BISTROVER / textage 28/denim)。
  'DENIM': 'DENIM DENIM DENIM (ELECTRO MIX)',
  // song_data 側が異体字を常用漢字で代用している。
  '焱影': '火影',
};

/**
 * 【関数の役割】 タイトルを比較用のキーに畳み込む。合字の読みが割れる場合は複数返す。
 *
 * <p>手順: NFKC で全角/半角と波ダッシュ類を統一 → 合字を展開 → 字形代用文字を置換 →
 * NFD で分解して結合文字 (発音区別符号) を落とす → 英数字・仮名・漢字だけ残す
 * (空白・記号・♡♥☆♫ などの装飾はすべて捨てる)。
 */
export const foldTitleVariants = (title: string): string[] => {
  const base = title.normalize('NFKC').toLowerCase();

  // 合字を含む位置で候補を分岐させる
  let variants: string[] = [''];
  for (const ch of base) {
    const readings = LIGATURE_VARIANTS[ch];
    if (readings && variants.length * readings.length <= MAX_VARIANTS) {
      variants = variants.flatMap(v => readings.map(r => v + r));
      continue;
    }
    variants = variants.map(v => v + (readings ? readings[0] : ch));
  }

  const folded = variants.map(v => {
    let mapped = '';
    for (const ch of v) mapped += HOMOGLYPHS[ch] ?? HOMOGLYPHS[ch.toLowerCase()] ?? ch;
    // 結合文字を落としたうえで、字母・数字以外 (空白・句読点・装飾記号) を捨てる
    return mapped.normalize('NFD').replace(/\p{M}/gu, '').replace(/[^\p{L}\p{N}]/gu, '');
  });

  return [...new Set(folded)].filter(v => v.length > 0);
};

/** {@link buildChartIndex} が返す索引。キーは「畳み込みタイトル + 難易度コード」。 */
export type ChartIndex<T> = Map<string, T | null>;

const indexKey = (foldedTitle: string, difficulty: string): string => `${foldedTitle}|${difficulty}`;

/**
 * 【関数の役割】 song_data の譜面一覧から、畳み込みタイトル → 譜面の索引を作る。
 *
 * <p>同じキーに別タイトルの譜面が 2 件以上落ちた場合は {@code null} を入れて「曖昧」と記録する。
 * 曖昧なキーは {@link resolveChartByTitle} が引かないので、誤った曲を返すことはない。
 */
export const buildChartIndex = <T extends { title: string; difficulty: string }>(
  charts: readonly T[],
): ChartIndex<T> => {
  const index: ChartIndex<T> = new Map();
  for (const chart of charts) {
    for (const folded of foldTitleVariants(chart.title)) {
      const key = indexKey(folded, chart.difficulty);
      const existing = index.get(key);
      if (existing === undefined) {
        index.set(key, chart);
      } else if (existing !== null && existing.title !== chart.title) {
        index.set(key, null); // 別タイトルが衝突 → 曖昧としてこのキーを封じる
      }
    }
  }
  return index;
};

/**
 * 【関数の役割】 タイトル + 難易度コードで song_data の譜面を引く。
 *
 * @param charts     song_data の譜面一覧 (難易度で絞る前の全件でよい)
 * @param index      {@link buildChartIndex} で作った索引 (毎回作り直さないよう呼び出し側で保持する)
 * @param title      楽曲プール側のタイトル
 * @param difficulty song_data の難易度コード ('4' = ANOTHER / '10' = LEGGENDARIA)
 * @return 見つかった譜面。表記ゆれを吸収しても見つからなければ null
 */
export const resolveChartByTitle = <T extends { title: string; difficulty: string }>(
  charts: readonly T[],
  index: ChartIndex<T>,
  title: string,
  difficulty: string,
): T | null => {
  const exact = charts.find(c => c.title === title && c.difficulty === difficulty);
  if (exact) return exact;

  const alias = TITLE_ALIASES[title];
  if (alias) {
    const aliased = charts.find(c => c.title === alias && c.difficulty === difficulty);
    if (aliased) return aliased;
  }

  for (const folded of foldTitleVariants(alias ?? title)) {
    const hit = index.get(indexKey(folded, difficulty));
    if (hit) return hit;
  }
  return null;
};
