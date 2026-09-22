package com.aiknowledge.common;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.env.EnvironmentPostProcessor;
import org.springframework.core.env.ConfigurableEnvironment;

import java.util.Arrays;
import java.util.List;

/**
 * Refuses to start a deployment that is still using the secrets published in this repository.
 *
 * <p>Both the session signing key and the service-to-service token have a development default, which keeps a
 * checkout runnable without configuration. That is a convenience for a laptop and a hole anywhere else: anyone
 * who has read the source can mint an administrator session. Compose already requires the real values, so this
 * catches the deployments that do not go through it — a hand-written unit, a bare {@code java -jar}, a copied
 * script.
 *
 * <p>Development profiles keep the defaults, so tests and local runs are unaffected.
 *
 * <p>This runs while the environment is being prepared, before any bean is created. As an auto-configuration
 * it ran after the datasource, so a service that could not reach its database failed on that instead and the
 * refusal never happened.
 */
public class DeploymentSecrets implements EnvironmentPostProcessor {
    static final String DEVELOPMENT_JWT_SECRET = "local-dev-secret-change-before-production";
    static final String DEVELOPMENT_INTERNAL_TOKEN = "ai-knowledge-local-internal";
    private static final List<String> DEVELOPMENT_PROFILES = List.of("local", "test", "dev", "development");

    @Override
    public void postProcessEnvironment(ConfigurableEnvironment environment, SpringApplication application) {
        String problem = problem(environment.getActiveProfiles(),
                System.getenv("AI_KNOWLEDGE_JWT_SECRET"),
                environment.getProperty("platform.internal-user-token"));
        if (problem != null) {
            throw new IllegalStateException(problem);
        }
    }

    /** True when nothing but development profiles are active, including the case of none at all. */
    static boolean development(String[] activeProfiles) {
        if (activeProfiles == null || activeProfiles.length == 0) return true;
        return Arrays.stream(activeProfiles)
                .allMatch(profile -> DEVELOPMENT_PROFILES.contains(profile.trim().toLowerCase()));
    }

    /** The reason this deployment must not start, or null when it may. */
    static String problem(String[] activeProfiles, String jwtSecret, String internalToken) {
        if (development(activeProfiles)) return null;
        if (unset(jwtSecret) || DEVELOPMENT_JWT_SECRET.equals(jwtSecret.trim())) {
            return "AI_KNOWLEDGE_JWT_SECRET is missing or still the development value. Anyone who has read the "
                    + "source could sign their own administrator session. Set it to a secret of your own, or "
                    + "run with the local profile.";
        }
        if (DEVELOPMENT_INTERNAL_TOKEN.equals(unset(internalToken) ? "" : internalToken.trim())) {
            return "PLATFORM_INTERNAL_USER_TOKEN is still the development value, which is published in this "
                    + "repository. Set it to a secret of your own, or run with the local profile.";
        }
        return null;
    }

    private static boolean unset(String value) {
        return value == null || value.isBlank();
    }
}
