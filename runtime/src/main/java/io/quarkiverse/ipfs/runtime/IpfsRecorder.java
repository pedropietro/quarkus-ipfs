package io.quarkiverse.ipfs.runtime;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import org.jboss.logging.Logger;
import org.peergos.EmbeddedIpfs;
import org.peergos.HostBuilder;
import org.peergos.RamAddressBook;
import org.peergos.blockstore.Blockstore;
import org.peergos.blockstore.RamBlockstore;
import org.peergos.config.IdentitySection;
import org.peergos.protocol.dht.RamRecordStore;

import io.ipfs.multiaddr.MultiAddress;
import io.libp2p.core.PeerId;
import io.libp2p.core.crypto.KeyKt;
import io.quarkiverse.ipfs.runtime.config.IpfsBuildTimeConfig.StorageType;
import io.quarkiverse.ipfs.runtime.config.IpfsRuntimeConfig;
import io.quarkus.runtime.RuntimeValue;
import io.quarkus.runtime.ShutdownContext;
import io.quarkus.runtime.annotations.Recorder;

@Recorder
public class IpfsRecorder {

    private static final Logger LOG = Logger.getLogger(IpfsRecorder.class);
    private static final int SHUTDOWN_TIMEOUT_SECONDS = 10;

    private final RuntimeValue<IpfsRuntimeConfig> config;

    public IpfsRecorder(RuntimeValue<IpfsRuntimeConfig> config) {
        this.config = config;
    }

    public RuntimeValue<EmbeddedIpfs> start(int defaultPort, StorageType storageType, ShutdownContext shutdownContext) {
        IpfsRuntimeConfig settings = config.getValue();
        var privateKey = settings.identityPrivateKey()
                .map(value -> KeyKt.unmarshalPrivateKey(Base64.getDecoder().decode(value)))
                .orElseGet(() -> new HostBuilder(new RamAddressBook()).generateIdentity().getPrivateKey());
        var identity = new IdentitySection(KeyKt.marshalPrivateKey(privateKey), PeerId.fromPubKey(privateKey.publicKey()));
        List<MultiAddress> swarm = settings.configuredSwarmAddresses()
                .orElseGet(() -> List.of("/ip4/127.0.0.1/tcp/" + defaultPort))
                .stream().map(MultiAddress::new).toList();
        List<MultiAddress> bootstrap = settings.bootstrapAddresses().orElseGet(List::of)
                .stream().map(MultiAddress::new).toList();
        Blockstore blocks = storageType == StorageType.FILE ? fileStore(settings) : new RamBlockstore();
        var records = new RamRecordStore();
        EmbeddedIpfs node;
        try {
            node = EmbeddedIpfs.build(records, blocks, settings.provideBlocks(), settings.runBitswap(),
                    swarm, bootstrap, identity, List.of(), (cid, peer, auth) -> CompletableFuture.completedFuture(true),
                    Optional.empty(), Optional.empty(), Optional.empty());
        } catch (RuntimeException | Error failure) {
            try {
                records.close();
            } catch (Exception cleanup) {
                failure.addSuppressed(cleanup);
            }
            throw failure;
        }

        AtomicBoolean stopped = new AtomicBoolean();
        try {
            node.start(settings.asyncBootstrap(), false);
            shutdownContext.addShutdownTask(() -> {
                try {
                    stop(node, stopped);
                } catch (Exception failure) {
                    LOG.warn("Failed to stop the embedded IPFS node", failure);
                }
            });
            return new RuntimeValue<>(node);
        } catch (RuntimeException | Error failure) {
            try {
                stop(node, stopped);
            } catch (Exception cleanup) {
                failure.addSuppressed(cleanup);
            }
            throw failure;
        }
    }

    private static Blockstore fileStore(IpfsRuntimeConfig settings) {
        String configured = settings.storagePath().orElseThrow(() -> new IllegalArgumentException(
                "quarkus.ipfs.storage-path is required when quarkus.ipfs.storage-type=FILE"));
        try {
            Path root = Path.of(configured).toAbsolutePath().normalize();
            Path blocks = Files.createDirectories(root.resolve("blocks"));
            if (!Files.isReadable(blocks) || !Files.isWritable(blocks)) {
                throw new IOException("Block directory must be readable and writable");
            }
            return new PersistentBlockstore(root);
        } catch (IOException | RuntimeException failure) {
            throw new IllegalArgumentException("Cannot open FILE blockstore at quarkus.ipfs.storage-path=" + configured,
                    failure);
        }
    }

    public RuntimeValue<IpfsNodeInfo> nodeInfo(RuntimeValue<EmbeddedIpfs> value) {
        var host = value.getValue().node;
        return new RuntimeValue<>(new IpfsNodeInfo(host.getPrivKey(), host.getPeerId()));
    }

    private static void stop(EmbeddedIpfs node, AtomicBoolean stopped) throws Exception {
        if (stopped.compareAndSet(false, true)) {
            try {
                node.stop().get(SHUTDOWN_TIMEOUT_SECONDS, TimeUnit.SECONDS);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw interrupted;
            }
        }
    }
}
