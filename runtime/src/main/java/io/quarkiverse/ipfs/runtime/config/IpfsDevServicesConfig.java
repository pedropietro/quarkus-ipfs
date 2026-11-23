package io.quarkiverse.ipfs.runtime.config;

import io.quarkus.runtime.annotations.ConfigPhase;
import io.quarkus.runtime.annotations.ConfigRoot;
import io.smallrye.config.ConfigMapping;
import io.smallrye.config.WithDefault;

@ConfigMapping(prefix = "quarkus.ipfs.devservices")
@ConfigRoot(phase = ConfigPhase.BUILD_AND_RUN_TIME_FIXED)
public interface IpfsDevServicesConfig {

    /**
     * Whether Dev Services are enabled.
     * When enabled, an in-process IPFS node is automatically started in dev/test mode
     * so that no manual configuration is required.
     */
    @WithDefault("true")
    boolean enabled();

    /**
     * TCP port for the Dev Services IPFS node.
     * Use {@code 0} (default) to let the OS assign a random available port.
     */
    @WithDefault("0")
    int port();

    /**
     * Legacy Dev Services sharing option. The embedded node restarts on Quarkus live reloads.
     *
     * @deprecated The option has no effect.
     */
    @Deprecated
    @WithDefault("true")
    boolean shared();
}
