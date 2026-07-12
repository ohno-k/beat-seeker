-- TL ⇄ システム運営 チャット機能のスキーマ変更。
-- ※ application.yml の hibernate.ddl-auto=update でも自動作成されるが、
--   本番で明示適用したい場合のための手動マイグレーション。
CREATE TABLE IF NOT EXISTS competition_chat_messages (
    id            BIGSERIAL PRIMARY KEY,
    team_id       BIGINT      NOT NULL REFERENCES competition_teams (id) ON DELETE CASCADE,
    sender        VARCHAR(16) NOT NULL,           -- 'tl' | 'admin'
    body          TEXT        NOT NULL,
    read_by_admin BOOLEAN     NOT NULL DEFAULT FALSE,
    created_at    TIMESTAMP   NOT NULL DEFAULT NOW()
);

-- スレッド表示・未読集計用のインデックス。
CREATE INDEX IF NOT EXISTS competition_chat_messages_team_idx
ON competition_chat_messages (team_id, created_at);
