package com.example.notification.controller;

import com.example.notification.model.UserPreferences;
import com.example.notification.repository.UserPreferencesRepository;
import com.example.notification.security.JwtTokenProvider;
import com.example.notification.service.SseEmitterManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import org.springframework.http.MediaType;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.io.IOException;

// SseController.java
@RestController
@RequestMapping("/api/notifications")
public class SseController {

    private static final Logger logger = LoggerFactory.getLogger(SseController.class);
    private final SseEmitterManager sseEmitterManager;
    private final JwtTokenProvider jwtTokenProvider;
    private final UserPreferencesRepository userPreferencesRepository;

    // Constructor injection
    public SseController(
        SseEmitterManager sseEmitterManager,
        JwtTokenProvider jwtTokenProvider,
        UserPreferencesRepository userPreferencesRepository) {
        this.sseEmitterManager = sseEmitterManager;
        this.jwtTokenProvider = jwtTokenProvider;
        this.userPreferencesRepository = userPreferencesRepository;
    }

    @GetMapping(value="/events", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public ResponseEntity<SseEmitter> streamEvents(@RequestParam("token") String token) {
        if (token == null || !jwtTokenProvider.validateToken(token)) {
            logger.warn("SSE connection attempt with invalid or missing token.");
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }

        String userId = jwtTokenProvider.getUserIdFromJWT(token);
        Optional<UserPreferences> userPreferencesOptional = userPreferencesRepository.findByUserId(userId);
        if (userId == null) {
            logger.warn("Could not extract userId from token for SSE connection.");
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }

        if (userPreferencesOptional.isPresent()) {
            UserPreferences preferences = userPreferencesOptional.get();
            if (!preferences.isSseEnabled()) {
                logger.warn("SSE connection denied for user: {}. SSE is disabled in user preferences.", userId);
                return ResponseEntity.status(HttpStatus.FORBIDDEN).<SseEmitter>build();
            }
            // If SSE is enabled, proceed (no specific action here, logic continues below)
            logger.info("SSE connection allowed for user: {}. SSE is enabled in user preferences.", userId);
        } else {
            // If no preferences are found, proceed (default to SSE enabled)
            logger.info("No user preferences found for user: {}. Allowing SSE connection by default.", userId);
        }

        final String userKey = userId; // Effectively final for lambdas

        // Close any existing emitters for this user before creating a new one
        sseEmitterManager.closeAndRemoveEmittersForUser(userKey);

        // Set a timeout
        SseEmitter emitter = new SseEmitter(TimeUnit.HOURS.toMillis(1));

        emitter.onCompletion(() -> {
            logger.info("SseEmitter completed for user: {}", userKey);
            sseEmitterManager.removeEmitter(userKey, emitter);
            emitter.complete();
        });

        emitter.onTimeout(() -> {
            logger.info("SseEmitter timed out for user: {}. Completing the emitter.", userKey); // Log message can be updated
            sseEmitterManager.removeEmitter(userKey, emitter);
            emitter.complete(); // Changed from completeWithError
        });

        emitter.onError(e -> {
            logger.error("SseEmitter error for user: {} - Error: {}", userKey, e.getMessage());
            sseEmitterManager.removeEmitter(userKey, emitter);
            emitter.completeWithError(e);
        });

        sseEmitterManager.addEmitter(userKey, emitter);

        try {
            // Send an initial event to confirm connection
            // emitter.send(SseEmitter.event().comment("stream-open"));
            emitter.send(SseEmitter.event().name("INIT").data("Connection established for user: " + userKey));
            logger.info("SSE connection established and initial events sent for user: {}", userKey);
        } catch (IOException e) { 
            logger.error("Error sending initial comment or INIT event to user: {} - Error: {}", userKey, e.getMessage());
            sseEmitterManager.removeEmitter(userKey, emitter);
            emitter.completeWithError(e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build();        
        }

        return ResponseEntity.ok(emitter);
    }
}
