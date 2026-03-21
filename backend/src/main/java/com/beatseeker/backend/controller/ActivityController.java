package com.beatseeker.backend.controller;

import com.beatseeker.backend.entity.ActivityLog;
import com.beatseeker.backend.repository.ActivityLogRepository;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api/activity")
public class ActivityController {

    private final ActivityLogRepository activityLogRepository;

    public ActivityController(ActivityLogRepository activityLogRepository) {
        this.activityLogRepository = activityLogRepository;
    }

    /** 最新20件のアクティビティを返す（全体ニュースフィード用）*/
    @GetMapping("/feed")
    public ResponseEntity<List<Map<String, Object>>> getFeed() {
        List<ActivityLog> logs = activityLogRepository.findRecentActivity(PageRequest.of(0, 20));

        List<Map<String, Object>> result = logs.stream().map(a -> Map.<String, Object>of(
                "id", a.getId(),
                "type", a.getType(),
                "displayName", a.getUser().getDisplayName() != null ? a.getUser().getDisplayName() : "プレイヤー",
                "oldValue", a.getOldValue() != null ? a.getOldValue() : "",
                "newValue", a.getNewValue() != null ? a.getNewValue() : "",
                "createdAt", a.getCreatedAt().toString()
        )).collect(Collectors.toList());

        return ResponseEntity.ok(result);
    }
}
