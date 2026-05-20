-- 旧版 apply_diff_table_draft.sql (ドラフト全体昇格) の実行で
-- active 側の Uncategorized(other) から消えてしまった曲を復元する SQL。
--
-- 復元対象 (active 側の Uncategorized(other) に末尾追加):
--   - Τέλος / Tέλος (先頭文字がギリシャ大文字タウ U+03A4 か Latin T かを判定不能なので両方試す)
--   - BitRacer
--   - GENE[L]
--   - THE BLACK KNIGHT[L]
--
-- 既に存在するタイトルはスキップ (NOT EXISTS で冪等)。
-- draft 側には触りません。

BEGIN;

INSERT INTO difficulty_rank_songs (difficulty_rank_id, song_title, sort_order)
SELECT dr.id,
       t.title,
       COALESCE(
         (SELECT MAX(sort_order) FROM difficulty_rank_songs
            WHERE difficulty_rank_id = dr.id),
         -1
       ) + 1 + t.offset
FROM difficulty_ranks dr
CROSS JOIN (
  VALUES
    ('Τέλος',              0),  -- Greek capital tau (U+03A4) 始まり
    ('Tέλος',              1),  -- Latin T 始まり (どちらが正解か不明なので両方試す)
    ('BitRacer',            2),
    ('GENE[L]',             3),
    ('THE BLACK KNIGHT[L]', 4)
) AS t(title, offset)
WHERE dr.revision = 'active'
  AND dr.rank_value = 'Uncategorized(other)'
  AND NOT EXISTS (
    SELECT 1 FROM difficulty_rank_songs s
    WHERE s.difficulty_rank_id = dr.id
      AND s.song_title = t.title
  );

COMMIT;

-- 復元結果確認:
-- SELECT drs.song_title, drs.sort_order
-- FROM difficulty_rank_songs drs
-- JOIN difficulty_ranks dr ON dr.id = drs.difficulty_rank_id
-- WHERE dr.revision = 'active' AND dr.rank_value = 'Uncategorized(other)'
-- ORDER BY drs.sort_order;
--
-- もし "Τέλος" と "Tέλος" の両方が登録されてしまったら、不要なほうを後で削除:
-- DELETE FROM difficulty_rank_songs WHERE song_title = '不要な方の表記';
