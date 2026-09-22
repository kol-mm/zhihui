package com.aiknowledge.gateway;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The gateway will not start a deployment that signs sessions with the key published in this repository. */
class GatewayDeploymentSecretsTest {
    private static final String[] PRODUCTION = {"mysql", "redis"};

    @Test
    void aConfiguredDeploymentStarts() {
        assertNull(GatewayDeploymentSecrets.problem(PRODUCTION, "a-secret-of-our-own-77ac"));
    }

    @Test
    void theDevelopmentKeyStopsADeployment() {
        String problem = GatewayDeploymentSecrets.problem(PRODUCTION, GatewayDeploymentSecrets.DEVELOPMENT_JWT_SECRET);

        assertNotNull(problem);
        assertTrue(problem.contains("AI_KNOWLEDGE_JWT_SECRET"), problem);
    }

    @Test
    void aMissingKeyStopsADeployment() {
        assertNotNull(GatewayDeploymentSecrets.problem(PRODUCTION, null));
        assertNotNull(GatewayDeploymentSecrets.problem(PRODUCTION, "  "));
    }

    @Test
    void developmentKeepsTheDefault() {
        assertNull(GatewayDeploymentSecrets.problem(new String[]{"local"}, null));
        assertNull(GatewayDeploymentSecrets.problem(new String[0], null));
        assertTrue(GatewayDeploymentSecrets.development(new String[]{"LOCAL", "test"}));
    }

    @Test
    void oneProductionProfileIsEnoughToRequireARealKey() {
        assertNotNull(GatewayDeploymentSecrets.problem(new String[]{"local", "mysql"}, null));
    }
}
