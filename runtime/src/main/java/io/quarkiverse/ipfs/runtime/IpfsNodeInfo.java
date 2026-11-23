package io.quarkiverse.ipfs.runtime;

import io.libp2p.core.PeerId;
import io.libp2p.core.crypto.PrivKey;

/**
 * Holds the node's cryptographic identity (PrivKey + PeerId).
 * Produced as a CDI Singleton alongside {@link org.peergos.EmbeddedIpfs}.
 * Required by {@link IpnsService} for publish/resolve operations.
 */
public class IpfsNodeInfo {

    private final PrivKey privateKey;
    private final PeerId peerId;

    public IpfsNodeInfo(PrivKey privateKey, PeerId peerId) {
        this.privateKey = privateKey;
        this.peerId = peerId;
    }

    public PrivKey getPrivateKey() {
        return privateKey;
    }

    public PeerId getPeerId() {
        return peerId;
    }

    public String getPeerIdBase58() {
        return peerId.toBase58();
    }
}
