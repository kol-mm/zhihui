package com.aiknowledge.gateway;

import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.MediaType;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

/**
 * Asks user-service whether an API key is live. Nothing is cached: revoking, rescoping or expiring a key takes
 * effect on its next request.
 *
 * <p>An answer comes back as a {@link Verification}; failing to get one — a timeout, user-service down, the
 * internal token refused — is an error signal, which the filter turns into a refusal. Unlike browser sessions,
 * which keep working when Redis is unreachable, a key is never let through on a guess.
 */
@FunctionalInterface
interface ApiKeyVerifier {
    /** token is the delegated identity to hand the services, or null when the key acts for no account. */
    record Verification(boolean valid, long keyId, Set<String> scopes, String token, String reason) {
        static Verification invalid(String reason) {
            return new Verification(false, 0, Set.of(), null, reason);
        }
    }

    Mono<Verification> verify(String key);

    final class Remote implements ApiKeyVerifier {
        static final String PATH = "/user/internal/api-keys/verify";

        private final WebClient client;
        private final String internalToken;
        private final Duration timeout;

        Remote(WebClient client, String internalToken, Duration timeout) {
            this.client = client;
            this.internalToken = internalToken;
            this.timeout = timeout;
        }

        @Override
        public Mono<Verification> verify(String key) {
            return client.post().uri(PATH)
                    .header("X-Internal-Token", internalToken)
                    .contentType(MediaType.APPLICATION_JSON)
                    .bodyValue(Map.of("key", key))
                    .retrieve()
                    .bodyToMono(new ParameterizedTypeReference<Map<String, Object>>() {})
                    .timeout(timeout)
                    .flatMap(Remote::read);
        }

        /** code 0 is an answer, valid or not; anything else means the question could not be asked. */
        static Mono<Verification> read(Map<String, Object> body) {
            Object code = body.get("code");
            if (!(code instanceof Number number) || number.intValue() != 0) {
                return Mono.error(new IllegalStateException("user-service refused the verification: " + body.get("message")));
            }
            if (!(body.get("data") instanceof Map<?, ?> data)) {
                return Mono.error(new IllegalStateException("user-service answered without data"));
            }
            if (!Boolean.TRUE.equals(data.get("valid"))) {
                return Mono.just(Verification.invalid(String.valueOf(data.get("reason"))));
            }
            Set<String> scopes = new LinkedHashSet<>();
            if (data.get("scopes") instanceof Collection<?> granted) {
                for (Object scope : granted) scopes.add(String.valueOf(scope));
            }
            Object keyId = data.get("keyId");
            Object token = data.get("token");
            return Mono.just(new Verification(true, keyId instanceof Number id ? id.longValue() : 0, Set.copyOf(scopes),
                    token == null ? null : token.toString(), null));
        }
    }
}
