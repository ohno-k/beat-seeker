package com.beatseeker.backend.controller;

import jakarta.validation.constraints.NotBlank;

public record LoginRequest(
        @NotBlank(message = "IIDX ID is required") String iidxId,

        @NotBlank(message = "Password is required") String password) {
}
