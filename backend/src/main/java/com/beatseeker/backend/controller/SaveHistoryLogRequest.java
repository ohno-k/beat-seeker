package com.beatseeker.backend.controller;

public record SaveHistoryLogRequest(
        Double totalBeatPt,
        Double beatPtIncrease,
        Integer updatedCount,
        String diffJson,
        Double totalPrecisionPt) {
}
