-- RANDOM の「どっちが押しやすい？」の回答（非公式難易度クイズのモーダルの「配置アンケート」）を保存するテーブル。
-- 当たり配置ランキングの減点の形ごとの係数を学習する正解データ（scripts/fit-random-weights.mts）。
-- ※ application.yml の hibernate.ddl-auto=update でも自動作成されるが、
--   本番（ddl-auto=none）で明示適用するための手動マイグレーション。
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
    created_at TIMESTAMP NOT NULL
);
CREATE INDEX IF NOT EXISTS idx_random_pair_votes_user_id ON random_pair_votes (user_id);
