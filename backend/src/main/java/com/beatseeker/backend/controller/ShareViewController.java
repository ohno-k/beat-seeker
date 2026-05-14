package com.beatseeker.backend.controller;

import com.beatseeker.backend.entity.Score;
import com.beatseeker.backend.entity.ScoreHistoryLog;
import com.beatseeker.backend.entity.ShareToken;
import com.beatseeker.backend.entity.User;
import com.beatseeker.backend.repository.ScoreHistoryLogRepository;
import com.beatseeker.backend.repository.ScoreRepository;
import com.beatseeker.backend.repository.ShareTokenRepository;
import com.beatseeker.backend.repository.UserSongOptionRepository;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 【クラスの役割】 共有トークン経由でユーザーデータを公開する未認証コントローラ。
 *
 * いずれのエンドポイントもログイン不要。トークンの「失効済み」「期限切れ」
 * は 410 Gone で返す（404 と区別することで、フロントから「リンク切れ」UI を出しやすくする）。
 *
 * 主要エンドポイント:
 *  - {@code GET /api/share/{token}/info}      … 公開対象ユーザー情報 + scope フラグ
 *  - {@code GET /api/share/{token}/scores}    … スコア一覧（dashboard / scores いずれかが ON のとき）
 */
@RestController
@RequestMapping("/api/share")
public class ShareViewController {

    private final ShareTokenRepository shareTokenRepository;
    private final ScoreRepository scoreRepository;
    private final ScoreHistoryLogRepository scoreHistoryLogRepository;
    private final UserSongOptionRepository userSongOptionRepository;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public ShareViewController(ShareTokenRepository shareTokenRepository,
                               ScoreRepository scoreRepository,
                               ScoreHistoryLogRepository scoreHistoryLogRepository,
                               UserSongOptionRepository userSongOptionRepository) {
        this.shareTokenRepository = shareTokenRepository;
        this.scoreRepository = scoreRepository;
        this.scoreHistoryLogRepository = scoreHistoryLogRepository;
        this.userSongOptionRepository = userSongOptionRepository;
    }

    /**
     * 【メソッドの役割】 トークン情報と公開対象ユーザーの基本情報を返す。
     *
     * @return 200: 有効。404: 存在しない。410: 失効済み or 期限切れ。
     */
    @GetMapping("/{token}/info")
    public ResponseEntity<?> getInfo(@PathVariable String token) {
        ShareToken st = shareTokenRepository.findByToken(token).orElse(null);
        if (st == null) return ResponseEntity.notFound().build();
        if (!isActive(st)) return expired(st);

        User u = st.getUser();
        Map<String, Object> body = new HashMap<>();
        body.put("scopeDashboard", st.getScopeDashboard());
        body.put("scopeScores", st.getScopeScores());
        body.put("scopeHistory", st.getScopeHistory());
        body.put("scopeProfile", st.getScopeProfile());
        body.put("expiresAt", st.getExpiresAt());
        body.put("createdAt", st.getCreatedAt());

        Map<String, Object> user = new HashMap<>();
        user.put("id", u.getId());
        user.put("displayName", u.getDisplayName() != null ? u.getDisplayName() : "");
        user.put("iidxId", u.getIidxId());
        user.put("danRank", u.getDanRank() != null ? u.getDanRank() : "");
        user.put("arenaRank", u.getArenaRank() != null ? u.getArenaRank() : "");
        user.put("playSide", u.getPlaySide() != null ? u.getPlaySide() : "1P");
        user.put("isSupporter", u.getIsSupporter() != null ? u.getIsSupporter() : false);
        user.put("showSupporterBorder", u.getShowSupporterBorder() != null ? u.getShowSupporterBorder() : true);
        user.put("showRateTier", u.getShowRateTier() != null ? u.getShowRateTier() : true);
        user.put("totalBeatPt", u.getTotalBeatPt() != null ? u.getTotalBeatPt() : 0.0);
        body.put("user", user);

        return ResponseEntity.ok(body);
    }

    /**
     * 【メソッドの役割】 公開対象ユーザーの全スコアを返す。
     *
     * dashboard / scores / profile のいずれかが ON ならアクセス可能
     * （いずれの画面もスコアデータを描画に使うため）。history のみ ON は弾く。
     *
     * @return 200: 有効。404: 存在しない。410: 失効済み / 期限切れ。403: 該当 scope なし。
     */
    @GetMapping("/{token}/scores")
    public ResponseEntity<?> getScores(@PathVariable String token) {
        ShareToken st = shareTokenRepository.findByToken(token).orElse(null);
        if (st == null) return ResponseEntity.notFound().build();
        if (!isActive(st)) return expired(st);

        boolean ok = Boolean.TRUE.equals(st.getScopeDashboard())
                || Boolean.TRUE.equals(st.getScopeScores())
                || Boolean.TRUE.equals(st.getScopeProfile());
        if (!ok) {
            return ResponseEntity.status(403).build();
        }

        User shareUser = st.getUser();
        List<Score> scores = scoreRepository.findByUserOrderByUploadedAtAsc(shareUser);
        Map<String, List<String>> optionsMap = loadOptionsMap(shareUser);
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
            map.put("missCount", s.getMissCount());
            map.put("options", optionsMap.getOrDefault(optionsKey(s.getTitle(), s.getDifficultyName()), List.of()));
            return map;
        }).toList();
        return ResponseEntity.ok(result);
    }

    /** ユーザーの UserSongOption を一括取得し (title||difficulty) キーの options マップに変換。 */
    private Map<String, List<String>> loadOptionsMap(User user) {
        Map<String, List<String>> result = new HashMap<>();
        for (com.beatseeker.backend.entity.UserSongOption o : userSongOptionRepository.findByUser(user)) {
            List<String> parsed;
            try {
                parsed = objectMapper.readValue(
                        o.getOptionsJson() == null ? "[]" : o.getOptionsJson(),
                        new TypeReference<List<String>>() {});
            } catch (Exception e) {
                parsed = List.of();
            }
            result.put(optionsKey(o.getTitle(), o.getDifficultyName()), parsed);
        }
        return result;
    }

    /** loadOptionsMap と整合する複合キー。 */
    private String optionsKey(String title, String difficultyName) {
        return (title == null ? "" : title) + "||" + (difficultyName == null ? "" : difficultyName);
    }

    /**
     * 【メソッドの役割】 公開対象ユーザーの履歴スナップショット（成長記録）を返す。
     *
     * history / profile のいずれかが ON ならアクセス可能（プロフィール画面の成長軌跡にも履歴が必要）。
     *
     * @return 200: 有効。404: 存在しない。410: 失効済み / 期限切れ。403: 該当 scope なし。
     */
    @GetMapping("/{token}/history")
    public ResponseEntity<?> getHistory(@PathVariable String token) {
        ShareToken st = shareTokenRepository.findByToken(token).orElse(null);
        if (st == null) return ResponseEntity.notFound().build();
        if (!isActive(st)) return expired(st);

        boolean ok = Boolean.TRUE.equals(st.getScopeHistory())
                || Boolean.TRUE.equals(st.getScopeProfile());
        if (!ok) {
            return ResponseEntity.status(403).build();
        }

        List<ScoreHistoryLog> logs = scoreHistoryLogRepository.findByUserOrderByUploadedAtAsc(st.getUser());
        List<Map<String, Object>> result = logs.stream().map(log -> {
            Map<String, Object> m = new HashMap<>();
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
        return ResponseEntity.ok(result);
    }

    private boolean isActive(ShareToken t) {
        if (t.getRevokedAt() != null) return false;
        if (t.getExpiresAt() != null && t.getExpiresAt().isBefore(LocalDateTime.now())) return false;
        return true;
    }

    /** 失効・期限切れ用のレスポンス（410 Gone）。 */
    private ResponseEntity<Map<String, Object>> expired(ShareToken t) {
        Map<String, Object> body = new HashMap<>();
        body.put("error", t.getRevokedAt() != null ? "revoked" : "expired");
        body.put("revokedAt", t.getRevokedAt());
        body.put("expiresAt", t.getExpiresAt());
        return ResponseEntity.status(410).body(body);
    }
}
