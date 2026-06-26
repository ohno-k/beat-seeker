package com.beatseeker.backend.controller;

import com.beatseeker.backend.entity.KinjoCupParticipant;
import com.beatseeker.backend.entity.Score;
import com.beatseeker.backend.entity.User;
import com.beatseeker.backend.repository.KinjoCupParticipantRepository;
import com.beatseeker.backend.repository.ScoreRepository;
import com.beatseeker.backend.repository.SongDefinitionRepository;
import com.beatseeker.backend.repository.UserRepository;
import com.beatseeker.backend.service.AdminAuthService;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 【クラスの役割】「きんじょー杯」特設ページ（/kinjocup）用の REST コントローラ。
 *
 * 大会のドラフト選考の参考として、登録された参加者名簿とその実力データを返す。
 * URL 直打ちで誰でも到達できる独立ページ用の API。
 *
 * 認可方針:
 *  - 参加者一覧の閲覧（GET）はログイン不要の公開エンドポイント。
 *    （掲載する総合力 / 段位 / アリーナはランキング等で既に公開済みの指標のみ）
 *  - 参加者の追加（POST）・削除（DELETE）は管理者のみ。
 *    Spring Security 側では「要ログイン」までガードし、管理者判定はここで {@link AdminAuthService} を使って行う。
 *    （SecurityConfig は末尾 anyRequest().permitAll() の fail-open なので、書き込み系は二重で締める）
 *
 * 主なエンドポイント:
 *  - GET    /api/kinjocup/participants        … 参加者一覧（公開・総合力降順）
 *  - POST   /api/kinjocup/participants        … 参加者を追加（管理者）
 *  - DELETE /api/kinjocup/participants/{id}    … 参加者を削除（管理者）
 */
@RestController
@RequestMapping("/api/kinjocup")
public class KinjoCupController {

    /** 参加者名簿の永続化リポジトリ。 */
    private final KinjoCupParticipantRepository participantRepository;
    /** 追加時のユーザー解決・認証ユーザー解決に使うリポジトリ。 */
    private final UserRepository userRepository;
    /** 参加者の詳細表示（ダッシュボード/スコア一覧）用にスコアを引くリポジトリ。 */
    private final ScoreRepository scoreRepository;
    /** LV12 総数・譜面メタ（notes/level）取得用リポジトリ。 */
    private final SongDefinitionRepository songDefinitionRepository;
    /** 管理者判定ロジックを集約した Service。 */
    private final AdminAuthService adminAuthService;

    public KinjoCupController(KinjoCupParticipantRepository participantRepository,
                              UserRepository userRepository,
                              ScoreRepository scoreRepository,
                              SongDefinitionRepository songDefinitionRepository,
                              AdminAuthService adminAuthService) {
        this.participantRepository = participantRepository;
        this.userRepository = userRepository;
        this.scoreRepository = scoreRepository;
        this.songDefinitionRepository = songDefinitionRepository;
        this.adminAuthService = adminAuthService;
    }

    /**
     * 【メソッドの役割】 参加者一覧を公開で返す。総合力(Beat-Pt)降順で並べる。
     *
     * 個人を特定し得る IIDX ID は公開ペイロードに含めない（ドラフト参考に不要なため）。
     * 各参加者の詳細ページ（/user/{userId}）へリンクできるよう userId は返す。
     *
     * @return 参加者サマリの List（総合力降順）
     */
    @GetMapping("/participants")
    public ResponseEntity<List<Map<String, Object>>> listParticipants() {
        List<KinjoCupParticipant> parts = participantRepository.findAllByOrderByCreatedAtAsc();

        // LV12 ANOTHER/LEGGENDARIA の総譜面数（AAA数/MAX-数 の分母）。全参加者共通の定数。
        long lv12Total = songDefinitionRepository.countActiveLv12AnotherLegg();

        // 参加者全員の譜面別ベストスコア（notes/level 付き）を 1 クエリで取得し、userId ごとに集計。
        Map<Long, Lv12RateStats> statsByUser = new HashMap<>();
        List<Long> userIds = parts.stream().map(p -> p.getUser().getId()).toList();
        if (!userIds.isEmpty()) {
            Map<Long, List<Map<String, Object>>> rowsByUser = scoreRepository
                    .findBestAnotherLeggWithDefForUsers(userIds).stream()
                    .collect(Collectors.groupingBy(r -> ((Number) r.get("userId")).longValue()));
            rowsByUser.forEach((uid, rows) -> statsByUser.put(uid, computeStats(rows)));
        }

        List<Map<String, Object>> result = parts.stream()
                .map(p -> {
                    Map<String, Object> m = toPublicMap(p);
                    Lv12RateStats st = statsByUser.getOrDefault(p.getUser().getId(), Lv12RateStats.EMPTY);
                    m.put("lv12AaaCount", st.aaa());
                    m.put("lv12MaxMinusCount", st.maxMinus());
                    m.put("lv12Total", lv12Total);
                    m.put("rateFloorScoreRate", st.rateFloor()); // 100曲未満なら null
                    m.put("rateEligibleCount", st.rateEligible());
                    return m;
                })
                .sorted(Comparator.comparingDouble(
                        (Map<String, Object> m) -> ((Number) m.getOrDefault("totalBeatPt", 0.0)).doubleValue())
                        .reversed())
                .toList();
        return ResponseEntity.ok(result);
    }

    /**
     * 【メソッドの役割】 1 参加者分の「LV12 AAA数・MAX-数」と「RATE-TIER 下限（100曲目のスコアレート）」を計算する。
     *
     * 入力は {@link ScoreRepository#findBestAnotherLeggWithDefForUsers} の行
     * （userId/title/difficultyName/score/notes/level、譜面別ベスト・重複排除済み）。
     *
     * - AAA  : LV12 かつ score*9 >= notes*16（スコア率 >= 8/9 ≒ 88.89%）
     * - MAX- : LV12 かつ score*9 >= notes*17（スコア率 >= 17/18 ≒ 94.44%）
     * - RATE-TIER 下限: ANOTHER/LEGGENDARIA 全レベルのうち RATE-PT 対象（スコア率 >= 77.77%）の
     *   レートを降順に並べ、100 番目のスコアレート。100 曲未満なら null。
     */
    private Lv12RateStats computeStats(List<Map<String, Object>> rows) {
        int aaa = 0;
        int maxMinus = 0;
        List<Double> eligibleRates = new ArrayList<>();
        for (Map<String, Object> r : rows) {
            long score = toLong(r.get("score"));
            long notes = toLong(r.get("notes"));
            if (notes <= 0) continue;
            Object lvlObj = r.get("level");
            int level = lvlObj == null ? 0 : ((Number) lvlObj).intValue();

            if (level == 12) {
                if (score * 9 >= notes * 16) aaa++;        // AAA 以上
                if (score * 9 >= notes * 17) maxMinus++;   // MAX- 以上
            }
            // RATE-PT 対象（スコア率 >= 77.77% で PT > 0）。全レベル。
            double rate = score * 100.0 / (notes * 2.0);
            if (rate >= 77.77) eligibleRates.add(rate);
        }
        eligibleRates.sort(Comparator.reverseOrder());
        Double rateFloor = eligibleRates.size() >= 100
                ? Math.round(eligibleRates.get(99) * 100.0) / 100.0
                : null;
        return new Lv12RateStats(aaa, maxMinus, rateFloor, eligibleRates.size());
    }

    /** ネイティブクエリ由来の数値（Integer/Long/BigInteger 等）を long に正規化する。 */
    private static long toLong(Object o) {
        return o == null ? 0L : ((Number) o).longValue();
    }

    /**
     * 【メソッドの役割】 指定参加者のスコア一覧を公開で返す（特設ページ内のダッシュボード/スコア一覧用）。
     *
     * 名簿に登録された参加者は「閲覧対象として登録済み」とみなし、本人のプライバシー設定に関わらず
     * スコアを返す。ただし名簿に居ないユーザーの ID を渡された場合は 404（任意ユーザーの情報漏洩防止）。
     * 応答はフロントの useScores と同じ「譜面フラット配列」。曲単位のグルーピングはクライアント側で行う。
     *
     * @param userId beat-seeker ユーザーの ID
     * @return スコアのフラット配列。参加者でなければ 404。
     */
    @GetMapping("/participants/{userId}/scores")
    public ResponseEntity<?> getParticipantScores(@PathVariable Long userId) {
        User user = userRepository.findById(userId).orElse(null);
        if (user == null || !participantRepository.existsByUser(user)) {
            return ResponseEntity.status(404).body(Map.of("error", "対象は参加者として登録されていません"));
        }

        List<Map<String, Object>> result = scoreRepository.findByUserOrderByUploadedAtAsc(user).stream()
                .map(this::toScoreMap)
                .toList();
        return ResponseEntity.ok(result);
    }

    /**
     * 【メソッドの役割】 参加者を名簿に追加する（管理者のみ）。
     *
     * @param auth 認証情報（管理者限定）
     * @param req  追加対象ユーザーの ID を含むリクエスト
     * @return 追加された参加者サマリ。権限不足は 403、対象ユーザー不在は 404、二重登録は 409。
     */
    @PostMapping("/participants")
    @Transactional
    public ResponseEntity<?> addParticipant(Authentication auth, @RequestBody AddParticipantRequest req) {
        User admin = requireAdmin(auth);
        if (admin == null) {
            return ResponseEntity.status(403).body(Map.of("error", "管理者のみ参加者を追加できます"));
        }
        if (req == null || req.userId() == null) {
            return ResponseEntity.badRequest().body(Map.of("error", "userId は必須です"));
        }

        User target = userRepository.findById(req.userId()).orElse(null);
        if (target == null) {
            return ResponseEntity.status(404).body(Map.of("error", "指定したユーザーが見つかりません"));
        }
        if (participantRepository.existsByUser(target)) {
            return ResponseEntity.status(409).body(Map.of("error", "既に登録済みの参加者です"));
        }

        KinjoCupParticipant p = new KinjoCupParticipant();
        p.setUser(target);
        participantRepository.save(p);

        return ResponseEntity.ok(toPublicMap(p));
    }

    /**
     * 【メソッドの役割】 参加者のメモを更新する。協同編集のため誰でも可（ログイン不要）。
     *
     * 追加・削除は管理者のみだが、メモ（ドラフト選考の覚書）は閲覧者全員が書き込める。
     *
     * @param id   参加者エントリ（KinjoCupParticipant）の ID
     * @param req  新しいメモ本文（null/空文字でメモ消去）
     * @return 更新後の参加者サマリ。対象不在は 404、長すぎは 400。
     */
    @PutMapping("/participants/{id}/note")
    @Transactional
    public ResponseEntity<?> updateNote(@PathVariable Long id,
                                        @RequestBody UpdateNoteRequest req) {
        KinjoCupParticipant p = participantRepository.findById(id).orElse(null);
        if (p == null) {
            return ResponseEntity.status(404).body(Map.of("error", "対象の参加者が見つかりません"));
        }
        String note = req == null || req.note() == null ? null : req.note().trim();
        if (note != null && note.length() > 2000) {
            return ResponseEntity.badRequest().body(Map.of("error", "メモは2000文字以内で入力してください"));
        }
        // 空文字は「メモ無し」として null 化する。
        p.setNote(note == null || note.isEmpty() ? null : note);
        participantRepository.save(p);
        return ResponseEntity.ok(toPublicMap(p));
    }

    /**
     * 【メソッドの役割】 参加者を名簿から削除する（管理者のみ）。
     *
     * @param auth 認証情報（管理者限定）
     * @param id   参加者エントリ（KinjoCupParticipant）の ID
     * @return 204 No Content。権限不足は 403、対象不在は 404。
     */
    @DeleteMapping("/participants/{id}")
    @Transactional
    public ResponseEntity<?> removeParticipant(Authentication auth, @PathVariable Long id) {
        User admin = requireAdmin(auth);
        if (admin == null) {
            return ResponseEntity.status(403).body(Map.of("error", "管理者のみ参加者を削除できます"));
        }
        if (!participantRepository.existsById(id)) {
            return ResponseEntity.status(404).body(Map.of("error", "対象の参加者が見つかりません"));
        }
        participantRepository.deleteById(id);
        return ResponseEntity.noContent().build();
    }

    // ---------------------------------------------------------------------
    // 内部ヘルパー
    // ---------------------------------------------------------------------

    /**
     * 認証情報から管理者ユーザーを解決する。未認証・ユーザー不在・非管理者なら null を返す。
     * （Controller 側で 403 を返す前提。例外は投げない）
     */
    private User requireAdmin(Authentication auth) {
        if (auth == null || !auth.isAuthenticated()) {
            return null;
        }
        Object principal = auth.getPrincipal();
        if (!(principal instanceof String iidxId)) {
            return null;
        }
        User user = userRepository.findByIidxId(iidxId).orElse(null);
        if (user == null || !adminAuthService.isAdmin(user)) {
            return null;
        }
        return user;
    }

    /**
     * Score エンティティをフロント（useScores）が期待する譜面フラット Map に変換する。
     * UI に必要な最小限のフィールドのみ（オプション情報は付与しない）。
     */
    private Map<String, Object> toScoreMap(Score s) {
        Map<String, Object> m = new HashMap<>();
        m.put("id", s.getId());
        m.put("title", s.getTitle() != null ? s.getTitle() : "");
        m.put("difficultyName", s.getDifficultyName() != null ? s.getDifficultyName() : "");
        m.put("difficultyLevel", s.getDifficultyLevel() != null ? s.getDifficultyLevel() : 0);
        m.put("score", s.getScore() != null ? s.getScore() : 0);
        m.put("clearType", s.getClearType() != null ? s.getClearType() : "");
        m.put("djLevel", s.getDjLevel() != null ? s.getDjLevel() : "");
        m.put("pgreat", s.getPgreat() != null ? s.getPgreat() : 0);
        m.put("great", s.getGreat() != null ? s.getGreat() : 0);
        m.put("missCount", s.getMissCount());
        return m;
    }

    /** 参加者エントリを公開用 Map に変換する。実力データは紐づく User から都度読み出す。 */
    private Map<String, Object> toPublicMap(KinjoCupParticipant p) {
        User u = p.getUser();
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", p.getId());
        m.put("userId", u.getId());
        m.put("iidxId", u.getIidxId() != null ? u.getIidxId() : "");
        m.put("displayName", u.getDisplayName() != null ? u.getDisplayName() : "");
        m.put("danRank", u.getDanRank() != null ? u.getDanRank() : "");
        m.put("arenaRank", u.getArenaRank() != null ? u.getArenaRank() : "");
        m.put("totalBeatPt", u.getTotalBeatPt() != null ? u.getTotalBeatPt() : 0.0);
        m.put("lastUploadedAt", u.getLastUploadedAt());
        m.put("note", p.getNote() != null ? p.getNote() : "");
        return m;
    }

    /** 参加者追加リクエストのボディ（追加対象ユーザーの ID）。 */
    public record AddParticipantRequest(Long userId) {
    }

    /** メモ更新リクエストのボディ。 */
    public record UpdateNoteRequest(String note) {
    }

    /**
     * 1 参加者分の集計結果。
     * @param aaa          LV12 AAA 達成数
     * @param maxMinus     LV12 MAX- 達成数
     * @param rateFloor    RATE-TIER 下限（100曲目）のスコアレート。100曲未満なら null
     * @param rateEligible RATE-PT 対象（スコア率 >= 77.77%）の譜面数
     */
    private record Lv12RateStats(int aaa, int maxMinus, Double rateFloor, int rateEligible) {
        /** 集計対象スコアが無い参加者向けの空集計。 */
        static final Lv12RateStats EMPTY = new Lv12RateStats(0, 0, null, 0);
    }
}
