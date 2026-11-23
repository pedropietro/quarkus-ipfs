package io.quarkiverse.ipfs.deployment;

import io.quarkiverse.ipfs.runtime.config.IpfsBuildTimeConfig;
import io.quarkiverse.ipfs.runtime.health.IpfsHealthCheck;
import io.quarkus.deployment.annotations.BuildStep;
import io.quarkus.smallrye.health.deployment.spi.HealthBuildItem;

/**
 * Registers the IPFS readiness health check conditionally.
 * This class is only loaded when quarkus-smallrye-health-deployment is on the classpath.
 */
class IpfsHealthProcessor {

    @BuildStep
    HealthBuildItem addHealthCheck(IpfsBuildTimeConfig config) {
        return !config.enabled() ? null : new HealthBuildItem(IpfsHealthCheck.class.getName(), config.healthEnabled());
    }
}
