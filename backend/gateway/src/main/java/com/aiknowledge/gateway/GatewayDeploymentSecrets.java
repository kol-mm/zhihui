package com.aiknowledge.gateway;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.env.EnvironmentPostProcessor;
import org.springframework.core.env.ConfigurableEnvironment;

import java.util.Arrays;
import java.util.List;

/**
 * The same refusal the other services make, kept here because the gateway does not depend on the common
 * module. It validates the session signing key, which is the one secret this service holds: with the published
 * default in place, anyone who has read the source could sign an administrator session and the gateway would
 * wave it through.
 *
 * <p>Development profiles keep the default, so a checkout still runs without configuration. It runs while the
 * environment is being prepared, before any bean exists, so nothing else can fail first and hide the reason.
 */
public class GatewayDeploymentSecrets implements EnvironmentPostProcessor {
    static final String DEVELOPMENT_JWT_SECRET = "local-dev-secret-change-before-production";
    private static final List<String> DEVELOPMENT_PROFILES = List.of("local", "test", "dev", "development");

    @Override
    public void postProcessEnvironment(ConfigurableEnvironment environment, SpringApplication application) {
        String problem = problem(environment.getActiveProfiles(), System.getenv("AI_KNOWLEDGE_JWT_SECRET"));
        if (problem != null) {
            throw new IllegalStateException(problem);
        }
    }

    static boolean development(String[] activeProfiles) {
        if (activeProfiles == null || activeProfiles.length == 0) return true;
        return Arrays.stream(activeProfiles)
                .allMatch(profile -> DEVELOPMENT_PROFILES.contains(profile.trim().toLowerCase()));
    }

    static String problem(String[] activeProfiles, String jwtSecret) {
        if (development(activeProfiles)) return null;
        boolean unset = jwtSecret == null || jwtSecret.isBlank();
        if (unset || DEVELOPMENT_JWT_SECRET.equals(jwtSecret.trim())) {
            return "AI_KNOWLEDGE_JWT_SECRET is missing or still the development value. Anyone who has read the "
                    + "source could sign their own administrator session. Set it to a secret of your own, or "
                    + "run with the local profile.";
        }
        return null;
    }
}
