-- リーグの課題曲に「フォールバックで埋めた枠か」の印を持たせるスキーマ変更。
-- 通常の選曲基準（グループ全員が未プレー／2 人以上がプレー済みで拮抗）を満たす候補が 3 曲に
-- 足りず、プール全体からの補填で埋まった曲を true にする。集計上の扱いは通常曲と同じで、
-- 管理者画面で色付き表示して「差し替え候補」を見つけやすくするためだけのフラグ。
--
-- NOT NULL は付けない: 既存行のあるテーブルへ NOT NULL 列を足す ALTER は失敗することがあり、
-- 失敗すると列が無いまま新コードが league_songs を SELECT して 500 になる（実際に発生した）。
-- アプリ側は null を false として扱う（LeagueSong#isFallback）。
--
-- ※ application.yml の hibernate.ddl-auto=update でも自動追加されるが、
--   自動追加が失敗した場合はこの SQL を本番 DB（Render → PSQL Command）で直接流すこと。
ALTER TABLE league_songs
ADD COLUMN IF NOT EXISTS fallback BOOLEAN DEFAULT FALSE;
