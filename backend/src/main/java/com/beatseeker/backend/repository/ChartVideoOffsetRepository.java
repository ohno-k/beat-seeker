package com.beatseeker.backend.repository;

import com.beatseeker.backend.entity.ChartVideoOffset;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface ChartVideoOffsetRepository extends JpaRepository<ChartVideoOffset, String> {

    List<ChartVideoOffset> findByTitleAndVideoId(String title, String videoId);

    /** そのチャンネルの動画で保存されたずれ（今使っている動画のものだけ）。初期値の推定に使う。 */
    @Query("select o.offsetSec from ChartVideoOffset o, SongVideo v"
            + " where v.title = o.title and v.videoId = o.videoId and v.channelTitle = :channel")
    List<Double> findOffsetsByChannel(@Param("channel") String channel);
}
