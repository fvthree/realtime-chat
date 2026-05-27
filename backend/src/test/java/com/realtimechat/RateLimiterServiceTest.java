package com.realtimechat;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class RateLimiterServiceTest {

    @Test
    void firstMessageIsAllowed() {
        RateLimiterService limiter = new RateLimiterService(10.0);
        assertThat(limiter.tryAcquire("alice")).isTrue();
    }

    @Test
    void messagesWithinLimitAreAllowed() throws InterruptedException {
        // 100 msg/sec — messages arriving at ~50/sec (every 20ms) should all pass.
        // Guava's tryAcquire() only grants a permit if one is immediately available;
        // sleeping 20ms between calls ensures the next permit has accumulated.
        RateLimiterService limiter = new RateLimiterService(100.0);
        for (int i = 0; i < 5; i++) {
            assertThat(limiter.tryAcquire("alice")).isTrue();
            Thread.sleep(20);
        }
    }

    @Test
    void excessiveMessagesAreDenied() {
        // 1 msg/sec — first is allowed, subsequent burst is not
        RateLimiterService limiter = new RateLimiterService(1.0);
        boolean first = limiter.tryAcquire("alice");
        assertThat(first).isTrue();

        int denied = 0;
        for (int i = 0; i < 10; i++) {
            if (!limiter.tryAcquire("alice")) denied++;
        }
        assertThat(denied).isGreaterThan(0);
    }

    @Test
    void differentPrincipalsHaveIndependentBuckets() {
        RateLimiterService limiter = new RateLimiterService(1.0);

        // Exhaust alice's bucket
        limiter.tryAcquire("alice");
        for (int i = 0; i < 5; i++) limiter.tryAcquire("alice");

        // Bob's bucket is independent — his first message must pass
        assertThat(limiter.tryAcquire("bob")).isTrue();
    }
}
