package com.beatseeker.backend.controller;

import com.beatseeker.backend.entity.PastScore;
import com.beatseeker.backend.entity.User;
import com.beatseeker.backend.repository.PastScoreRepository;
import com.beatseeker.backend.repository.UserRepository;
import com.beatseeker.backend.service.IidxVersions;
import com.beatseeker.backend.service.LeagueChartNotation;
import com.beatseeker.backend.service.SongTitleAliases;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 【クラスの役割】 過去作（IIDX 30 RESIDENT 〜 32 Pinky Crush）スコアの取り込み・閲覧・削除 API。
 *
 * 現実世界の概念: IIDX は毎作品でスコアがリセットされる。手元に保存してある過去シリーズの
 * スコア CSV を取り込み、「歴代自己ベスト」を振り返れるようにするための機能。
 *
 * 設計上の不変条件:
 *  - 本コントローラが読み書きするのは {@code past_scores} テーブルのみで、{@code scores} には一切触れない。
 *  - よってランキング・BEAT-PT・RATE-PT・曲別順位・リーグ・大会・Tier 投票の集計値には影響しない。
 *  - 現行作（{@link IidxVersions#CURRENT}）の取り込みは従来通り {@link ScoreController#uploadScores}
 *    が担当する。ここに現行作を渡した場合は 400 で弾き、「どちらのテーブルが正か」の曖昧さを作らない。
 *
 * バージョンの判定はフロントエンドが CSV の「バージョン」列（＝楽曲の初出作品名）から行う。
 * サーバ側は受け取った値が 30〜32 に収まっているかだけを検証する（多層防御）。
 *
 * ルーティングが {@code /api/scores/past/**} 配下にあるため、SecurityConfig の
 * {@code /api/scores/**} → authenticated() のキャッチオールがそのまま適用される（設定追加不要）。
 *
 * 主なエンドポイント:
 *  - POST   /api/scores/past/upload      過去作 CSV の取り込み（ベストレコードマージ）
 *  - GET    /api/scores/past/summary     作品ごとの取り込み状況
 *  - GET    /api/scores/past/best        歴代表示用の全過去スコア（遅延取得前提）
 *  - DELETE /api/scores/past/{version}   作品単位の削除
 */
@RestController
@RequestMapping("/api/scores/past")
public class PastScoreController {

    /** 過去作スコアのリポジトリ。 */
    private final PastScoreRepository pastScoreRepository;
    /** ユーザーリポジトリ。iidxId から User を解決するのに使う。 */
    private final UserRepository userRepository;

    public PastScoreController(PastScoreRepository pastScoreRepository,
            UserRepository userRepository) {
        this.pastScoreRepository = pastScoreRepository;
        this.userRepository = userRepository;
    }

    /**
     * 【メソッドの役割】 過去作 1 作品分のスコアを取り込む。
     *
     * マージ方式: 既存の {@code scores} 側 upload と同じ「ベストレコードマージ」。
     *   スコア / クリアランプ / ミスカウントのいずれかが改善している場合のみ上書きする。
     *   全置換にしないのは、同一作品の古い CSV を後から取り込んだときに
     *   データが静かに減るのを防ぐため（順序非依存にする）。取り込みミスは作品単位 DELETE で復旧する。
     *
     * 曲名の正規化: 作品によって表記が変わった曲名（31 EPOLIS の "VØID" と現行の "VOID" など）は
     *   {@link SongTitleAliases} で現行表記に寄せてから保存する。過去作スコアは現行スコアと
     *   曲名 + 難易度名で突き合わせるため、ここで揃えないと同一譜面が歴代ベストから漏れる。
     *
     * @param auth 認証情報
     * @param req  バージョン番号と譜面単位レコードの配列
     * @return 取り込み件数・新規件数・更新件数を含む Map
     */
    @PostMapping("/upload")
    @Transactional
    public ResponseEntity<Map<String, Object>> uploadPastScores(
            Authentication auth,
            @RequestBody PastScoreUploadRequest req) {

        User user = getUser(auth);

        // 手順0: バージョンを検証する。現行作はこのエンドポイントの管轄外。
        if (req == null || req.version() == null) {
            return badRequest("バージョンが指定されていません");
        }
        if (req.version() == IidxVersions.CURRENT) {
            return badRequest("現行作のスコアは通常の取り込みを使用してください");
        }
        if (!IidxVersions.isSupportedPast(req.version())) {
            return badRequest("対応していないバージョンです（対応範囲: "
                    + IidxVersions.MIN_PAST + "〜" + IidxVersions.MAX_PAST + "）");
        }
        List<PastScoreRecord> records = req.records() != null ? req.records() : List.of();

        // 手順1: 対象作品の既存レコードを 1 クエリで読み込み、(曲名|難易度名) で O(1) 参照できるようにする。
        //        ★（difficultyLevel）はキーに含めない。作品間で変動する値なのでキーに使うと重複行を生む。
        Map<String, PastScore> existingMap = new HashMap<>();
        for (PastScore p : pastScoreRepository.findByUserAndVersion(user, req.version())) {
            existingMap.put(chartKey(p.getTitle(), p.getDifficultyName()), p);
        }

        LocalDateTime now = LocalDateTime.now();
        int inserted = 0;
        int updated = 0;

        for (PastScoreRecord raw : records) {
            // タイトル・難易度名が欠けた行は識別できないのでスキップする。
            if (raw.title() == null || raw.title().isBlank()
                    || raw.difficultyName() == null || raw.difficultyName().isBlank()) {
                continue;
            }

            // 作品によって表記が変わった曲名は現行表記に寄せてから突き合わせ・保存する。
            PastScoreRecord r = raw.withTitle(SongTitleAliases.canonical(raw.title()));

            String key = chartKey(r.title(), r.difficultyName());
            PastScore existing = existingMap.get(key);

            if (existing == null) {
                PastScore ps = new PastScore();
                ps.setUser(user);
                ps.setVersion(req.version());
                applyRecord(ps, r, now);
                pastScoreRepository.save(ps);
                // 同一ペイロード内に同じ譜面が複数含まれた場合に重複 INSERT しないよう Map に載せておく。
                existingMap.put(key, ps);
                inserted++;
            } else {
                // ミスカウントは「小さいほど優秀」。null は最悪値に置換して比較する。
                int oldMiss = existing.getMissCount() != null ? existing.getMissCount() : Integer.MAX_VALUE;
                int newMiss = r.missCount() != null ? r.missCount() : Integer.MAX_VALUE;
                int oldScore = existing.getScore() != null ? existing.getScore() : 0;
                int newScore = r.score() != null ? r.score() : 0;
                int oldRank = LeagueChartNotation.clearTypeRank(existing.getClearType());
                int newRank = LeagueChartNotation.clearTypeRank(r.clearType());

                if (newScore > oldScore || newRank > oldRank || newMiss < oldMiss) {
                    applyRecord(existing, r, now);
                    pastScoreRepository.save(existing);
                    updated++;
                }
            }
        }

        Map<String, Object> response = new HashMap<>();
        response.put("version", req.version());
        response.put("versionName", IidxVersions.nameOf(req.version()));
        response.put("inserted", inserted);
        response.put("updated", updated);
        response.put("totalCount", pastScoreRepository.countByUserAndVersion(user, req.version()));
        response.put("message", IidxVersions.nameOf(req.version()) + " のスコアを取り込みました");
        return ResponseEntity.ok(response);
    }

    /**
     * 【メソッドの役割】 作品ごとの取り込み状況を返す（過去データ管理 UI 用）。
     *
     * @return [{version, versionName, chartCount, importedAt, lastPlayedAt}, ...] を作品降順で
     */
    @GetMapping("/summary")
    public ResponseEntity<List<Map<String, Object>>> getSummary(Authentication auth) {
        User user = getUser(auth);

        List<Map<String, Object>> result = new ArrayList<>();
        for (Object[] row : pastScoreRepository.findSummaryByUser(user)) {
            Integer version = (Integer) row[0];
            Map<String, Object> entry = new HashMap<>();
            entry.put("version", version);
            entry.put("versionName", IidxVersions.nameOf(version));
            entry.put("chartCount", row[1]);
            entry.put("importedAt", row[2]);
            entry.put("lastPlayedAt", row[3]);
            result.add(entry);
        }
        return ResponseEntity.ok(result);
    }

    /**
     * 【メソッドの役割】 歴代表示用に、本人の過去作スコアを全件返す。
     *
     * 現行作（33）のスコアは含めない。フロントは既に {@code /api/scores/me} で現行作を持っているので、
     * 二重取得を避け、クライアント側で突き合わせる設計にしている。
     *
     * ペイロード削減のためキーを 1 文字に切り詰めている:
     *   v=version, t=title, d=difficultyName, l=difficultyLevel,
     *   s=score, c=clearType, j=djLevel, m=missCount, p=pgreat, g=great
     *
     * この応答は歴代タブ／歴代モードを開いたときにだけ遅延取得する想定（初期ロードには載せない）。
     */
    @GetMapping("/best")
    public ResponseEntity<List<Map<String, Object>>> getPastBest(Authentication auth) {
        User user = getUser(auth);

        List<PastScore> all = pastScoreRepository.findByUserOrderByVersionAscTitleAsc(user);
        List<Map<String, Object>> result = new ArrayList<>(all.size());
        for (PastScore p : all) {
            Map<String, Object> m = new HashMap<>();
            m.put("v", p.getVersion());
            m.put("t", p.getTitle());
            m.put("d", p.getDifficultyName());
            m.put("l", p.getDifficultyLevel());
            m.put("s", p.getScore());
            m.put("c", p.getClearType());
            m.put("j", p.getDjLevel());
            m.put("m", p.getMissCount());
            m.put("p", p.getPgreat());
            m.put("g", p.getGreat());
            result.add(m);
        }
        return ResponseEntity.ok(result);
    }

    /**
     * 【メソッドの役割】 指定作品の過去スコアを丸ごと削除する（取り込みミスのリカバリ用）。
     *
     * 別テーブルなので、既存の集計やランキングには一切影響しない。
     */
    @DeleteMapping("/{version}")
    @Transactional
    public ResponseEntity<Map<String, Object>> deleteVersion(
            Authentication auth,
            @PathVariable Integer version) {

        User user = getUser(auth);

        if (!IidxVersions.isSupportedPast(version)) {
            return badRequest("対応していないバージョンです");
        }

        long deleted = pastScoreRepository.countByUserAndVersion(user, version);
        pastScoreRepository.deleteByUserAndVersion(user, version);

        Map<String, Object> response = new HashMap<>();
        response.put("version", version);
        response.put("deleted", deleted);
        response.put("message", IidxVersions.nameOf(version) + " のスコアを削除しました");
        return ResponseEntity.ok(response);
    }

    // ---- 内部ヘルパー ----

    /** 一意性制約（user_id, version, title, difficultyName）に対応する lookup キー。 */
    private String chartKey(String title, String difficultyName) {
        return (title == null ? "" : title) + "||" + (difficultyName == null ? "" : difficultyName);
    }

    /** リクエスト 1 件の値をエンティティへ反映する。新規・更新の両方から呼ぶ。 */
    private void applyRecord(PastScore ps, PastScoreRecord r, LocalDateTime now) {
        ps.setTitle(r.title());
        ps.setArtist(r.artist());
        ps.setGenre(r.genre());
        ps.setDifficultyName(r.difficultyName());
        ps.setDifficultyLevel(r.difficultyLevel());
        ps.setScore(r.score() != null ? r.score() : 0);
        ps.setClearType(r.clearType());
        ps.setDjLevel(r.djLevel());
        ps.setPgreat(r.pgreat());
        ps.setGreat(r.great());
        ps.setMissCount(r.missCount());
        ps.setPlayCount(r.playCount());
        ps.setLastPlayedAt(r.lastPlayedAt());
        ps.setImportedAt(now);
    }

    /** エラーレスポンスの組み立てヘルパー。 */
    private ResponseEntity<Map<String, Object>> badRequest(String message) {
        Map<String, Object> body = new HashMap<>();
        body.put("message", message);
        return ResponseEntity.badRequest().body(body);
    }

    /**
     * 【メソッドの役割】 認証情報から User を解決する共通ヘルパー。
     *
     * @param auth 認証情報
     * @return ログインユーザー
     * @throws RuntimeException 未認証 or User 不在
     */
    private User getUser(Authentication auth) {
        if (auth == null || !auth.isAuthenticated()) {
            throw new RuntimeException("Not authenticated");
        }
        String iidxId = (String) auth.getPrincipal();
        return userRepository.findByIidxId(iidxId)
                .orElseThrow(() -> new RuntimeException("User not found"));
    }

    // ---- リクエスト DTO ----

    /**
     * 【レコード】 過去作スコア取り込みのリクエスト DTO。
     *
     * @param version 作品バージョン番号（30〜32）。フロントが CSV の「バージョン」列から自動判定した値
     * @param records 譜面単位のスコアレコード
     */
    public record PastScoreUploadRequest(
            Integer version,
            List<PastScoreRecord> records) {
    }

    /**
     * 【レコード】 譜面 1 件分のスコア。{@link ScoreController.ScoreUploadRequest} と同じ粒度だが、
     * source（arcade/infinitas）は持たない。INFINITAS は作品リセットが無いため過去作の概念が存在しない。
     */
    public record PastScoreRecord(
            String title,
            String artist,
            String genre,
            String difficultyName,
            Integer difficultyLevel,
            Integer score,
            String clearType,
            String djLevel,
            Integer pgreat,
            Integer great,
            Integer missCount,
            Integer playCount,
            /** CSV の「最終プレー日時」列（例: "2025-09-17 08:27"）。無加工で保持する。 */
            String lastPlayedAt) {

        /**
         * 【メソッドの役割】 タイトルだけ差し替えたコピーを返す（曲名の表記ゆれ正規化用）。
         * 差し替え後の値が同じなら自身をそのまま返す。
         */
        PastScoreRecord withTitle(String newTitle) {
            if (newTitle == null || newTitle.equals(title)) return this;
            return new PastScoreRecord(newTitle, artist, genre, difficultyName, difficultyLevel,
                    score, clearType, djLevel, pgreat, great, missCount, playCount, lastPlayedAt);
        }
    }
}
