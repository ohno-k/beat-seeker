package com.beatseeker.backend;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class TestController {
    @GetMapping("/api/test-root")
    public String test() {
        return "Root controller is active";
    }
}
