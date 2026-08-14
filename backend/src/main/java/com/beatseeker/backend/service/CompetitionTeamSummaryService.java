package com.beatseeker.backend.service;

import com.beatseeker.backend.entity.Competition;
import com.beatseeker.backend.entity.CompetitionMatch;
import com.beatseeker.backend.entity.CompetitionMatchup;
import com.beatseeker.backend.entity.CompetitionParticipant;
import com.beatseeker.backend.entity.CompetitionTeam;
import com.beatseeker.backend.repository.CompetitionMatchRepository;
import com.beatseeker.backend.repository.CompetitionMatchupRepository;
import com.beatseeker.backend.repository.CompetitionParticipantRepository;
import com.beatseeker.backend.repository.CompetitionTeamRepository;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 【Service の役割】 team5 大会の「サマリー」(試合別 + 選手別の全結果一覧) を組み立てる。
 *
 * <p>順位表 ({@link CompetitionTeamStandingsService}) が「チーム単位の勝ち点」だけを返すのに対し、
 * こちらは大会終了後の振り返り用に <b>1 曲単位まで展開した生の結果</b>を返す。同じ試合データを
 * 2 通りの軸でピボットして返すのがこのサービスの仕事:
 * <ul>
 *   <li>{@code matchups} … 試合別。matchup → 試合 (先鋒/中堅/…) → 2 曲のスコアと勝敗</li>
 *   <li>{@code players} … 選手別。参加者 → その選手が出た全試合のスコアと勝敗 + 通算成績</li>
 * </ul>
 * どちらも同じ {@code matchId} を持つので、フロント側で相互に突き合わせできる。
 *
 * <p><b>引分の扱い</b>: 結果記録 ({@code CompetitionAdminController#setMatchResult}) の運営仕様に合わせ、
 * 同スコアの曲は「両者が勝った」として両側の獲得曲数に +1 する。したがって
 * {@code songsWon = songWins + songDraws} であり、戦ポイントも同じ基準で入る。表示のために
 * 勝ち / 引分 / 負けは別々にも数えて返す。
 *
 * <p>曲名は {@code competition_matches} の記録値をそのまま出さず
 * {@link CompetitionPlayedSongService} で解決し直す (StrategyCard の抽選が結果記録より後に確定した場合、
 * 記録値は自選曲のまま古くなるため)。
 */
@Service
public class CompetitionTeamSummaryService {

    /** 勝敗コード: A 側 (= 選手別では本人) の勝ち。 */
    private static final String RESULT_A = "A";
    /** 勝敗コード: B 側 (= 選手別では相手) の勝ち。 */
    private static final String RESULT_B = "B";
    /** 勝敗コード: 引分。 */
    private static final String RESULT_DRAW = "D";

    private final CompetitionTeamRepository teamRepository;
    private final CompetitionParticipantRepository participantRepository;
    private final CompetitionMatchupRepository matchupRepository;
    private final CompetitionMatchRepository matchRepository;
    private final CompetitionPlayedSongService playedSongService;

    /**
     * 【コンストラクタ】 Spring が依存を注入する。
     */
    public CompetitionTeamSummaryService(CompetitionTeamRepository teamRepository,
                                         CompetitionParticipantRepository participantRepository,
                                         CompetitionMatchupRepository matchupRepository,
                                         CompetitionMatchRepository matchRepository,
                                         CompetitionPlayedSongService playedSongService) {
        this.teamRepository = teamRepository;
        this.participantRepository = participantRepository;
        this.matchupRepository = matchupRepository;
        this.matchRepository = matchRepository;
        this.playedSongService = playedSongService;
    }

    /**
     * 【メソッドの役割】 大会 1 件のサマリー (試合別 + 選手別) を組み立てる。
     *
     * <p>{@code publicView} は「ログイン不要の公開 URL 用に伏せ情報を落とすか」のスイッチ。
     * 公開ページ ({@code CompetitionPublicSummaryController}) はサマリーを誰でも読めるようにするが、
     * 観戦 URL ({@code CompetitionSpectatorController}) が守っている staged reveal を壊してはいけないので、
     * 同じ規則でマスクする:
     * <ul>
     *   <li>運営が「設定済み」にしていない matchup は返さない</li>
     *   <li>起用 (選手名) は、その matchup のラインアップ公開日時を過ぎているときだけ返す</li>
     *   <li>結果は記録済みのときだけ返す (これは publicView に関わらず共通)</li>
     *   <li>選手別セクションは「起用公開済み かつ 結果記録済み」の試合だけを積む</li>
     * </ul>
     * 運営画面から開いた場合は {@code publicView=false} で全部見える。
     *
     * @param comp       対象大会 (team5 前提)
     * @param publicView 公開 URL 用にマスクするなら true
     * @return {@code competition} / {@code teams} / {@code matchups} / {@code players} を含むレスポンス Map
     */
    public Map<String, Object> compute(Competition comp, boolean publicView) {
        // 選手別の集計を貯める箱。matchups を舐めながら同時に埋めていく
        // (試合別と選手別は同じ試合データの別ピボットなので、走査は 1 回で済む)。
        Map<Long, PlayerAgg> playerAggs = new LinkedHashMap<>();
        List<CompetitionTeam> teams = teamRepository.findByCompetitionOrderByTeamOrderAsc(comp);
        for (CompetitionTeam team : teams) {
            for (CompetitionParticipant p : participantRepository.findByTeamOrderByCreatedAtAsc(team)) {
                playerAggs.put(p.getId(), new PlayerAgg(p, team));
            }
        }

        List<Map<String, Object>> matchupMaps = new ArrayList<>();
        for (CompetitionMatchup mu : matchupRepository.findByCompetitionOrderByMatchupOrderAsc(comp)) {
            // 未設定 matchup は運営がまだ実施対象にしていない枠。公開 URL には出さない。
            if (publicView && !Boolean.TRUE.equals(mu.getConfigured())) continue;
            // 起用の公開は日時到達で自動 (予選と決勝で別日時)。観戦 URL と同じ判定を使う。
            boolean lineupPublished = !publicView
                    || comp.isLineupPublishedFor(Boolean.TRUE.equals(mu.getIsFinals()));
            matchupMaps.add(buildMatchup(mu, playerAggs, lineupPublished, publicView));
        }

        Map<String, Object> root = new LinkedHashMap<>();
        root.put("competition", competitionMap(comp));
        // 公開ページ側で「未公開の起用は伏せています」と断るためのフラグ。
        root.put("publicView", publicView);
        List<Map<String, Object>> teamMaps = new ArrayList<>();
        for (CompetitionTeam t : teams) teamMaps.add(teamMap(t));
        root.put("teams", teamMaps);
        root.put("matchups", matchupMaps);

        List<Map<String, Object>> playerMaps = new ArrayList<>();
        for (PlayerAgg agg : playerAggs.values()) playerMaps.add(agg.toMap());
        root.put("players", playerMaps);
        return root;
    }

    // ── 試合別 (matchup → match → song) ─────────────────────

    /**
     * 【メソッドの役割】 1 matchup ぶんの試合別エントリを組み立て、同時に選手別集計へも反映する。
     *
     * <p>matchup の勝敗は順位表と同じ「戦ポイント合計の大小」で決める
     * ({@link CompetitionTeamStandingsService} と同じ規則)。未記録の試合が 1 つでもあれば
     * {@code recorded=false} とし、勝敗は null で返して画面側に「集計中」を出させる。
     *
     * @param lineupPublished 起用 (選手名) を出してよいか。false なら選手名を伏せ、選手別にも積まない
     * @param publicView      公開 URL 用のマスクを掛けるか (未記録の試合の曲名を伏せる判定に使う)
     */
    private Map<String, Object> buildMatchup(CompetitionMatchup mu, Map<Long, PlayerAgg> playerAggs,
                                             boolean lineupPublished, boolean publicView) {
        boolean isFinals = Boolean.TRUE.equals(mu.getIsFinals());
        List<CompetitionMatch> matches = matchRepository.findByMatchupOrderByIdAsc(mu);
        matches.sort(Comparator.comparingInt(m -> CompetitionMatchKinds.order(m.getMatchKind())));

        int aPoints = 0, bPoints = 0;
        int aMatchWins = 0, bMatchWins = 0, matchDraws = 0;
        boolean allRecorded = !matches.isEmpty();

        List<Map<String, Object>> matchMaps = new ArrayList<>();
        for (CompetitionMatch m : matches) {
            MatchResult r = buildMatch(m, mu, isFinals, playerAggs, lineupPublished, publicView);
            matchMaps.add(r.map());
            if (!r.recorded()) {
                allRecorded = false;
                continue;
            }
            aPoints += r.aPoints();
            bPoints += r.bPoints();
            if (RESULT_A.equals(r.result())) aMatchWins++;
            else if (RESULT_B.equals(r.result())) bMatchWins++;
            else matchDraws++;
        }

        Map<String, Object> mum = new LinkedHashMap<>();
        mum.put("matchupId", mu.getId());
        mum.put("matchupOrder", mu.getMatchupOrder());
        mum.put("isFinals", isFinals);
        mum.put("configured", Boolean.TRUE.equals(mu.getConfigured()));
        mum.put("teamAId", mu.getTeamA() != null ? mu.getTeamA().getId() : null);
        mum.put("teamAName", mu.getTeamA() != null ? mu.getTeamA().getTeamName() : null);
        mum.put("teamBId", mu.getTeamB() != null ? mu.getTeamB().getId() : null);
        mum.put("teamBName", mu.getTeamB() != null ? mu.getTeamB().getTeamName() : null);
        mum.put("aPoints", aPoints);
        mum.put("bPoints", bPoints);
        mum.put("aMatchWins", aMatchWins);
        mum.put("bMatchWins", bMatchWins);
        mum.put("matchDraws", matchDraws);
        mum.put("recorded", allRecorded);
        mum.put("result", allRecorded ? compare(aPoints, bPoints) : null);
        mum.put("matches", matchMaps);
        return mum;
    }

    /** 1 試合の組み立て結果。matchup 集計に必要な値を map と一緒に持ち回る。 */
    private record MatchResult(Map<String, Object> map, boolean recorded,
                               int aPoints, int bPoints, String result) {}

    /**
     * 【メソッドの役割】 1 試合ぶんの試合別エントリを組み立て、両サイドの選手別集計へも反映する。
     *
     * @param m               対象試合
     * @param mu              所属 matchup (相手チーム名の解決に使う)
     * @param isFinals        決勝の試合か (戦ポイント表が予選と異なる)
     * @param playerAggs      選手別集計の箱 (この試合ぶんを追記する)
     * @param lineupPublished 起用 (選手名) を出してよいか
     */
    private MatchResult buildMatch(CompetitionMatch m, CompetitionMatchup mu, boolean isFinals,
                                   Map<Long, PlayerAgg> playerAggs, boolean lineupPublished, boolean publicView) {
        int pointsPerSong = CompetitionMatchKinds.pointsPerSong(m.getMatchKind(), isFinals);
        CompetitionPlayedSongService.PlayedSongs played = playedSongService.resolve(m);
        boolean recorded = m.getResultRecordedAt() != null;

        // 曲名を出してよいか。まだ結果が記録されていない試合の曲は、公開 URL では伏せる:
        // 未記録の枠の曲名は「これから演奏される自選曲」そのものなので、出すと Song Reveal を先取りしてしまう
        // (観戦 URL も結果記録済みの試合しか曲名を返さない)。運営画面では常に見せる。
        boolean revealSongs = !publicView || recorded;

        // 1 戦 = 2 曲。song1 = A 側の自選 (or 抽選) 曲、song2 = B 側の曲。両者が両方を演奏する。
        // originalTitle は相手の StrategyCard で差し替えられた枠にだけ入る (= 差し替え前の自選曲)。
        List<SongLine> songs = List.of(
                songLine(1, played.song1(), revealSongs, m.getSong1ScoreA(), m.getSong1ScoreB()),
                songLine(2, played.song2(), revealSongs, m.getSong2ScoreA(), m.getSong2ScoreB()));

        // 獲得曲数は「引分も両者の勝ち」で数える (結果記録の運営仕様と同じ)。
        int aSongsWon = 0, bSongsWon = 0;
        int aSongWins = 0, bSongWins = 0, songDraws = 0;
        List<Map<String, Object>> songMaps = new ArrayList<>();
        for (SongLine s : songs) {
            songMaps.add(s.toMap());
            if (!s.hasScores()) continue;
            String w = s.winner();
            if (RESULT_A.equals(w)) { aSongsWon++; aSongWins++; }
            else if (RESULT_B.equals(w)) { bSongsWon++; bSongWins++; }
            else { aSongsWon++; bSongsWon++; songDraws++; }
        }

        int aPoints = aSongsWon * pointsPerSong;
        int bPoints = bSongsWon * pointsPerSong;
        String result = recorded ? compare(aSongsWon, bSongsWon) : null;

        CompetitionParticipant pa = m.getPlayerA();
        CompetitionParticipant pb = m.getPlayerB();

        Map<String, Object> map = new LinkedHashMap<>();
        map.put("matchId", m.getId());
        map.put("matchKind", m.getMatchKind());
        map.put("matchKindLabel", CompetitionMatchKinds.label(m.getMatchKind()));
        map.put("requiredGenre", m.getRequiredGenre());
        map.put("pointsPerSong", pointsPerSong);
        // 起用が未公開の間は選手名も ID も出さない (公開 URL 用のマスク。運営画面では常に公開扱い)。
        map.put("playerAId", (lineupPublished && pa != null) ? pa.getId() : null);
        map.put("playerAName", (lineupPublished && pa != null) ? pa.getDisplayName() : null);
        map.put("playerBId", (lineupPublished && pb != null) ? pb.getId() : null);
        map.put("playerBName", (lineupPublished && pb != null) ? pb.getDisplayName() : null);
        map.put("lineupPublished", lineupPublished);
        map.put("recorded", recorded);
        map.put("resultRecordedAt", m.getResultRecordedAt());
        map.put("songs", songMaps);
        map.put("aSongsWon", aSongsWon);
        map.put("bSongsWon", bSongsWon);
        map.put("aSongWins", aSongWins);
        map.put("bSongWins", bSongWins);
        map.put("songDraws", songDraws);
        map.put("aPoints", aPoints);
        map.put("bPoints", bPoints);
        map.put("result", result);

        // 選手別ピボット: 両サイドの参加者に「自分視点」のエントリを 1 件ずつ足す。
        // 積むのは結果記録済みの試合だけ。さらに公開 URL では起用が公開済みの試合に限る
        // (未公開の起用を選手別から逆算できてしまうと試合別のマスクが意味を失うため)。
        if (recorded && lineupPublished) {
            addPlayerEntry(playerAggs, pa, m, mu, isFinals, true, songs, aSongsWon, bSongsWon, aPoints, bPoints, result);
            addPlayerEntry(playerAggs, pb, m, mu, isFinals, false, songs, bSongsWon, aSongsWon, bPoints, aPoints, result);
        }

        return new MatchResult(map, recorded, aPoints, bPoints, result);
    }

    // ── 選手別 (participant → 出場試合) ─────────────────────

    /**
     * 【メソッドの役割】 1 試合ぶんの結果を、指定サイドの選手の視点 (自分のスコア / 相手のスコア) に
     * 変換して選手別集計へ追記する。
     *
     * @param isSideA     その選手が A 側か。曲スコアの自分/相手の割り当てに使う
     * @param ownSongsWon その選手が取った曲数 (引分含む)
     * @param result      試合の勝敗コード (A/B/D)。A 側視点で入っているのでサイドに合わせて読み替える
     */
    private void addPlayerEntry(Map<Long, PlayerAgg> playerAggs, CompetitionParticipant player,
                                CompetitionMatch m, CompetitionMatchup mu, boolean isFinals, boolean isSideA,
                                List<SongLine> songs, int ownSongsWon, int oppSongsWon,
                                int ownPoints, int oppPoints, String result) {
        if (player == null) return;
        PlayerAgg agg = playerAggs.get(player.getId());
        if (agg == null) return; // 参加者が削除済み等 (通常は起こらない)

        CompetitionParticipant opponent = isSideA ? m.getPlayerB() : m.getPlayerA();
        CompetitionTeam oppTeam = isSideA ? mu.getTeamB() : mu.getTeamA();

        // 試合の勝敗を本人視点の win/lose/draw へ読み替える。
        String outcome;
        if (RESULT_DRAW.equals(result)) outcome = "draw";
        else if ((RESULT_A.equals(result)) == isSideA) outcome = "win";
        else outcome = "lose";

        int songWins = 0, songLosses = 0, songDraws = 0;
        List<Map<String, Object>> songMaps = new ArrayList<>();
        for (SongLine s : songs) {
            songMaps.add(s.toOwnMap(isSideA));
            if (!s.hasScores()) continue;
            String w = s.winner();
            if (RESULT_DRAW.equals(w)) songDraws++;
            else if (RESULT_A.equals(w) == isSideA) songWins++;
            else songLosses++;
        }

        Map<String, Object> e = new LinkedHashMap<>();
        e.put("matchId", m.getId());
        e.put("matchupId", mu.getId());
        e.put("matchupOrder", mu.getMatchupOrder());
        e.put("isFinals", isFinals);
        e.put("matchKind", m.getMatchKind());
        e.put("matchKindLabel", CompetitionMatchKinds.label(m.getMatchKind()));
        e.put("requiredGenre", m.getRequiredGenre());
        e.put("side", isSideA ? "A" : "B");
        e.put("opponentId", opponent != null ? opponent.getId() : null);
        e.put("opponentName", opponent != null ? opponent.getDisplayName() : null);
        e.put("opponentTeamName", oppTeam != null ? oppTeam.getTeamName() : null);
        e.put("songs", songMaps);
        e.put("songsWon", ownSongsWon);
        e.put("opponentSongsWon", oppSongsWon);
        e.put("songWins", songWins);
        e.put("songLosses", songLosses);
        e.put("songDraws", songDraws);
        e.put("points", ownPoints);
        e.put("opponentPoints", oppPoints);
        e.put("result", outcome);
        agg.add(e, outcome, songWins, songLosses, songDraws, ownPoints, oppPoints);
    }

    /** 1 選手ぶんの出場試合リストと通算成績を貯める可変ホルダ。 */
    private static final class PlayerAgg {
        private final CompetitionParticipant participant;
        private final CompetitionTeam team;
        private final List<Map<String, Object>> matches = new ArrayList<>();
        private int wins, draws, losses;
        private int songWins, songLosses, songDraws;
        private int pointsFor, pointsAgainst;

        private PlayerAgg(CompetitionParticipant participant, CompetitionTeam team) {
            this.participant = participant;
            this.team = team;
        }

        /** 記録済みの 1 試合を通算成績へ加算する。 */
        private void add(Map<String, Object> entry, String outcome,
                         int sWins, int sLosses, int sDraws, int ptsFor, int ptsAgainst) {
            matches.add(entry);
            switch (outcome) {
                case "win" -> wins++;
                case "lose" -> losses++;
                default -> draws++;
            }
            songWins += sWins;
            songLosses += sLosses;
            songDraws += sDraws;
            pointsFor += ptsFor;
            pointsAgainst += ptsAgainst;
        }

        private Map<String, Object> toMap() {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("participantId", participant.getId());
            m.put("displayName", participant.getDisplayName());
            m.put("isTl", Boolean.TRUE.equals(participant.getIsTl()));
            m.put("teamId", team != null ? team.getId() : null);
            m.put("teamName", team != null ? team.getTeamName() : null);
            m.put("teamOrder", team != null ? team.getTeamOrder() : null);
            m.put("matchCount", matches.size());
            m.put("wins", wins);
            m.put("draws", draws);
            m.put("losses", losses);
            m.put("songWins", songWins);
            m.put("songLosses", songLosses);
            m.put("songDraws", songDraws);
            m.put("pointsFor", pointsFor);
            m.put("pointsAgainst", pointsAgainst);
            // 出場試合は matchup 順 → 戦種別順 (先鋒 → … → 大将) で並べる。
            matches.sort(Comparator
                    .comparingInt((Map<String, Object> e) -> (Integer) e.get("matchupOrder"))
                    .thenComparingInt(e -> CompetitionMatchKinds.order((String) e.get("matchKind"))));
            m.put("matches", matches);
            return m;
        }
    }

    // ── 内部ヘルパ ───────────────────────────────────────────

    /**
     * 【メソッドの役割】 解決済みの演奏曲 1 枠を {@link SongLine} に変換する。
     *
     * @param reveal 曲名を出してよいか。false なら実際の曲も差し替え前の曲も伏せる
     */
    private static SongLine songLine(int index, CompetitionPlayedSongService.PlayedSong song,
                                     boolean reveal, Integer scoreA, Integer scoreB) {
        return new SongLine(index,
                reveal ? song.title() : null,
                reveal ? song.originalTitle() : null,
                reveal && song.replacedByStrategy(),
                scoreA, scoreB);
    }

    /**
     * 1 曲ぶんのスコア行 (A/B の生スコア)。
     *
     * @param originalTitle      差し替えられる前の自選曲。差し替えが無い / 特定できない枠では null
     * @param replacedByStrategy 相手の StrategyCard 発動でこの枠がランダム化されたか
     */
    private record SongLine(int index, String title, String originalTitle, boolean replacedByStrategy,
                            Integer scoreA, Integer scoreB) {

        /** 両側のスコアが揃っているか (揃っていない曲は勝敗判定の対象外)。 */
        private boolean hasScores() {
            return scoreA != null && scoreB != null;
        }

        /** A/B/D の勝敗コード。スコア未入力なら null。 */
        private String winner() {
            if (!hasScores()) return null;
            return compare(scoreA, scoreB);
        }

        /** 試合別表示用 (A/B の並び)。 */
        private Map<String, Object> toMap() {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("index", index);
            m.put("title", title);
            // 差し替え前の自選曲。画面では取り消し線付きで実際の演奏曲と並べて出す。
            m.put("originalTitle", originalTitle);
            m.put("replacedByStrategy", replacedByStrategy);
            m.put("scoreA", scoreA);
            m.put("scoreB", scoreB);
            m.put("winner", winner());
            return m;
        }

        /** 選手別表示用 (自分 / 相手の並びに読み替え)。 */
        private Map<String, Object> toOwnMap(boolean isSideA) {
            Integer own = isSideA ? scoreA : scoreB;
            Integer opp = isSideA ? scoreB : scoreA;
            String w = winner();
            String outcome = null;
            if (w != null) {
                if (RESULT_DRAW.equals(w)) outcome = "draw";
                else outcome = (RESULT_A.equals(w) == isSideA) ? "win" : "lose";
            }
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("index", index);
            m.put("title", title);
            m.put("originalTitle", originalTitle);
            m.put("replacedByStrategy", replacedByStrategy);
            // 自選曲かどうか: song1 = A 側の曲 / song2 = B 側の曲。
            m.put("ownPick", isSideA ? index == 1 : index == 2);
            m.put("ownScore", own);
            m.put("opponentScore", opp);
            m.put("outcome", outcome);
            return m;
        }
    }

    /** a と b を比べて A 勝ち / B 勝ち / 引分 のコードを返す。 */
    private static String compare(int a, int b) {
        if (a > b) return RESULT_A;
        if (b > a) return RESULT_B;
        return RESULT_DRAW;
    }

    private Map<String, Object> competitionMap(Competition c) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", c.getId());
        m.put("name", c.getName());
        m.put("status", c.getStatus());
        m.put("format", c.getFormat());
        return m;
    }

    private Map<String, Object> teamMap(CompetitionTeam t) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", t.getId());
        m.put("teamName", t.getTeamName());
        m.put("teamOrder", t.getTeamOrder());
        return m;
    }
}
