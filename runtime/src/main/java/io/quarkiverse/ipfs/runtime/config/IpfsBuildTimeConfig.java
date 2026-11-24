package io.quarkiverse.ipfs.runtime.config;

import io.quarkus.runtime.annotations.ConfigPhase;
import io.quarkus.runtime.annotations.ConfigRoot;
import io.smallrye.config.ConfigMapping;
import io.smallrye.config.WithDefault;

@ConfigMapping(prefix = "quarkus.ipfs")
@ConfigRoot(phase = ConfigPhase.BUILD_AND_RUN_TIME_FIXED)
public interface IpfsBuildTimeConfig {

    /**
     * Whether the IPFS extension is enabled.
     * When false, no EmbeddedIpfs CDI bean is produced.
     */
    @WithDefault("true")
    boolean enabled();

    /**
     * Whether to register an IPFS readiness health check at {@code /q/health/ready}.
     * Requires the IPFS extension to be enabled and SmallRye Health to be installed.
     * The check only inspects the local node.
     */
    @WithDefault("true")
    boolean healthEnabled();

    /**
     * Whether to collect IPFS metrics when the node is enabled and the application has Micrometer.
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
