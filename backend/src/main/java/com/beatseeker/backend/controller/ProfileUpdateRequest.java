package com.beatseeker.backend.controller;

public record ProfileUpdateRequest(
                String displayName,
                String iidxId,
                String danRank,
                String arenaRank,
                String playSide,
                String currentPassword,
                String newPassword,
                Integer privacyLevel,
                String email) {
}
