package com.beatseeker.backend.service;

import com.beatseeker.backend.entity.CompetitionMatch;
import com.beatseeker.backend.entity.CompetitionParticipant;
import com.beatseeker.backend.entity.CompetitionPick;
import com.beatseeker.backend.entity.CompetitionStrategyUse;
import com.beatseeker.backend.repository.CompetitionPickRepository;
import com.beatseeker.backend.repository.CompetitionStrategyUseRepository;
import org.springframework.stereotype.Service;

/**
 * 【Service の役割】 1 戦で「実際に演奏された 2 曲」を解決する単一実装。
 *
 * <p>1 戦 = 2 曲制を {@code song1 = A 側が演奏する曲} / {@code song2 = B 側が演奏する曲} と定義し、
 * 次の優先順で決める (運営画面の結果記録 UI が使う導出ロジックと同じ規則):
 * <ol>
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

    /** 演奏曲 1 枠 (管理番号 + タイトル)。どちらも解決できなければ null が入る。 */
    public record PlayedSong(Integer strategyId, String title) {}

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

        // 発動側に抽選結果が入る: A が発動 → B の曲が置き換わる (= song2)、B が発動 → song1。
        CompetitionStrategyUse suA = drawnStrategyOf(match, pa);
        CompetitionStrategyUse suB = drawnStrategyOf(match, pb);

        return new PlayedSongs(
                resolveSide(suB, pickOf(match, pa), match.getSong1StrategyId(), match.getSong1Title()),
                resolveSide(suA, pickOf(match, pb), match.getSong2StrategyId(), match.getSong2Title()));
    }

    /**
     * 【メソッドの役割】 演奏曲 1 枠を「抽選曲 → 自選曲 → 記録値」の優先順で解決する。
     *
     * @param opponentStrategy 相手が発動した抽選済み StrategyCard (無ければ null)
     * @param ownPick          本人の自選曲 (未提出なら null)
     * @param recordedId       記録済みの管理番号 (フォールバック)
     * @param recordedTitle    記録済みの曲名 (フォールバック)
     */
    private PlayedSong resolveSide(CompetitionStrategyUse opponentStrategy, CompetitionPick ownPick,
                                   Integer recordedId, String recordedTitle) {
        if (opponentStrategy != null) {
            return new PlayedSong(opponentStrategy.getResultSongStrategyId(), opponentStrategy.getResultSongTitle());
        }
        if (ownPick != null) {
            return new PlayedSong(ownPick.getSongStrategyId(), ownPick.getSongTitle());
        }
        return new PlayedSong(recordedId, recordedTitle);
    }

    /**
     * 【メソッドの役割】 その参加者が発動し、かつ抽選まで済んでいる StrategyCard を返す。
     *
     * <p>「発動予定にしただけで抽選前」(= Reveal 未生成) の場合は null を返し、自選曲へフォールバックさせる。
     */
    private CompetitionStrategyUse drawnStrategyOf(CompetitionMatch match, CompetitionParticipant participant) {
        if (participant == null) return null;
        return strategyUseRepository.findByMatchAndUsedByParticipant(match, participant)
                .filter(su -> Boolean.TRUE.equals(su.getEnabled()))
                .filter(su -> su.getResultSongStrategyId() != null)
                .orElse(null);
    }

    /** 【メソッドの役割】 その参加者の自選曲を返す (未提出なら null)。 */
    private CompetitionPick pickOf(CompetitionMatch match, CompetitionParticipant participant) {
        if (participant == null) return null;
        return pickRepository.findByMatchAndParticipant(match, participant).orElse(null);
    }
}
