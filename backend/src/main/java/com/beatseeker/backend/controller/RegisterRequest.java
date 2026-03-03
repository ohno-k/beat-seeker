package com.beatseeker.backend.controller;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

public record RegisterRequest(
        @NotBlank(message = "IIDX ID is required") @Pattern(regexp = "^\\d{4}-\\d{4}$", message = "IIDX ID must be in format XXXX-XXXX") String iidxId,

        @NotBlank(message = "Password is required") String password,

        String displayName,
        String danRank,
        String arenaRank) {
}
