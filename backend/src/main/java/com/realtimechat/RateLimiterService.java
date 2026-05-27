package com.realtimechat;

import com.google.common.util.concurrent.RateLimiter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.concurrent.ConcurrentHashMap;

/**
 * In-memory per-principal token bucket using Guava RateLimiter.tryAcquire() (non-blocking).
 * Each principal gets a separate bucket at the configured rate.
 *
 * Known limitation: the map grows unbounded for long-lived instances with many unique principals.
 * This is acceptable for Stage 4 (demo scale). Stage 5 replaces this with a Redis-backed
 * limiter that works across multiple instances.
 */
@Service
public class RateLimiterService {

    private static final Logger log = LoggerFactory.getLogger(RateLimiterService.class);

    private final double messagesPerSecond;
    private final ConcurrentHashMap<String, RateLimiter> buckets = new ConcurrentHashMap<>();

    public RateLimiterService(@Value("${app.rate-limit-messages-per-second:3}") double messagesPerSecond) {
        this.messagesPerSecond = messagesPerSecond;
    }

    /**
     * Returns true if the principal is allowed to send a message now.
     * Non-blocking: never sleeps, never blocks the Reactor thread.
     */
    @SuppressWarnings("UnstableApiUsage")
    public boolean tryAcquire(String principalName) {
        RateLimiter limiter = buckets.computeIfAbsent(principalName,
                k -> RateLimiter.create(messagesPerSecond));
        boolean allowed = limiter.tryAcquire();
        if (!allowed) {
            log.warn("rate limit exceeded for principal '{}'", principalName);
        }
        return allowed;
    }
}
