package com.beatseeker.backend.controller;

import com.beatseeker.backend.entity.OptionVote;
import com.beatseeker.backend.entity.User;
import com.beatseeker.backend.repository.OptionVoteRepository;
import com.beatseeker.backend.repository.UserRepository;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.util.*;

/**
 * 【クラスの役割】 譜面ごとの「推奨オプション（正規、ミラー、乱、R乱、S乱）」投票を扱うコントローラ。
 *
 * ユーザー各自が「自分がこの譜面をプレイするときに使っているオプション」を投票し、
 * 閲覧者には投票数の集計が表示される。
 *
 * 【複数選択対応】 1 ユーザーが同一譜面に複数オプションを投票できる
 * （例: RANDOM と MIRROR を両方選択）。iidx-memo 連携で複数選択のオプションが
 * 同期されてくるため、それと整合させる設計。
 *
 * 【1P/2P 視点の対称性補正】
 *  - 1P の "REGULAR" と 2P の "MIRROR" は、鍵盤配置としては実質同じ。
 *  - サーバー側では **常に 1P 視点** にノーマライズして保存し、
 *    閲覧者のプレイサイドに応じて表示時に再変換する。
 *
 * 主要エンドポイント:
 *  - {@code POST   /api/votes}   … オプションを 1 つ追加（既に投票済みなら何もしない）
 *  - {@code DELETE /api/votes}   … 単一オプションを取り消す（{@code optionType} が必須）
 *  - {@code GET    /api/votes}   … 楽曲の集計結果を取得（閲覧者視点で変換済み）
 *
 *  集計レスポンス:
 *  - {@code counts} : {REGULAR: N, MIRROR: N, ...} の譜面別投票数（行数）
 *  - {@code totalVotes} : ユニークユーザー数（複数選択しても 1 と数える）
 *  - {@code myVotes} : 自分が投票している全オプションの配列（閲覧者視点に変換済み）
 */
@RestController
@RequestMapping("/api/votes")
public class OptionVoteController {

    private final OptionVoteRepository optionVoteRepository;
    private final UserRepository userRepository;

    public OptionVoteController(OptionVoteRepository optionVoteRepository, UserRepository userRepository) {
        this.optionVoteRepository = optionVoteRepository;
        this.userRepository = userRepository;
    }

    /**
     * 【メソッドの役割】 オプションを 1 つ投票（追加）する。
     *
     * 複数選択対応のため、既存投票を上書きせず、追加レコードとして 1 行 INSERT する。
     * 同じユーザーが同じオプションに二度投票しても UNIQUE 制約で重複しない（冪等）。
     */
    @PostMapping
    @Transactional
    public ResponseEntity<Map<String, Object>> vote(
            Authentication auth,
            @RequestBody VoteRequest request) {

        User user = getUser(auth);
        String normalizedOption = normalizeToFirstPlayer(request.optionType(), user.getPlaySide());

        // 既に同じオプションに投票済みなら何もしない（冪等）
        Optional<OptionVote> existing = optionVoteRepository
                .findByUserAndTitleAndDifficultyNameAndOptionType(
                        user, request.title(), request.difficultyName(), normalizedOption);
        if (existing.isPresent()) {
            return ResponseEntity.ok(Map.of(
                    "message", "既に投票済みです",
                    "normalizedOption", normalizedOption));
        }

        OptionVote vote = new OptionVote();
        vote.setUser(user);
        vote.setTitle(request.title());
        vote.setDifficultyName(request.difficultyName());
        vote.setOptionType(normalizedOption);
        vote.setVotedAt(LocalDateTime.now());
        optionVoteRepository.save(vote);

        return ResponseEntity.ok(Map.of("message", "投票しました", "normalizedOption", normalizedOption));
    }

    /**
     * 【メソッドの役割】 単一オプションへの自分の投票を取り消す。
     *
     * 投票が存在しない場合も 200 を返す（冪等）。
     * {@code optionType} は **必須** （複数選択化に伴い、どれを消すか明示する必要がある）。
     */
    @DeleteMapping
    @Transactional
    public ResponseEntity<Map<String, Object>> deleteVote(
            Authentication auth,
            @RequestParam String title,
            @RequestParam String difficultyName,
            @RequestParam String optionType) {

        User user = getUser(auth);
        String normalizedOption = normalizeToFirstPlayer(optionType, user.getPlaySide());
        optionVoteRepository.deleteByUserAndTitleAndDifficultyNameAndOptionType(
                user, title, difficultyName, normalizedOption);
        return ResponseEntity.ok(Map.of("message", "投票を取り消しました"));
    }

    /**
     * 【メソッドの役割】 楽曲の投票集計を「閲覧者のプレイサイド視点」で返す。
     *
     * - {@code counts}: 行数ベースの選択肢別集計（複数選択なので合計 ≥ ユーザー数）
     * - {@code totalVotes}: ユニークユーザー数。バーチャートの分母に使う
     * - {@code myVotes}: 自分が投票している全オプションの配列
     */
    @GetMapping
    public ResponseEntity<Map<String, Object>> getVotes(
            @RequestParam String title,
            @RequestParam String difficultyName,
            Authentication auth) {

        List<OptionVote> votes = optionVoteRepository.findByTitleAndDifficultyName(title, difficultyName);

        String viewerSide = "1P";
        List<String> myVotes = new ArrayList<>();
        if (auth != null && auth.isAuthenticated()) {
            try {
                User viewer = getUser(auth);
                viewerSide = viewer.getPlaySide() != null ? viewer.getPlaySide() : "1P";

                List<OptionVote> myVoteRows = optionVoteRepository
                        .findByUserAndTitleAndDifficultyName(viewer, title, difficultyName);
                for (OptionVote v : myVoteRows) {
                    myVotes.add(convertToViewerPerspective(v.getOptionType(), viewerSide));
                }
            } catch (Exception ignored) {
                // ユーザー解決失敗時は未ログイン同様に扱う
            }
        }

        // 5 種のオプションをゼロ初期化。LinkedHashMap で表示順を固定する。
        Map<String, Integer> counts = new LinkedHashMap<>();
        counts.put("REGULAR", 0);
        counts.put("MIRROR", 0);
        counts.put("RANDOM", 0);
        counts.put("R-RANDOM", 0);
        counts.put("S-RANDOM", 0);

        for (OptionVote v : votes) {
            String displayed = convertToViewerPerspective(v.getOptionType(), viewerSide);
            counts.put(displayed, counts.getOrDefault(displayed, 0) + 1);
        }

        long totalVotes = optionVoteRepository
                .countDistinctUsersByTitleAndDifficultyName(title, difficultyName);

        Map<String, Object> result = new HashMap<>();
        result.put("counts", counts);
        result.put("totalVotes", totalVotes);
        result.put("myVotes", myVotes);

        return ResponseEntity.ok(result);
    }

    /**
     * 保存用にオプション値を「1P 視点」へ正規化する。RANDOM 系は左右対称の影響を受けないので変換しない。
     */
    private String normalizeToFirstPlayer(String optionType, String playSide) {
        if ("2P".equals(playSide)) {
            if ("REGULAR".equals(optionType)) return "MIRROR";
            if ("MIRROR".equals(optionType)) return "REGULAR";
        }
        return optionType;
    }

    /** DB に保存された 1P 視点の値を「閲覧者視点」に戻す。 */
    private String convertToViewerPerspective(String storedOption, String viewerSide) {
        if ("2P".equals(viewerSide)) {
            if ("REGULAR".equals(storedOption)) return "MIRROR";
            if ("MIRROR".equals(storedOption)) return "REGULAR";
        }
        return storedOption;
    }

    private User getUser(Authentication auth) {
        if (auth == null || !auth.isAuthenticated()) {
            throw new RuntimeException("Not authenticated");
        }
        String iidxId = (String) auth.getPrincipal();
        return userRepository.findByIidxId(iidxId)
                .orElseThrow(() -> new RuntimeException("User not found"));
    }

    /** 投票 API のリクエストボディ。 */
    public record VoteRequest(String title, String difficultyName, String optionType) {
    }
}
