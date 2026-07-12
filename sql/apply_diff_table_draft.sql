-- GENE と THE BLACK KNIGHT (どちらも ANOTHER) を active 難易度表で 11.7 に戻す SQL。
-- 他のドラフト変更 (昇格 / 降格) には一切触れません。
--
-- 動作範囲:
--   - 触る: active 側の difficulty_rank_songs のうち
--          song_title = 'GENE' または 'THE BLACK KNIGHT' の行のみ
--   - 触らない: draft 側の difficulty_ranks / difficulty_rank_songs
--             active の他楽曲、song_definitions、その他のテーブル
--
-- 想定するクライアント側: PostgreSQL 12+

BEGIN;

-- 1) active 側の "GENE" / "THE BLACK KNIGHT" を 11.7 以外のランクから削除する。
--    (Uncategorized(other) に紛れ込んだ ANO 行を除去する)
--    "GENE[L]" や "THE BLACK KNIGHT[L]" は対象外なので Uncategorized に残る。
DELETE FROM difficulty_rank_songs
WHERE song_title IN ('GENE', 'THE BLACK KNIGHT')
  AND difficulty_rank_id IN (
    SELECT id FROM difficulty_ranks
    WHERE revision = 'active' AND rank_value <> '11.7'
  );

-- 2) active 11.7 ランクに "GENE" / "THE BLACK KNIGHT" が無ければ末尾に追加する。
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

COMMIT;

-- 確認 (適用後に両曲が active 11.7 のみに居て、draft は手付かずになっていることを検証):
-- SELECT dr.revision, dr.rank_value, drs.song_title
-- FROM difficulty_rank_songs drs
-- JOIN difficulty_ranks dr ON dr.id = drs.difficulty_rank_id
-- WHERE drs.song_title IN ('GENE', 'THE BLACK KNIGHT', 'GENE[L]', 'THE BLACK KNIGHT[L]')
-- ORDER BY dr.revision, dr.sort_order;
