-- =============================================================================
-- アリーナTOP RANKER 仮想プレイヤー用テーブル
-- =============================================================================
-- 本番 (PostgreSQL) は JPA の ddl-auto: update により
-- VirtualArenaRanker / VirtualArenaRankerScore エンティティから自動生成される。
-- この SQL は「スキーマの明文化」と、手動作成・調査・スモークテスト用の参照。
--
-- 保存内容: e-amusement GATE の「アリーナクラス TOP RANKER ランキング」から取り込んだ、
--          プレイデータ公開プレイヤーの譜面別ベスト。実ユーザー (users) とは分離する。
-- 集計:     BEAT-PT / RATE-PT はバックエンド VirtualArenaRankerService が都度算出（DB には保存しない）。
-- 取り込み: scripts/scrape-arena-top-rankers.js
-- =============================================================================

CREATE TABLE IF NOT EXISTS virtual_arena_rankers (
    id          BIGSERIAL PRIMARY KEY,
    iidx_id     VARCHAR(9)  NOT NULL UNIQUE,   -- "1234-5678"
    dj_name     VARCHAR(64),
    arena_class VARCHAR(10),                   -- 例: "A5"
    rank_pos    INTEGER,                       -- アリーナランキング順位 (1..1000)
    arena_point INTEGER,                       -- アリーナポイント（任意）
    scraped_at  TIMESTAMP,
    created_at  TIMESTAMP DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_virtual_arena_rankers_iidx_id
    ON virtual_arena_rankers (iidx_id);

CREATE TABLE IF NOT EXISTS virtual_arena_ranker_scores (
    id               BIGSERIAL PRIMARY KEY,
    ranker_id        BIGINT NOT NULL REFERENCES virtual_arena_rankers (id) ON DELETE CASCADE,
    title            VARCHAR(255),
    difficulty_name  VARCHAR(20),              -- BEGINNER/NORMAL/HYPER/ANOTHER/LEGGENDARIA
    difficulty_level INTEGER,
    score            INTEGER,                  -- EX スコア
    pgreat           INTEGER,
    great            INTEGER,
    miss_count       INTEGER,
    clear_type       VARCHAR(20),
    dj_level         VARCHAR(4)
);

CREATE INDEX IF NOT EXISTS idx_vars_ranker_id
    ON virtual_arena_ranker_scores (ranker_id);

-- スモークテスト用サンプル（任意）:
-- INSERT INTO virtual_arena_rankers (iidx_id, dj_name, arena_class, rank_pos)
--   VALUES ('9999-0001', 'TEST-DJ', 'A5', 1);
-- INSERT INTO virtual_arena_ranker_scores (ranker_id, title, difficulty_name, difficulty_level, score, clear_type, dj_level)
--   SELECT id, 'AA', 'ANOTHER', 12, 3000, 'FULLCOMBO CLEAR', 'AAA' FROM virtual_arena_rankers WHERE iidx_id = '9999-0001';
