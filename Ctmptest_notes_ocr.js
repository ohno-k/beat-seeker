const path = require('path');
const TESS = path.join('C:\Users\ohno\.gemini\antigravity\scratch\beat-seeker\frontend', 'node_modules', 'tesseract.js');
const { createWorker, PSM } = require(TESS);

const cases = [
  { name: 'iam', expected: 1096 },
  { name: 'being_torn', expected: 998 },
  { name: 'ghel', expected: 1877 },
  { name: 'reflux', expected: 1660 },
  { name: 'ryouran', expected: 1492 },
  { name: 'minarai', expected: 1706 },
];

(async () => {
  const w = await createWorker('eng');
  await w.setParameters({
    tessedit_pageseg_mode: PSM.SINGLE_LINE,
    tessedit_char_whitelist: '0123456789',
  });
  for (const c of cases) {
    const r = await w.recognize(`C:\tmp\notes_proc\${c.name}.png`);
    const txt = (r.data.text || '').trim();
    const num = parseInt(txt.replace(/\D/g, ''), 10);
    const ok = num === c.expected ? '✓' : `✗ expected=${c.expected}`;
    console.log(`${c.name.padEnd(12)} OCR="${txt}" num=${num} ${ok}`);
  }
  await w.terminate();
})();
