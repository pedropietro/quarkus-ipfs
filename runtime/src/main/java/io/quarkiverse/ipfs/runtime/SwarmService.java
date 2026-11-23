package io.quarkiverse.ipfs.runtime;

import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Collectors;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import org.peergos.EmbeddedIpfs;

import io.libp2p.core.Connection;
import io.libp2p.core.Host;
import io.libp2p.core.PeerId;
import io.libp2p.core.multiformats.Multiaddr;

/**
 * Peer and swarm management: connect, disconnect, list connected peers, get listen addresses.
 */
@ApplicationScoped
public class SwarmService {

    @Inject
    EmbeddedIpfs ipfs;

    /**
     * Connect to a remote peer by multiaddress (e.g. {@code /ip4/1.2.3.4/tcp/4001/p2p/Qm...}).
     */
    public CompletableFuture<Void> connect(String multiaddrStr) {
        Host host = ipfs.node;
        Multiaddr addr = new Multiaddr(multiaddrStr);
        return host.getNetwork().connect(addr).thenApply(conn -> null);
    }

    /**
     * Disconnect from a peer by its base58 PeerId.
     */
    public CompletableFuture<Void> disconnect(String peerIdBase58) {
        Host host = ipfs.node;
        PeerId target = PeerId.fromBase58(peerIdBase58);
        List<Connection> matching = host.getNetwork().getConnections().stream()
                .filter(c -> target.equals(c.remoteAddress().getPeerId()))
                .collect(Collectors.toList());
        if (matching.isEmpty()) {
            return CompletableFuture.completedFuture(null);
        }
        return CompletableFuture.allOf(matching.stream()
                .map(c -> host.getNetwork().disconnect(c).thenApply(u -> (Void) null))
                .toArray(CompletableFuture[]::new));
    }

    /**
     * List base58 PeerIds of currently connected peers.
     */
    public List<String> listPeers() {
        return ipfs.node.getNetwork().getConnections().stream()
                .map(conn -> conn.remoteAddress().getPeerId())
                .filter(Objects::nonNull)
                .map(PeerId::toBase58)
                .distinct()
                .collect(Collectors.toList());
    }

    /**
     * Return this node's own listen addresses.
     */
    public List<String> getSwarmAddresses() {
        return ipfs.node.listenAddresses().stream()
                .map(Multiaddr::toString)
                .collect(Collectors.toList());
    }

    /**
     * Return this node's PeerId as base58.
     */
    public String getLocalPeerId() {
        return ipfs.node.getPeerId().toBase58();
    }
}
