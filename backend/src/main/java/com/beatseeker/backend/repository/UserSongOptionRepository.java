package com.beatseeker.backend.repository;

import com.beatseeker.backend.entity.User;
import com.beatseeker.backend.entity.UserSongOption;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

/**
 * 【Repository の役割】 {@link UserSongOption}（連携アプリから同期された譜面オプション）の
 * リポジトリ。
 *
 * 用途:
 *  - 同期エンドポイント: (user, title, difficultyName) の 3 項目で既存レコードを引き、
 *    upsert（無ければ insert / あれば update）する。
 *  - スコア応答ビルダ: ユーザー単位で全件取得し、(title, difficultyName) をキーにした
 *    インメモリマップを作って各スコア行に options を埋める。
 */
@Repository
public interface UserSongOptionRepository extends JpaRepository<UserSongOption, Long> {

    /** ユーザー × 曲 × 難易度名で 1 件取得（upsert 判定用）。 */
    Optional<UserSongOption> findByUserAndTitleAndDifficultyName(User user, String title, String difficultyName);

    /** ユーザーの全オプションを取得（スコア応答ビルダで一括ロードする用途）。 */
    List<UserSongOption> findByUser(User user);
}
