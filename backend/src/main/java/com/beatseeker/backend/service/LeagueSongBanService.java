package com.beatseeker.backend.service;

import com.beatseeker.backend.entity.LeagueSongBan;
import com.beatseeker.backend.entity.User;
import com.beatseeker.backend.repository.LeagueSongBanRepository;
import com.beatseeker.backend.util.JstTime;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;

/**
 * 【Service の役割】 リーグの楽曲 BAN（課題曲に出てほしくない曲）の参照・登録。
 *
 * 仕様（2026-10-05 ユーザー指示）:
 *  - DIVISION ごとに、その DIVISION の課題曲の候補（{@link LeagueSongDrawService#banPoolForTier}）から
 *    最大 {@link #MAX_BANS} 曲まで登録できる。ユーザー × DIVISION ごとに記憶し、週をまたいで残る。
 *  - 月曜 0:00 の事前編成で課題曲を抽選するとき、グループのメンバーが<b>その卓の DIVISION</b> に
 *    登録した BAN を除く（{@link LeagueSongDrawService#drawSongsForGroup}）。
 *    つまり日曜 23:59 までの登録がその週の抽選に効く。
 *  - 事前編成から開始までの間（月曜 0:00〜12:00。{@link LeagueService#isRegistrationLocked}）は、
 *    参加・離脱と同じく編集できない（抽選に使った BAN と画面の表示がずれないように）。
 */
@Service
public class LeagueSongBanService {

    /** 1 DIVISION あたりの BAN の上限曲数。 */
    public static final int MAX_BANS = 20;

    private final LeagueSongBanRepository banRepository;
    private final LeagueSongDrawService songDrawService;

    public LeagueSongBanService(LeagueSongBanRepository banRepository, LeagueSongDrawService songDrawService) {
        this.banRepository = banRepository;
        this.songDrawService = songDrawService;
    }

    /**
     * 【メソッドの役割】 ユーザーが指定 DIVISION に登録した BAN を返す。
     *
     * @param user 対象ユーザー
     * @param tier DIVISION
     * @return BAN（登録順）
     */
    @Transactional(readOnly = true)
    public List<LeagueSongBan> bansOf(User user, int tier) {
        return banRepository.findByUserAndTierOrderByIdAsc(user, tier);
    }

    /**
     * 【メソッドの役割】 ユーザーの DIVISION ごとの BAN 数を返す。
     *
     * @param user 対象ユーザー
     * @return DIVISION → 件数（登録の無い DIVISION は含まない）
     */
    @Transactional(readOnly = true)
    public Map<Integer, Long> countsByTier(User user) {
        Map<Integer, Long> out = new TreeMap<>();
        for (Object[] row : banRepository.countByTier(user)) {
            out.put(((Number) row[0]).intValue(), ((Number) row[1]).longValue());
        }
        return out;
    }

    /**
     * 【メソッドの役割】 指定 DIVISION の BAN を、渡したタイトルの集合で置き換える。
     *
     * @param user   対象ユーザー
     * @param tier   DIVISION
     * @param titles BAN するタイトル（{@link #MAX_BANS} 曲以内、その DIVISION の候補にある曲だけ）
     * @return 保存後の BAN
     * @throws IllegalArgumentException 上限超え・候補に無い曲を含むとき
     */
    @Transactional
    public List<LeagueSongBan> replaceBans(User user, int tier, List<String> titles) {
        LinkedHashSet<String> unique = new LinkedHashSet<>();
        for (String t : titles) if (t != null && !t.isBlank()) unique.add(t);
        if (unique.size() > MAX_BANS) {
            throw new IllegalArgumentException("BAN できるのは " + MAX_BANS + " 曲までです");
        }
        Map<String, LeagueSongDrawService.PoolSong> pool = new HashMap<>();
        for (LeagueSongDrawService.PoolSong ps : songDrawService.banPoolForTier(tier)) pool.put(ps.song().getTitle(), ps);
        for (String t : unique) {
            if (!pool.containsKey(t)) throw new IllegalArgumentException("この DIVISION の課題曲の候補にない曲です: " + t);
        }
        banRepository.deleteByUserAndTier(user, tier);
        banRepository.flush();
        List<LeagueSongBan> rows = new ArrayList<>();
        for (String t : unique) {
            LeagueSongBan b = new LeagueSongBan();
            b.setUser(user);
            b.setTier(tier);
            b.setTitle(t);
            b.setDifficultyName(LeagueChartNotation.codeToName(pool.get(t).song().getDifficulty()));
            b.setCreatedAt(java.time.LocalDateTime.now(JstTime.JST));
            rows.add(b);
        }
        return banRepository.saveAll(rows);
    }
}
