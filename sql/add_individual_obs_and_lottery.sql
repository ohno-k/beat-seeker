-- 個人戦 OBS ブラウザソース + 抽選番号方式のスキーマ変更
-- competitions テーブルに OBS 用トークンを追加。
ALTER TABLE competitions
ADD COLUMN IF NOT EXISTS obs_token VARCHAR(64);
CREATE UNIQUE INDEX IF NOT EXISTS competitions_obs_token_uq
ON competitions (obs_token)
WHERE obs_token IS NOT NULL;

-- 個人戦スロットに「抽選番号」カラムを追加し、参加者紐付け前の空スロットを許容するため
-- participant_id を NULL 許容に変更。既存の non-null データは保たれる。
ALTER TABLE competition_individual_match_slots
ADD COLUMN IF NOT EXISTS slot_number INTEGER;
ALTER TABLE competition_individual_match_slots
ALTER COLUMN participant_id DROP NOT NULL;
