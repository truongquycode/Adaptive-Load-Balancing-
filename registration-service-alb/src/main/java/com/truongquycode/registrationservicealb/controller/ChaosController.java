package com.truongquycode.registrationservicealb.controller;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.Map;

@RestController
@RequestMapping("/api/chaos")
public class ChaosController {

    // Flag dùng chung với RegistrationController qua Spring bean
    public static final AtomicBoolean chaosEnabled = new AtomicBoolean(false);

    @PostMapping("/enable")
    public ResponseEntity<Map<String, Object>> enable() {
        chaosEnabled.set(true);
        return ResponseEntity.ok(Map.of(
            "status", "chaos ENABLED",
            "port", System.getProperty("server.port", "unknown")
        ));
    }

    @PostMapping("/disable")
    public ResponseEntity<Map<String, Object>> disable() {
        chaosEnabled.set(false);
        return ResponseEntity.ok(Map.of(
            "status", "chaos DISABLED",
            "port", System.getProperty("server.port", "unknown")
        ));
    }

    @GetMapping("/status")
    public ResponseEntity<Map<String, Object>> status() {
        return ResponseEntity.ok(Map.of(
            "chaosEnabled", chaosEnabled.get(),
            "port", System.getProperty("server.port", "unknown")
        ));
    }
}