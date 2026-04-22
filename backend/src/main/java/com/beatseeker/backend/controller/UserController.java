package com.beatseeker.backend.controller;

import com.beatseeker.backend.entity.Score;
import com.beatseeker.backend.entity.ScoreHistoryLog;
import com.beatseeker.backend.entity.User;
import com.beatseeker.backend.repository.FriendRequestRepository;
import com.beatseeker.backend.repository.FriendshipRepository;
import com.beatseeker.backend.repository.ScoreHistoryLogRepository;
import com.beatseeker.backend.repository.ScoreRepository;
import com.beatseeker.backend.repository.UserRepository;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 【クラスの役割】 他ユーザーの「公開プロフィール／公開スコア／履歴」および
 * フレンド状態を返すコントローラ。
 *
 * 本アプリは {@code User.privacyLevel} で公開範囲を切り替えており、
 * ここで公開するのは {@code privacyLevel == 0} のユーザーのみ。
 * 非公開ユーザーに対するアクセスは 403 を返す。
 *
 * 主要エンドポイント:
 *  - {@code GET /api/users/{userId}/profile}       … 公開プロフィール取得
 *  - {@code GET /api/users/{userId}/scores}        … 公開スコア一覧
 *  - {@code GET /api/users/{userId}/history}       … 公開履歴スナップショット一覧
 *  - {@code GET /api/users/{userId}/friend-status} … フレンド関係ステータス
 */
@RestController
@RequestMapping("/api/users")
public class UserController {

    /** ユーザー本体の永続化 Repository。 */
    private final UserRepository userRepository;
    /** 公開スコア取得用 Repository。 */
    private final ScoreRepository scoreRepository;
    /** 公開履歴取得用 Repository。 */
    private final ScoreHistoryLogRepository scoreHistoryLogRepository;
    /** 成立済みフレンド関係の Repository。 */
    private final FriendshipRepository friendshipRepository;
    /** フレンド申請（未承認含む）の Repository。 */
    private final FriendRequestRepository friendRequestRepository;

    /**
     * 【コンストラクタ】 Spring が DI で各 Repository を注入する。
     */
    public UserController(UserRepository userRepository,
                          ScoreRepository scoreRepository,
                          ScoreHistoryLogRepository scoreHistoryLogRepository,
                          FriendshipRepository friendshipRepository,
                          FriendRequestRepository friendRequestRepository) {
        this.userRepository = userRepository;
        this.scoreRepository = scoreRepository;
        this.scoreHistoryLogRepository = scoreHistoryLogRepository;
        this.friendshipRepository = friendshipRepository;
        this.friendRequestRepository = friendRequestRepository;
    }

    /**
     * 【メソッドの役割】 指定ユーザーの公開プロフィールを返す。
     *
     * {@code privacyLevel != 0} のユーザー（非公開設定）は 403。
     * 存在しないユーザーは 404。
     *
     * @param userId 対象ユーザー ID
     * @return プロフィール情報の Map
     */
    @GetMapping("/{userId}/profile")
    public ResponseEntity<Map<String, Object>> getPublicProfile(@PathVariable Long userId) {
        User user = userRepository.findById(userId).orElse(null);
        if (user == null) return ResponseEntity.notFound().build();

        // null の場合は「非公開 (1)」と同等に扱う（安全側に倒す）
        Integer privacyLevel = user.getPrivacyLevel() != null ? user.getPrivacyLevel() : 1;
        if (privacyLevel != 0) {
            return ResponseEntity.status(403).build();
        }

        // 各フィールドに null 安全なフォールバックを入れながら整形する
        Map<String, Object> body = new HashMap<>();
        body.put("id", user.getId());
        body.put("displayName", user.getDisplayName() != null ? user.getDisplayName() : "");
        body.put("iidxId", user.getIidxId());
        body.put("danRank", user.getDanRank() != null ? user.getDanRank() : "");
        body.put("arenaRank", user.getArenaRank() != null ? user.getArenaRank() : "");
        body.put("playSide", user.getPlaySide() != null ? user.getPlaySide() : "1P");
        body.put("privacyLevel", privacyLevel);
        body.put("showRateTier", user.getShowRateTier() != null ? user.getShowRateTier() : true);
        body.put("isSupporter", user.getIsSupporter() != null ? user.getIsSupporter() : false);
        body.put("showSupporterBorder", user.getShowSupporterBorder() != null ? user.getShowSupporterBorder() : true);
        body.put("lastUploadedAt", user.getLastUploadedAt());
        body.put("totalBeatPt", user.getTotalBeatPt() != null ? user.getTotalBeatPt() : 0.0);
        return ResponseEntity.ok(body);
    }

    /**
     * 【メソッドの役割】 指定ユーザーの全スコアを返す（公開プロフィールの場合のみ）。
     *
     * 他人のスコアを全件公開する機能のため、必ず privacyLevel チェックを通す。
     *
     * @param userId 対象ユーザー ID
     * @return スコアの Map リスト（アップロード日時の昇順）
     */
    @GetMapping("/{userId}/scores")
    public ResponseEntity<List<Map<String, Object>>> getPublicScores(@PathVariable Long userId) {
        User user = userRepository.findById(userId).orElse(null);
        if (user == null) return ResponseEntity.notFound().build();

        Integer privacyLevel = user.getPrivacyLevel() != null ? user.getPrivacyLevel() : 1;
        if (privacyLevel != 0) {
            return ResponseEntity.status(403).build();
        }

        List<Score> scores = scoreRepository.findByUserOrderByUploadedAtAsc(user);
        // 各フィールドに null 安全なフォールバックを入れながら整形
        List<Map<String, Object>> result = scores.stream().map(s -> {
            Map<String, Object> map = new HashMap<>();
            map.put("id", s.getId());
            map.put("title", s.getTitle() != null ? s.getTitle() : "");
            map.put("difficultyName", s.getDifficultyName() != null ? s.getDifficultyName() : "");
            map.put("difficultyLevel", s.getDifficultyLevel() != null ? s.getDifficultyLevel() : 0);
            map.put("score", s.getScore() != null ? s.getScore() : 0);
            map.put("clearType", s.getClearType() != null ? s.getClearType() : "");
            map.put("djLevel", s.getDjLevel() != null ? s.getDjLevel() : "");
            map.put("pgreat", s.getPgreat() != null ? s.getPgreat() : 0);
            map.put("great", s.getGreat() != null ? s.getGreat() : 0);
            // missCount は null のままクライアントに渡す（未計測 vs 0 を区別したい）
            map.put("missCount", s.getMissCount());
            map.put("memo", s.getMemo() != null ? s.getMemo() : "");
            return map;
        }).toList();

        return ResponseEntity.ok(result);
    }

    /**
     * 【メソッドの役割】 指定ユーザーの履歴スナップショット（グラフ用）を返す。
     *
     * プライバシーチェックは profile/scores と同一。公開（privacyLevel==0）のみ許可。
     *
     * @param userId 対象ユーザー ID
     * @return 履歴ログの Map リスト（アップロード日時の昇順）
     */
    @GetMapping("/{userId}/history")
    public ResponseEntity<List<Map<String, Object>>> getPublicHistory(@PathVariable Long userId) {
        User user = userRepository.findById(userId).orElse(null);
        if (user == null) return ResponseEntity.notFound().build();

        Integer privacyLevel = user.getPrivacyLevel() != null ? user.getPrivacyLevel() : 1;
        if (privacyLevel != 0) {
            return ResponseEntity.status(403).build();
        }

        List<ScoreHistoryLog> logs = scoreHistoryLogRepository.findByUserOrderByUploadedAtAsc(user);
        List<Map<String, Object>> history = logs.stream().map(log -> {
            Map<String, Object> m = new HashMap<>();
            // 履歴グラフはフロントで snapshotId をキーに扱うため文字列化
            m.put("snapshotId", log.getId().toString());
            m.put("date", log.getUploadedAt().toString());
            m.put("totalScore", log.getTotalScore());
            m.put("fcCount", log.getFcCount());
            m.put("exhCount", log.getExhCount());
            m.put("hCount", log.getHCount());
            m.put("clearCount", log.getClearCount());
            m.put("easyCount", log.getEasyCount());
            m.put("aaaCount", log.getAaaCount());
            m.put("aaCount", log.getAaCount());
            m.put("aCount", log.getACount());
            m.put("totalBeatPt", log.getTotalBeatPt());
            m.put("beatPtIncrease", log.getBeatPtIncrease());
            m.put("updatedCount", log.getUpdatedCount());
            m.put("diffJson", log.getDiffJson());
            m.put("totalRatePt", log.getTotalRatePt());
            return m;
        }).toList();

        return ResponseEntity.ok(history);
    }

    /**
     * 【メソッドの役割】 認証ユーザーと対象ユーザー間のフレンド関係ステータスを返す。
     *
     * ダッシュボードがフレンド申請バナーを出すかどうかを判定するために呼び出す。
     *
     * 返しうるステータス:
     *   - "self"     … 同一ユーザー
     *   - "friend"   … 既にフレンド成立
     *   - "requested"… 自分が相手に送った PENDING 申請あり
     *   - "incoming" … 相手から自分への PENDING 申請あり
     *   - "none"     … 関係なし
     *
     * 未ログイン時は常に "none" として扱う（クライアントで分岐しやすくするため）。
     *
     * @param auth   認証情報（未ログインでもエラーにしない）
     * @param userId 対象ユーザー ID
     */
    @GetMapping("/{userId}/friend-status")
    public ResponseEntity<Map<String, String>> getFriendStatus(Authentication auth, @PathVariable Long userId) {
        if (auth == null || !auth.isAuthenticated()) {
            return ResponseEntity.ok(Map.of("status", "none"));
        }
        String iidxId = (String) auth.getPrincipal();
        User currentUser = userRepository.findByIidxId(iidxId).orElse(null);
        if (currentUser == null) return ResponseEntity.ok(Map.of("status", "none"));

        User target = userRepository.findById(userId).orElse(null);
        if (target == null) return ResponseEntity.notFound().build();

        // 自分自身のプロフィールを見ているケース
        if (currentUser.getId().equals(target.getId())) {
            return ResponseEntity.ok(Map.of("status", "self"));
        }
        // 既にフレンド成立済み
        if (friendshipRepository.findByUserAndFriend(currentUser, target).isPresent()) {
            return ResponseEntity.ok(Map.of("status", "friend"));
        }
        // 自分 → 相手の申請が PENDING
        if (friendRequestRepository.findBySenderAndReceiverAndStatus(currentUser, target, "PENDING").isPresent()) {
            return ResponseEntity.ok(Map.of("status", "requested"));
        }
        // 相手 → 自分の申請が PENDING（承認／却下の UI を出す）
        if (friendRequestRepository.findBySenderAndReceiverAndStatus(target, currentUser, "PENDING").isPresent()) {
            return ResponseEntity.ok(Map.of("status", "incoming"));
        }
        return ResponseEntity.ok(Map.of("status", "none"));
    }
}
