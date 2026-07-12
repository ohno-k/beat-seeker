-- usersテーブルにis_publicカラムを追加（デフォルト値: true）
ALTER TABLE users
ADD COLUMN IF NOT EXISTS is_public BOOLEAN DEFAULT true;
-- 既存のレコードがNULLにならないように更新（念のため）
UPDATE users
SET is_public = true
WHERE is_public IS NULL;