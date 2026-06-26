package com.beatseeker.backend.controller;

import com.beatseeker.backend.entity.KinjoCupParticipant;
import com.beatseeker.backend.entity.Score;
import com.beatseeker.backend.entity.User;
import com.beatseeker.backend.repository.KinjoCupParticipantRepository;
import com.beatseeker.backend.repository.ScoreRepository;
import com.beatseeker.backend.repository.UserRepository;
import com.beatseeker.backend.service.AdminAuthService;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

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
    /** 管理者判定ロジックを集約した Service。 */
    private final AdminAuthService adminAuthService;

    public KinjoCupController(KinjoCupParticipantRepository participantRepository,
                              UserRepository userRepository,
                              ScoreRepository scoreRepository,
                              AdminAuthService adminAuthService) {
        this.participantRepository = participantRepository;
        this.userRepository = userRepository;
        this.scoreRepository = scoreRepository;
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
        List<Map<String, Object>> result = participantRepository.findAllByOrderByCreatedAtAsc().stream()
                .map(this::toPublicMap)
                .sorted(Comparator.comparingDouble(
                        (Map<String, Object> m) -> ((Number) m.getOrDefault("totalBeatPt", 0.0)).doubleValue())
                        .reversed())
                .toList();
        return ResponseEntity.ok(result);
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
}
