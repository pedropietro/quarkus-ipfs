package io.quarkiverse.ipfs.runtime;

import static java.nio.charset.StandardCharsets.UTF_8;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import jakarta.inject.Inject;

import org.eclipse.microprofile.context.ManagedExecutor;
import org.peergos.EmbeddedIpfs;
import org.peergos.protocol.ipns.IpnsRecord;

import io.ipfs.multihash.Multihash;
import io.libp2p.core.PeerId;
import io.libp2p.core.crypto.PrivKey;

/**
 * IPNS publish and resolve operations.
 */
public class IpnsService {

    @Inject
    EmbeddedIpfs ipfs;

    @Inject
    IpfsNodeInfo nodeInfo;

    @Inject
    ManagedExecutor executor;

    /**
     * Publish a value (typically {@code /ipfs/<CID>}) under this node's IPNS name.
     *
     * @param value the value to publish (e.g. {@code "/ipfs/QmXxx..."})
     * @param sequence monotonically increasing sequence number (must be > previous)
     * @param hoursTtl TTL in hours before the record expires
     * @return number of DHT peers that received the record
     */
    public CompletableFuture<Integer> publish(String value, long sequence, int hoursTtl) {
        return CompletableFuture.supplyAsync(() -> {
            PrivKey privKey = nodeInfo.getPrivateKey();
            return ipfs.publishValue(privKey, value.getBytes(UTF_8), Optional.empty(), Optional.empty(), sequence, hoursTtl);
        }, executor).thenCompose(result -> result);
    }

    /**
     * Resolve an IPNS name to its current published value.
     *
     * @param peerIdBase58 base58-encoded PeerId of the publisher
     * @param minResults target number of valid records to collect before stopping the DHT lookup
     * @return the value with the highest sequence among the valid records found, or a failed future
     *         if no valid record is found or the lookup times out
     */
    public CompletableFuture<String> resolve(String peerIdBase58, int minResults) {
        return CompletableFuture.supplyAsync(() -> {
            Multihash multihash = Multihash.deserialize(PeerId.fromBase58(peerIdBase58).getBytes());
            var records = ipfs.resolveRecords(multihash, minResults);
            var now = LocalDateTime.now(ZoneOffset.UTC);
            return records.stream()
                    .filter(record -> record.expiry.isAfter(now))
                    .max(IpnsRecord::compareTo)
                    .map(record -> new String(record.value, UTF_8))
                    .orElseThrow(() -> new IllegalStateException("No valid IPNS records found for: " + peerIdBase58));
        }, executor).orTimeout(30, TimeUnit.SECONDS);
    }

    /**
     * Return the IPNS name (PeerId as base58) for this node.
     */
    public String getLocalIpnsName() {
        return nodeInfo.getPeerIdBase58();
    }
}
