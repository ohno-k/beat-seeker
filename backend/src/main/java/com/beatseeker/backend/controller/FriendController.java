package com.beatseeker.backend.controller;

import com.beatseeker.backend.entity.FriendRequest;
import com.beatseeker.backend.entity.Friendship;
import com.beatseeker.backend.entity.Score;
import com.beatseeker.backend.entity.User;
import com.beatseeker.backend.entity.ScoreHistoryLog;
import com.beatseeker.backend.entity.VirtualRival;
import com.beatseeker.backend.repository.FriendRequestRepository;
import com.beatseeker.backend.repository.FriendshipRepository;
import com.beatseeker.backend.repository.ScoreRepository;
import com.beatseeker.backend.repository.UserRepository;
import com.beatseeker.backend.repository.ScoreHistoryLogRepository;
import com.beatseeker.backend.repository.VirtualRivalRepository;
import com.beatseeker.backend.service.PushNotificationService;
import com.beatseeker.backend.service.TopRankersBeatPtService;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 【クラスの役割】 フレンド関係およびバーチャルライバル機能を担当する REST コントローラ。
 *
 * 本アプリにおける「フレンド」は双方向の承認制リレーションで、
 * 加えて KONAMI の公式ランカーを擬似ライバルとして登録できる「バーチャルライバル」機能もここに集約している。
 *
 * 責務:
 *  - フレンド検索・申請・承認・拒否・削除
 *  - フレンドのスコア閲覧（privacyLevel による開示制御）
 *  - バーチャルライバル（公式ランカーの版＋都道府県で指定）の登録・解除
 *  - Web Push の購読情報保存・テスト送信
 *
 * 依存:
 *  - {@link UserRepository} / {@link FriendRequestRepository} / {@link FriendshipRepository}
 *  - {@link ScoreRepository} / {@link ScoreHistoryLogRepository}: スコア表示用
 *  - {@link VirtualRivalRepository}: バーチャルライバル
 *  - {@link PushNotificationService}: 申請時・テスト時の push 通知送信
 *  - {@link TopRankersBeatPtService}: バーチャルライバルの最新 BEAT-PT / RATE-PT 参照
 *
 * 主なエンドポイント:
 *  - GET    /api/friends                           … フレンド一覧
 *  - GET    /api/friends/search?query=...          … ユーザー検索
 *  - POST   /api/friends/request                   … フレンド申請
 *  - GET    /api/friends/requests/pending          … 自分宛ての未応答申請
 *  - POST   /api/friends/requests/{id}/accept      … 承認
 *  - POST   /api/friends/requests/{id}/reject      … 拒否
 *  - GET    /api/friends/scores                    … 曲単位のフレンドスコア
 *  - GET    /api/friends/{friendId}/scores         … フレンドの全スコア
 *  - DELETE /api/friends/{friendId}                … フレンド解除
 *  - *      /api/friends/virtual-rivals{,/status}  … バーチャルライバル CRUD
 *  - POST   /api/friends/push-subscription         … push 購読情報の保存
 *  - POST   /api/friends/push-test                 … push テスト送信
 *
 * プライバシー: User.privacyLevel は 0=公開 / 1=フレンドのみ / 2=非公開 で、
 *   privacyLevel == 2 のユーザーのスコアはフレンドにも見せない。
 */
@RestController
@RequestMapping("/api/friends")
public class FriendController {

    /** ユーザー検索・ユーザー情報取得に利用するリポジトリ。 */
    private final UserRepository userRepository;
    /** フレンド申請（PENDING/ACCEPTED/REJECTED）を扱うリポジトリ。 */
    private final FriendRequestRepository friendRequestRepository;
    /** 双方向のフレンドリレーションを永続化するリポジトリ。 */
    private final FriendshipRepository friendshipRepository;
    /** フレンド一覧に最新 BEAT-PT を添えるためのリポジトリ。 */
    private final ScoreHistoryLogRepository scoreHistoryLogRepository;
    /** フレンドの個別曲スコアを引くためのリポジトリ。 */
    private final ScoreRepository scoreRepository;
    /** バーチャルライバル（公式ランカーへの追従）リポジトリ。 */
    private final VirtualRivalRepository virtualRivalRepository;
    /** 申請時・テスト時に Web Push を送信するサービス。 */
    private final PushNotificationService pushNotificationService;
    /** バーチャルライバルの BEAT-PT / RATE-PT をキャッシュから引くサービス。 */
    private final TopRankersBeatPtService topRankersBeatPtService;

    /**
     * 【コンストラクタ】 Spring DI で依存を受け取る。
     */
    public FriendController(UserRepository userRepository,
            FriendRequestRepository friendRequestRepository,
            FriendshipRepository friendshipRepository,
            ScoreHistoryLogRepository scoreHistoryLogRepository,
            ScoreRepository scoreRepository,
            VirtualRivalRepository virtualRivalRepository,
            PushNotificationService pushNotificationService,
            TopRankersBeatPtService topRankersBeatPtService) {
        this.userRepository = userRepository;
        this.friendRequestRepository = friendRequestRepository;
        this.friendshipRepository = friendshipRepository;
        this.scoreHistoryLogRepository = scoreHistoryLogRepository;
        this.scoreRepository = scoreRepository;
        this.virtualRivalRepository = virtualRivalRepository;
        this.pushNotificationService = pushNotificationService;
        this.topRankersBeatPtService = topRankersBeatPtService;
    }

    /**
     * 【メソッドの役割】 コントローラが起動済みであることを確認する疎通確認用エンドポイント。
     *
     * @return 固定文字列 "FriendController is active"
     */
    @GetMapping("/test")
    public String test() {
        return "FriendController is active";
    }

    /**
     * 【メソッドの役割】 ログインユーザーのフレンド一覧を、最新 BEAT-PT 付きで返す。
     *
     * 処理の流れ:
     *  1. 認証情報から User を特定し、その User の Friendship を全件取得。
     *  2. 各フレンドの ScoreHistoryLog を昇順で引き、末尾レコードを「最新」とみなす。
     *  3. フレンドの基本情報 + totalBeatPt を Map にして返す。
     *
     * @param auth 認証情報
     * @return フレンド情報 Map の List（空の場合もある）
     */
    @GetMapping
    public ResponseEntity<List<Map<String, Object>>> getFriends(Authentication auth) {
        User user = getUser(auth);
        List<Friendship> friendships = friendshipRepository.findByUser(user);

        List<Map<String, Object>> result = friendships.stream().map(f -> {
            User friend = f.getFriend();
            Map<String, Object> map = new HashMap<>();
            map.put("id", friend.getId());
            map.put("displayName", friend.getDisplayName());
            map.put("iidxId", friend.getIidxId());
            map.put("lastUploadedAt", friend.getLastUploadedAt());
            // privacyLevel が null の旧ユーザーは 0（公開）として扱う。
            map.put("privacyLevel", friend.getPrivacyLevel() != null ? friend.getPrivacyLevel() : 0);

            // 履歴を昇順で取得し、末尾（= 最新スナップショット）の BEAT-PT を添える。
            // 注意: ランキング用の高速パスではなく、N+1 に近いクエリなのでフレンド数が多いと遅くなる。
            List<ScoreHistoryLog> logs = scoreHistoryLogRepository.findByUserOrderByUploadedAtAsc(friend);
            if (!logs.isEmpty()) {
                ScoreHistoryLog latest = logs.get(logs.size() - 1);
                map.put("totalBeatPt", latest.getTotalBeatPt());
            } else {
                // 履歴未登録ユーザーは 0 扱い（フロントで「未プレイ」表示にする）。
                map.put("totalBeatPt", 0);
            }
            return map;
        }).collect(Collectors.toList());

        return ResponseEntity.ok(result);
    }

    /**
     * 【メソッドの役割】 ユーザーを表示名または iidxId で検索し、上位 20 件を返す。
     *
     * 処理の流れ:
     *  1. 空クエリは空リスト返却。
     *  2. iidxId のハイフン有無を吸収するため、"12345678" ↔ "1234-5678" の両方でヒットするよう variant を作る。
     *  3. リポジトリで検索し、各ヒットに対し最新 BEAT-PT / フレンド状態 / 申請済みフラグを付与。
     *
     * @param auth  認証情報
     * @param query 検索クエリ（表示名または iidxId）
     * @return 検索結果 Map の List（最大 20 件）
     */
    @GetMapping("/search")
    public ResponseEntity<List<Map<String, Object>>> searchUsers(Authentication auth, @RequestParam String query) {
        User currentUser = getUser(auth);
        String trimmedQuery = query.trim();

        // 手順1: 空文字は即時で空リスト。DB 無駄打ちを避ける。
        if (trimmedQuery.isEmpty()) {
            return ResponseEntity.ok(List.of());
        }

        // 手順2: ハイフン有無を相互変換し、2 つの表記で OR 検索できるようにする。
        String variant = trimmedQuery;
        if (trimmedQuery.matches("\\d{8}")) {
            // ハイフン無し 8 桁 → 4-4 形式に変換
            variant = trimmedQuery.substring(0, 4) + "-" + trimmedQuery.substring(4);
        } else if (trimmedQuery.matches("\\d{4}-\\d{4}")) {
            // 4-4 形式 → ハイフン除去版に変換
            variant = trimmedQuery.replace("-", "");
        }

        // 手順3: 2 つの表記で検索し、最大 20 件に絞る。
        List<User> users = userRepository.searchUsers(trimmedQuery, variant).stream()
                .limit(20)
                .collect(Collectors.toList());

        List<Map<String, Object>> result = users.stream().map(u -> {
            Map<String, Object> map = new HashMap<>();
            map.put("id", u.getId());
            map.put("displayName", u.getDisplayName());
            map.put("iidxId", u.getIidxId());
            map.put("lastUploadedAt", u.getLastUploadedAt());

            // ヒットユーザーの最新 BEAT-PT を履歴から引く。未登録なら 0。
            List<ScoreHistoryLog> logs = scoreHistoryLogRepository.findByUserOrderByUploadedAtAsc(u);
            if (!logs.isEmpty()) {
                ScoreHistoryLog latest = logs.get(logs.size() - 1);
                map.put("totalBeatPt", latest.getTotalBeatPt());
            } else {
                map.put("totalBeatPt", 0);
            }

            // 既にフレンドか／申請中かをチェックし、UI のボタン状態を制御するために返す。
            boolean isFriend = friendshipRepository.findByUserAndFriend(currentUser, u).isPresent();
            boolean hasSentRequest = friendRequestRepository.findBySenderAndReceiverAndStatus(currentUser, u, "PENDING")
                    .isPresent();

            map.put("isFriend", isFriend);
            map.put("hasSentRequest", hasSentRequest);

            return map;
        }).collect(Collectors.toList());

        return ResponseEntity.ok(result);
    }

    /**
     * 【メソッドの役割】 受信者に対してフレンド申請を作成する。
     *
     * 処理の流れ:
     *  1. 自分自身への申請を拒否。
     *  2. 既にフレンドの場合・PENDING 申請が既存の場合はエラー応答。
     *  3. FriendRequest を PENDING 状態で保存し、受信者に push 通知を送る。
     *
     * @param auth    認証情報（申請者）
     * @param payload {receiverId: Long, message: String?}
     * @return 成功/失敗メッセージ
     */
    @PostMapping("/request")
    @Transactional
    public ResponseEntity<Map<String, Object>> sendRequest(Authentication auth,
            @RequestBody Map<String, Object> payload) {
        User sender = getUser(auth);
        // JSON の Number/String いずれで来ても吸収するため toString() 経由で Long に変換する。
        Long receiverId = payload.get("receiverId") != null ? Long.valueOf(payload.get("receiverId").toString()) : null;
        String message = payload.get("message") != null ? payload.get("message").toString() : null;
        User receiver = userRepository.findById(receiverId).orElseThrow();

        // 手順1: 自分自身への申請は意味がないので 400。
        if (sender.getId().equals(receiver.getId())) {
            return ResponseEntity.badRequest().body(Map.of("message", "自分自身にフレンド申請は送れません。"));
        }

        // 手順2a: 既にフレンド登録済みの場合は重複申請を防ぐ。
        if (friendshipRepository.findByUserAndFriend(sender, receiver).isPresent()) {
            return ResponseEntity.badRequest().body(Map.of("message", "既にフレンドです。"));
        }

        // 手順2b: PENDING 申請が残っている場合は二重送信を防ぐ。
        if (friendRequestRepository.findBySenderAndReceiverAndStatus(sender, receiver, "PENDING").isPresent()) {
            return ResponseEntity.badRequest().body(Map.of("message", "既に申請済みです。"));
        }

        // 手順3: FriendRequest を PENDING で永続化する。
        FriendRequest request = new FriendRequest();
        request.setSender(sender);
        request.setReceiver(receiver);
        request.setMessage(message);
        request.setStatus("PENDING");
        friendRequestRepository.save(request);

        // 手順3b: 受信者が push 購読済みであれば、即座に通知する。
        if (receiver.getPushSubscription() != null) {
            pushNotificationService.sendNotification(
                    receiver.getPushSubscription(),
                    "ライバル申請が届きました",
                    sender.getDisplayName() + "さんからライバル申請が届きました。",
                    "/friends");
        }

        return ResponseEntity.ok(Map.of("message", "フレンド申請を送信しました。"));
    }

    /**
     * 【メソッドの役割】 自分宛てに届いた未応答（PENDING）のフレンド申請を一覧で返す。
     *
     * 通知バッジやフレンド申請受信ボックスの表示に使う。
     *
     * @param auth 認証情報
     * @return 申請 Map の List
     */
    @GetMapping("/requests/pending")
    public ResponseEntity<List<Map<String, Object>>> getPendingRequests(Authentication auth) {
        User user = getUser(auth);
        List<FriendRequest> requests = friendRequestRepository.findByReceiverAndStatus(user, "PENDING");

        List<Map<String, Object>> result = requests.stream().map(r -> {
            Map<String, Object> map = new HashMap<>();
            map.put("id", r.getId());
            // 送信者情報は表示用に id / 表示名 / iidxId をすべて展開する。
            map.put("senderId", r.getSender().getId());
            map.put("senderName", r.getSender().getDisplayName());
            map.put("senderIidxId", r.getSender().getIidxId());
            map.put("message", r.getMessage());
            map.put("createdAt", r.getCreatedAt());
            return map;
        }).collect(Collectors.toList());

        return ResponseEntity.ok(result);
    }

    /**
     * 【メソッドの役割】 届いたフレンド申請を承認し、双方向の Friendship を作成する。
     *
     * 処理の流れ:
     *  1. 申請の受信者が自分であることを確認（他人の申請を承認できないように）。
     *  2. FriendRequest を ACCEPTED に更新。
     *  3. sender→receiver / receiver→sender の両方向に Friendship を作成（冪等）。
     *
     * @param auth 認証情報
     * @param id   FriendRequest の ID
     * @return 成功メッセージ。受信者が一致しない場合 403
     */
    @PostMapping("/requests/{id}/accept")
    @Transactional
    public ResponseEntity<Map<String, Object>> acceptRequest(Authentication auth, @PathVariable Long id) {
        User receiver = getUser(auth);
        FriendRequest request = friendRequestRepository.findById(id).orElseThrow();

        // 手順1: 申請の受信者が現在のログインユーザー本人か確認。他人の申請は 403。
        if (!request.getReceiver().getId().equals(receiver.getId())) {
            return ResponseEntity.status(403).build();
        }

        // 手順2: FriendRequest を ACCEPTED に遷移させる。
        request.setStatus("ACCEPTED");
        friendRequestRepository.save(request);

        // 手順3: 双方向に Friendship を作る。既に存在する場合はスキップして冪等性を担保。
        User sender = request.getSender();

        if (friendshipRepository.findByUserAndFriend(sender, receiver).isEmpty()) {
            Friendship f1 = new Friendship();
            f1.setUser(sender);
            f1.setFriend(receiver);
            friendshipRepository.save(f1);
        }

        if (friendshipRepository.findByUserAndFriend(receiver, sender).isEmpty()) {
            Friendship f2 = new Friendship();
            f2.setUser(receiver);
            f2.setFriend(sender);
            friendshipRepository.save(f2);
        }

        return ResponseEntity.ok(Map.of("message", "フレンド申請を承認しました。"));
    }

    /**
     * 【メソッドの役割】 届いたフレンド申請を拒否する。
     *
     * Friendship は作成せず、FriendRequest の状態を REJECTED に変更するのみ。
     *
     * @param auth 認証情報
     * @param id   FriendRequest の ID
     * @return 成功メッセージ。受信者が一致しない場合 403
     */
    @PostMapping("/requests/{id}/reject")
    @Transactional
    public ResponseEntity<Map<String, Object>> rejectRequest(Authentication auth, @PathVariable Long id) {
        User receiver = getUser(auth);
        FriendRequest request = friendRequestRepository.findById(id).orElseThrow();

        // 受信者が本人でない場合は 403。
        if (!request.getReceiver().getId().equals(receiver.getId())) {
            return ResponseEntity.status(403).build();
        }

        request.setStatus("REJECTED");
        friendRequestRepository.save(request);

        return ResponseEntity.ok(Map.of("message", "フレンド申請を拒否しました。"));
    }

    /**
     * 【メソッドの役割】 指定曲 × 難易度における全フレンドのスコアを返す（1 行比較用）。
     *
     * 曲詳細画面の「フレンドのスコア」欄で使う。privacyLevel == 2（非公開）のフレンドは
     * スコアキーを付けずに基本情報のみ返すことで、フロントで「非公開」表示できる。
     *
     * @param auth           認証情報
     * @param title          曲名
     * @param difficultyName 難易度名（ANOTHER / LEGGENDARIA 等）
     * @return 各フレンドのスコアサマリ Map の List
     */
    @GetMapping("/scores")
    public ResponseEntity<List<Map<String, Object>>> getFriendScores(
            Authentication auth,
            @RequestParam String title,
            @RequestParam String difficultyName) {
        User user = getUser(auth);
        List<Friendship> friendships = friendshipRepository.findByUser(user);

        List<Map<String, Object>> result = friendships.stream()
                .map(f -> {
                    User friend = f.getFriend();
                    Map<String, Object> map = new HashMap<>();
                    map.put("id", friend.getId());
                    map.put("displayName", friend.getDisplayName());
                    map.put("iidxId", friend.getIidxId());
                    Integer privacyLevel = friend.getPrivacyLevel() != null ? friend.getPrivacyLevel() : 0;
                    map.put("privacyLevel", privacyLevel);

                    // privacyLevel == 2（非公開）の場合はスコア系フィールドを添付しない。
                    // 基本情報だけ返して、フロントで「非公開」表示させる。
                    if (privacyLevel != 2) {
                        scoreRepository.findFirstByUserAndTitleAndDifficultyNameOrderByUploadedAtDesc(
                                friend, title, difficultyName)
                                .ifPresent(score -> {
                                    map.put("score", score.getScore());
                                    map.put("clearType", score.getClearType());
                                    map.put("djLevel", score.getDjLevel());
                                    map.put("pgreat", score.getPgreat());
                                    map.put("great", score.getGreat());
                                    map.put("missCount", score.getMissCount());
                                    map.put("difficultyLevel", score.getDifficultyLevel());
                                });
                    }

                    return map;
                })
                .collect(Collectors.toList());

        return ResponseEntity.ok(result);
    }

    /**
     * 【メソッドの役割】 指定フレンドの全スコアを取得する。
     *
     * 処理の流れ:
     *  1. 対象 User を取得。
     *  2. フレンド関係が存在しなければ 403。
     *  3. privacyLevel == 2 なら同じく 403（非公開）。
     *  4. スコア一覧を Map にして返す。
     *
     * @param auth     認証情報
     * @param friendId 対象フレンドの User ID
     * @return スコア一覧。無権限は 403
     */
    @GetMapping("/{friendId}/scores")
    public ResponseEntity<List<Map<String, Object>>> getFriendAllScores(
            Authentication auth,
            @PathVariable Long friendId) {
        User user = getUser(auth);
        User friend = userRepository.findById(friendId)
                .orElseThrow(() -> new RuntimeException("User not found"));

        // 手順2: フレンド関係がなければアクセス不可（他人のスコアを見られない）。
        if (friendshipRepository.findByUserAndFriend(user, friend).isEmpty()) {
            return ResponseEntity.status(403).build();
        }

        // 手順3: privacyLevel==2 は全スコア非公開。
        Integer privacyLevel = friend.getPrivacyLevel() != null ? friend.getPrivacyLevel() : 0;
        if (privacyLevel == 2) {
            return ResponseEntity.status(403).build();
        }

        // 手順4: スコア全件を Map に変換して返す。
        List<Score> scores = scoreRepository.findByUserOrderByUploadedAtAsc(friend);

        List<Map<String, Object>> result = scores.stream().map(s -> {
            Map<String, Object> map = new HashMap<>();
            map.put("id", s.getId());
            map.put("title", s.getTitle());
            map.put("difficultyName", s.getDifficultyName());
            map.put("difficultyLevel", s.getDifficultyLevel());
            map.put("score", s.getScore());
            map.put("clearType", s.getClearType());
            map.put("djLevel", s.getDjLevel());
            map.put("pgreat", s.getPgreat());
            map.put("great", s.getGreat());
            map.put("missCount", s.getMissCount());
            return map;
        }).collect(Collectors.toList());

        return ResponseEntity.ok(result);
    }

    /**
     * 【メソッドの役割】 指定フレンドの履歴スナップショット（ScoreHistoryLog）を時系列昇順で返す。
     *
     * 勝敗変遷グラフでフレンド側の過去スコアを再構築するために利用する。
     * フレンド関係必須。privacyLevel==0（公開）と 1（フレンドのみ）は取得可、2（非公開）は 403。
     *
     * レスポンス形式は {@link ScoreController#getHistory(Authentication)} と一致させ、
     * フロントは同一パーサで扱える。
     *
     * @param auth     認証情報
     * @param friendId 対象フレンドの User ID
     * @return 履歴ログ Map の List（uploadedAt 昇順）。無権限は 403
     */
    @GetMapping("/{friendId}/history")
    public ResponseEntity<List<Map<String, Object>>> getFriendHistory(
            Authentication auth,
            @PathVariable Long friendId) {
        User user = getUser(auth);
        User friend = userRepository.findById(friendId)
                .orElseThrow(() -> new RuntimeException("User not found"));

        // フレンド関係必須。/scores と同様に他人の履歴は閲覧不可。
        if (friendshipRepository.findByUserAndFriend(user, friend).isEmpty()) {
            return ResponseEntity.status(403).build();
        }

        // privacyLevel==2 (非公開) はフレンドにも履歴を開示しない。
        // 0 (公開) と 1 (フレンドのみ) はどちらも取得可能。
        Integer privacyLevel = friend.getPrivacyLevel() != null ? friend.getPrivacyLevel() : 0;
        if (privacyLevel == 2) {
            return ResponseEntity.status(403).build();
        }

        List<ScoreHistoryLog> logs = scoreHistoryLogRepository.findByUserOrderByUploadedAtAsc(friend);
        List<Map<String, Object>> history = logs.stream().map(log -> {
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
        }).collect(Collectors.toList());

        return ResponseEntity.ok(history);
    }

    /**
     * 【メソッドの役割】 双方向の Friendship レコードを削除してフレンド関係を解除する。
     *
     * 片側だけ残すとリストに整合性違反が発生するため、必ず両方向を削除する。
     *
     * @param auth     認証情報
     * @param friendId 解除したい相手の User ID
     * @return 成功メッセージ
     */
    @DeleteMapping("/{friendId}")
    @Transactional
    public ResponseEntity<Map<String, Object>> removeFriend(Authentication auth, @PathVariable Long friendId) {
        User user = getUser(auth);
        User friend = userRepository.findById(friendId)
                .orElseThrow(() -> new RuntimeException("User not found"));

        // 自分→相手、相手→自分の両方向の Friendship を削除する（存在すれば）。
        friendshipRepository.findByUserAndFriend(user, friend)
                .ifPresent(friendshipRepository::delete);
        friendshipRepository.findByUserAndFriend(friend, user)
                .ifPresent(friendshipRepository::delete);

        return ResponseEntity.ok(Map.of("message", "フレンドを削除しました。"));
    }

    /**
     * 【メソッドの役割】 ログインユーザーが登録済みのバーチャルライバル一覧を返す。
     *
     * バーチャルライバルは「IIDX バージョン番号 + 都道府県ファイル番号」で識別される公式ランカー。
     * 処理の流れ:
     *  1. TopRankersBeatPtService から BEAT-PT / RATE-PT のランキングを取得し lookup Map を構築。
     *  2. 自分のライバルを走査し、lookup から最新ポイントを付与して返す。
     * キー区切り文字に NUL('\0') を使うのは、数値が連結時に衝突しないようにするため。
     *
     * @param auth 認証情報
     * @return バーチャルライバル情報の List
     */
    @GetMapping("/virtual-rivals")
    public ResponseEntity<List<Map<String, Object>>> getVirtualRivals(Authentication auth) {
        User user = getUser(auth);
        List<VirtualRival> rivals = virtualRivalRepository.findByOwner(user);

        // 手順1: BEAT-PT ランキングから (version, prefecture) → beatPt の lookup を作る。
        // キー区切りに NUL を使うのは "11+2" と "1+12" の衝突を避ける簡易策。
        Map<String, Number> beatPtLookup = new HashMap<>();
        for (Map<String, Object> row : topRankersBeatPtService.getRanking()) {
            beatPtLookup.put(row.get("versionNum") + "\0" + row.get("prefectureFileNum"),
                    (Number) row.get("beatPt"));
        }
        // 手順1b: 同様に RATE-PT 側の lookup を作る。
        Map<String, Number> ratePtLookup = new HashMap<>();
        for (Map<String, Object> row : topRankersBeatPtService.getRateRanking()) {
            ratePtLookup.put(row.get("versionNum") + "\0" + row.get("prefectureFileNum"),
                    (Number) row.get("ratePt"));
        }

        // 手順2: 自分のライバル配列に lookup 由来のポイントを添えて返す。
        List<Map<String, Object>> result = rivals.stream().map(r -> {
            Map<String, Object> map = new HashMap<>();
            map.put("id", r.getId());
            map.put("versionNum", r.getVersionNum());
            map.put("versionName", r.getVersionName());
            map.put("prefectureFileNum", r.getPrefectureFileNum());
            map.put("prefectureName", r.getPrefectureName());
            map.put("createdAt", r.getCreatedAt());
            String key = r.getVersionNum() + "\0" + r.getPrefectureFileNum();
            Number beatPt = beatPtLookup.get(key);
            Number ratePt = ratePtLookup.get(key);
            // ランキングキャッシュに該当がなければ 0.0 をフォールバックに出す。
            map.put("totalBeatPt", beatPt != null ? beatPt.doubleValue() : 0.0);
            map.put("totalRatePt", ratePt != null ? ratePt.doubleValue() : 0.0);
            return map;
        }).collect(Collectors.toList());
        return ResponseEntity.ok(result);
    }

    /**
     * 【メソッドの役割】 指定 (version, prefecture) がライバル登録済みかどうかを返す。
     *
     * バーチャルライバル候補の画面で「登録済み」バッジを表示するのに使う軽量チェック。
     *
     * @param auth              認証情報
     * @param versionNum        IIDX バージョン番号
     * @param prefectureFileNum 都道府県ファイル番号
     * @return {registered: true/false}
     */
    @GetMapping("/virtual-rivals/status")
    public ResponseEntity<Map<String, Object>> getVirtualRivalStatus(Authentication auth,
            @RequestParam Integer versionNum,
            @RequestParam Integer prefectureFileNum) {
        User user = getUser(auth);
        boolean registered = virtualRivalRepository
                .findByOwnerAndVersionNumAndPrefectureFileNum(user, versionNum, prefectureFileNum)
                .isPresent();
        return ResponseEntity.ok(Map.of("registered", registered));
    }

    /**
     * 【メソッドの役割】 バーチャルライバルを新規追加する。
     *
     * 処理の流れ:
     *  1. 必須項目（versionNum / prefectureFileNum）の存在確認。
     *  2. 重複登録をチェックし、既にあれば冪等に registered:true を返す。
     *  3. VirtualRival を保存。
     *
     * @param auth    認証情報
     * @param payload {versionNum, prefectureFileNum, versionName?, prefectureName?}
     * @return 登録結果のメッセージ
     */
    @PostMapping("/virtual-rivals")
    @Transactional
    public ResponseEntity<Map<String, Object>> addVirtualRival(Authentication auth,
            @RequestBody Map<String, Object> payload) {
        User user = getUser(auth);
        // 手順1: 必須の2キーが欠落していれば 400。
        if (payload.get("versionNum") == null || payload.get("prefectureFileNum") == null) {
            return ResponseEntity.badRequest().body(Map.of("message", "versionNum と prefectureFileNum は必須です。"));
        }
        // JSON 由来の値を toString() 経由で Integer 化する（Number/String 両対応）。
        Integer versionNum = Integer.valueOf(payload.get("versionNum").toString());
        Integer prefectureFileNum = Integer.valueOf(payload.get("prefectureFileNum").toString());
        String versionName = payload.get("versionName") != null ? payload.get("versionName").toString() : null;
        String prefectureName = payload.get("prefectureName") != null ? payload.get("prefectureName").toString() : null;

        // 手順2: 既に登録済みなら 200 OK で「登録済み」メッセージを返す（冪等動作）。
        if (virtualRivalRepository
                .findByOwnerAndVersionNumAndPrefectureFileNum(user, versionNum, prefectureFileNum)
                .isPresent()) {
            return ResponseEntity.ok(Map.of("message", "既に登録済みです。", "registered", true));
        }

        // 手順3: 新規 VirtualRival を作成して保存。
        VirtualRival rival = new VirtualRival();
        rival.setOwner(user);
        rival.setVersionNum(versionNum);
        rival.setPrefectureFileNum(prefectureFileNum);
        rival.setVersionName(versionName);
        rival.setPrefectureName(prefectureName);
        virtualRivalRepository.save(rival);

        return ResponseEntity.ok(Map.of("message", "ライバルに登録しました。", "registered", true));
    }

    /**
     * 【メソッドの役割】 指定 (version, prefecture) のバーチャルライバル登録を解除する。
     *
     * 存在しない場合もエラーにならず冪等に動作する。
     *
     * @param auth              認証情報
     * @param versionNum        IIDX バージョン番号
     * @param prefectureFileNum 都道府県ファイル番号
     * @return 解除メッセージ
     */
    @DeleteMapping("/virtual-rivals")
    @Transactional
    public ResponseEntity<Map<String, Object>> removeVirtualRival(Authentication auth,
            @RequestParam Integer versionNum,
            @RequestParam Integer prefectureFileNum) {
        User user = getUser(auth);
        virtualRivalRepository.deleteByOwnerAndVersionNumAndPrefectureFileNum(user, versionNum, prefectureFileNum);
        return ResponseEntity.ok(Map.of("message", "ライバルを解除しました。", "registered", false));
    }

    /**
     * 【メソッドの役割】 ブラウザの Web Push 購読 JSON を User レコードに保存する。
     *
     * フロントの Service Worker が getSubscription() で得た JSON 文字列をそのまま受け取り、
     * 後で push 送信する際に users.pushSubscription を参照する。
     *
     * @param auth    認証情報
     * @param payload {subscription: String(JSON)}
     * @return {status: "success"}
     */
    @PostMapping("/push-subscription")
    @Transactional
    public ResponseEntity<Map<String, String>> updatePushSubscription(Authentication auth,
            @RequestBody Map<String, String> payload) {
        User user = getUser(auth);
        String subscription = payload.get("subscription");
        user.setPushSubscription(subscription);
        userRepository.save(user);
        return ResponseEntity.ok(Map.of("status", "success"));
    }

    /**
     * 【メソッドの役割】 ログインユーザーに自分宛のテスト push 通知を送る。
     *
     * 「通知を有効にする」ボタンの動作確認に使う。購読情報が未登録なら 400、
     * 送信中に VAPID 系等のエラーが出れば 500 に畳む。
     *
     * @param auth 認証情報
     * @return テスト通知送信結果メッセージ
     */
    @PostMapping("/push-test")
    public ResponseEntity<Map<String, String>> testPushNotification(Authentication auth) {
        try {
            User user = getUser(auth);
            if (user.getPushSubscription() != null && !user.getPushSubscription().isEmpty()) {
                // 購読情報があれば例外ベースの send を呼ぶ（詳細エラーを返せるよう sendNotificationWithEx を選択）。
                pushNotificationService.sendNotificationWithEx(
                        user.getPushSubscription(),
                        "テスト通知",
                        "Push通知が正常に設定されています！",
                        "/");
                return ResponseEntity.ok(Map.of("status", "success", "message", "テスト通知を送信しました。"));
            } else {
                // 購読情報が保存されていなければ 400 で再購読を促す。
                return ResponseEntity.badRequest().body(Map.of("error", "サーバー側に通知の購読情報が登録されていません。再度「通知を有効にする」をやり直すか、端末の設定を確認してください。"));
            }
        } catch (Exception e) {
            // 鍵の不整合・相手 push サーバーのエラーなどはここに来る。500 として原因をフロントへ返す。
            return ResponseEntity.internalServerError().body(Map.of("error", "サーバー側でエラーが発生しました: " + e.getMessage()));
        }
    }

    /**
     * 【メソッドの役割】 認証情報からログイン User を取り出す共通ヘルパー。
     *
     * JwtAuthFilter が principal に iidxId を格納している前提。
     * 未認証 / User 不在はいずれも RuntimeException にして上位でハンドリングさせる。
     *
     * @param auth 認証情報
     * @return ログインユーザー
     * @throws RuntimeException 未認証またはユーザー不在
     */
    private User getUser(Authentication auth) {
        if (auth == null || !auth.isAuthenticated()) {
            throw new RuntimeException("Not authenticated");
        }
        String iidxId = (String) auth.getPrincipal();
        return userRepository.findByIidxId(iidxId)
                .orElseThrow(() -> new RuntimeException("User not found"));
    }
}
