package io.quarkiverse.ipfs.deployment;

import io.quarkiverse.ipfs.runtime.config.IpfsBuildTimeConfig;
import io.quarkiverse.ipfs.runtime.health.IpfsHealthCheck;
import io.quarkus.deployment.Capabilities;
import io.quarkus.deployment.Capability;
import io.quarkus.deployment.annotations.BuildStep;
import io.quarkus.smallrye.health.deployment.spi.HealthBuildItem;

class IpfsHealthProcessor {

    @BuildStep
    HealthBuildItem addHealthCheck(IpfsBuildTimeConfig config, Capabilities capabilities) {
        if (!config.enabled() || !config.healthEnabled() || !capabilities.isPresent(Capability.SMALLRYE_HEALTH)) {
            return null;
        }
        return new HealthBuildItem(IpfsHealthCheck.class.getName(), true);
    }
}
