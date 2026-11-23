package io.quarkiverse.ipfs.runtime.config;

import java.util.List;
import java.util.Optional;

import io.quarkus.runtime.annotations.ConfigPhase;
import io.quarkus.runtime.annotations.ConfigRoot;
import io.smallrye.config.ConfigMapping;
import io.smallrye.config.WithDefault;
import io.smallrye.config.WithName;

@ConfigMapping(prefix = "quarkus.ipfs")
@ConfigRoot(phase = ConfigPhase.RUN_TIME)
public interface IpfsRuntimeConfig {

    /**
     * Multiaddresses the IPFS node will listen on for peer connections.
     * Example: {@code /ip4/0.0.0.0/tcp/4001}.
     * Use {@code /ip4/127.0.0.1/tcp/0} for a random port on loopback (default).
     */
    @WithName("swarm-addresses")
    Optional<List<String>> configuredSwarmAddresses();

    default List<String> swarmAddresses() {
        return configuredSwarmAddresses().orElseGet(() -> List.of("/ip4/127.0.0.1/tcp/0"));
    }

    /**
     * Multiaddresses of bootstrap peers used to join the IPFS network.
     * When empty the node operates in isolated mode (no DHT bootstrap).
     */
    Optional<List<String>> bootstrapAddresses();

    /**
     * Filesystem path used when {@code storage-type=FILE}.
     * Ignored when using the default RAM storage.
     */
    @WithDefault("${user.home}/.ipfs")
    String storagePath();

    /**
     * Whether to actively provide (announce) blocks to the DHT.
     * Enable only when connected to the public IPFS network.
     */
    @WithDefault("false")
    boolean provideBlocks();

    /**
     * Whether to enable the Bitswap 1.2.0 block-exchange protocol.
     * Disable to reduce startup time in isolated/test environments.
     */
    @WithDefault("true")
    boolean runBitswap();

    /**
     * Whether to run DHT bootstrap asynchronously.
     * Set to {@code false} in tests to ensure the node is fully bootstrapped before proceeding.
     */
    @WithDefault("true")
    boolean asyncBootstrap();

    /**
     * Base64-encoded protobuf private key bytes for a persistent peer identity.
     * When absent, a new Ed25519 key is generated on each startup.
     */
    Optional<String> identityPrivateKey();
}
