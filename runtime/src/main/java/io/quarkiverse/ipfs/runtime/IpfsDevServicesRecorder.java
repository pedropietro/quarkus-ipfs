package io.quarkiverse.ipfs.runtime;

import java.util.Collections;
import java.util.List;
import java.util.Optional;

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
import io.quarkus.runtime.RuntimeValue;
import io.quarkus.runtime.ShutdownContext;
import io.quarkus.runtime.annotations.Recorder;

@Recorder
public class IpfsDevServicesRecorder {

    /**
     * Starts the embedded IPFS dev service node at RUNTIME_INIT.
     * Called only in dev/test mode; the resulting RuntimeValues are passed to the main
     * IpfsProcessor so it does not start a second node.
     *
     * @param port TCP port to listen on (0 = random)
     * @return pair of RuntimeValues for EmbeddedIpfs and IpfsNodeInfo
     */
    public RuntimeValue<EmbeddedIpfs> startDevNode(int port, ShutdownContext shutdownContext) {
        try {
            var builder = new HostBuilder(new RamAddressBook()).generateIdentity();
            PrivKey privKey = builder.getPrivateKey();
            byte[] privKeyBytes = KeyKt.marshalPrivateKey(privKey);
            PeerId peerId = PeerId.fromPubKey(privKey.publicKey());
            IdentitySection identity = new IdentitySection(privKeyBytes, peerId);

            String addr = port == 0 ? "/ip4/127.0.0.1/tcp/0" : "/ip4/127.0.0.1/tcp/" + port;
            List<MultiAddress> swarmAddresses = Collections.singletonList(new MultiAddress(addr));

            EmbeddedIpfs node = EmbeddedIpfs.build(
                    new RamRecordStore(),
                    new RamBlockstore(),
                    false,
                    swarmAddresses,
                    Collections.emptyList(),
                    identity,
                    (cid, peer, auth) -> java.util.concurrent.CompletableFuture.completedFuture(true),
                    Optional.empty());

            node.start(false);

            shutdownContext.addLastShutdownTask(() -> {
                try {
                    node.stop().get();
                } catch (Exception ignored) {
                }
            });

            return new RuntimeValue<>(node);
        } catch (Exception e) {
            throw new RuntimeException("Failed to start IPFS Dev Services node", e);
        }
    }

    /**
     * Extracts node identity from an already-started EmbeddedIpfs node.
     */
    public RuntimeValue<IpfsNodeInfo> extractNodeInfo(RuntimeValue<EmbeddedIpfs> ipfsValue) {
        EmbeddedIpfs ipfs = ipfsValue.getValue();
        PrivKey privKey = ipfs.node.getPrivKey();
        PeerId peerId = ipfs.node.getPeerId();
        return new RuntimeValue<>(new IpfsNodeInfo(privKey, peerId));
    }
}
