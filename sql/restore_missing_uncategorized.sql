-- 旧版 apply_diff_table_draft.sql (ドラフト全昇格) の実行で
-- active 側の Uncategorized(other) から消えてしまった曲を復元する SQL。
--
-- DBeaver で実行する手順:
--   1. このファイルを開く
--   2. Alt+X (またはツールバーの「SQL スクリプトを実行」) で全体を一括実行
--      ※「Ctrl+Enter (現在のステートメント実行)」では DO ブロックも 1 文として走るので OK
--   3. メッセージタブ (画面下) に NOTICE が複数出力されるので、それを見て結果を判断
--
-- 期待される NOTICE 出力例:
--   NOTICE:  active Uncategorized(other) の rank_id = 42
--   NOTICE:  追加: Τέλος (sort_order=3)
--   NOTICE:  追加: BitRacer (sort_order=4)
--   NOTICE:  追加: GENE[L] (sort_order=5)
--   NOTICE:  追加: THE BLACK KNIGHT[L] (sort_order=6)
--   NOTICE:  合計 4 件を Uncategorized(other) に追加しました
--
-- 既にあるタイトルはスキップ (冪等)。draft 側には触りません。

DO $$
DECLARE
  rank_id BIGINT;
  cur_order INT;
  inserted_count INT := 0;
  title_var TEXT;
  titles TEXT[] := ARRAY[
    'Τέλος',              -- Greek capital tau U+03A4 始まり
    'BitRacer',
    'GENE[L]',
    'THE BLACK KNIGHT[L]'
  ];
BEGIN
  -- 1) active "Uncategorized(other)" ランクの id を取得
  SELECT id INTO rank_id
  FROM difficulty_ranks
  WHERE revision = 'active' AND rank_value = 'Uncategorized(other)'
  LIMIT 1;

  IF rank_id IS NULL THEN
    RAISE EXCEPTION 'active 側に "Uncategorized(other)" ランクが見つかりません。中止します。';
  END IF;

  RAISE NOTICE 'active Uncategorized(other) の rank_id = %', rank_id;

  -- 現在の最大 sort_order を取得 (空なら -1 にして +1 で 0 から始まる)
  SELECT COALESCE(MAX(sort_order), -1) INTO cur_order
  FROM difficulty_rank_songs
  WHERE difficulty_rank_id = rank_id;

  -- 2) 各タイトルを順に投入
  FOREACH title_var IN ARRAY titles LOOP
    IF EXISTS (
      SELECT 1 FROM difficulty_rank_songs
      WHERE difficulty_rank_id = rank_id AND song_title = title_var
    ) THEN
      RAISE NOTICE '既に存在: %', title_var;
    ELSE
      cur_order := cur_order + 1;
      INSERT INTO difficulty_rank_songs (difficulty_rank_id, song_title, sort_order)
      VALUES (rank_id, title_var, cur_order);
      inserted_count := inserted_count + 1;
      RAISE NOTICE '追加: % (sort_order=%)', title_var, cur_order;
    END IF;
  END LOOP;

  RAISE NOTICE '合計 % 件を Uncategorized(other) に追加しました', inserted_count;
END $$;

-- 復元結果確認 (これは別ステートメントなので DBeaver では別途実行が必要):
SELECT drs.song_title, drs.sort_order
FROM difficulty_rank_songs drs
JOIN difficulty_ranks dr ON dr.id = drs.difficulty_rank_id
WHERE dr.revision = 'active' AND dr.rank_value = 'Uncategorized(other)'
ORDER BY drs.sort_order;
