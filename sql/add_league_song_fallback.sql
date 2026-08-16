-- リーグの課題曲に「フォールバックで埋めた枠か」の印を持たせるスキーマ変更。
-- 通常の選曲基準（グループ全員が未プレー／2 人以上がプレー済みで拮抗）を満たす候補が 3 曲に
-- 足りず、プール全体からの補填で埋まった曲を true にする。集計上の扱いは通常曲と同じで、
-- 管理者画面で色付き表示して「差し替え候補」を見つけやすくするためだけのフラグ。
-- ※ application.yml の hibernate.ddl-auto=update でも自動追加されるが、
--   本番で明示適用したい場合のための手動マイグレーション。
ALTER TABLE league_songs
ADD COLUMN IF NOT EXISTS fallback BOOLEAN DEFAULT FALSE NOT NULL;
