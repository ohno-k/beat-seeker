package com.beatseeker.backend.controller;

import com.beatseeker.backend.entity.Friendship;
import com.beatseeker.backend.entity.TimelineEvent;
import com.beatseeker.backend.entity.User;
import com.beatseeker.backend.repository.FriendshipRepository;
import com.beatseeker.backend.repository.ScoreHistoryLogRepository;
import com.beatseeker.backend.repository.TimelineEventRepository;
import com.beatseeker.backend.repository.UserRepository;
import com.beatseeker.backend.service.AdminAuthService;
import com.beatseeker.backend.service.TimelineBackfillService;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 【クラスの役割】 フレンド画面のタイムラインタブ専用フィードを返すコントローラ。
 *
 * 公開エンドポイント:
 *  - {@code GET /api/timeline} : ログインユーザー + そのフレンドの最近のタイムラインイベントを
 *    新しい順に返す。SCORE_UPDATE / OVERTAKE_SONG / OVERTAKE_TOTAL の 3 種別が混在する。
 *
 * 認証必須。フレンド関係は {@link FriendshipRepository#findByUser(User)} で取得する。
 */
@RestController
@RequestMapping("/api/timeline")
public class TimelineController {

    /** タイムラインイベントの読み出しを担うリポジトリ。 */
    private final TimelineEventRepository timelineEventRepository;
    /** フレンド関係の取得（自分が「user」側の Friendship を引いて friend を集める）。 */
    private final FriendshipRepository friendshipRepository;
    /** 認証情報からユーザーを解決するためのリポジトリ。 */
    private final UserRepository userRepository;
    /** ScoreHistoryLog 件数を診断情報として返すために使う。 */
    private final ScoreHistoryLogRepository scoreHistoryLogRepository;
    /** バックフィル実行を委譲するサービス。 */
    private final TimelineBackfillService timelineBackfillService;
    /** 「全ユーザ」バックフィルを管理者だけに開放するための判定。 */
    private final AdminAuthService adminAuthService;
    /** payload (JSON 文字列) を Map にパースするための Jackson。 */
    private final ObjectMapper objectMapper = new ObjectMapper();

    /** 日本時間。レスポンスの createdAt は JST で返す。 */
    private static final ZoneId JST = ZoneId.of("Asia/Tokyo");
    /** ISO 8601 オフセット形式。フロントの new Date() でそのままパース可能。 */
    private static final DateTimeFormatter ISO_JST = DateTimeFormatter.ISO_OFFSET_DATE_TIME;

    /** デフォルトの取得件数。フロントが limit を渡さない場合に使う上限。 */
    private static final int DEFAULT_LIMIT = 50;
    /** 1 回の取得で許容する最大件数。クライアントが大きな値を送ってきても上限でクランプする。 */
    private static final int MAX_LIMIT = 200;

    public TimelineController(TimelineEventRepository timelineEventRepository,
                              FriendshipRepository friendshipRepository,
                              UserRepository userRepository,
                              ScoreHistoryLogRepository scoreHistoryLogRepository,
                              TimelineBackfillService timelineBackfillService,
                              AdminAuthService adminAuthService) {
        this.timelineEventRepository = timelineEventRepository;
        this.friendshipRepository = friendshipRepository;
        this.userRepository = userRepository;
        this.scoreHistoryLogRepository = scoreHistoryLogRepository;
        this.timelineBackfillService = timelineBackfillService;
        this.adminAuthService = adminAuthService;
    }

    /**
     * 【メソッドの役割】 自分専用の手動バックフィル。過去の {@link com.beatseeker.backend.entity.ScoreHistoryLog}
     * から SCORE_UPDATE イベントを再生成する。
     *
     * 起動時の自動バックフィルが何らかの理由で動かなかった場合や、再実行したいときに使う。
     *
     * @param auth  認証情報
     * @param force 既に TimelineEvent を持つ場合でも処理を続けるか。デフォルト false（既存があればスキップ）
     * @return 診断情報: { historyLogs, createdEvents, skipped }
     */
    @PostMapping("/backfill")
    public ResponseEntity<Map<String, Object>> backfillMine(
            Authentication auth,
            @RequestParam(value = "force", required = false, defaultValue = "false") boolean force) {

        if (auth == null || !auth.isAuthenticated()) {
            return ResponseEntity.status(401).body(Map.of("message", "Not authenticated"));
        }
        User me = userRepository.findByIidxId(auth.getName()).orElse(null);
        if (me == null) {
            return ResponseEntity.status(401).body(Map.of("message", "User not found"));
        }

        int historyCount = scoreHistoryLogRepository.findByUserOrderByUploadedAtAsc(me).size();
        int created = timelineBackfillService.backfillSingleUser(me, force);

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("historyLogs", historyCount);
        body.put("skipped", created < 0);
        body.put("createdEvents", Math.max(0, created));
        body.put("hadExistingEvents", created < 0);
        return ResponseEntity.ok(body);
    }

    /**
     * 【メソッドの役割】 管理者向け: 全ユーザーをまとめてバックフィルする（非同期トリガ）。
     *
     * 同期的に最後まで待つと数分〜十数分かかり HTTP タイムアウトに引っかかるため、
     * トリガ呼び出しはバックグラウンドジョブの起動だけを行い 202 Accepted を即座に返す。
     * 進捗は {@code GET /api/timeline/backfill-status} でポーリングできる。
     *
     * 既に走行中のジョブがある場合は新たに起動せず、状態だけ返す。
     *
     * @param auth  認証情報（管理者必須）
     * @param force 既存イベントがあっても処理を続けるか
     * @return ジョブ起動結果: { started, alreadyRunning, status }
     */
    @PostMapping("/backfill-all")
    public ResponseEntity<Map<String, Object>> backfillAll(
            Authentication auth,
            @RequestParam(value = "force", required = false, defaultValue = "false") boolean force) {

        if (auth == null || !auth.isAuthenticated()) {
            return ResponseEntity.status(401).body(Map.of("message", "Not authenticated"));
        }
        User me = userRepository.findByIidxId(auth.getName()).orElse(null);
        if (me == null || !adminAuthService.isAdmin(me)) {
            return ResponseEntity.status(403).body(Map.of("message", "Forbidden"));
        }

        boolean alreadyRunning = timelineBackfillService.isRunning();
        if (!alreadyRunning) {
            // 非同期で実行開始。実体は Spring の TaskExecutor 上で動く。
            timelineBackfillService.backfillAllAsync(force);
        }

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("started", !alreadyRunning);
        body.put("alreadyRunning", alreadyRunning);
        body.put("status", timelineBackfillService.getStatus());
        // 受理してすぐ返す（処理自体はバックグラウンドで継続）。
        return ResponseEntity.accepted().body(body);
    }

    /**
     * 【メソッドの役割】 直近 / 進行中のバックフィルジョブの進捗を返す。
     *
     * フロントは {@code POST /backfill-all} → このエンドポイントを数秒おきにポーリングして
     * 進捗バーや完了表示を更新する想定。
     *
     * @return TimelineBackfillService の進捗 Map（running / processed / created など）
     */
    @GetMapping("/backfill-status")
    public ResponseEntity<Map<String, Object>> backfillStatus(Authentication auth) {
        if (auth == null || !auth.isAuthenticated()) {
            return ResponseEntity.status(401).body(Map.of("message", "Not authenticated"));
        }
        return ResponseEntity.ok(timelineBackfillService.getStatus());
    }

    /**
     * 【メソッドの役割】 自分とフレンドのタイムラインイベントを時系列降順で取得する。
     *
     * 処理の流れ:
     *  1. 認証ユーザーを取得し、自分 + フレンド全員の {@link User} 集合を作る。
     *  2. {@link TimelineEventRepository#findRecentByUsers} で新しい順に取得（最大 limit 件）。
     *  3. payload (JSON 文字列) を Map にパースし、レスポンス用に整形する。
     *
     * @param auth  認証情報
     * @param limit 取得件数（省略時は {@link #DEFAULT_LIMIT}、上限は {@link #MAX_LIMIT}）
     * @return 整形済みのタイムラインエントリ配列
     */
    @GetMapping
    public ResponseEntity<List<Map<String, Object>>> getTimeline(
            Authentication auth,
            @RequestParam(value = "limit", required = false) Integer limit) {

        if (auth == null || !auth.isAuthenticated()) {
            return ResponseEntity.status(401).body(List.of());
        }

        String iidxId = auth.getName();
        User me = userRepository.findByIidxId(iidxId).orElse(null);
        if (me == null) {
            return ResponseEntity.status(401).body(List.of());
        }

        int effLimit = limit == null ? DEFAULT_LIMIT : Math.min(Math.max(limit, 1), MAX_LIMIT);

        // 手順1: 自分 + フレンドの User 集合を作る（重複は Set で除く）。
        Set<User> targetUsers = new HashSet<>();
        targetUsers.add(me);
        for (Friendship f : friendshipRepository.findByUser(me)) {
            // 非公開フレンドはタイムラインから除外する。
            User friend = f.getFriend();
            if (friend.getPrivacyLevel() != null && friend.getPrivacyLevel() == 2) continue;
            targetUsers.add(friend);
        }

        // 手順2: イベントを新しい順に取得。
        List<TimelineEvent> events = timelineEventRepository.findRecentByUsers(
                targetUsers, PageRequest.of(0, effLimit));

        // 手順3: フロントが扱いやすい Map 形式に整形。
        List<Map<String, Object>> result = new ArrayList<>(events.size());
        for (TimelineEvent ev : events) {
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("id", ev.getId());
            entry.put("type", ev.getType());
            entry.put("userId", ev.getUser().getId());
            entry.put("displayName", ev.getUser().getDisplayName() != null
                    ? ev.getUser().getDisplayName() : "プレイヤー");
            entry.put("isMe", ev.getUser().getId().equals(me.getId()));
            // payload は JSON 文字列。SCORE_UPDATE は配列、OVERTAKE_* は Map。
            entry.put("payload", parsePayload(ev.getPayload()));
            entry.put("createdAt", ev.getCreatedAt()
                    .atZone(ZoneId.systemDefault()).withZoneSameInstant(JST).format(ISO_JST));
            result.add(entry);
        }

        return ResponseEntity.ok(result);
    }

    /**
     * payload を可能なら JSON としてパース。失敗時は文字列のまま返す（壊れたデータでもエラーにしない）。
     */
    private Object parsePayload(String payload) {
        if (payload == null || payload.isBlank()) return null;
        try {
            // SCORE_UPDATE は配列、それ以外は Map なので Object として読む。
            return objectMapper.readValue(payload, new TypeReference<Object>() {});
        } catch (Exception e) {
            return payload;
        }
    }
}
