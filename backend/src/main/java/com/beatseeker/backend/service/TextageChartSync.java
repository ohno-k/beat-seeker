package com.beatseeker.backend.service;

import com.beatseeker.backend.entity.SongDefinition;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 【クラスの役割】 textage 譜面同期の中身（対象譜面の洗い出し → ページの特定 → 取得・実行・照合・解析）。
 *
 * DB と HTTP には依存せず、ページの取得は {@link Fetcher} で受け取る（テストではキャッシュ済み HTML を渡す）。
 * DB への書き込みと実行記録は {@link TextageChartSyncService} が行う。
 *
 * 対象（{@link #plan}）:
 *  - 公開中（active）の SP 譜面（B/N/H/A/L）で、ノーツ数が登録済みのもの
 *  - プロファイルが無い（NEW）、または旧方式で解析された（LEGACY、{@code reanalyzeLegacy} のとき）もの
 *
 * ページの特定（{@link #execute}）:
 *  1. 譜面自身の textage、無ければ同じ曲の他の譜面の textage のページ
 *  2. 既存プロファイル（旧方式を含む）が指しているページ。楽曲マスタの textage が失われた譜面
 *     （2026-09-30 時点で ★11/12 A/L の 176 譜面、うち 173 はプロファイルに正しいページが残っていた）を曲名の推測なしで拾う
 *  3. ここまでで照合できなかった譜面は titletbl.js の候補ページ（{@link TextageTitleTable#candidates}）を順に試す
 *
 * 照合: ページの JS をその難易度のフラグで実行し、ページが宣言するノーツ数（{@code notes=}）が
 * 楽曲マスタのノーツ数と一致（差が 1% 以内、最低 2 ノーツまで許容）したときだけ採用する。
 * 一致しなければ別譜面（その難易度がページに無く既定の譜面が出た・リンク誤り・譜面変更）とみなして保留する。
 * キャッシュ済み 6,033 譜面では 5,806 譜面が一致し、外れた譜面は別難易度・別ページの値だった。
 *
 * 1 回に取得するページ数には上限（budget）があり、NEW を含む曲 → 譜面分析ページに出ていない譜面
 * （楽曲マスタに textage が無い ★11/12 A/L）がある曲 → まだ取りに行っていないページ → 最後に取りに行ったのが古いページ の順に回す。照合できなかった譜面のページは {@link Retry} の間隔を空けてから再試行する。
 */
final class TextageChartSync {

    /** SP の難易度コード。 */
    static final List<String> SP_CODES = List.of("1", "2", "3", "4", "10");

    /**
     * 曲を回す優先度: NEW を含む曲 → 譜面分析に出ていない譜面がある曲（{@link SongWork#hiddenRank}。★11/12 の A/L が先）→ その他。
     */
    private static final Comparator<SongWork> PRIORITY = Comparator
            .<SongWork>comparingInt(s -> s.hasNew() ? 0 : 1)
            .thenComparingInt(SongWork::hiddenRank);

    /** ページ（{@code 33/showtime.html}）の本文を返す。取得に失敗したら例外。 */
    @FunctionalInterface
    interface Fetcher {
        String fetch(String path) throws Exception;
    }

    enum Reason { NEW, LEGACY }

    /**
     * 処理対象の 1 譜面。
     *
     * @param title        楽曲マスタの曲名
     * @param difficulty   難易度コード
     * @param level        レベル
     * @param notes        公式ノーツ数
     * @param ownTextage   この譜面に登録済みの textage（無ければ null）
     * @param reason       NEW / LEGACY
     */
    record ChartWork(String title, String difficulty, Integer level, int notes, String ownTextage, Reason reason) {
        String label() {
            return title + " [" + BemaniwikiSongListParser.difficultyName(difficulty) + "]";
        }
    }

    /**
     * 処理対象の 1 曲（同じ曲名の譜面をまとめたもの）。
     *
     * @param knownPages   楽曲マスタに登録済みの textage のページ
     * @param profilePages 楽曲マスタには無いが、既存プロファイル（旧方式を含む）が指しているページ。
     *                     楽曲マスタの textage が失われた譜面の手がかりとして、titletbl.js の候補より先に試す。
     *                     旧方式にはリンク誤りもあるので、他の曲の titletbl 候補から外す対象（knownPages）には入れない
     */
    record SongWork(String title, String artist, String genre, String bpm, Set<String> knownPages, Set<String> profilePages,
                    List<ChartWork> charts) {
        boolean hasNew() {
            return charts.stream().anyMatch(c -> c.reason() == Reason.NEW);
        }

        /**
         * 楽曲マスタに textage が無いため譜面分析ページの一覧に出ていない譜面があるか（利用者に見えている欠けなので、
         * 旧方式の再解析だけの曲より先に回す）。
         *
         * 譜面分析ページの一覧は ANOTHER / LEGGENDARIA（全レベル）なので、N/H/B の欠けは優先しない。
         *
         * @return 0 = ★11/12 の A/L に欠けがある（利用者が最も多い）、1 = ☆10 以下の A/L に欠けがある、2 = A/L の欠けなし
         */
        int hiddenRank() {
            int rank = 2;
            for (ChartWork c : charts) {
                if (c.ownTextage() != null) continue;
                if (!"4".equals(c.difficulty()) && !"10".equals(c.difficulty())) continue;
                if (c.level() != null && (c.level() == 11 || c.level() == 12)) return 0;
                rank = 1;
            }
            return rank;
        }
    }

    /**
     * 洗い出しの結果。
     *
     * @param songs     処理対象の曲（NEW を含む曲が先）
     * @param upToDate  新方式で解析済みの譜面数
     * @param noNotes   ノーツ数が未登録で照合できないため対象外にした譜面数
     * @param linkRestores 新方式で解析済みなのに楽曲マスタの textage が空の譜面 → プロファイルの textage（曲名\0難易度 → textage）。
     *                     照合済みのページなので、取得せずにそのまま楽曲マスタへ書き戻す
     */
    record Plan(List<SongWork> songs, int upToDate, int noNotes, Map<String, String> linkRestores) {
        int newCount() {
            return (int) songs.stream().flatMap(s -> s.charts().stream()).filter(c -> c.reason() == Reason.NEW).count();
        }

        int legacyCount() {
            return (int) songs.stream().flatMap(s -> s.charts().stream()).filter(c -> c.reason() == Reason.LEGACY).count();
        }
    }

    /**
     * 再試行の間隔。
     *
     * @param newCharts    未解析の譜面（新曲・譜面追加）を含む曲。textage に載るのを待つので短め
     * @param legacyCharts 旧方式の再解析だけの曲。照合できない譜面（textage に無い難易度・譜面変更）は長く待つ
     */
    record Retry(Duration newCharts, Duration legacyCharts) {
        static final Retry NONE = new Retry(Duration.ZERO, Duration.ZERO);
    }

    /** 既存プロファイルのキー情報（{@code ChartTendencyProfileRepository#findAllKeys} の 1 行）。 */
    record ProfileKey(String textage, String title, String difficulty, String analyzerVersion) {}

    /**
     * 実行の結果。
     *
     * @param profiles        保存するプロファイル（{@link ChartTendencyAnalyzer#profile} + メタ情報）
     * @param textageUpdates  楽曲マスタに書き込む textage（曲名\0難易度 → textage）
     * @param added           新規に解析した譜面（表示用）
     * @param reanalyzed      旧方式から解析し直した譜面（表示用）
     * @param held            照合できず保留した譜面と理由
     * @param warnings        取得失敗など
     * @param resolutions     textage のページを新たに特定・訂正した記録
     * @param deferred        取得ページ数の上限で次回に回した曲
     * @param attempts        取りに行ったページ（またはページ未特定の曲）→ 結果の要約
     * @param pagesFetched    取得したページ数（titletbl.js を含む）
     * @param waiting         前回の試行から再試行の間隔が空いていないので今回は見送った曲の数
     */
    record Outcome(List<Map<String, Object>> profiles, Map<String, String> textageUpdates,
                   List<String> added, List<String> reanalyzed, List<String> held, List<String> warnings,
                   List<String> resolutions, List<String> deferred, Map<String, String> attempts, int pagesFetched,
                   int waiting) {}

    private TextageChartSync() {}

    // ── 洗い出し ─────────────────────────────────────────────

    /**
     * 【メソッドの役割】 公開中の楽曲マスタと既存プロファイルから、解析が必要な譜面を曲ごとに洗い出す。
     *
     * @param actives         active の楽曲マスタ
     * @param profiles        既存プロファイルのキー情報
     * @param analyzerVersion 現行の解析方式の版
     * @param reanalyzeLegacy 旧方式の譜面も対象にするか
     */
    static Plan plan(List<SongDefinition> actives, List<ProfileKey> profiles, String analyzerVersion, boolean reanalyzeLegacy) {
        Map<String, ProfileKey> byTextage = new HashMap<>();
        Map<String, ProfileKey> byTitleDiff = new HashMap<>();
        for (ProfileKey p : profiles) {
            byTextage.put(p.textage(), p);
            ProfileKey prev = byTitleDiff.get(p.title() + "\0" + p.difficulty());
            // 同じ (曲名, 難易度) に複数行あれば新方式の行を優先して見る
            if (prev == null || (prev.analyzerVersion() == null && p.analyzerVersion() != null)) {
                byTitleDiff.put(p.title() + "\0" + p.difficulty(), p);
            }
        }

        Map<String, List<SongDefinition>> byTitle = new LinkedHashMap<>();
        for (SongDefinition sd : actives) {
            if (!SP_CODES.contains(sd.getDifficulty())) continue;
            byTitle.computeIfAbsent(sd.getTitle(), t -> new ArrayList<>()).add(sd);
        }

        List<SongWork> songs = new ArrayList<>();
        Map<String, String> linkRestores = new LinkedHashMap<>();
        int upToDate = 0;
        int noNotes = 0;
        for (Map.Entry<String, List<SongDefinition>> e : byTitle.entrySet()) {
            String title = e.getKey();
            Set<String> knownPages = new LinkedHashSet<>();
            String artist = null;
            String genre = null;
            String bpm = null;
            for (SongDefinition sd : e.getValue()) {
                String page = pageOf(sd.getTextage());
                if (page != null) knownPages.add(page);
                if (artist == null) artist = sd.getArtist();
                if (genre == null) genre = sd.getGenre();
                if (bpm == null || bpm.isBlank()) bpm = sd.getBpm();
            }
            List<ChartWork> charts = new ArrayList<>();
            Set<String> profilePages = new LinkedHashSet<>();
            Set<String> seenDiffs = new HashSet<>();
            for (SongDefinition sd : e.getValue()) {
                if (!seenDiffs.add(sd.getDifficulty())) continue; // (曲名, 難易度) の重複行は先の 1 行だけ見る
                ProfileKey p = byTitleDiff.get(title + "\0" + sd.getDifficulty());
                if (p == null && sd.getTextage() != null) p = byTextage.get(sd.getTextage());
                String profilePage = p != null ? pageOf(p.textage()) : null;
                if (profilePage != null && !knownPages.contains(profilePage)) profilePages.add(profilePage);
                if (p != null && analyzerVersion.equals(p.analyzerVersion())) {
                    upToDate++;
                    if (blankToNull(sd.getTextage()) == null && pageOf(p.textage()) != null) {
                        linkRestores.put(title + "\0" + sd.getDifficulty(), p.textage());
                    }
                    continue;
                }
                Reason reason = p == null ? Reason.NEW : Reason.LEGACY;
                if (reason == Reason.LEGACY && !reanalyzeLegacy) continue;
                if (sd.getNotes() == null || sd.getNotes() <= 0) {
                    noNotes++;
                    continue;
                }
                charts.add(new ChartWork(title, sd.getDifficulty(), sd.getLevel(), sd.getNotes(), blankToNull(sd.getTextage()), reason));
            }
            if (!charts.isEmpty()) songs.add(new SongWork(title, artist, genre, bpm, knownPages, profilePages, charts));
        }
        // NEW を含む曲 → 譜面分析に出ていない譜面がある曲 → その他（安定ソートなので同順位はマスタの並び）
        songs.sort(PRIORITY);
        return new Plan(songs, upToDate, noNotes, linkRestores);
    }

    // ── 実行 ─────────────────────────────────────────────────

    /**
     * 【メソッドの役割】 洗い出した譜面について、ページを取得・実行・照合して解析する。
     *
     * @param plan          {@link #plan} の結果
     * @param fetcher       ページの取得
     * @param titleTableSrc titletbl.js の取得（必要になったときだけ 1 回呼ぶ）
     * @param budget        取得してよいページ数（titletbl.js を含む）
     * @param lastAttempts  ページ（またはページ未特定の曲 {@link #unresolvedKey}）→ 最後に取りに行った日時
     * @param retry         再試行の間隔。前回の試行からこれが経っていない曲は見送る
     *                      （textage に載っていない譜面のページを毎回取りに行かないため）
     * @param now           現在時刻（{@code lastAttempts} と同じ時刻系）
     * @param analyzerVersion プロファイルに記録する解析方式の版
     */
    static Outcome execute(Plan plan, Fetcher fetcher, Fetcher titleTableSrc, int budget,
                           Map<String, LocalDateTime> lastAttempts, Retry retry, LocalDateTime now, String analyzerVersion) {
        Ctx ctx = new Ctx(fetcher, budget, analyzerVersion);
        int waiting = 0;

        // 取りに行く順: NEW を含む曲 → 譜面分析に出ていない譜面がある曲 → 未取得 → 古い順
        List<SongWork> order = new ArrayList<>(plan.songs());
        order.sort(PRIORITY
                .thenComparing(s -> lastAttemptOf(s, lastAttempts), Comparator.nullsFirst(Comparator.naturalOrder())));

        // 曲ごとの既知ページは、titletbl の artist/genre 候補から除外する（他の曲のページを誤って拾わない）
        Set<String> pagesOfOtherSongs = new HashSet<>();
        for (SongWork s : plan.songs()) pagesOfOtherSongs.addAll(s.knownPages());

        TextageTitleTable table = null;
        boolean tableFailed = false;

        for (SongWork song : order) {
            LocalDateTime last = lastAttemptOf(song, lastAttempts);
            Duration interval = song.hasNew() ? retry.newCharts() : retry.legacyCharts();
            if (last != null && last.plus(interval).isAfter(now)) {
                waiting++;
                continue;
            }
            if (ctx.budget <= 0) {
                ctx.deferred.add(song.title() + "（" + song.charts().size() + " 譜面）");
                continue;
            }
            List<ChartWork> remaining = new ArrayList<>(song.charts());
            Set<String> tried = new HashSet<>();

            // 1. 登録済みの textage のページ（譜面自身 → 同じ曲の他の譜面）
            for (String page : song.knownPages()) {
                if (remaining.isEmpty() || ctx.budget <= 0) break;
                tried.add(page);
                remaining = ctx.tryPage(song, page, remaining, false, null);
            }

            // 2. 既存プロファイルが指しているページ（楽曲マスタの textage が失われた譜面の手がかり。照合は同じくノーツ数で行う）
            for (String page : song.profilePages()) {
                if (remaining.isEmpty() || ctx.budget <= 0) break;
                if (!tried.add(page)) continue;
                int before = remaining.size();
                remaining = ctx.tryPage(song, page, remaining, false, null);
                if (remaining.size() < before) {
                    ctx.resolutions.add(song.title() + " → " + page + "（既存プロファイルのページでノーツ数が一致）");
                }
            }

            // 3. titletbl.js の候補
            if (!remaining.isEmpty() && ctx.budget > 0) {
                if (table == null && !tableFailed) {
                    try {
                        ctx.budget--;
                        ctx.pagesFetched++;
                        table = TextageTitleTable.parse(titleTableSrc.fetch("titletbl.js"));
                    } catch (Exception ex) {
                        tableFailed = true;
                        ctx.warnings.add("titletbl.js の取得・解析に失敗したため、textage 未登録の曲のページを探せませんでした: " + ex.getMessage());
                    }
                }
                if (table != null) {
                    Set<String> excluded = new HashSet<>(pagesOfOtherSongs);
                    excluded.removeAll(song.knownPages());
                    List<TextageTitleTable.Candidate> candidates =
                            table.candidates(song.title(), song.artist(), song.genre(), excluded);
                    if (candidates.isEmpty() && song.knownPages().isEmpty()) {
                        ctx.attempts.put(unresolvedKey(song.title()), "titletbl.js に候補なし");
                    }
                    for (TextageTitleTable.Candidate c : candidates) {
                        if (remaining.isEmpty() || ctx.budget <= 0) break;
                        String page = c.entry().pagePath();
                        if (!tried.add(page)) continue;
                        remaining = ctx.tryPage(song, page, remaining, true, c.kind());
                    }
                    if (song.knownPages().isEmpty() && !candidates.isEmpty()) {
                        ctx.attempts.put(unresolvedKey(song.title()), "titletbl.js の候補 " + candidates.size() + " ページ、未照合 "
                                + remaining.size() + " 譜面");
                    }
                }
            }

            List<ChartWork> untried = new ArrayList<>();
            for (ChartWork c : remaining) {
                String why = ctx.lastMismatch.get(c);
                if (why != null) {
                    ctx.held.add(c.label() + "：" + why);
                } else if (ctx.budget <= 0) {
                    untried.add(c); // 上限でページを試せなかった → 次回
                } else {
                    ctx.held.add(c.label() + "：textage に該当するページが見つからない");
                }
            }
            if (!untried.isEmpty()) ctx.deferred.add(song.title() + "（" + untried.size() + " 譜面）");
        }

        return new Outcome(ctx.profiles, ctx.textageUpdates, ctx.added, ctx.reanalyzed, ctx.held, ctx.warnings,
                ctx.resolutions, ctx.deferred, ctx.attempts, ctx.pagesFetched, waiting);
    }

    /** 実行中の状態。 */
    private static final class Ctx {
        final Fetcher fetcher;
        final String analyzerVersion;
        final TextagePageRunner runner = new TextagePageRunner();
        int budget;
        int pagesFetched;
        final List<Map<String, Object>> profiles = new ArrayList<>();
        final Map<String, String> textageUpdates = new LinkedHashMap<>();
        final List<String> added = new ArrayList<>();
        final List<String> reanalyzed = new ArrayList<>();
        final List<String> held = new ArrayList<>();
        final List<String> warnings = new ArrayList<>();
        final List<String> resolutions = new ArrayList<>();
        final List<String> deferred = new ArrayList<>();
        final Map<String, String> attempts = new LinkedHashMap<>();
        final Map<ChartWork, String> lastMismatch = new HashMap<>();

        Ctx(Fetcher fetcher, int budget, String analyzerVersion) {
            this.fetcher = fetcher;
            this.budget = budget;
            this.analyzerVersion = analyzerVersion;
        }

        /**
         * 1 ページを取得して、残っている譜面を照合・解析する。
         *
         * @param fromTable titletbl の候補から来たページか（訂正・特定の記録を残す）
         * @param kind      titletbl の照合方法（ARTIST_GENRE なら 2 譜面以上の一致を要求）
         * @return まだ照合できていない譜面
         */
        List<ChartWork> tryPage(SongWork song, String page, List<ChartWork> charts, boolean fromTable,
                                TextageTitleTable.MatchKind kind) {
            budget--;
            pagesFetched++;
            String script;
            try {
                script = TextagePageRunner.extractScript(fetcher.fetch(page));
            } catch (Exception e) {
                attempts.put(page, "取得失敗: " + e.getMessage());
                warnings.add("textage のページを取得できませんでした: " + page + "（" + song.title() + "）: " + e.getMessage());
                return charts;
            }

            List<ChartWork> rest = new ArrayList<>();
            List<ChartWork> matched = new ArrayList<>();
            Map<ChartWork, TextagePageRunner.PageChart> data = new HashMap<>();
            for (ChartWork c : charts) {
                TextagePageRunner.Difficulty d = TextagePageRunner.Difficulty.ofCode(c.difficulty());
                try {
                    TextagePageRunner.PageChart pc = runner.run(script, d);
                    if (notesMatch(pc.notes(), c.notes()) && !pc.sp().isEmpty()) {
                        matched.add(c);
                        data.put(c, pc);
                    } else {
                        lastMismatch.put(c, "textage（" + page + "）のノーツ数 " + pc.notes() + " が登録値 " + c.notes() + " と一致しない");
                        rest.add(c);
                    }
                } catch (IllegalStateException e) {
                    lastMismatch.put(c, "textage（" + page + "）の JS を実行できない: " + e.getMessage());
                    rest.add(c);
                }
            }

            // artist/genre だけで拾った候補は、偶然の一致を避けるため 2 譜面以上（1 譜面しか無い曲はその 1 譜面）の一致を要求
            if (kind == TextageTitleTable.MatchKind.ARTIST_GENRE && matched.size() < Math.min(2, charts.size())) {
                attempts.put(page, "候補（ARTIST・GENRE 一致）だがノーツ数の一致が不足");
                rest.addAll(matched);
                return rest;
            }

            for (ChartWork c : matched) {
                TextagePageRunner.Difficulty d = TextagePageRunner.Difficulty.ofCode(c.difficulty());
                String textage = c.ownTextage() != null && page.equals(pageOf(c.ownTextage()))
                        ? c.ownTextage()
                        : page + "?1" + d.urlChar + "C00";
                profiles.add(toProfile(song, c, textage, ChartTendencyAnalyzer.profile(data.get(c), song.bpm()), analyzerVersion));
                if (!textage.equals(c.ownTextage())) {
                    textageUpdates.put(c.title() + "\0" + c.difficulty(), textage);
                    if (c.ownTextage() != null) {
                        resolutions.add(c.label() + "：textage のリンクを訂正 " + c.ownTextage() + " → " + textage);
                    }
                }
                (c.reason() == Reason.NEW ? added : reanalyzed).add(c.label() + " ★" + c.level() + " / " + c.notes() + " notes");
            }
            if (fromTable && !matched.isEmpty() && song.knownPages().isEmpty()) {
                resolutions.add(song.title() + " → " + page
                        + (kind == TextageTitleTable.MatchKind.ARTIST_GENRE ? "（ARTIST・GENRE・ノーツ数が一致）" : "（曲名が一致）"));
            }
            attempts.put(page, "解析 " + matched.size() + " / 不一致 " + rest.size() + " 譜面");
            return rest;
        }
    }

    /** chart_cache/profiles と同じ並びのプロファイル（textage・曲情報 → 解析値、notes は公式ノーツ数で上書き）。 */
    private static Map<String, Object> toProfile(SongWork song, ChartWork c, String textage,
                                                 Map<String, Object> analyzed, String analyzerVersion) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("textage", textage);
        m.put("analyzer_version", analyzerVersion);
        m.put("title", c.title());
        m.put("artist", song.artist() != null ? song.artist() : "");
        m.put("bpm_raw", song.bpm() != null ? song.bpm() : "");
        m.put("level", c.level());
        m.put("difficulty", c.difficulty());
        m.putAll(analyzed);
        m.put("notes", c.notes());
        return m;
    }

    /** ページのノーツ数が公式ノーツ数と一致するか（差が 1% 以内、最低 2 ノーツまで許容）。 */
    static boolean notesMatch(int pageNotes, int officialNotes) {
        if (pageNotes <= 0 || officialNotes <= 0) return false;
        return Math.abs(pageNotes - officialNotes) <= Math.max(2, officialNotes / 100);
    }

    /** textage（{@code 33/showtime.html?1AC00}）からページ（{@code 33/showtime.html}）を取り出す。 */
    static String pageOf(String textage) {
        if (textage == null || textage.isBlank()) return null;
        String t = textage.trim();
        int q = t.indexOf('?');
        String page = q >= 0 ? t.substring(0, q) : t;
        return page.matches("[0-9a-z]+/[^/?]+\\.html") ? page : null;
    }

    /** ページ未特定の曲の試行記録のキー（{@code TextagePageAttempt.page} は 128 文字まで）。 */
    static String unresolvedKey(String title) {
        String k = "?" + title;
        return k.length() > 128 ? k.substring(0, 128) : k;
    }

    private static LocalDateTime lastAttemptOf(SongWork s, Map<String, LocalDateTime> lastAttempts) {
        LocalDateTime latest = null;
        List<String> keys = new ArrayList<>(s.knownPages());
        keys.addAll(s.profilePages());
        if (keys.isEmpty()) keys.add(unresolvedKey(s.title()));
        for (String k : keys) {
            LocalDateTime t = lastAttempts.get(k);
            if (t == null) return null; // 1 ページでも未取得なら「未取得」扱い
            if (latest == null || t.isAfter(latest)) latest = t;
        }
        return latest;
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }
}
