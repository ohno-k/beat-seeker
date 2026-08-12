-- リーグの課題曲を「無効化」できるようにするスキーマ変更。
-- 解禁できない譜面が抽選で入ってしまったとき、管理者がグループ単位でその曲を集計から外す
-- （有効曲にも着順ポイントにも数えない）ためのフラグ。曲の行自体は残すので、順位表・課題曲
-- 一覧には「無効」表示の列として残る。切り替えは
--   POST /api/league/admin/weeks/{weekId}/songs/{songId}/disabled  {"disabled": true}
-- ※ application.yml の hibernate.ddl-auto=update でも自動追加されるが、
--   本番で明示適用したい場合のための手動マイグレーション。
ALTER TABLE league_songs
ADD COLUMN IF NOT EXISTS disabled BOOLEAN DEFAULT FALSE NOT NULL;
