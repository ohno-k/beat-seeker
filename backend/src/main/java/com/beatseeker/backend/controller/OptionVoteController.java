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
 * ユーザー各自が「自分がこの譜面をプレイするときはどのオプションを推奨するか」を投票し、
 * 閲覧者には投票数の集計が表示される。
 *
 * 【重要な仕様】 1P/2P 間の正規・ミラー対称性を補正する:
 *  - ある譜面について 1P の "REGULAR" と 2P の "MIRROR" は、鍵盤配置としては実質同じ。
 *  - そのためサーバー側では **常に 1P 視点** にノーマライズして保存し、
 *    閲覧者のプレイサイドに応じて表示時に再変換している。
 *
 * 主要エンドポイント:
 *  - {@code POST   /api/votes}   … 投票 or 更新（upsert）
 *  - {@code DELETE /api/votes}   … 自分の投票を取り消す
 *  - {@code GET    /api/votes}   … 楽曲の集計結果を取得（閲覧者視点で変換済み）
 */
@RestController
@RequestMapping("/api/votes")
public class OptionVoteController {

    /** 投票エンティティの永続化 Repository。 */
    private final OptionVoteRepository optionVoteRepository;
    /** 認証ユーザー解決用 Repository。 */
    private final UserRepository userRepository;

    /**
     * 【コンストラクタ】 Spring が DI で Repository を注入する。
     */
    public OptionVoteController(OptionVoteRepository optionVoteRepository, UserRepository userRepository) {
        this.optionVoteRepository = optionVoteRepository;
        this.userRepository = userRepository;
    }

    /**
     * 【メソッドの役割】 推奨オプションに投票する（既存票があれば更新）。
     *
     * 投票内容は 1P 視点にノーマライズしてから保存する（{@link #normalizeToFirstPlayer}）。
     * これにより 2P プレイヤーが "REGULAR" と選んでも DB には "MIRROR" として保存される。
     *
     * @param auth    認証情報
     * @param request {@code (title, difficultyName, optionType)} を含むリクエストボディ
     * @return 正規化後のオプション種別を含む成功メッセージ
     */
    @PostMapping
    @Transactional
    public ResponseEntity<Map<String, Object>> vote(
            Authentication auth,
            @RequestBody VoteRequest request) {

        User user = getUser(auth);

        // 投票者のプレイサイドを考慮して 1P 視点に正規化
        String normalizedOption = normalizeToFirstPlayer(request.optionType(), user.getPlaySide());

        // Upsert: 既存投票があれば更新、なければ新規作成
        Optional<OptionVote> existing = optionVoteRepository
                .findByUserAndTitleAndDifficultyName(user, request.title(), request.difficultyName());

        OptionVote vote;
        if (existing.isPresent()) {
            vote = existing.get();
            vote.setOptionType(normalizedOption);
            vote.setVotedAt(LocalDateTime.now());
        } else {
            vote = new OptionVote();
            vote.setUser(user);
            vote.setTitle(request.title());
            vote.setDifficultyName(request.difficultyName());
            vote.setOptionType(normalizedOption);
        }

        optionVoteRepository.save(vote);

        return ResponseEntity.ok(Map.of("message", "投票しました", "normalizedOption", normalizedOption));
    }

    /**
     * 【メソッドの役割】 楽曲に対する自分の投票を取り消す。
     *
     * 投票が存在しない場合も 200 を返す（冪等な挙動）。
     *
     * @param auth           認証情報
     * @param title          曲名
     * @param difficultyName 難易度名（例: "ANOTHER"）
     */
    @DeleteMapping
    @Transactional
    public ResponseEntity<Map<String, Object>> deleteVote(
            Authentication auth,
            @RequestParam String title,
            @RequestParam String difficultyName) {

        User user = getUser(auth);
        Optional<OptionVote> existing = optionVoteRepository
                .findByUserAndTitleAndDifficultyName(user, title, difficultyName);

        // 存在する場合のみ削除（なくてもエラーにしない）
        existing.ifPresent(optionVoteRepository::delete);

        return ResponseEntity.ok(Map.of("message", "投票を取り消しました"));
    }

    /**
     * 【メソッドの役割】 楽曲の投票集計を「閲覧者のプレイサイド視点」で返す。
     *
     * DB 上は 1P 視点で統一保存されているが、2P の閲覧者には REGULAR/MIRROR を
     * 入れ替えて返すことで、プレイヤーから見た「左右どちらの配置か」が
     * 一致するようにしている。
     *
     * @param title          曲名
     * @param difficultyName 難易度名
     * @param auth           認証情報（未ログインでも呼び出し可）
     * @return {@code {counts, totalVotes, myVote}} の Map
     */
    @GetMapping
    public ResponseEntity<Map<String, Object>> getVotes(
            @RequestParam String title,
            @RequestParam String difficultyName,
            Authentication auth) {

        List<OptionVote> votes = optionVoteRepository.findByTitleAndDifficultyName(title, difficultyName);

        // 閲覧者のプレイサイドを決定（未ログインなら 1P と仮定）
        String viewerSide = "1P";
        String myVote = null;
        if (auth != null && auth.isAuthenticated()) {
            try {
                User viewer = getUser(auth);
                viewerSide = viewer.getPlaySide() != null ? viewer.getPlaySide() : "1P";

                // 閲覧者自身の投票を探し、視点変換して返す
                Optional<OptionVote> myVoteOpt = optionVoteRepository
                        .findByUserAndTitleAndDifficultyName(viewer, title, difficultyName);
                if (myVoteOpt.isPresent()) {
                    // 保存値（1P 視点）を閲覧者視点に戻す
                    myVote = convertToViewerPerspective(myVoteOpt.get().getOptionType(), viewerSide);
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

        // 全投票を閲覧者視点に変換して集計
        for (OptionVote v : votes) {
            String displayed = convertToViewerPerspective(v.getOptionType(), viewerSide);
            counts.put(displayed, counts.getOrDefault(displayed, 0) + 1);
        }

        Map<String, Object> result = new HashMap<>();
        result.put("counts", counts);
        result.put("totalVotes", votes.size());
        result.put("myVote", myVote);

        return ResponseEntity.ok(result);
    }

    /**
     * 保存用にオプション値を「1P 視点」へ正規化する。
     *
     * 2P プレイヤーの REGULAR ↔ MIRROR を入れ替えて保存することで、
     * 譜面に対する物理配置（左右どちらの皿か）を全ユーザーで統一する。
     * RANDOM 系は左右対称の影響を受けないので変換しない。
     *
     * @param optionType 投票されたオプション値
     * @param playSide   投票者のプレイサイド（"1P" or "2P"）
     * @return 1P 視点のオプション値
     */
    private String normalizeToFirstPlayer(String optionType, String playSide) {
        if ("2P".equals(playSide)) {
            if ("REGULAR".equals(optionType))
                return "MIRROR";
            if ("MIRROR".equals(optionType))
                return "REGULAR";
        }
        return optionType;
    }

    /**
     * DB に保存された 1P 視点の値を「閲覧者視点」に戻す。
     * {@link #normalizeToFirstPlayer} の逆変換（実質同じ対称変換）。
     *
     * @param storedOption DB の保存値（1P 視点）
     * @param viewerSide   閲覧者のプレイサイド
     * @return 閲覧者視点の値
     */
    private String convertToViewerPerspective(String storedOption, String viewerSide) {
        if ("2P".equals(viewerSide)) {
            if ("REGULAR".equals(storedOption))
                return "MIRROR";
            if ("MIRROR".equals(storedOption))
                return "REGULAR";
        }
        return storedOption;
    }

    /**
     * 認証情報からユーザーエンティティを引く共通ヘルパ。
     *
     * @param auth 認証情報
     * @return ユーザーエンティティ
     */
    private User getUser(Authentication auth) {
        if (auth == null || !auth.isAuthenticated()) {
            throw new RuntimeException("Not authenticated");
        }
        String iidxId = (String) auth.getPrincipal();
        return userRepository.findByIidxId(iidxId)
                .orElseThrow(() -> new RuntimeException("User not found"));
    }

    /**
     * 【DTO】 投票 API のリクエストボディ。
     *
     * @param title          曲名
     * @param difficultyName 難易度名（例: "ANOTHER"）
     * @param optionType     投票するオプション種別（REGULAR/MIRROR/RANDOM/R-RANDOM/S-RANDOM）
     */
    public record VoteRequest(String title, String difficultyName, String optionType) {
    }
}
