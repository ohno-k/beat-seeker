package com.beatseeker.backend;

import com.beatseeker.backend.service.GameDataService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;
import jakarta.persistence.EntityManager;
import jakarta.transaction.Transactional;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;

/**
 * 【クラスの役割】 アプリケーション起動直後に 1 度だけ実行される初期化処理。
 *
 * Spring Boot の {@link ApplicationRunner} 実装なので、
 * {@link com.beatseeker.backend.BackendApplication} の起動後、
 * 受信前のタイミングで {@link #run(ApplicationArguments)} が自動呼び出しされる。
 *
 * 主な責務:
 *  - 既存ユーザーの未設定カラム（language, showRateTier）にデフォルト値を埋める
 *  - 曲データ / 難易度表のマスターデータを JSON リソースから投入（未シード時のみ）
 *
 * データ重複を避ける仕組みは {@link GameDataService} 側の seed メソッドに委ねており、
 * 既に投入済みであれば再投入されない想定。
 *
 * 例外方針: 初期化に失敗してもアプリ本体は起動させる。
 *   リソース未配置・JSON 形式不正などは警告ログのみ出して握り潰す。
 */
@Component
public class DataInitializer implements ApplicationRunner {

    private static final Logger logger = LoggerFactory.getLogger(DataInitializer.class);

    /** JPA の EntityManager。簡易な UPDATE 文を直接発行するために使う。 */
    private final EntityManager entityManager;

    /** 曲データ・難易度表の投入処理を担うサービス層。 */
    private final GameDataService gameDataService;

    /**
     * 【コンストラクタ】 Spring の DI コンテナから依存オブジェクトを注入する。
     *
     * @param entityManager   JPA の EntityManager
     * @param gameDataService 曲・難易度表シード用サービス
     */
    public DataInitializer(EntityManager entityManager, GameDataService gameDataService) {
        this.entityManager = entityManager;
        this.gameDataService = gameDataService;
    }

    /**
     * 【メソッドの役割】 アプリ起動直後に Spring が自動で呼び出すエントリポイント。
     *
     * {@code @Transactional} によって UPDATE 群は単一トランザクションでまとめて commit される。
     *
     * @param args コマンドライン引数（本処理では未使用）
     */
    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        // 手順1: 既存ユーザーの language カラムが未設定（NULL）なら 'ja' を既定値として埋める。
        //        後からカラム追加した際のバックフィル処理。
        entityManager.createQuery("UPDATE User u SET u.language = 'ja' WHERE u.language IS NULL")
                .executeUpdate();
        // 手順2: showRateTier フラグが未設定のユーザーには true を入れる。
        //        Rate-Tier 機能追加時に既存ユーザーで表示が OFF にならないようにするための初期化。
        entityManager.createQuery("UPDATE User u SET u.showRateTier = true WHERE u.showRateTier IS NULL")
                .executeUpdate();

        // 手順2b: arcade / INFINITAS スコア表示トグルも未設定なら true で埋める。
        //         両方とも「既存ユーザーの見た目を壊さない」よう既定 true。
        entityManager.createQuery("UPDATE User u SET u.showArcadeScores = true WHERE u.showArcadeScores IS NULL")
                .executeUpdate();
        entityManager.createQuery("UPDATE User u SET u.showInfinitasScores = true WHERE u.showInfinitasScores IS NULL")
                .executeUpdate();

        // 手順2c: 既存スコアの source カラムが未設定ならアーケード扱いで埋める。
        //         INFINITAS 機能追加前のレコードは当然 arcade 出自なので、安全に一括代入できる。
        entityManager.createNativeQuery("UPDATE scores SET source = 'arcade' WHERE source IS NULL")
                .executeUpdate();

        // 手順2d: 旧ユニーク制約（source 列を含まない 4 列バージョン）を DROP する。
        //         Hibernate ddl-auto=update は既存制約を自動で落とさないため、PostgreSQL 限定で
        //         pg_constraint から「conkey が user_id, title, difficultyName, difficultyLevel の 4 列だけを指す UNIQUE 制約」を
        //         動的検索して DROP する。新しい 5 列制約（source 含む）は Hibernate が別名で作るので衝突しない。
        try {
            entityManager.createNativeQuery(
                    "DO $$ DECLARE c RECORD; BEGIN " +
                    "FOR c IN " +
                    "  SELECT conname FROM pg_constraint " +
                    "  WHERE conrelid = 'scores'::regclass " +
                    "    AND contype = 'u' " +
                    "    AND array_length(conkey, 1) = 4 " +
                    "    AND (SELECT array_agg(attname ORDER BY attname) FROM pg_attribute " +
                    "         WHERE attrelid = 'scores'::regclass AND attnum = ANY(conkey)) " +
                    // 実際の DB 物理カラム名はスネークケース（difficulty_level / difficulty_name）。
                    // 旧実装はここがキャメルケース（difficultyLevel / difficultyName）で実カラム名と
                    // 一致せず、古い 4 列制約が永遠に DROP されず残り続けていた（INFINITAS スコア
                    // 保存時に source を無視した重複違反 → 500 の原因）。
                    "        = ARRAY['difficulty_level','difficulty_name','title','user_id']::name[] " +
                    "LOOP " +
                    "  EXECUTE 'ALTER TABLE scores DROP CONSTRAINT ' || quote_ident(c.conname); " +
                    "END LOOP; END $$;"
            ).executeUpdate();
        } catch (Exception e) {
            // 既に DROP 済み or PostgreSQL 以外で動かしている場合は無視。
            logger.warn("Legacy unique constraint cleanup skipped: {}", e.getMessage());
        }

        // 手順2e: 新しい 5 列ユニーク制約（source 含む）が無ければ作成する。
        //         Score エンティティの @UniqueConstraint で宣言済みだが、ddl-auto=update は
        //         既存テーブルへの UNIQUE 制約追加を行わないため、ここで明示的に作成する。
        //         これが無いと「source を含まないユニーク性」しか効かず、arcade で保有済みの曲を
        //         INFINITAS から取り込めない（旧 4 列制約の名残）。PostgreSQL 限定・冪等。
        try {
            entityManager.createNativeQuery(
                    "DO $$ BEGIN " +
                    "IF NOT EXISTS (SELECT 1 FROM pg_constraint " +
                    "    WHERE conname = 'uk_scores_user_title_diff_source' " +
                    "      AND conrelid = 'scores'::regclass) THEN " +
                    "  ALTER TABLE scores ADD CONSTRAINT uk_scores_user_title_diff_source " +
                    "    UNIQUE (user_id, title, difficulty_name, difficulty_level, source); " +
                    "END IF; END $$;"
            ).executeUpdate();
        } catch (Exception e) {
            // PostgreSQL 以外、または既存データに重複があり追加できない場合は警告のみ。
            logger.warn("5-column unique constraint creation skipped: {}", e.getMessage());
        }

        // 手順3: 曲データと難易度表を JSON から投入する（テーブルが空の場合のみ実効）。
        //        失敗してもアプリ起動は続行させるため、try 内で握り潰す。
        try {
            seedFromResource("data/song_data.json", "song");
            seedFromResource("data/difficulty_table.json", "difficulty");
        } catch (Exception e) {
            logger.error("Warning: Could not seed game data from JSON: {}", e.getMessage(), e);
        }
    }

    /**
     * 【メソッドの役割】 クラスパス上の JSON リソースを読み込み、対応するシード処理に渡す。
     *
     * リソースが見つからない場合はスキップし、読み込みや投入中に例外が出ても警告のみ。
     *
     * @param resourcePath クラスパスリソースのパス（例: {@code data/song_data.json}）
     * @param type         投入対象の種別。{@code "song"} なら曲データ、それ以外は難易度表として扱う
     * @throws Exception 外側の呼び出し元には実質伝播しない（内部で catch 済み）
     */
    private void seedFromResource(String resourcePath, String type) throws Exception {
        try {
            // 手順1: クラスパスからリソースを取得。
            ClassPathResource resource = new ClassPathResource(resourcePath);
            // 手順2: リソースが無い場合は黙ってスキップ。jar 内に含め忘れた場合の保険。
            if (!resource.exists()) {
                logger.info("Seed resource not found: {} (skipping)", resourcePath);
                return;
            }
            // 手順3: try-with-resources でストリームを開き、必ずクローズさせる。
            try (InputStream is = resource.getInputStream()) {
                // UTF-8 固定でテキストに変換（日本語タイトル等が文字化けしないように）。
                String json = new String(is.readAllBytes(), StandardCharsets.UTF_8);
                if ("song".equals(type)) {
                    // 曲データの投入
                    gameDataService.seedSongData(json);
                } else {
                    // それ以外（= 難易度表）の投入
                    gameDataService.seedDifficultyTable(json);
                }
            }
        } catch (Exception e) {
            // リソース読み込み失敗や JSON パース失敗はアプリ起動を止めないよう警告のみ。
            logger.error("Could not seed {} data: {}", type, e.getMessage(), e);
        }
    }
}
