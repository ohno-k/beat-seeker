-- RANDOM の「どっちが押しやすい？」の回答（サイドバーの「配置アンケート」）を保存するテーブル。
-- 当たり配置ランキングの減点の形ごとの係数を学習する正解データ（scripts/fit-random-weights.mts）。
-- ※ 手で流す必要は無い。デプロイ時に hibernate.ddl-auto=update が作り、ddl-auto=none の環境でも
--   起動時に DataInitializer（手順4.65）が同じ CREATE TABLE IF NOT EXISTS を流す。記録と手動確認用に残している。
CREATE TABLE IF NOT EXISTS random_pair_votes (
    id BIGSERIAL PRIMARY KEY,
    user_id BIGINT NOT NULL REFERENCES users(id),
    textage VARCHAR(200) NOT NULL,
    title VARCHAR(255) NOT NULL,
    difficulty VARCHAR(10) NOT NULL,
    level INTEGER,
    side INTEGER NOT NULL,
    pattern_left VARCHAR(7) NOT NULL,
    pattern_right VARCHAR(7) NOT NULL,
    start_time DOUBLE PRECISION NOT NULL,
    end_time DOUBLE PRECISION NOT NULL,
    start_measure INTEGER,
    end_measure INTEGER,
    choice VARCHAR(8) NOT NULL,
    strategy VARCHAR(16),
    model_left DOUBLE PRECISION,
    model_right DOUBLE PRECISION,
    response_ms INTEGER,
    repeat_of BIGINT,
    created_at TIMESTAMP NOT NULL
);
CREATE INDEX IF NOT EXISTS idx_random_pair_votes_user_id ON random_pair_votes (user_id);
ALTER TABLE random_pair_votes ADD COLUMN IF NOT EXISTS repeat_of BIGINT;
