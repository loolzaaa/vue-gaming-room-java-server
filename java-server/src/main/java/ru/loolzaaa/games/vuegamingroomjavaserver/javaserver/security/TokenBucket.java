package ru.loolzaaa.games.vuegamingroomjavaserver.javaserver.security;

/**
 * Простой token bucket для ограничения частоты запросов.
 * Потокобезопасен.
 */
public final class TokenBucket {

    private final double capacity;
    private final double tokensPerNano;
    private double tokens;
    private long lastRefillNanos;
    private volatile long lastUsedNanos;

    public TokenBucket(int perSecond, int burst) {
        if (perSecond < 1) {
            throw new IllegalArgumentException("perSecond must be positive");
        }
        if (burst < 1) {
            throw new IllegalArgumentException("burst must be positive");
        }
        this.capacity = burst;
        this.tokens = burst;
        this.tokensPerNano = perSecond / 1_000_000_000.0;
        this.lastRefillNanos = System.nanoTime();
        this.lastUsedNanos = lastRefillNanos;
    }

    public synchronized boolean tryAcquire() {
        long now = System.nanoTime();
        tokens = Math.min(capacity, tokens + (now - lastRefillNanos) * tokensPerNano);
        lastRefillNanos = now;
        lastUsedNanos = now;
        if (tokens >= 1) {
            tokens -= 1;
            return true;
        }
        return false;
    }

    public long lastUsedNanos() {
        return lastUsedNanos;
    }
}
