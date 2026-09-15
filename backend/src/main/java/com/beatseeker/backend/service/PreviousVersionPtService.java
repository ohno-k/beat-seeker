package com.beatseeker.backend.service;

import com.beatseeker.backend.entity.VersionPtSnapshot;
import com.beatseeker.backend.repository.VersionPtSnapshotRepository;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 【Service の役割】 「前作の最終 BEAT-PT / RATE-PT」をユーザー行に添える。
 *
 * 現実世界の概念: 新作稼働で BEAT-PT は初期化されるが、前作の到達点はティアアイコンの
 * 外枠（色＝前作のティア、光量＝サブティア）として残る（2026-08-19 ユーザー決定）。
 * 外枠は今作でティアが上がっても前作のまま固定なので、値の源泉は世代切り替え時に撮った
 * {@code version_pt_snapshots}（{@link VersionTransitionService#captureSnapshot}）だけでよい。
 *
 * 前作 = {@link IidxVersions#current()} − 1。切り替え前（現行作がまだ 33）は 32 のスナップショットを
 * 探すことになるが存在しないので、全員 null（外枠なし）になる。切り替えの瞬間に 33 のスナップショットへ
 * 自動的に切り替わる。
 *
 * スナップショットは切り替え後は不変なので、メモリに 5 分キャッシュする（ランキング API は
 * 頻繁に呼ばれるが、行ごとに DB を引く必要はない）。
 */
@Service
public class PreviousVersionPtService {

    private static final long CACHE_TTL_MS = 5 * 60_000L;

    private final VersionPtSnapshotRepository snapshotRepository;

    private volatile int cachedVersion = Integer.MIN_VALUE;
    private volatile long cachedAt = 0L;
    private volatile Map<Long, VersionPtSnapshot> cache = Map.of();

    public PreviousVersionPtService(VersionPtSnapshotRepository snapshotRepository) {
        this.snapshotRepository = snapshotRepository;
    }

    /** 【メソッドの役割】 「前作」のバージョン番号（現行作の 1 つ前）。 */
    public int previousVersion() {
        return IidxVersions.current() - 1;
    }

    /**
     * 【メソッドの役割】 前作のスナップショットを userId → 行 の Map で返す（5 分キャッシュ）。
     * 前作が変わった（世代切り替え）ときは即座に読み直す。
     */
    public Map<Long, VersionPtSnapshot> snapshots() {
        int version = previousVersion();
        long now = System.currentTimeMillis();
        if (version != cachedVersion || now - cachedAt > CACHE_TTL_MS) {
            Map<Long, VersionPtSnapshot> m = new HashMap<>();
            for (VersionPtSnapshot s : snapshotRepository.findByVersionOrderByTotalBeatPtDesc(version)) {
                if (s.getUserId() != null) m.put(s.getUserId(), s);
            }
            cache = Collections.unmodifiableMap(m);
            cachedVersion = version;
            cachedAt = now;
        }
        return cache;
    }

    /** 【メソッドの役割】 1 ユーザーぶんを 1 つの Map に書き込む（自分の /me、公開プロフィールなど）。 */
    public void putPrevious(Map<String, Object> target, Long userId) {
        VersionPtSnapshot s = userId == null ? null : snapshots().get(userId);
        target.put("previousBeatPt", s != null ? s.getTotalBeatPt() : null);
        target.put("previousRatePt", s != null ? s.getTotalRatePt() : null);
    }

    /**
     * 【メソッドの役割】 過去作の最終ランキングを、現行ランキング API と同じ行の形で返す。
     *
     * ランキングページの作品セレクトで「Sparkle Shower（終了時点）」を選んだときに使う。
     * 指定した指標（totalBeatPt / totalRatePt / totalKenbanPt / totalSaraPt）が 0 の人は除外し、
     * 指標の降順に並べる。前日比（rankChange）はアーカイブには無いので 0（＝変動なし表示）。
     * 前作 PT（外枠用）も現行と同じ規則で添える。
     *
     * @param version   作品バージョン（例: 33）
     * @param metricKey 並び順と 0 除外に使うキー
     * @return ランキング行（0 件なら空）
     */
    public List<Map<String, Object>> archivedRanking(int version, String metricKey) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (Map<String, Object> row : snapshotRepository.findArchivedRankingRows(version)) {
            Object v = row.get(metricKey);
            double pt = v instanceof Number n ? n.doubleValue() : 0.0;
            if (pt <= 0) continue;
            Map<String, Object> copy = new LinkedHashMap<>(row);
            copy.put("rankChange", 0);
            out.add(copy);
        }
        out.sort((a, b) -> Double.compare(((Number) b.get(metricKey)).doubleValue(), ((Number) a.get(metricKey)).doubleValue()));
        return decorate(out, "userId");
    }

    /**
     * 【メソッドの役割】 ランキング行の一覧に {@code previousBeatPt} / {@code previousRatePt} を添えて返す。
     *
     * ネイティブクエリの戻り（Tuple ベースの Map）は書き換えられないので、行をコピーして返す。
     *
     * @param rows      ランキング行（{@code userIdKey} でユーザー ID を持つ）
     * @param userIdKey ユーザー ID のキー名（"userId" / "id" など）
     * @return 前作 PT を添えた新しいリスト
     */
    public List<Map<String, Object>> decorate(List<Map<String, Object>> rows, String userIdKey) {
        if (rows == null || rows.isEmpty()) return rows;
        Map<Long, VersionPtSnapshot> m = snapshots();
        List<Map<String, Object>> out = new ArrayList<>(rows.size());
        for (Map<String, Object> row : rows) {
            Map<String, Object> copy = new LinkedHashMap<>(row);
            Object id = row.get(userIdKey);
            Long userId = id instanceof Number n ? n.longValue() : null;
            VersionPtSnapshot s = userId == null ? null : m.get(userId);
            copy.put("previousBeatPt", s != null ? s.getTotalBeatPt() : null);
            copy.put("previousRatePt", s != null ? s.getTotalRatePt() : null);
            out.add(copy);
        }
        return out;
    }
}
