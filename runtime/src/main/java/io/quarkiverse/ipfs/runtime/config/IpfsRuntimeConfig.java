package io.quarkiverse.ipfs.runtime.config;

import java.net.URI;
import java.time.Duration;
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
     * Dedicated filesystem directory required when {@code storage-type=FILE}.
     * Ignored when using the default RAM storage.
     */
    Optional<String> storagePath();

    /**
     * Whether to actively provide (announce) blocks to the DHT.
     * Enable only when connected to the public IPFS network.
     */
    @WithDefault("false")
    boolean provideBlocks();

    /**
     * Whether to enable the Bitswap 1.2.0 block-exchange protocol.
     * When disabled, block reads use the local store only. The node does not exchange blocks with peers.
     */
    @WithDefault("true")
    boolean runBitswap();

    /**
     * Skip the initial synchronous DHT routing-table refresh.
     * Configured bootstrap peers are still contacted during startup, and periodic refresh runs in the background.
     */
    @WithDefault("true")
    boolean asyncBootstrap();

    /**
     * Base64-encoded protobuf private key bytes for a persistent peer identity.
     * When absent, a new Ed25519 key is generated on each startup.
     */
    Optional<String> identityPrivateKey();

    /** Configuration for the Kubo HTTP client. */
    KuboConfig kubo();

    interface KuboConfig {
        /**
         * Base URL of the Kubo RPC server, including any reverse-proxy prefix.
         * The client appends {@code /api/v0/block/get}, {@code /api/v0/cat} or {@code /api/v0/add}.
         */
        @WithDefault("http://127.0.0.1:5001")
        URI apiUrl();

        /** Maximum time for a Kubo request, including the complete body transfer. */
        @WithDefault("PT30S")
        Duration timeout();

        /** Optional bearer token for the Kubo RPC API. */
        Optional<String> bearerToken();
    }
}
