package com.beatseeker.backend.service;

import com.beatseeker.backend.entity.SongDefinition;
import com.beatseeker.backend.service.BemaniwikiSongListParser.Chart;
import com.beatseeker.backend.service.BemaniwikiSongListParser.Song;

import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * 【クラスの役割】 bemaniwiki の曲名を、beat-seeker の公開中（active）楽曲マスタの曲名へ対応づける。
 *
 * 現実世界の概念: 楽曲マスタの曲名は公式スコア CSV の表記に合わせてある。公式 CSV は特殊文字を ASCII に
 * 置き換えるので（"ÆTHER" → "ATHER"、"焱影" → "火影"、"LOVE♡SHINE" → "LOVE SHINE"）、wiki の表記とは
 * 一致しない曲が旧曲だけで 35 曲ある（2026-09-17 時点）。これを別の曲として追加すると二重登録になるため、
 * 取り込み前に「同じ曲か」を判定する。
 *
 * 判定の順序:
 *  - 手順1: 完全一致
 *  - 手順2: 正規化一致（{@link BemaniwikiSongListParser#normalizeTitle}: 全角半角・大文字小文字・空白・波ダッシュ）。
 *           ただし "SHOOTING STAR"(小坂りゆ) と "Shooting Star"(ReGLOSS) のような同名異曲があるので、
 *           同じ正規化キーの曲が既存側・ページ側に複数ある場合と、寄せ先の既存曲がページの別の曲と
 *           完全一致している場合は断定しない（別曲にレベル・ノーツ数を上書きしないため）
 *  - 手順3: 指紋一致。ARTIST と GENRE が（正規化して）一致し、かつ同じ難易度のノーツ数が 2 譜面以上一致する
 *           既存曲がちょうど 1 曲だけある場合に、その曲とみなす
 *
 * 指紋一致を厳しくしている理由: ノーツ数だけなら別の曲どうしで 2 譜面一致する組が実データに 40 組以上ある
 * （Evans と crew と V は譜面そのものを共有している）。ARTIST と GENRE の一致を併せて要求し、さらに
 * 候補を「このページのどの曲とも 手順1・2 で対応していない既存曲」に限ることで誤対応を防ぐ。
 * 同じ既存曲に 2 つの wiki 曲が指紋一致した場合、後から来たほうは対応なしとして扱う。
 *
 * 依存: なし（Spring や DB に依存しない。ユニットテストで直接検証する）。
 */
final class WikiTitleResolver {

    /** 対応づけの根拠。 */
    enum Kind { EXACT, NORMALIZED, FINGERPRINT, NONE }

    /**
     * 対応づけの結果。
     *
     * @param dbTitle 楽曲マスタ側の曲名。{@link Kind#NONE} のときは wiki の曲名そのまま（新規曲として扱う）
     * @param kind    根拠
     */
    record Match(String dbTitle, Kind kind) {}

    /** 既存 1 曲分（全難易度をまとめたもの）。 */
    private static final class ActiveSong {
        final String title;
        String genre;
        String artist;
        final Map<String, Integer> notesByDifficulty = new HashMap<>();

        ActiveSong(String title) {
            this.title = title;
        }
    }

    /** 指紋一致に必要な「同じ難易度でノーツ数が一致する譜面」の最小数。 */
    private static final int MIN_MATCHING_CHARTS = 2;

    private final Map<String, ActiveSong> byTitle = new LinkedHashMap<>();
    private final Map<String, String> titleByNorm = new HashMap<>();
    /**
     * 正規化すると同じキーになる既存曲が 2 曲以上ある正規化キー（"SHOOTING STAR" と "Shooting Star" など）。
     * どちらの曲か断定できないので 手順2 では使わない（別曲にレベル・ノーツ数を上書きしないため）。
     */
    private final Set<String> ambiguousNorm = new HashSet<>();
    /** 正規化すると同じキーになる曲が、このページに 2 曲以上ある正規化キー。 */
    private final Set<String> ambiguousPageNorm = new HashSet<>();
    /** このページの曲と完全一致している既存曲名（その曲のものなので、別の曲の正規化一致には使わせない）。 */
    private final Set<String> exactClaimed = new HashSet<>();
    /** このページの曲と 手順1・2 で対応済みの既存曲名（指紋一致の候補から外す）。 */
    private final Set<String> claimed = new HashSet<>();
    /** 指紋一致で既に使った既存曲名。 */
    private final Set<String> fingerprintTaken = new HashSet<>();

    /**
     * @param actives   公開中の全譜面
     * @param pageSongs 今回のページに載っている全曲（指紋一致の候補を絞るために先に渡す）
     */
    WikiTitleResolver(Collection<SongDefinition> actives, Collection<Song> pageSongs) {
        for (SongDefinition sd : actives) {
            ActiveSong a = byTitle.computeIfAbsent(sd.getTitle(), ActiveSong::new);
            if (a.genre == null) a.genre = sd.getGenre();
            if (a.artist == null) a.artist = sd.getArtist();
            if (sd.getNotes() != null && sd.getNotes() > 0) a.notesByDifficulty.putIfAbsent(sd.getDifficulty(), sd.getNotes());
            String norm = BemaniwikiSongListParser.normalizeTitle(sd.getTitle());
            String prev = titleByNorm.putIfAbsent(norm, sd.getTitle());
            if (prev != null && !prev.equals(sd.getTitle())) ambiguousNorm.add(norm);
        }
        // 正規化一致を許すかの判断材料を先に作る（完全一致でページの別の曲に取られている既存曲・ページ側の同名衝突）
        Map<String, String> pageTitleByNorm = new HashMap<>();
        for (Song s : pageSongs) {
            if (byTitle.containsKey(s.title())) exactClaimed.add(s.title());
            String norm = BemaniwikiSongListParser.normalizeTitle(s.title());
            String prev = pageTitleByNorm.putIfAbsent(norm, s.title());
            if (prev != null && !prev.equals(s.title())) ambiguousPageNorm.add(norm);
        }
        for (Song s : pageSongs) {
            Match m = resolveByTitle(s.title());
            if (m != null) claimed.add(m.dbTitle());
        }
    }

    /** 【メソッドの役割】 wiki の 1 曲を楽曲マスタの曲名へ対応づける。 */
    Match resolve(Song song) {
        Match byTitleMatch = resolveByTitle(song.title());
        if (byTitleMatch != null) return byTitleMatch;
        String fp = resolveByFingerprint(song);
        if (fp != null) return new Match(fp, Kind.FINGERPRINT);
        return new Match(song.title(), Kind.NONE);
    }

    private Match resolveByTitle(String wikiTitle) {
        if (byTitle.containsKey(wikiTitle)) return new Match(wikiTitle, Kind.EXACT);
        String norm = BemaniwikiSongListParser.normalizeTitle(wikiTitle);
        // 同じキーの曲が既存側・ページ側に複数ある / その既存曲はページの別の曲と完全一致している → 断定しない
        if (ambiguousNorm.contains(norm) || ambiguousPageNorm.contains(norm)) return null;
        String viaNorm = titleByNorm.get(norm);
        if (viaNorm == null || exactClaimed.contains(viaNorm)) return null;
        return new Match(viaNorm, Kind.NORMALIZED);
    }

    private String resolveByFingerprint(Song song) {
        String artist = normalizeText(song.artist());
        String genre = normalizeText(song.genre());
        if (artist == null || genre == null) return null;

        String found = null;
        for (ActiveSong a : byTitle.values()) {
            if (claimed.contains(a.title) || fingerprintTaken.contains(a.title)) continue;
            if (!artist.equals(normalizeText(a.artist)) || !genre.equals(normalizeText(a.genre))) continue;
            int matching = 0;
            for (Chart c : song.charts().values()) {
                if (c.notes() != null && c.notes() > 0 && Objects.equals(c.notes(), a.notesByDifficulty.get(c.difficulty()))) {
                    matching++;
                }
            }
            if (matching < MIN_MATCHING_CHARTS) continue;
            if (found != null) return null; // 候補が複数 → 断定しない
            found = a.title;
        }
        if (found != null) fingerprintTaken.add(found);
        return found;
    }

    /** ARTIST / GENRE の比較キー（曲名と同じ正規化）。空は null。 */
    private static String normalizeText(String s) {
        if (s == null) return null;
        String n = BemaniwikiSongListParser.normalizeTitle(s);
        return n.isEmpty() ? null : n;
    }
}
