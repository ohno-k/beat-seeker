-- 観戦客向け対戦表公開トークン (team5 用) のスキーマ変更。
-- competitions テーブルに観戦公開用トークンを追加。
-- 発行は管理画面の「観戦用 URL 発行」、公開は /api/competition-access/spectator/{token} (認証不要)。
-- ※ application.yml の hibernate.ddl-auto=update でも自動追加されるが、
--   本番で明示適用したい場合のための手動マイグレーション。
ALTER TABLE competitions
ADD COLUMN IF NOT EXISTS spectator_token VARCHAR(64);

CREATE UNIQUE INDEX IF NOT EXISTS competitions_spectator_token_uq
ON competitions (spectator_token)
WHERE spectator_token IS NOT NULL;
