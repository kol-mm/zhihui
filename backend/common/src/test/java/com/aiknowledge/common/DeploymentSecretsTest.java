package com.aiknowledge.common;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** A deployment may not run on the secrets that are published in this repository. */
class DeploymentSecretsTest {
    private static final String[] PRODUCTION = {"mysql", "redis"};
    private static final String[] LOCAL = {"local"};
    private static final String REAL_SECRET = "a-secret-of-our-own-9f2b";
    private static final String REAL_TOKEN = "another-secret-4c7d";

    @Test
    void aProperlyConfiguredDeploymentStarts() {
        assertNull(DeploymentSecrets.problem(PRODUCTION, REAL_SECRET, REAL_TOKEN));
    }

    @Test
    void aMissingSigningSecretStopsADeployment() {
        String problem = DeploymentSecrets.problem(PRODUCTION, null, REAL_TOKEN);

        assertNotNull(problem);
        assertTrue(problem.contains("AI_KNOWLEDGE_JWT_SECRET"), problem);
        assertNotNull(DeploymentSecrets.problem(PRODUCTION, "   ", REAL_TOKEN), "blank counts as missing");
    }

    @Test
    void theDevelopmentSigningSecretStopsADeployment() {
        String problem = DeploymentSecrets.problem(PRODUCTION, DeploymentSecrets.DEVELOPMENT_JWT_SECRET, REAL_TOKEN);

        assertNotNull(problem);
        assertTrue(problem.contains("administrator session"), problem);
    }

    @Test
    void theDevelopmentInternalTokenStopsADeployment() {
        String problem = DeploymentSecrets.problem(PRODUCTION, REAL_SECRET, DeploymentSecrets.DEVELOPMENT_INTERNAL_TOKEN);

        assertNotNull(problem);
        assertTrue(problem.contains("PLATFORM_INTERNAL_USER_TOKEN"), problem);
    }

    @Test
    void anUnsetInternalTokenIsLeftAlone() {
        // Not every service talks to another one, and the callers carry their own default.
        assertNull(DeploymentSecrets.problem(PRODUCTION, REAL_SECRET, null));
    }

    @Test
    void developmentKeepsTheDefaultsSoACheckoutStillRuns() {
        assertNull(DeploymentSecrets.problem(LOCAL, null, null));
        assertNull(DeploymentSecrets.problem(LOCAL, DeploymentSecrets.DEVELOPMENT_JWT_SECRET,
                DeploymentSecrets.DEVELOPMENT_INTERNAL_TOKEN));
        assertNull(DeploymentSecrets.problem(new String[0], null, null), "no profile at all is a test run");
        assertNull(DeploymentSecrets.problem(null, null, null));
    }

    @Test
    void oneProductionProfileIsEnoughToRequireRealSecrets() {
        assertNotNull(DeploymentSecrets.problem(new String[]{"local", "mysql"}, null, null),
                "a deployment that is partly production is production");
    }

    @Test
    void profileNamesAreMatchedWhateverTheirCase() {
        assertTrue(DeploymentSecrets.development(new String[]{"LOCAL"}));
        assertTrue(DeploymentSecrets.development(new String[]{" Test "}));
        assertTrue(DeploymentSecrets.development(new String[]{"dev", "development"}));
    }
}
