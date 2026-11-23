package io.quarkiverse.ipfs.runtime;

import java.util.Base64;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

import org.peergos.EmbeddedIpfs;
import org.peergos.HostBuilder;
import org.peergos.RamAddressBook;
import org.peergos.blockstore.RamBlockstore;
import org.peergos.config.IdentitySection;
import org.peergos.protocol.dht.RamRecordStore;

import io.ipfs.multiaddr.MultiAddress;
import io.libp2p.core.PeerId;
import io.libp2p.core.crypto.KeyKt;
import io.libp2p.core.crypto.PrivKey;
import io.quarkiverse.ipfs.runtime.config.IpfsBuildTimeConfig;
import io.quarkiverse.ipfs.runtime.config.IpfsRuntimeConfig;
import io.quarkus.arc.Arc;
import io.quarkus.runtime.RuntimeValue;
import io.quarkus.runtime.ShutdownContext;
import io.quarkus.runtime.annotations.Recorder;

@Recorder
public class IpfsRecorder {

    /**
     * Creates the node identity (generates or loads from config).
     * Must be called before {@link #createEmbeddedIpfs} so that both share the same identity.
     * Accesses {@link IpfsRuntimeConfig} from CDI at RUNTIME_INIT time.
     */
    public RuntimeValue<IpfsNodeInfo> createNodeInfo() {
        IpfsRuntimeConfig runtimeConfig = Arc.container().instance(IpfsRuntimeConfig.class).get();
        byte[] privKeyBytes = resolvePrivKeyBytes(runtimeConfig);
        PrivKey privKey = KeyKt.unmarshalPrivateKey(privKeyBytes);
        PeerId peerId = PeerId.fromPubKey(privKey.publicKey());
        return new RuntimeValue<>(new IpfsNodeInfo(privKey, peerId));
    }

    /**
     * Creates and starts the embedded IPFS node using the pre-resolved identity from
     * {@link #createNodeInfo}. When Dev Services already started a node (dev/test mode),
     * that node is reused instead of starting a new one.
     * Accesses {@link IpfsRuntimeConfig} from CDI at RUNTIME_INIT time.
     */
    public RuntimeValue<EmbeddedIpfs> createEmbeddedIpfs(
            RuntimeValue<IpfsNodeInfo> nodeInfoValue,
            IpfsBuildTimeConfig buildTimeConfig,
            ShutdownContext shutdownContext) {

        IpfsRuntimeConfig runtimeConfig = Arc.container().instance(IpfsRuntimeConfig.class).get();

        try {
            IpfsNodeInfo nodeInfo = nodeInfoValue.getValue();
            PrivKey privKey = nodeInfo.getPrivateKey();
            PeerId peerId = nodeInfo.getPeerId();
            byte[] privKeyBytes = KeyKt.marshalPrivateKey(privKey);
            IdentitySection identity = new IdentitySection(privKeyBytes, peerId);

            var blockstore = new RamBlockstore();
            var records = new RamRecordStore();

            List<MultiAddress> swarmAddresses = runtimeConfig.swarmAddresses().stream()
                    .map(MultiAddress::new)
                    .collect(Collectors.toList());

            List<MultiAddress> bootstrap = runtimeConfig.bootstrapAddresses()
                    .map(addrs -> addrs.stream()
                            .map(MultiAddress::new)
                            .collect(Collectors.<MultiAddress> toList()))
                    .orElse(Collections.emptyList());

            EmbeddedIpfs ipfs = EmbeddedIpfs.build(
                    records,
                    blockstore,
                    runtimeConfig.provideBlocks(),
                    swarmAddresses,
                    bootstrap,
                    identity,
                    (cid, peer, auth) -> java.util.concurrent.CompletableFuture.completedFuture(true),
                    Optional.empty());

            ipfs.start(runtimeConfig.asyncBootstrap());

            shutdownContext.addShutdownTask(() -> {
                try {
                    ipfs.stop().get();
                } catch (Exception e) {
                    // do not block shutdown
                }
            });

            return new RuntimeValue<>(ipfs);
        } catch (Exception e) {
            throw new RuntimeException("Failed to start embedded IPFS node", e);
        }
    }

    private byte[] resolvePrivKeyBytes(IpfsRuntimeConfig config) {
        if (config.identityPrivateKey().isPresent()) {
            return Base64.getDecoder().decode(config.identityPrivateKey().get());
        }
        var builder = new HostBuilder(new RamAddressBook()).generateIdentity();
        return KeyKt.marshalPrivateKey(builder.getPrivateKey());
    }
}
