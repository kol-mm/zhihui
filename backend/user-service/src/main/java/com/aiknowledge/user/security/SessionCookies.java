package com.aiknowledge.user.security;

import com.aiknowledge.common.LocalAuth;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseCookie;

import java.time.Duration;

/**
 * The browser session lives in an httpOnly cookie, so page scripts never see the token. The gateway turns the
 * cookie back into the Authorization header the services read, and checks it against revoked sessions first.
 */
public final class SessionCookies {
    /** Also read by the gateway (GatewaySessionFilter). */
    public static final String NAME = "zh_session";

    private SessionCookies() {
    }

    /** For a token issued just now. */
    public static void write(HttpServletRequest request, HttpServletResponse response, String token) {
        write(request, response, token, Duration.ofSeconds(LocalAuth.tokenLifetimeSeconds()));
    }

    /** The cookie should not outlive the token it carries. */
    public static void write(HttpServletRequest request, HttpServletResponse response, String token, Duration lifetime) {
        Duration maxAge = lifetime.isNegative() ? Duration.ZERO : lifetime;
        response.addHeader(HttpHeaders.SET_COOKIE, cookie(request, token, maxAge).toString());
        response.setHeader(HttpHeaders.CACHE_CONTROL, "no-store");
    }

    public static void clear(HttpServletRequest request, HttpServletResponse response) {
        response.addHeader(HttpHeaders.SET_COOKIE, cookie(request, "", Duration.ZERO).toString());
    }

    private static ResponseCookie cookie(HttpServletRequest request, String value, Duration maxAge) {
        return ResponseCookie.from(NAME, value)
                .httpOnly(true)
                .secure(isHttps(request))
                .sameSite("Lax")
                .path("/")
                .maxAge(maxAge)
                .build();
    }

    /** nginx passes the visitor's scheme on; the gateway may append its own hop, so the first value counts. */
    private static boolean isHttps(HttpServletRequest request) {
        if (request == null) return false;
        if (request.isSecure()) return true;
        String forwarded = request.getHeader("X-Forwarded-Proto");
        return forwarded != null && forwarded.split(",")[0].trim().equalsIgnoreCase("https");
    }
}
