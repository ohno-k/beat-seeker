package com.beatseeker.backend.service;

import com.beatseeker.backend.entity.User;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.Arrays;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 【クラスの役割】 Competition (大会主催) 機能のアクセス権限判定を 1 箇所に集約する Spring コンポーネント。
 *
 * <p>サイドバーの Competition セクション (Strategy Card / Song Reveal / 自選曲送信管理) は
 * 特定ユーザー ID のホワイトリスト方式で公開しており、バックエンドの主催向け API
 * ({@code /api/competitions/**}) も同じ ID 集合に限定する。
 *
 * <p>判定値は {@code application.yml} の {@code competition.organizer-ids}
 * (カンマ区切り CSV、デフォルト {@code "18,19,23,210"}) から注入する。
 * 設定上書きしなければフロント側の現行ガード ID と一致する。
 */
@Component
public class OrganizerAuthService {

    /** 主催権限を持つユーザー ID 集合。コンストラクタで CSV からパースしたものを不変 Set で保持。 */
    private final Set<Long> organizerUserIds;

    /**
     * 【コンストラクタ】 設定値を {@link Value} 経由で注入する。
     *
     * @param csvIds カンマ区切りのユーザー ID 群。空白は自動 trim、空エントリは除外。
     */
    public OrganizerAuthService(
            @Value("${competition.organizer-ids:18,19,23,210}") String csvIds) {
        this.organizerUserIds = Arrays.stream(csvIds.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .map(Long::parseLong)
                .collect(Collectors.toUnmodifiableSet());
    }

    /**
     * 【メソッドの役割】 ログインユーザーが大会主催権限を持つかを判定する。
     *
     * @param user 判定対象 (null 許容)
     * @return ホワイトリストに含まれていれば {@code true}
     */
    public boolean isOrganizer(User user) {
        return user != null && organizerUserIds.contains(user.getId());
    }

    /**
     * 【メソッドの役割】 ユーザー ID 単体で主催判定を行う。
     */
    public boolean isOrganizerById(Long userId) {
        return userId != null && organizerUserIds.contains(userId);
    }
}
