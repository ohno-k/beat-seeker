package com.beatseeker.backend.service;

import com.beatseeker.backend.entity.CompetitionMatch;
import com.beatseeker.backend.entity.CompetitionParticipant;
import com.beatseeker.backend.entity.CompetitionPick;
import com.beatseeker.backend.entity.CompetitionStrategyUse;
import com.beatseeker.backend.repository.CompetitionPickRepository;
import com.beatseeker.backend.repository.CompetitionStrategyUseRepository;
import org.springframework.stereotype.Service;

import java.util.Objects;
import java.util.Optional;

/**
 * 【Service の役割】 1 戦で「実際に演奏された 2 曲」を解決する単一実装。
 *
 * <p>1 戦 = 2 曲制を {@code song1 = A 側が演奏する曲} / {@code song2 = B 側が演奏する曲} と定義し、
 * 次の優先順で決める (運営画面の結果記録 UI が使う導出ロジックと同じ規則):
 * <ol>
 *   <li>運営が結果記録 UI で<b>手動指定</b>した曲 ({@code songXManual} が true の枠)</li>
 *   <li>両者が StrategyCard を発動していれば<b>相殺</b>となり、双方とも自選曲 (下記 4.)</li>
 *   <li>相手が StrategyCard を発動していれば、その抽選曲 (発動 = 相手の曲をランダム化)</li>
 *   <li>発動が無ければ本人の自選曲</li>
 *   <li>どちらも引けなければ {@code competition_matches} に記録済みの曲 (フォールバック)</li>
 * </ol>
 *
 * <p>記録済みの {@code song1_title} / {@code song2_title} は、運営が結果を保存した時点の
 * <b>スナップショット</b>でしかない。StrategyCard の抽選はサーバの遅延抽選 (Reveal データ生成時に確定)
 * なので、「結果を先に記録 → あとから発動が確定」の順になると、記録済みの曲名は自選曲のまま古くなる。
 * 曲名は運営が手入力するものではなく常にこの規則で導出されるため、公開系の画面は記録値をそのまま出さず
 * ここで解決し直す。
 */
@Service
public class CompetitionPlayedSongService {

    /**
     * 演奏曲 1 枠 (管理番号 + タイトル)。どちらも解決できなければ null が入る。
     *
     * <p>{@code replacedByStrategy} は「相手の StrategyCard 発動でこの枠がランダム化されたか」。
     * {@code original*} はその発動前の自選曲で、サマリー画面が取り消し線で見せるのに使う。
     *
     * <p>この 2 つは連動しない: 発動はあったが元の自選曲を特定できない
     * (提出が取り消された / 抽選が元と同じ曲を引いた) 場合、{@code replacedByStrategy} は true のまま
     * {@code original*} だけ null になる。画面は前者で ⚡ を、後者で取り消し線を出し分ける。
     */
    public record PlayedSong(Integer strategyId, String title,
                             Integer originalStrategyId, String originalTitle,
                             boolean replacedByStrategy) {}

    /** 1 戦ぶんの演奏曲 (song1 = A 側が演奏した曲 / song2 = B 側が演奏した曲)。 */
    public record PlayedSongs(PlayedSong song1, PlayedSong song2) {}

    private final CompetitionPickRepository pickRepository;
    private final CompetitionStrategyUseRepository strategyUseRepository;

    /**
     * 【コンストラクタ】 Spring が依存を注入する。
     */
    public CompetitionPlayedSongService(CompetitionPickRepository pickRepository,
                                        CompetitionStrategyUseRepository strategyUseRepository) {
        this.pickRepository = pickRepository;
        this.strategyUseRepository = strategyUseRepository;
    }

    /**
     * 【メソッドの役割】 1 戦の演奏曲 2 枠を解決する。
     *
     * @param match 対象の試合
     * @return song1 (A 側演奏) / song2 (B 側演奏)
     */
    public PlayedSongs resolve(CompetitionMatch match) {
        CompetitionParticipant pa = match.getPlayerA();
        CompetitionParticipant pb = match.getPlayerB();

        Optional<CompetitionStrategyUse> rawA = strategyUseOf(match, pa);
        Optional<CompetitionStrategyUse> rawB = strategyUseOf(match, pb);

        // 相殺: 両者が発動していれば双方の StrategyCard は打ち消し合い、両者とも自選曲を演奏する。
        // 判定は「抽選済みか」ではなく enabled で行う (片側の抽選が保留中でも発動の事実で相殺は成立する)。
        boolean canceled = isEnabled(rawA) && isEnabled(rawB);

        // 発動側に抽選結果が入る: A が発動 → B の曲が置き換わる (= song2)、B が発動 → song1。
        CompetitionStrategyUse suA = canceled ? null : drawnOf(rawA);
        CompetitionStrategyUse suB = canceled ? null : drawnOf(rawB);

        // 「発動があったか」は抽選済みかとは別に見る。サーバの遅延抽選が確定する前に運営が結果を
        // 記録した試合では、抽選曲が手入力 (song*Manual) で入り resultSong* が空のままになる。
        // 差し替えの表示までサーバ抽選に依存させると、そういう試合で由来が失われるため enabled で判定する。
        boolean aActivated = !canceled && isEnabled(rawA); // B の枠 (song2) を飛ばした発動
        boolean bActivated = !canceled && isEnabled(rawB); // A の枠 (song1) を飛ばした発動

        return new PlayedSongs(
                resolveSide(Boolean.TRUE.equals(match.getSong1Manual()), suB, bActivated, pickOf(match, pa),
                        match.getSong1StrategyId(), match.getSong1Title()),
                resolveSide(Boolean.TRUE.equals(match.getSong2Manual()), suA, aActivated, pickOf(match, pb),
                        match.getSong2StrategyId(), match.getSong2Title()));
    }

    /**
     * 【メソッドの役割】 その試合の StrategyCard が相殺状態か (= A/B 双方が発動している) を返す。
     *
     * <p>相殺した試合では両者とも自選曲を演奏する。抽選済みかどうかは見ず {@code enabled} だけで判定するので、
     * 片側の抽選が保留 (相手の自選曲未提出など) でも「両者が発動した」時点で相殺が成立する。
     *
     * <p>相殺は<b>曲の解決結果</b>だけを打ち消す。StrategyCard の使用回数
     * ({@code strategyUsedMatchupCount}) は発動した事実に基づくので、相殺しても消費されたままになる。
     *
     * @param match 対象の試合
     * @return 両者が発動していれば true
     */
    public boolean isStrategyCanceled(CompetitionMatch match) {
        return isEnabled(strategyUseOf(match, match.getPlayerA()))
                && isEnabled(strategyUseOf(match, match.getPlayerB()));
    }

    /** 【メソッドの役割】 その参加者の StrategyCard 意思決定レコードを引く (未決定なら empty)。 */
    private Optional<CompetitionStrategyUse> strategyUseOf(CompetitionMatch match, CompetitionParticipant participant) {
        if (participant == null) return Optional.empty();
        return strategyUseRepository.findByMatchAndUsedByParticipant(match, participant);
    }

    /** 【メソッドの役割】 発動予定になっているか (抽選済みかは問わない)。 */
    private boolean isEnabled(Optional<CompetitionStrategyUse> su) {
        return su.map(x -> Boolean.TRUE.equals(x.getEnabled())).orElse(false);
    }

    /**
     * 【メソッドの役割】 演奏曲 1 枠を「手動指定 → 抽選曲 → 自選曲 → 記録値」の優先順で解決し、
     * あわせて「StrategyCard で差し替えられた枠か / 差し替え前の自選曲は何か」を判定する。
     *
     * <p>差し替えの判定は<b>演奏曲の決まり方とは独立</b>に行い、{@code opponentActivated}
     * (相手が発動したか) だけを見る。理由は 2 つ:
     * <ul>
     *   <li>運営が結果記録 UI で曲を選び直した枠 ({@code manual}) でも、
     *       「相手の発動で自選曲が飛ばされた」事実は変わらない</li>
     *   <li>サーバの遅延抽選が確定する前に結果を記録した試合では、抽選曲が手入力で入り
     *       {@code opponentStrategy} (抽選済みレコード) が null のままになる。
     *       抽選済みかどうかを条件にすると、そういう試合で差し替えの記録が失われる</li>
     * </ul>
     *
     * <p>実際に演奏された曲が自選曲と同じ場合は差し替え無しとして扱う。発動はしたが置き換えが
     * まだ確定していない (= 自選曲のまま) 状態を「差し替え済み」と表示しないため。
     * 自選曲を特定できない枠 (提出取り消し・起用差し替え後) は、差し替えありとしつつ
     * {@code original*} だけ null にする。
     *
     * @param manual            運営が結果記録 UI で曲を手動指定した枠か。true なら演奏曲は記録値
     * @param opponentStrategy  相手が発動した<b>抽選済み</b> StrategyCard (未抽選・相殺なら null)
     * @param opponentActivated 相手が発動したか (抽選済みかは問わない。相殺時は false)
     * @param ownPick           本人の自選曲 (未提出なら null)
     * @param recordedId        記録済みの管理番号 (フォールバック)
     * @param recordedTitle     記録済みの曲名 (フォールバック)
     */
    private PlayedSong resolveSide(boolean manual, CompetitionStrategyUse opponentStrategy,
                                   boolean opponentActivated, CompetitionPick ownPick,
                                   Integer recordedId, String recordedTitle) {
        // ── 1. 実際に演奏された曲 ───────────────────────────
        Integer playedId;
        String playedTitle;
        if (manual) {
            // 手動指定は運営の明示的な意思なので、自選曲でも抽選曲でも上書きしない。
            playedId = recordedId;
            playedTitle = recordedTitle;
        } else if (opponentStrategy != null) {
            playedId = opponentStrategy.getResultSongStrategyId();
            playedTitle = opponentStrategy.getResultSongTitle();
        } else if (ownPick != null) {
            playedId = ownPick.getSongStrategyId();
            playedTitle = ownPick.getSongTitle();
        } else {
            playedId = recordedId;
            playedTitle = recordedTitle;
        }

        // ── 2. 差し替えの有無と、差し替え前の自選曲 ──────────
        if (!opponentActivated) {
            return new PlayedSong(playedId, playedTitle, null, null, false);
        }
        if (ownPick == null) {
            // 発動はあったが自選曲を特定できない枠 (提出取り消し・起用差し替え後など)。
            // 何と差し替わったかは出せないので、発動があったことだけ伝える。
            return new PlayedSong(playedId, playedTitle, null, null, true);
        }
        if (Objects.equals(ownPick.getSongTitle(), playedTitle)) {
            // 演奏曲が自選曲のまま = 置き換えがまだ確定していない (or 抽選が同名の曲を引いた)。
            // 差し替え済みとして見せると誤解を招くので、発動フラグごと伏せる。
            return new PlayedSong(playedId, playedTitle, null, null, false);
        }
        return new PlayedSong(playedId, playedTitle,
                ownPick.getSongStrategyId(), ownPick.getSongTitle(), true);
    }

    /**
     * 【メソッドの役割】 発動済みかつ抽選まで済んでいる StrategyCard を返す。
     *
     * <p>「発動予定にしただけで抽選前」(= Reveal 未生成) の場合は null を返し、自選曲へフォールバックさせる。
     */
    private CompetitionStrategyUse drawnOf(Optional<CompetitionStrategyUse> su) {
        return su.filter(x -> Boolean.TRUE.equals(x.getEnabled()))
                .filter(x -> x.getResultSongStrategyId() != null)
                .orElse(null);
    }

    /** 【メソッドの役割】 その参加者の自選曲を返す (未提出なら null)。 */
    private CompetitionPick pickOf(CompetitionMatch match, CompetitionParticipant participant) {
        if (participant == null) return null;
        return pickRepository.findByMatchAndParticipant(match, participant).orElse(null);
    }
}
