package com.beatseeker.backend.controller;

import com.beatseeker.backend.entity.ActivityLog;
import com.beatseeker.backend.repository.ActivityLogRepository;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 【クラスの役割】 全体アクティビティフィード（サイト横断ニュースフィード）を返すコントローラ。
 *
 * ダッシュボードのサイドバー等で「最近のプレイヤーの昇格」「段位更新」などを
 * タイムライン表示するために使う。認証なしでも閲覧可能な公開 API。
 *
 * 主要エンドポイント:
 *  - {@code GET /api/activity/feed} … 全ユーザーの最新アクティビティ 20 件を取得
 */
@RestController
@RequestMapping("/api/activity")
public class ActivityController {

    /** アクティビティログの永続化を担う Repository。 */
    private final ActivityLogRepository activityLogRepository;

    /**
     * 【コンストラクタ】 Spring が DI で Repository を注入する。
     *
     * @param activityLogRepository ActivityLog 用の JPA Repository
     */
    public ActivityController(ActivityLogRepository activityLogRepository) {
        this.activityLogRepository = activityLogRepository;
    }

    /** 日本時間のタイムゾーン。レスポンス日時を JST に揃えるために使う。 */
    private static final ZoneId JST = ZoneId.of("Asia/Tokyo");
    /** ISO 8601（オフセット付き）フォーマッタ。フロントが Date() でパースしやすい形式。 */
    private static final DateTimeFormatter ISO_JST = DateTimeFormatter.ISO_OFFSET_DATE_TIME;

    /**
     * 【メソッドの役割】 最新 20 件のアクティビティを返す（全体ニュースフィード用）。
     *
     * DB の {@code ActivityLog} から作成日時の新しい順に 20 件取ってきて、
     * フロントが扱いやすい Map 形式に整形して返す。
     * 日時は JST に変換し、ISO オフセット形式（例: 2026-04-23T15:00:00+09:00）で返す。
     *
     * @return 各アクティビティを表す Map のリスト。キーは id/type/displayName/oldValue/newValue/createdAt
     */
    @GetMapping("/feed")
    public ResponseEntity<List<Map<String, Object>>> getFeed() {
        // ページサイズ 20 のみ取得。内部的には Repository 側で createdAt 降順にソートされる。
        List<ActivityLog> logs = activityLogRepository.findRecentActivity(PageRequest.of(0, 20));

        List<Map<String, Object>> result = logs.stream().map(a -> Map.<String, Object>of(
                "id", a.getId(),
                "type", a.getType(),
                // displayName が未設定のユーザーもあり得るのでフォールバック文字列をセット
                "displayName", a.getUser().getDisplayName() != null ? a.getUser().getDisplayName() : "プレイヤー",
                // null はそのまま返すと Map.of で NPE になるため空文字にフォールバック
                "oldValue", a.getOldValue() != null ? a.getOldValue() : "",
                "newValue", a.getNewValue() != null ? a.getNewValue() : "",
                // DB の createdAt は LocalDateTime（システム TZ 前提）→ JST へ変換して ISO 文字列化
                "createdAt", a.getCreatedAt().atZone(ZoneId.systemDefault()).withZoneSameInstant(JST).format(ISO_JST)
        )).collect(Collectors.toList());

        return ResponseEntity.ok(result);
    }
}
