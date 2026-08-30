package com.beatseeker.backend.controller;

import com.beatseeker.backend.repository.VersionPtSnapshotRepository;
import com.beatseeker.backend.service.IidxVersions;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 【Controller の役割】 過去作の最終 PT アーカイブ（{@code version_pt_snapshots}）を読み出す公開 API。
 *
 * 現実世界の概念: IIDX は新作稼働のたびにスコアがリセットされ、beat-seeker でも BEAT-PT / RATE-PT を
 * 0 から積み直す。そのままでは「あの作品で自分は何位だったか」が失われるため、初期化の直前に
 * 全員の最終値を焼き付けている（{@link com.beatseeker.backend.service.VersionTransitionService#captureSnapshot}）。
 * ここはその保存済みデータを、作品を選んで順位表として見せるための入口。
 *
 * 一世代限りの作りにしていない: {@code version} で作品を選ぶ形にしてあるので、34 が終われば 34 の
 * ランキングが同じ経路で並び、作品を選んで見比べられる。
 *
 * 現行作の集計には一切関与しない（{@link com.beatseeker.backend.entity.VersionPtSnapshot} の不変条件）。
 * 副作用のある操作は持たず、GET のみ。既存のランキング API と同じく未ログインでも閲覧できる
 * （公開範囲は {@code privacyLevel} を返してフロント側の表示に委ねる形も同じ）。
 *
 * {@link ScoreController} に足さず別クラスにしてあるのは、参照するテーブルも公開範囲の考え方も
 * 現行作のランキングとは独立しているため。稼働中のスコア API に手を入れずに追加できる。
 *
 * エンドポイント:
 *  - GET /api/past-rankings/versions        … アーカイブ済みの作品一覧（新しい順）
 *  - GET /api/past-rankings/previous-tiers  … 前作の BEAT-PT 一覧（ティアアイコンの外枠用）
 *  - GET /api/past-rankings/{version}       … 指定作品の順位表
 */
@RestController
@RequestMapping("/api/past-rankings")
public class PastRankingController {

    private final VersionPtSnapshotRepository snapshotRepository;

    public PastRankingController(VersionPtSnapshotRepository snapshotRepository) {
        this.snapshotRepository = snapshotRepository;
    }

    /**
     * 【メソッドの役割】 アーカイブ済みの作品一覧を返す（新しい順）。
     *
     * 画面の作品セレクタを組み立てるのに使う。まだ 1 作品も保存していない間は空リストになり、
     * 画面側は「まだ過去作のランキングはありません」の表示になる。
     *
     * 返却キー: version / name / userCount / capturedAt
     *
     * @return 作品ごとの要約（新しい順。0 件でも空リスト）
     */
    @GetMapping("/versions")
    public ResponseEntity<List<Map<String, Object>>> versions() {
        List<Map<String, Object>> rows = new ArrayList<>();
        for (Map<String, Object> row : snapshotRepository.findArchiveVersionSummaries()) {
            Map<String, Object> out = new LinkedHashMap<>(row);
            Object version = row.get("version");
            // 作品名はサーバ側の対応表から引く。フロントにも同じ表があるが、
            // 未知の番号のときのフォールバック（"IIDX 35" 形式）まで含めて一本化しておく。
            out.put("name", IidxVersions.nameOf(version instanceof Number n ? n.intValue() : null));
            rows.add(out);
        }
        return ResponseEntity.ok(rows);
    }

    /**
     * 【メソッドの役割】 前作（アーカイブ済みで最も新しい作品）の BEAT-PT をユーザー ID 付きで返す。
     *
     * ティアアイコンの外枠（前作の到達点を示す発光）を描くための供給源。ティア名ではなく
     * BEAT-PT を返し、ティアとサブティアの導出はフロントの {@code beatTier.ts} に任せる。
     * 閾値の調整がアイコンの見え方に自動で反映されるようにするため。
     *
     * アーカイブが 1 件も無い間は {@code version} が null・{@code entries} が空になり、
     * 画面側では誰にも外枠が付かない。
     *
     * @return {@code { version, entries: [{ userId, totalBeatPt }] }}
     */
    @GetMapping("/previous-tiers")
    public ResponseEntity<Map<String, Object>> previousTiers() {
        List<Integer> archived = snapshotRepository.findArchivedVersions();
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("version", archived.isEmpty() ? null : archived.get(0));
        body.put("entries", snapshotRepository.findPreviousVersionBeatPt());
        return ResponseEntity.ok(body);
    }

    /**
     * 【メソッドの役割】 指定作品の順位表を返す。
     *
     * BEAT-PT の順位で並べて返す。RATE-PT 順の表示も同じ行を並べ替えて作れるよう、
     * どちらの PT と順位も 1 行に含めてある（作品ごとに 1 回取れば両方の表が作れる）。
     *
     * @param version 作品バージョン番号（例: 33）
     * @return 順位表の行（未アーカイブの作品なら空リスト）
     */
    @GetMapping("/{version}")
    public ResponseEntity<List<Map<String, Object>>> ranking(@PathVariable int version) {
        return ResponseEntity.ok(snapshotRepository.findArchiveRanking(version));
    }
}
