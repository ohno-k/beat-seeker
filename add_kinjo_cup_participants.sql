-- きんじょー杯 特設ページ（/kinjocup）の参加者名簿テーブル。
-- 主催者が登録した参加者を保持する。実力データ（総合力/段位/アリーナ）は users から都度引くため
-- ここでは user_id への参照のみを持つ。ddl-auto: update でも自動生成されるが、本番手動適用用に明示。
CREATE TABLE IF NOT EXISTS kinjo_cup_participants (
    id         BIGSERIAL PRIMARY KEY,
    user_id    BIGINT      NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    created_at TIMESTAMP   NOT NULL DEFAULT NOW(),
    CONSTRAINT uk_kinjo_cup_participants_user UNIQUE (user_id)
);

CREATE INDEX IF NOT EXISTS idx_kinjo_cup_participants_user_id
    ON kinjo_cup_participants (user_id);
