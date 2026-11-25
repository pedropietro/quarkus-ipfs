package io.quarkiverse.ipfs.runtime.config;

import io.quarkus.runtime.annotations.ConfigPhase;
import io.quarkus.runtime.annotations.ConfigRoot;
import io.smallrye.config.ConfigMapping;
import io.smallrye.config.WithDefault;
import io.smallrye.config.WithName;

@ConfigMapping(prefix = "quarkus.ipfs")
@ConfigRoot(phase = ConfigPhase.BUILD_AND_RUN_TIME_FIXED)
public interface IpfsBuildTimeConfig {

    /**
     * Whether to enable the embedded IPFS node and its CDI services.
     * When false, no EmbeddedIpfs CDI bean is produced.
     */
    @WithDefault("true")
    boolean enabled();

    /**
     * Whether to register the HTTP client for an external Kubo node.
     */
    @WithName("kubo.enabled")
    @WithDefault("false")
    boolean kuboEnabled();

    /**
     * Whether to register a Kubo readiness check when the Kubo client and SmallRye Health are enabled.
     * The check makes an authenticated version request with a deadline of at most two seconds.
     */
    @WithName("kubo.health-enabled")
    @WithDefault("false")
    boolean kuboHealthEnabled();

    /**
     * Whether to register an IPFS readiness health check at {@code /q/health/ready}.
     * Requires the IPFS extension to be enabled and SmallRye Health to be installed.
     * The check only inspects the local node.
     */
    @WithDefault("true")
    boolean healthEnabled();

    /**
     * Whether to collect metrics for the enabled IPFS node or Kubo client when the application has Micrometer.
     */
    @WithDefault("true")
    boolean metricsEnabled();

    /**
     * Block storage backend to use.
     * {@code RAM} keeps all blocks in memory (default, suitable for testing).
     * {@code FILE} persists blocks to the path specified by {@code quarkus.ipfs.storage-path}.
     */
    @WithDefault("RAM")
    StorageType storageType();

    /** Block storage backend options. */
    enum StorageType {
        /** In-memory blockstore — fast but non-persistent. */
        RAM,
        /** File-based blockstore — persists blocks across restarts. */
        FILE
    }
}
