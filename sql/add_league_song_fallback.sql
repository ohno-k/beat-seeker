-- リーグの課題曲に「フォールバックで埋めた枠か」の印を持たせるスキーマ変更。
-- 通常の選曲基準（グループ全員が未プレー／2 人以上がプレー済みで拮抗）を満たす候補が 3 曲に
-- 足りず、プール全体からの補填で埋まった曲を true にする。集計上の扱いは通常曲と同じで、
-- 管理者画面で色付き表示して「差し替え候補」を見つけやすくするためだけのフラグ。
--
-- NOT NULL は付けない: 既存行のあるテーブルへ NOT NULL 列を足す ALTER は失敗し得るため。
-- アプリ側は null を false として扱う（LeagueSong#isFallback）。
--
-- ※ application.yml の hibernate.ddl-auto=update で自動追加される（本番でも追加済み）。
--   これは手動で明示適用したい場合のための控え。
ALTER TABLE league_songs
ADD COLUMN IF NOT EXISTS fallback BOOLEAN DEFAULT FALSE;
