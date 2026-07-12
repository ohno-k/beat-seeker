-- 現状診断用 SQL。実行して結果を貼ってください。
-- これだけ実行しても DB は変更されません (SELECT のみ)。

-- 1) active 側に "Uncategorized(other)" ランクが存在するか? id を確認。
SELECT id, rank_value, sort_order
FROM difficulty_ranks
WHERE revision = 'active' AND rank_value LIKE '%ncategorized%'
ORDER BY sort_order;

-- 2) active Uncategorized(other) ランクに今何曲入っているか? (sort_order 順に全件)
SELECT drs.song_title, drs.sort_order
FROM difficulty_rank_songs drs
JOIN difficulty_ranks dr ON dr.id = drs.difficulty_rank_id
WHERE dr.revision = 'active' AND dr.rank_value = 'Uncategorized(other)'
ORDER BY drs.sort_order;

-- 3) BitRacer / Τέλος / Tέλος / GENE[L] / THE BLACK KNIGHT[L] が
--    DB 上に「どこかに」存在するかどうか? (Uncategorized 以外の rank に居る可能性も含めて全範囲検索)
SELECT dr.revision, dr.rank_value, drs.song_title
FROM difficulty_rank_songs drs
JOIN difficulty_ranks dr ON dr.id = drs.difficulty_rank_id
WHERE drs.song_title IN (
  'Τέλος',
  'Tέλος',
  'BitRacer',
  'GENE[L]',
  'THE BLACK KNIGHT[L]'
)
   OR drs.song_title LIKE '%έλος%'
   OR drs.song_title LIKE '%itRacer%'
ORDER BY dr.revision, dr.rank_value;
