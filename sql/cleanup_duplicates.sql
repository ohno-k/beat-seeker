-- 重複したユーザーデータを特定し、IDが一番大きい（最新の）ものだけを残して削除するスクリプトです。
-- 実行前に必ずバックアップを取得してください。
-- 1. どのIIDX IDが重複しているか確認する場合
-- SELECT iidx_id, COUNT(*) FROM users GROUP BY iidx_id HAVING COUNT(*) > 1;
-- 2. 重複を削除（IDが最大のものを残し、それ以外を削除）
-- ※ もし特定のデータを残したい場合は、このSQLを実行する前に手動で調整してください。
DELETE FROM users a USING users b
WHERE a.id < b.id -- IDが小さい方を削除（大きい方を残す）
    AND a.iidx_id = b.iidx_id;
-- 3. （オプション）一意制約（UNIQUE）をデータベースレベルで追加して今後の重複を防ぐ
-- ALTER TABLE users ADD CONSTRAINT unique_iidx_id UNIQUE (iidx_id);