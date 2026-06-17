package com.beatseeker.backend.repository;

import com.beatseeker.backend.entity.ResultImage;
import com.beatseeker.backend.entity.User;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

/**
 * 【リポジトリの役割】 {@link ResultImage}（リザルト画像メタ情報）の永続化を担う。
 *
 * 譜面の同定は (user, title, difficultyName) の組で行う。
 */
@Repository
public interface ResultImageRepository extends JpaRepository<ResultImage, Long> {

    /** 指定ユーザーの、ある譜面（曲名×難易度名）のリザルト画像を登録順に取得する。 */
    List<ResultImage> findByUserAndTitleAndDifficultyNameOrderByUploadedAtAsc(
            User user, String title, String difficultyName);

    /** 所有者チェック付きで 1 枚取得する（削除時の認可に使う）。 */
    Optional<ResultImage> findByIdAndUser(Long id, User user);

    /** 指定譜面の登録枚数（上限チェック用）。 */
    long countByUserAndTitleAndDifficultyName(User user, String title, String difficultyName);
}
