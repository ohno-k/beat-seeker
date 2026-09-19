package com.beatseeker.backend.service;

import com.beatseeker.backend.entity.DifficultyRank;
import com.beatseeker.backend.entity.DifficultyRevision;
import com.beatseeker.backend.repository.DifficultyRevisionRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.time.YearMonth;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 【Service の役割】 難易度表の改訂履歴（更新履歴ページの「第N版」）を記録・配信する。
 *
 * 責務:
 *  - 「難易度表を適用」時に active と draft の差分を {@link DifficultyRevision} として自動追記する
 *  - 更新履歴ページ向けに、フロントの {@code difficulty_revisions.json} と同じ形の一覧を返す
 *  - 起動時に手書き JSON（第1〜4版）をシードする（テーブルが空のときだけ）
 *
 * 差分の判定そのものは {@link DifficultyTableDiff}（純粋関数）に切り出してある。
 */
@Service
public class DifficultyRevisionService {

    private static final Logger log = LoggerFactory.getLogger(DifficultyRevisionService.class);

    /** 適用日時は JST で記録する（Render のサーバ TZ は UTC）。 */
    private static final ZoneId JST = ZoneId.of("Asia/Tokyo");

    /** 更新履歴ページが受け取る日付表記（"YYYY-MM"）。 */
    private static final DateTimeFormatter YEAR_MONTH = DateTimeFormatter.ofPattern("yyyy-MM");

    private final DifficultyRevisionRepository repo;
    private final ObjectMapper objectMapper;

    public DifficultyRevisionService(DifficultyRevisionRepository repo, ObjectMapper objectMapper) {
        this.repo = repo;
        this.objectMapper = objectMapper;
    }

    /**
     * 【メソッドの役割】 適用前（active）と適用後（draft）の差分を新しい版として記録する。
     *
     * 差分が無ければ何も記録せず空を返す（同じ表を適用し直しただけの操作で版が増えないように）。
     * 版数は「記録済みの最大版数 + 1」。呼び出し側のトランザクションに参加するので、
     * 適用が失敗すればこの記録も巻き戻る。
     *
     * @param before 適用前の帯一覧（active）
     * @param after  適用後の帯一覧（draft）
     * @return 記録した版。差分が無ければ空
     * @throws Exception 明細の JSON 化に失敗した場合
     */
    @Transactional
    public Optional<DifficultyRevision> record(List<DifficultyRank> before, List<DifficultyRank> after) throws Exception {
        DifficultyTableDiff.Result diff = DifficultyTableDiff.compute(before, after);
        if (diff.isEmpty()) {
            log.info("[難易度改訂] 公開中の表との差分が無いため更新履歴には記録しない");
            return Optional.empty();
        }

        int edition = repo.findTopByOrderByEditionDesc().map(r -> r.getEdition() + 1).orElse(1);
        DifficultyRevision rev = new DifficultyRevision();
        rev.setEdition(edition);
        rev.setAppliedAt(LocalDateTime.now(JST));
        rev.setAddedCount(diff.added().size());
        rev.setChangedCount(diff.changed().size());
        rev.setRemovedCount(diff.removed().size());
        rev.setAddedJson(objectMapper.writeValueAsString(diff.added()));
        rev.setChangedJson(objectMapper.writeValueAsString(diff.changed()));
        rev.setRemovedJson(objectMapper.writeValueAsString(diff.removed()));

        DifficultyRevision saved = repo.save(rev);
        log.info("[難易度改訂] 第{}版を記録: 新規追加 {} / 既存変更 {} / 表から除外 {}",
                edition, diff.added().size(), diff.changed().size(), diff.removed().size());
        return Optional.of(saved);
    }

    /**
     * 【メソッドの役割】 更新履歴ページ向けの一覧を版数の昇順で返す。
     *
     * 形はフロント同梱の {@code difficulty_revisions.json} と互換
     * （{@code version/label/appVersion/date/added/changed}）で、{@code removed} と {@code appliedAt} を足してある。
     */
    @Transactional(readOnly = true)
    public List<Map<String, Object>> listForPublic() {
        List<Map<String, Object>> out = new ArrayList<>();
        for (DifficultyRevision rev : repo.findAllByOrderByEditionAsc()) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("version", rev.getEdition());
            m.put("label", "v" + rev.getEdition());
            m.put("appVersion", rev.getAppVersion());
            m.put("date", rev.getAppliedAt().format(YEAR_MONTH));
            // appliedAt は LocalDateTime.now(JST) で記録した「JST の壁時計」。+09:00 を付けて返す。
            m.put("appliedAt", com.beatseeker.backend.util.JstTime.fromJst(rev.getAppliedAt()));
            m.put("added", readArray(rev.getAddedJson()));
            m.put("changed", readArray(rev.getChangedJson()));
            m.put("removed", readArray(rev.getRemovedJson()));
            out.add(m);
        }
        return out;
    }

    /**
     * 【メソッドの役割】 手書きの改訂履歴 JSON（フロントの {@code difficulty_revisions.json} と同形）を
     * シード投入する。既に 1 件でも記録があれば何もしない（冪等）。
     *
     * @param json {@code [{version, appVersion, date:"YYYY-MM", added:[…], changed:[…]}, …]}
     * @throws Exception JSON パース失敗時
     */
    @Transactional
    public void seed(String json) throws Exception {
        if (repo.count() > 0) return;

        JsonNode root = objectMapper.readTree(json);
        if (!root.isArray()) return;

        List<DifficultyRevision> batch = new ArrayList<>();
        for (JsonNode n : root) {
            int edition = n.path("version").asInt(0);
            if (edition <= 0) continue;

            DifficultyRevision rev = new DifficultyRevision();
            rev.setEdition(edition);
            rev.setAppVersion(n.hasNonNull("appVersion") ? n.get("appVersion").asText() : null);
            rev.setAppliedAt(parseMonth(n.path("date").asText("")));

            JsonNode added = n.path("added");
            JsonNode changed = n.path("changed");
            JsonNode removed = n.path("removed");
            rev.setAddedCount(added.isArray() ? added.size() : 0);
            rev.setChangedCount(changed.isArray() ? changed.size() : 0);
            rev.setRemovedCount(removed.isArray() ? removed.size() : 0);
            rev.setAddedJson(added.isArray() ? added.toString() : "[]");
            rev.setChangedJson(changed.isArray() ? changed.toString() : "[]");
            rev.setRemovedJson(removed.isArray() ? removed.toString() : "[]");
            batch.add(rev);
        }
        repo.saveAll(batch);
        log.info("[難易度改訂] 改訂履歴を {} 版分シードした", batch.size());
    }

    /** 保存した明細 JSON を配列ノードに戻す。壊れていれば空配列。 */
    private JsonNode readArray(String json) {
        try {
            JsonNode n = objectMapper.readTree(json == null || json.isBlank() ? "[]" : json);
            return n.isArray() ? n : objectMapper.createArrayNode();
        } catch (Exception e) {
            return objectMapper.createArrayNode();
        }
    }

    /** "YYYY-MM" をその月の 1 日 0:00 にする。形式が違えば現在時刻（JST）。 */
    private static LocalDateTime parseMonth(String yearMonth) {
        try {
            return YearMonth.parse(yearMonth).atDay(1).atStartOfDay();
        } catch (Exception e) {
            return LocalDateTime.now(JST);
        }
    }
}
