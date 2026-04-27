package com.bidstream.common.security;

import com.bidstream.adapter.out.cache.RedisRateLimiter;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.time.Duration;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.lang.NonNull;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Per-user + per-IP sliding-window rate limiting on bid and auth endpoints (PDR §17). Runs after
 * {@link JwtAuthenticationFilter} so an authenticated bid request can be limited by user id, not
 * just IP (multiple users can share a NAT'd IP; a user shouldn't be throttled by their neighbors).
 */
public class RateLimitFilter extends OncePerRequestFilter {

    // Generous enough that a shared-NAT office or a busy integration test suite never collides
    // with normal traffic, while still bounding a genuine credential-stuffing flood from one IP.
    private static final int AUTH_LIMIT = 300;
    private static final Duration AUTH_WINDOW = Duration.ofMinutes(1);
    private static final int BID_LIMIT = 20;
    private static final Duration BID_WINDOW = Duration.ofSeconds(10);

    private final RedisRateLimiter rateLimiter;
    private final ObjectMapper objectMapper;

    public RateLimitFilter(RedisRateLimiter rateLimiter, ObjectMapper objectMapper) {
        this.rateLimiter = rateLimiter;
        this.objectMapper = objectMapper;
    }

    @Override
    protected void doFilterInternal(@NonNull HttpServletRequest request,
                                     @NonNull HttpServletResponse response,
                                     @NonNull FilterChain filterChain)
            throws ServletException, IOException {
        String path = request.getRequestURI();
        boolean limited;

        if (path.startsWith("/api/v1/auth/")) {
            limited = !rateLimiter.tryAcquire("ratelimit:ip:" + request.getRemoteAddr(),
                    AUTH_LIMIT, AUTH_WINDOW);
        } else if ("POST".equalsIgnoreCase(request.getMethod()) && path.matches("/api/v1/auctions/[^/]+/bids")) {
            var auth = SecurityContextHolder.getContext().getAuthentication();
            if (auth != null && auth.getPrincipal() instanceof JwtAuthenticationFilter.AuthenticatedUser user) {
                limited = !rateLimiter.tryAcquire("ratelimit:user:" + user.id(), BID_LIMIT, BID_WINDOW);
            } else {
                limited = false; // unauthenticated - security config will reject with 403 anyway
            }
        } else {
            limited = false;
        }

        if (limited) {
            // QA-REVIEW.md Low: this used to write its own ad-hoc {"error":"RATE_LIMITED"} body,
            // the only rejection path in the app not shaped like GlobalExceptionHandler's RFC
            // 7807 responses - a filter runs outside Spring MVC's exception handling, so it can't
            // reuse that @ExceptionHandler, but it can match the same wire shape by hand.
            ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.TOO_MANY_REQUESTS,
                    "Rate limit exceeded");
            problem.setProperty("reason", "RATE_LIMITED");
            response.setStatus(HttpStatus.TOO_MANY_REQUESTS.value());
            response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
            objectMapper.writeValue(response.getWriter(), problem);
            return;
        }

        filterChain.doFilter(request, response);
    }
}
