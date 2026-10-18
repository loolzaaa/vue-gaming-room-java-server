package ru.loolzaaa.games.vuegamingroomjavaserver.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpStatus;
import org.springframework.web.filter.OncePerRequestFilter;
import ru.loolzaaa.games.vuegamingroomjavaserver.javaserver.security.TokenBucket;

import java.io.IOException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

/**
 * Ограничивает частоту HTTP-запросов к REST API по IP клиента.
 */
public class HttpRateLimitFilter extends OncePerRequestFilter {

    private static final int CLEANUP_THRESHOLD = 10_000;
    private static final long BUCKET_TTL_NANOS = TimeUnit.MINUTES.toNanos(10);
    private static final long CLEANUP_INTERVAL_NANOS = TimeUnit.MINUTES.toNanos(1);

    private final int rateLimitPerSecond;
    private final int rateLimitBurst;
    private final ConcurrentHashMap<String, TokenBucket> buckets = new ConcurrentHashMap<>();
    private volatile long lastCleanupNanos = System.nanoTime();

    public HttpRateLimitFilter(int rateLimitPerSecond, int rateLimitBurst) {
        this.rateLimitPerSecond = rateLimitPerSecond;
        this.rateLimitBurst = rateLimitBurst;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        if ("OPTIONS".equalsIgnoreCase(request.getMethod())) {
            return true;
        }
        String path = request.getRequestURI();
        String contextPath = request.getContextPath();
        if (contextPath != null && !contextPath.isEmpty() && path.startsWith(contextPath)) {
            path = path.substring(contextPath.length());
        }
        return !path.equals("/room") && !path.startsWith("/room/");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        cleanupIfNeeded();

        String clientKey = request.getRemoteAddr();
        if (clientKey == null || clientKey.isBlank()) {
            clientKey = "unknown";
        }
        TokenBucket bucket = buckets.computeIfAbsent(clientKey,
                key -> new TokenBucket(rateLimitPerSecond, rateLimitBurst));
        if (!bucket.tryAcquire()) {
            response.setStatus(HttpStatus.TOO_MANY_REQUESTS.value());
            response.setContentType("application/json");
            response.setCharacterEncoding("UTF-8");
            response.getWriter().write("{\"message\":\"Too many requests\"}");
            return;
        }

        filterChain.doFilter(request, response);
    }

    private void cleanupIfNeeded() {
        long now = System.nanoTime();
        if (buckets.size() < CLEANUP_THRESHOLD || now - lastCleanupNanos < CLEANUP_INTERVAL_NANOS) {
            return;
        }
        lastCleanupNanos = now;
        long cutoff = now - BUCKET_TTL_NANOS;
        buckets.entrySet().removeIf(entry -> entry.getValue().lastUsedNanos() < cutoff);
    }
}
