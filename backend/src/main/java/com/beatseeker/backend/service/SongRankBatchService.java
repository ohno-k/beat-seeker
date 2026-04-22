package com.beatseeker.backend.service;

import com.beatseeker.backend.repository.ScoreRepository;
import org.springframework.scheduling.annotation.Async;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;


/**
 * 【Service の役割】 曲別ランキング（user_song_ranks）のキャッシュを
 * 全ユーザー分一括再構築するバッチサービス。
 *
 * 責務:
 *  - 毎日 03:00 に cron で起動し、全ユーザーの「曲・難易度ごとの順位」を再計算する
 *  - 管理者 API から非同期で任意タイミングの再構築も受け付ける
 *  - スコアアップロード時に user_song_ranks を参照して高速に順位を取得できるようにする
 *
 * 依存:
 *  - {@link ScoreRepository}: truncate / insert を直接発行するネイティブクエリに委譲
 *
 * 主要ロジックの概観:
 *  - Java 側にデータを一切引き上げず、PostgreSQL 内部で TRUNCATE → INSERT ... SELECT を実行する
 *  - これにより全ユーザー・全スコアを集計してもヒープ OOM を引き起こさない
 */
@Service
public class SongRankBatchService {

    /** user_song_ranks に対するネイティブクエリ発行用リポジトリ */
    private final ScoreRepository scoreRepository;

    /**
     * 【コンストラクタ】 Spring が {@link ScoreRepository} を注入する。
     */
    public SongRankBatchService(ScoreRepository scoreRepository) {
        this.scoreRepository = scoreRepository;
    }

    /**
     * 【メソッドの役割】 管理者 API から呼ばれる非同期版。
     * 別スレッドで {@link #recalculateAll()} を起動し、HTTP レスポンスは即座に返す。
     *
     * 実行時間が長いため、同期的に呼ぶと HTTP タイムアウトやロードバランサの
     * 切断を招くため必ずこちらを経由させる。
     */
    @Async
    public void recalculateAllAsync() {
        recalculateAll();
    }

    /**
     * 【メソッドの役割】 全ユーザーの user_song_ranks を一括再構築する。
     *
     * 処理の流れ:
     *  - 手順1: user_song_ranks テーブルを TRUNCATE（高速かつ WAL 量少なめ）
     *  - 手順2: scores を元に INSERT ... SELECT で順位付きレコードを一気に作り直す
     *
     * {@code @Scheduled(cron = "0 0 3 * * *")} により毎日 03:00 に自動実行される。
     * {@code @Transactional} でラップされているため、途中失敗時はロールバックされ
     * 既存キャッシュが中途半端な状態で残ることはない。
     */
    @Scheduled(cron = "0 0 3 * * *")
    @Transactional
    public void recalculateAll() {
        // メモリ不足（OOM）を回避するため、Java側にデータを全く取得せず、
        // PostgreSQLの内部で直接全データをINSERT/置換する
        scoreRepository.truncateUserSongRanks();
        scoreRepository.insertAllUserSongRanks();
    }
}
