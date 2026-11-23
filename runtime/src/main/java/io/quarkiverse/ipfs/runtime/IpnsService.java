package io.quarkiverse.ipfs.runtime;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

import jakarta.inject.Inject;

import org.peergos.EmbeddedIpfs;
import org.peergos.protocol.ipns.IpnsRecord;

import io.ipfs.multihash.Multihash;
import io.libp2p.core.PeerId;
import io.libp2p.core.crypto.PrivKey;

/**
 * IPNS publish and resolve operations.
 * Uses {@link EmbeddedIpfs#publishValue} and {@link EmbeddedIpfs#resolveValue}.
 */
public class IpnsService {

    @Inject
    EmbeddedIpfs ipfs;

    @Inject
    IpfsNodeInfo nodeInfo;

    /**
     * Publish a value (typically {@code /ipfs/<CID>}) under this node's IPNS name.
     *
     * @param value the value to publish (e.g. {@code "/ipfs/QmXxx..."})
     * @param sequence monotonically increasing sequence number (must be > previous)
     * @param hoursTtl TTL in hours before the record expires
     * @return number of DHT peers that received the record
     */
    public CompletableFuture<Integer> publish(String value, long sequence, int hoursTtl) {
        PrivKey privKey = nodeInfo.getPrivateKey();
        byte[] valueBytes = value.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        return ipfs.publishValue(privKey, valueBytes, Optional.empty(), Optional.empty(), sequence, hoursTtl);
    }

    /**
     * Resolve an IPNS name to its current published value.
     *
     * @param peerIdBase58 base58-encoded PeerId of the publisher
     * @param minResults minimum number of DHT results to wait for before returning
     * @return the published value as a string
     */
    public CompletableFuture<String> resolve(String peerIdBase58, int minResults) {
        return CompletableFuture.supplyAsync(() -> {
            Multihash multihash = Multihash.deserialize(PeerId.fromBase58(peerIdBase58).getBytes());
            List<IpnsRecord> records = ipfs.resolveRecords(multihash, minResults);
            if (records.isEmpty()) {
                throw new IllegalStateException("No IPNS records found for: " + peerIdBase58);
            }
            return new String(records.get(0).value, java.nio.charset.StandardCharsets.UTF_8);
        });
    }

    /**
     * Return the IPNS name (PeerId as base58) for this node.
     */
    public String getLocalIpnsName() {
        return nodeInfo.getPeerIdBase58();
    }
}
