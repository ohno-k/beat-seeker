package com.beatseeker.backend.controller;

import com.beatseeker.backend.entity.KinjoCupParticipant;
import com.beatseeker.backend.entity.User;
import com.beatseeker.backend.repository.KinjoCupParticipantRepository;
import com.beatseeker.backend.repository.UserRepository;
import com.beatseeker.backend.service.AdminAuthService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.util.Arrays;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 【クラスの役割】「きんじょー杯」特設ページ（/kinjocup）用の REST コントローラ。
 *
 * 大会のドラフト選考の参考として、登録された参加者名簿とその実力データを返す。
 *
 * 認可方針:
 *  - 参加者一覧の閲覧（GET）は「閲覧ホワイトリスト」に載っているユーザー（ドラフト選考の関係者）
 *    および管理者のみ。ホワイトリストは {@code kinjocup.viewer-ids}（既定 18,24,30,655,134,108）。
 *  - 参加者の追加（POST）・削除（DELETE）は管理者のみ。
 *  - Spring Security 側では「要ログイン」までガードし、ユーザー単位の権限判定はここで行う
 *    （SecurityConfig は末尾 anyRequest().permitAll() の fail-open なので、二重で締める）。
 *
 * 主なエンドポイント:
 *  - GET    /api/kinjocup/participants        … 参加者一覧（閲覧ホワイトリストのみ・総合力降順）
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
    /** 管理者判定ロジックを集約した Service。 */
    private final AdminAuthService adminAuthService;
    /** 参加者一覧を閲覧できるユーザー ID のホワイトリスト（ドラフト選考の関係者）。 */
    private final Set<Long> viewerIds;

    public KinjoCupController(KinjoCupParticipantRepository participantRepository,
                              UserRepository userRepository,
                              AdminAuthService adminAuthService,
                              @Value("${kinjocup.viewer-ids:18,24,30,655,134,108}") String viewerIdsCsv) {
        this.participantRepository = participantRepository;
        this.userRepository = userRepository;
        this.adminAuthService = adminAuthService;
        this.viewerIds = parseIds(viewerIdsCsv);
    }

    /**
     * 【メソッドの役割】 参加者一覧を返す。閲覧ホワイトリストに載るユーザー（または管理者）のみ。
     * 総合力(Beat-Pt)降順で並べる。
     *
     * 個人を特定し得る IIDX ID は応答に含めない。各参加者の詳細ページ（/user/{userId}）へ
     * リンクできるよう userId は返す。
     *
     * @param auth 認証情報
     * @return 参加者サマリの List（総合力降順）。未ログインは 401、権限なしは 403。
     */
    @GetMapping("/participants")
    public ResponseEntity<?> listParticipants(Authentication auth) {
        User viewer = resolveUser(auth);
        if (viewer == null) {
            return ResponseEntity.status(401).body(Map.of("error", "ログインが必要です"));
        }
        if (!canView(viewer)) {
            return ResponseEntity.status(403).body(Map.of("error", "このページの閲覧権限がありません"));
        }

        List<Map<String, Object>> result = participantRepository.findAllByOrderByCreatedAtAsc().stream()
                .map(this::toPublicMap)
                .sorted(Comparator.comparingDouble(
                        (Map<String, Object> m) -> ((Number) m.getOrDefault("totalBeatPt", 0.0)).doubleValue())
                        .reversed())
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

    /** 認証情報から User を解決する。未認証・不正 principal・ユーザー不在なら null。 */
    private User resolveUser(Authentication auth) {
        if (auth == null || !auth.isAuthenticated()) {
            return null;
        }
        Object principal = auth.getPrincipal();
        if (!(principal instanceof String iidxId)) {
            return null;
        }
        return userRepository.findByIidxId(iidxId).orElse(null);
    }

    /** 参加者一覧を閲覧できるか（ホワイトリスト or 管理者）。 */
    private boolean canView(User user) {
        return adminAuthService.isAdmin(user)
                || (user.getId() != null && viewerIds.contains(user.getId()));
    }

    /** 認証情報から管理者ユーザーを解決する。非管理者・未認証なら null。 */
    private User requireAdmin(Authentication auth) {
        User user = resolveUser(auth);
        if (user == null || !adminAuthService.isAdmin(user)) {
            return null;
        }
        return user;
    }

    /** カンマ区切りのユーザー ID 文字列を Long の Set に変換する（空・不正値は無視）。 */
    private static Set<Long> parseIds(String csv) {
        Set<Long> ids = new HashSet<>();
        if (csv == null || csv.isBlank()) {
            return ids;
        }
        Arrays.stream(csv.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .forEach(s -> {
                    try {
                        ids.add(Long.parseLong(s));
                    } catch (NumberFormatException ignored) {
                        // 不正な値はスキップ（設定ミスでも起動を妨げない）
                    }
                });
        return ids;
    }

    /** 参加者エントリを応答用 Map に変換する。実力データは紐づく User から都度読み出す。 */
    private Map<String, Object> toPublicMap(KinjoCupParticipant p) {
        User u = p.getUser();
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", p.getId());
        m.put("userId", u.getId());
        m.put("displayName", u.getDisplayName() != null ? u.getDisplayName() : "");
        m.put("danRank", u.getDanRank() != null ? u.getDanRank() : "");
        m.put("arenaRank", u.getArenaRank() != null ? u.getArenaRank() : "");
        m.put("totalBeatPt", u.getTotalBeatPt() != null ? u.getTotalBeatPt() : 0.0);
        m.put("lastUploadedAt", u.getLastUploadedAt());
        return m;
    }

    /** 参加者追加リクエストのボディ（追加対象ユーザーの ID）。 */
    public record AddParticipantRequest(Long userId) {
    }
}
