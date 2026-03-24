package com.beatseeker.backend.controller;

public record RecalculatePointsRequest(
        String songDataJson,
        String difficultyTableJson
) {}
