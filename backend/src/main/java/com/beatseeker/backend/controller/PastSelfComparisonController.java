package com.beatseeker.backend.controller;

import com.beatseeker.backend.entity.PastScore;
import com.beatseeker.backend.entity.ScoreHistoryLog;
import com.beatseeker.backend.entity.User;
import com.beatseeker.backend.repository.PastScoreRepository;
import com.beatseeker.backend.repository.ScoreHistoryLogRepository;
import com.beatseeker.backend.repository.UserRepository;
import com.beatseeker.backend.service.AdminAuthService;
import com.beatseeker.backend.service.IidxVersions;
import com.beatseeker.backend.service.SongTitleAliases;
import com.beatseeker.backend.util.JstTime;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 【クラスの役割】 「前作の自分」と今作の自分の勝敗比較に使う、前作側のスコアを返す API（管理者専用）。
 *
 * 現実世界の概念: 新作稼働でスコアはリセットされるので、今作の自分が前作の自分に
 * どれだけ追いついたかを譜面ごとの EX スコアで比べたい。今作側はフロントが持っている
 * 自分のスコアをそのまま使い、ここでは前作側だけを返す。
 *
 * 前作側は 2 通りで取れる:
 *  - 最終: {@code past_scores}（世代切り替えで複製した前作の最終スコア + 後から取り込んだ前作 CSV）
 *  - CSV 読み込み日指定: その作品の成長記録（{@code score_history_logs}）の 1 行を指定し、
 *    その取り込みを終えた時点のスコアを diffJson から組み立て直す（{@link #rebuildAsOf}）
 *
 * 検証段階のため管理者だけが使える。ルーティングが {@code /api/scores/**} 配下なので
 * ログイン必須は SecurityConfig のキャッチオールで掛かり、管理者判定はここで行う。
 * 本人のデータしか返さず、既存の集計には一切書き込まない。
 *
 * エンドポイント:
 *  - GET /api/scores/past/self-comparison/import-dates?version= … 指定作品の CSV 読み込み日の一覧
 *  - GET /api/scores/past/self-comparison?version=&asOf=         … 前作側のスコア（asOf 省略で最終）
 */
@RestController
@RequestMapping("/api/scores/past/self-comparison")
public class PastSelfComparisonController {

    private final PastScoreRepository pastScoreRepository;
    private final ScoreHistoryLogRepository scoreHistoryLogRepository;
    private final UserRepository userRepository;
    private final AdminAuthService adminAuthService;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public PastSelfComparisonController(PastScoreRepository pastScoreRepository,
                                        ScoreHistoryLogRepository scoreHistoryLogRepository,
                                        UserRepository userRepository,
                                        AdminAuthService adminAuthService) {
        this.pastScoreRepository = pastScoreRepository;
        this.scoreHistoryLogRepository = scoreHistoryLogRepository;
        this.userRepository = userRepository;
        this.adminAuthService = adminAuthService;
    }

    /**
     * 【メソッドの役割】 指定作品の CSV 読み込み日（成長記録の行）を新しい順に返す。
     *
     * 世代切り替えの 0PT 行（tag 付き）と、更新の無かった取り込みは選んでも意味が無いので除く。
     *
     * @param version 作品バージョン（過去作のみ）
     * @return [{ id, uploadedAt, updatedCount }] の配列
     */
    @GetMapping("/import-dates")
    @Transactional(readOnly = true)
    public ResponseEntity<?> getImportDates(Authentication auth, @RequestParam Integer version) {
        User user = getUser(auth);
        if (!adminAuthService.isAdmin(user)) return ResponseEntity.status(403).build();
        if (!IidxVersions.isSupportedPast(version)) return badRequest("対応していないバージョンです");

        List<Map<String, Object>> result = new ArrayList<>();
        for (ScoreHistoryLog log : importLogs(user, version)) {
            if (!hasDiff(log)) continue;
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", log.getId());
            m.put("uploadedAt", JstTime.toIsoString(log.getUploadedAt()));
            m.put("updatedCount", log.getUpdatedCount());
            result.add(m);
        }
        java.util.Collections.reverse(result);
        return ResponseEntity.ok(result);
    }

    /**
     * 【メソッドの役割】 前作側のスコアを譜面単位のフラットな配列で返す。
     *
     * @param version 作品バージョン（過去作のみ）
     * @param asOf    CSV 読み込み日として選んだ成長記録の ID。省略時は最終スコア（past_scores）
     * @return { version, asOf, asOfUploadedAt, records: [...] }
     */
    @GetMapping
    @Transactional(readOnly = true)
    public ResponseEntity<?> getPastSelfScores(Authentication auth,
                                               @RequestParam Integer version,
                                               @RequestParam(required = false) Long asOf) {
        User user = getUser(auth);
        if (!adminAuthService.isAdmin(user)) return ResponseEntity.status(403).build();
        if (!IidxVersions.isSupportedPast(version)) return badRequest("対応していないバージョンです");

        // 最終スコアを (曲名|難易度名) で引けるようにしておく。CSV 読み込み日指定でも土台に使う。
        Map<String, Map<String, Object>> finalRecords = new LinkedHashMap<>();
        for (PastScore p : pastScoreRepository.findByUserAndVersion(user, version)) {
            finalRecords.put(chartKey(p.getTitle(), p.getDifficultyName()), toRecord(p));
        }

        Map<String, Object> body = new HashMap<>();
        body.put("version", version);
        body.put("asOf", asOf);

        if (asOf == null) {
            body.put("asOfUploadedAt", null);
            body.put("records", new ArrayList<>(finalRecords.values()));
            return ResponseEntity.ok(body);
        }

        List<ScoreHistoryLog> logs = importLogs(user, version);
        int asOfIndex = -1;
        for (int i = 0; i < logs.size(); i++) {
            if (logs.get(i).getId().equals(asOf)) {
                asOfIndex = i;
                break;
            }
        }
        if (asOfIndex < 0) return badRequest("指定した CSV 読み込み日が見つかりません");

        body.put("asOfUploadedAt", JstTime.toIsoString(logs.get(asOfIndex).getUploadedAt()));
        body.put("records", rebuildAsOf(finalRecords, logs, asOfIndex));
        return ResponseEntity.ok(body);
    }

    /**
     * 【メソッドの役割】 成長記録の asOfIndex 番目の取り込みを終えた時点のスコアを組み立て直す。
     *
     * 譜面ごとに次の優先順で値を決める:
     *  1. asOf 以前に更新があれば、その最後の更新の newScore / newClearType
     *  2. asOf 以前に更新が無く、後で更新があれば、その最初の更新の oldScore / oldClearType
     *     （＝ asOf の時点ではまだその値だった）
     *  3. どちらも無ければ最終スコアのまま（その作品の間ずっと変わっていない）
     *
     * 1 を 2 より優先するのは、成長記録に載らない後からの前作 CSV 取り込み（past_scores だけが伸びる）を
     * asOf の時点に混ぜないため。値が最終スコアと変わった譜面は DJ LEVEL / BP / 判定数が分からないので null にする。
     *
     * @param finalRecords 最終スコア（キー → レコード）
     * @param logs         その作品の成長記録（古い順）
     * @param asOfIndex    基準にする成長記録の位置
     * @return 譜面単位のレコード配列（その時点で未プレーの譜面は含まない）
     */
    private List<Map<String, Object>> rebuildAsOf(Map<String, Map<String, Object>> finalRecords,
                                                  List<ScoreHistoryLog> logs, int asOfIndex) {
        // キー → [score, clearType]。asOf 以前は上書き（最後が勝つ）、以後は最初の 1 件だけを残す。
        Map<String, Object[]> before = new HashMap<>();
        Map<String, Object[]> after = new HashMap<>();
        for (int i = 0; i < logs.size(); i++) {
            boolean isBefore = i <= asOfIndex;
            for (JsonNode d : parseDiffs(logs.get(i))) {
                String title = d.path("title").asText(null);
                String diff = d.hasNonNull("difficulty") ? d.get("difficulty").asText()
                        : d.path("difficultyName").asText(null);
                if (title == null || diff == null) continue;
                // past_scores はアーケードの記録だけなので、INFINITAS の更新は混ぜない。
                String source = d.path("source").asText(null);
                if (source != null && !"arcade".equals(source)) continue;
                String key = chartKey(SongTitleAliases.canonical(title), diff);
                if (isBefore) {
                    before.put(key, new Object[] {
                            d.path("newScore").asInt(0), d.path("newClearType").asText("NO PLAY") });
                } else {
                    after.putIfAbsent(key, new Object[] {
                            d.path("oldScore").asInt(0), d.path("oldClearType").asText("NO PLAY") });
                }
            }
        }

        // 最終スコアに無い譜面（成長記録にだけ現れる）も拾えるようにキーを合わせる。
        Map<String, Map<String, Object>> merged = new LinkedHashMap<>(finalRecords);
        for (String key : before.keySet()) merged.putIfAbsent(key, null);
        for (String key : after.keySet()) merged.putIfAbsent(key, null);

        List<Map<String, Object>> result = new ArrayList<>();
        for (Map.Entry<String, Map<String, Object>> e : merged.entrySet()) {
            String key = e.getKey();
            Map<String, Object> fin = e.getValue();
            Object[] v = before.containsKey(key) ? before.get(key) : after.get(key);

            Map<String, Object> rec;
            if (v == null) {
                rec = fin;
            } else {
                int score = (Integer) v[0];
                String clearType = (String) v[1];
                boolean sameAsFinal = fin != null
                        && Integer.valueOf(score).equals(fin.get("score"))
                        && clearType.equals(fin.get("clearType"));
                if (sameAsFinal) {
                    rec = fin;
                } else {
                    String[] parts = key.split("\\|\\|", 2);
                    rec = new HashMap<>();
                    rec.put("title", parts[0]);
                    rec.put("difficultyName", parts.length > 1 ? parts[1] : "");
                    rec.put("difficultyLevel", fin != null ? fin.get("difficultyLevel") : null);
                    rec.put("score", score);
                    rec.put("clearType", clearType);
                    rec.put("djLevel", null);
                    rec.put("missCount", null);
                    rec.put("pgreat", null);
                    rec.put("great", null);
                }
            }
            if (rec == null) continue;
            Object score = rec.get("score");
            boolean played = (score instanceof Integer s && s > 0)
                    || !"NO PLAY".equals(rec.get("clearType"));
            if (played) result.add(rec);
        }
        return result;
    }

    /** その作品の CSV 取り込みで出来た成長記録（古い順）。世代切り替えの 0PT 行は除く。 */
    private List<ScoreHistoryLog> importLogs(User user, int version) {
        List<ScoreHistoryLog> logs = scoreHistoryLogRepository.findByUserAndVersionOrderByUploadedAtAsc(
                user, version, IidxVersions.PREVIOUS);
        logs.removeIf(l -> l.getTag() != null);
        return logs;
    }

    /** diffJson に更新曲が 1 件以上あるか。 */
    private boolean hasDiff(ScoreHistoryLog log) {
        String json = log.getDiffJson();
        return json != null && !json.isBlank() && !"[]".equals(json.trim());
    }

    /** diffJson を配列として読む。壊れていたら空（他の取り込みに影響させない）。 */
    private List<JsonNode> parseDiffs(ScoreHistoryLog log) {
        if (!hasDiff(log)) return List.of();
        try {
            JsonNode root = objectMapper.readTree(log.getDiffJson());
            if (root == null || !root.isArray()) return List.of();
            List<JsonNode> out = new ArrayList<>();
            root.forEach(out::add);
            return out;
        } catch (Exception e) {
            return List.of();
        }
    }

    /** past_scores の 1 行を返却用のレコードにする。 */
    private Map<String, Object> toRecord(PastScore p) {
        Map<String, Object> m = new HashMap<>();
        m.put("title", p.getTitle());
        m.put("difficultyName", p.getDifficultyName());
        m.put("difficultyLevel", p.getDifficultyLevel());
        m.put("score", p.getScore() != null ? p.getScore() : 0);
        m.put("clearType", p.getClearType() != null ? p.getClearType() : "NO PLAY");
        m.put("djLevel", p.getDjLevel());
        m.put("missCount", p.getMissCount());
        m.put("pgreat", p.getPgreat());
        m.put("great", p.getGreat());
        return m;
    }

    /** (曲名|難易度名) の lookup キー。PastScoreController と同じ形。 */
    private String chartKey(String title, String difficultyName) {
        return (title == null ? "" : title) + "||" + (difficultyName == null ? "" : difficultyName);
    }

    private ResponseEntity<Map<String, Object>> badRequest(String message) {
        Map<String, Object> body = new HashMap<>();
        body.put("message", message);
        return ResponseEntity.badRequest().body(body);
    }

    private User getUser(Authentication auth) {
        if (auth == null || !auth.isAuthenticated()) {
            throw new RuntimeException("Not authenticated");
        }
        String iidxId = (String) auth.getPrincipal();
        return userRepository.findByIidxId(iidxId)
                .orElseThrow(() -> new RuntimeException("User not found"));
    }
}
