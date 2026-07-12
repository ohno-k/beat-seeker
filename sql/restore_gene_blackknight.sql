-- GENE / THE BLACK KNIGHT を非公式難易度 11.7 に戻すためのデータ修正。
-- ANO に LEG を追加した直後の apply で addToActiveUncategorized() が
-- 「Uncategorized 内にあるか」しか見ていなかった旧コードのために、
-- ANO の "GENE" / "THE BLACK KNIGHT" が Uncategorized(other) に重複追加されて
-- UI 上「11.7 から外れた」ように見えていた問題のデータ修正。

BEGIN;

-- 1. active 側: 11.7 以外のランクから "GENE" / "THE BLACK KNIGHT" を全削除。
--    (Uncategorized(other) に紛れ込んだものを除去する)
DELETE FROM difficulty_rank_songs
WHERE song_title IN ('GENE', 'THE BLACK KNIGHT')
  AND difficulty_rank_id IN (
    SELECT id FROM difficulty_ranks
    WHERE revision = 'active' AND rank_value <> '11.7'
  );

-- 2. active 側: 11.7 ランクに "GENE" / "THE BLACK KNIGHT" が無ければ追加する。
--    sort_order は当該ランクの max + 1 で末尾追加。
INSERT INTO difficulty_rank_songs (difficulty_rank_id, song_title, sort_order)
SELECT dr.id,
       t.title,
       COALESCE(
         (SELECT MAX(sort_order) + 1 FROM difficulty_rank_songs
            WHERE difficulty_rank_id = dr.id),
         0
       )
FROM difficulty_ranks dr
CROSS JOIN (VALUES ('GENE'), ('THE BLACK KNIGHT')) AS t(title)
WHERE dr.revision = 'active'
  AND dr.rank_value = '11.7'
  AND NOT EXISTS (
    SELECT 1 FROM difficulty_rank_songs s
    WHERE s.difficulty_rank_id = dr.id
      AND s.song_title = t.title
  );

-- 3. draft 側にも同じ補正をかける (ドラフト中の難易度表をユーザーが適用した時に
--    11.7 配置を保てるように)。draft が無ければ NOOP。
DELETE FROM difficulty_rank_songs
WHERE song_title IN ('GENE', 'THE BLACK KNIGHT')
  AND difficulty_rank_id IN (
    SELECT id FROM difficulty_ranks
    WHERE revision = 'draft' AND rank_value <> '11.7'
  );

INSERT INTO difficulty_rank_songs (difficulty_rank_id, song_title, sort_order)
SELECT dr.id,
       t.title,
       COALESCE(
         (SELECT MAX(sort_order) + 1 FROM difficulty_rank_songs
            WHERE difficulty_rank_id = dr.id),
         0
       )
FROM difficulty_ranks dr
CROSS JOIN (VALUES ('GENE'), ('THE BLACK KNIGHT')) AS t(title)
WHERE dr.revision = 'draft'
  AND dr.rank_value = '11.7'
  AND NOT EXISTS (
    SELECT 1 FROM difficulty_rank_songs s
    WHERE s.difficulty_rank_id = dr.id
      AND s.song_title = t.title
  );

COMMIT;

-- 確認用クエリ (実行後、両曲が 11.7 以外に居ないことを確かめる):
-- SELECT dr.revision, dr.rank_value, drs.song_title
-- FROM difficulty_rank_songs drs
-- JOIN difficulty_ranks dr ON dr.id = drs.difficulty_rank_id
-- WHERE drs.song_title IN ('GENE', 'THE BLACK KNIGHT')
-- ORDER BY dr.revision, dr.sort_order;
