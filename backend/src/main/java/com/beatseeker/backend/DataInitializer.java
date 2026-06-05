package com.beatseeker.backend;

import com.beatseeker.backend.service.GameDataService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import jakarta.persistence.EntityManager;

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
 *  - 既存ユーザーの未設定カラム（language, showRateTier 等）にデフォルト値を埋める
 *  - 旧/新ユニーク制約の付け替え（INFINITAS の source 列対応）
 *  - 曲データ / 難易度表のマスターデータを JSON リソースから投入（未シード時のみ）
 *
 * 【例外方針 — 厳守】 初期化に失敗してもアプリ本体は必ず起動させる。
 *   そのため各ステップは **独立したトランザクション + try/catch** で実行し、
 *   1 ステップの失敗が他ステップや起動自体に波及しないようにする。
 *
 *   ※ 以前は run() 全体を 1 つの {@code @Transactional} で囲っていたが、これが原因で
 *     本番がクラッシュループした:
 *       起動時 ALTER TABLE（ACCESS EXCLUSIVE 必要）が、並行して走る重い集計 SELECT
 *       （[[SongArenaAveragesCacheService]]）のロック解放待ちで statement_timeout(30s) に
 *       かかり中断 → catch で握っても PostgreSQL トランザクションは「中断状態」に汚染され、
 *       run() 終了後の commit が ApplicationRunner の外で例外を投げて起動失敗（exit 1）に
 *       なっていた。各ステップを独立トランザクション化することで、ある操作の中断が
 *       後続や commit を巻き込まないようにしている。
 */
@Component
public class DataInitializer implements ApplicationRunner {

    private static final Logger logger = LoggerFactory.getLogger(DataInitializer.class);

    /** JPA の EntityManager。簡易な UPDATE / DDL を直接発行するために使う。 */
    private final EntityManager entityManager;

    /** 曲データ・難易度表の投入処理を担うサービス層。 */
    private final GameDataService gameDataService;

    /**
     * ステップ単位で独立トランザクションを張るためのテンプレート。
     * run() に {@code @Transactional} を付ける旧方式だと、汚染トランザクションの commit が
     * 起動を殺すため、明示的にステップ毎の境界を切る。
     */
    private final TransactionTemplate txTemplate;

    /**
     * 【コンストラクタ】 Spring の DI コンテナから依存オブジェクトを注入する。
     *
     * @param entityManager   JPA の EntityManager
     * @param gameDataService 曲・難易度表シード用サービス
     * @param txManager       トランザクション境界をステップ毎に張るためのマネージャ
     */
    public DataInitializer(EntityManager entityManager,
                           GameDataService gameDataService,
                           PlatformTransactionManager txManager) {
        this.entityManager = entityManager;
        this.gameDataService = gameDataService;
        this.txTemplate = new TransactionTemplate(txManager);
    }

    /**
     * 【メソッドの役割】 アプリ起動直後に Spring が自動で呼び出すエントリポイント。
     *
     * 各ステップは {@link #runStep} で独立トランザクション + 例外握り潰しで実行するため、
     * どれか 1 つが失敗しても他ステップと起動自体には影響しない。
     *
     * @param args コマンドライン引数（本処理では未使用）
     */
    @Override
    public void run(ApplicationArguments args) {
        // 手順1: 既存ユーザーの未設定フラグ/カラムにデフォルトをバックフィルする。
        //        後からカラム追加した際の初期化。見た目を壊さないよう既定 true / 'ja'。
        runStep("backfill user columns", () -> {
            entityManager.createQuery("UPDATE User u SET u.language = 'ja' WHERE u.language IS NULL")
                    .executeUpdate();
            entityManager.createQuery("UPDATE User u SET u.showRateTier = true WHERE u.showRateTier IS NULL")
                    .executeUpdate();
            entityManager.createQuery("UPDATE User u SET u.showArcadeScores = true WHERE u.showArcadeScores IS NULL")
                    .executeUpdate();
            entityManager.createQuery("UPDATE User u SET u.showInfinitasScores = true WHERE u.showInfinitasScores IS NULL")
                    .executeUpdate();
        });

        // 手順2: 既存スコアの source カラムが未設定ならアーケード扱いで埋める。
        //        INFINITAS 機能追加前のレコードは arcade 出自なので安全に一括代入できる。
        runStep("backfill scores.source", () ->
            entityManager.createNativeQuery("UPDATE scores SET source = 'arcade' WHERE source IS NULL")
                    .executeUpdate());

        // 手順3: 旧ユニーク制約（source 列を含まない 4 列バージョン）を DROP する。
        //        Hibernate ddl-auto=update は既存制約を自動で落とさないため、PostgreSQL 限定で
        //        pg_constraint から「conkey が user_id, title, difficultyName, difficultyLevel の 4 列だけを指す UNIQUE 制約」を
        //        動的検索して DROP する。新しい 5 列制約（source 含む）は Hibernate が別名で作るので衝突しない。
        runStep("drop legacy 4-column unique constraint", () ->
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
                    "        = ARRAY['difficulty_level','difficulty_name','title','user_id']::name[] " +
                    "LOOP " +
                    "  EXECUTE 'ALTER TABLE scores DROP CONSTRAINT ' || quote_ident(c.conname); " +
                    "END LOOP; END $$;"
            ).executeUpdate());

        // 手順3.5: 5 列ユニークキー (user_id, title, difficulty_name, difficulty_level, source) で
        //          重複している行を削除する（各キーで score 最大・id 最大の 1 行のみ残す）。
        //          ユニーク制約が無かった期間に重複アップロード等で生まれた重複行を掃除する。
        //          これを先に行わないと手順4の UNIQUE 制約追加が「重複あり」で失敗し、毎回スキップされ続ける。
        runStep("dedupe scores before unique constraint", () ->
            entityManager.createNativeQuery(
                    "DELETE FROM scores s USING ( " +
                    "  SELECT id, ROW_NUMBER() OVER ( " +
                    "    PARTITION BY user_id, title, difficulty_name, difficulty_level, source " +
                    "    ORDER BY score DESC NULLS LAST, id DESC " +
                    "  ) AS rn FROM scores " +
                    ") d WHERE s.id = d.id AND d.rn > 1"
            ).executeUpdate());

        // 手順4: 新しい 5 列ユニーク制約（source 含む）が無ければ作成する。
        //        Score エンティティの @UniqueConstraint で宣言済みだが、ddl-auto=update は
        //        既存テーブルへの UNIQUE 制約追加を行わないため、ここで明示的に作成する。
        //        これが無いと arcade で保有済みの曲を INFINITAS から取り込めない（旧 4 列制約の名残）。
        runStep("add 5-column unique constraint", () ->
            entityManager.createNativeQuery(
                    "DO $$ BEGIN " +
                    "IF NOT EXISTS (SELECT 1 FROM pg_constraint " +
                    "    WHERE conname = 'uk_scores_user_title_diff_source' " +
                    "      AND conrelid = 'scores'::regclass) THEN " +
                    "  ALTER TABLE scores ADD CONSTRAINT uk_scores_user_title_diff_source " +
                    "    UNIQUE (user_id, title, difficulty_name, difficulty_level, source); " +
                    "END IF; END $$;"
            ).executeUpdate());

        // 手順5: 曲データと難易度表を JSON から投入する（テーブルが空の場合のみ実効）。
        //        gameDataService 側が自前で @Transactional を張るため、ここでは txTemplate を使わない。
        try {
            seedFromResource("data/song_data.json", "song");
            seedFromResource("data/difficulty_table.json", "difficulty");
        } catch (Exception e) {
            logger.error("Warning: Could not seed game data from JSON: {}", e.getMessage(), e);
        }
    }

    /**
     * 【メソッドの役割】 1 つの初期化ステップを「独立トランザクション + 例外握り潰し」で実行する。
     *
     * これにより、ある操作が失敗（DDL のロック待ちタイムアウト等）しても、
     *  - そのステップのトランザクションだけがロールバックされ、
     *  - 他ステップや ApplicationRunner（＝起動）には一切波及しない。
     *
     * @param name 失敗時ログ用のステップ名
     * @param work 実行する処理
     */
    private void runStep(String name, Runnable work) {
        try {
            txTemplate.executeWithoutResult(status -> work.run());
        } catch (Exception e) {
            // 起動を止めないため、失敗は警告ログのみ（次回起動で冪等に再試行される）。
            logger.warn("DataInitializer step '{}' skipped (continuing startup): {}", name, e.getMessage());
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
