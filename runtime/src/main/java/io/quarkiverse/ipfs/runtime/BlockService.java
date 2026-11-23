package io.quarkiverse.ipfs.runtime;

import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

import jakarta.inject.Inject;

import org.peergos.EmbeddedIpfs;
import org.peergos.HashedBlock;
import org.peergos.Want;

import io.ipfs.cid.Cid;

/**
 * CDI bean for raw block-level IPFS operations (put, get, has, remove by CID).
 */
public class BlockService {

    @Inject
    EmbeddedIpfs ipfs;

    /**
     * Retrieve a block by CID from the local store, or from the network if Bitswap is enabled.
     */
    public CompletableFuture<byte[]> get(String cidString) {
        Cid cid = Cid.decode(cidString);
        List<HashedBlock> results = ipfs.getBlocks(
                Collections.singletonList(new Want(cid)),
                Collections.emptySet(),
                true);
        if (results.isEmpty()) {
            return CompletableFuture.failedFuture(
                    new IllegalStateException("Block not found: " + cidString));
        }
        return CompletableFuture.completedFuture(results.get(0).block);
    }

    /**
     * Store a raw block in the local blockstore and return its CID.
     */
    public CompletableFuture<Cid> put(byte[] data, Cid.Codec codec) {
        return ipfs.blockstore.put(data, codec);
    }

    /**
     * Check whether a block is present in the local blockstore.
     */
    public CompletableFuture<Boolean> has(String cidString) {
        return ipfs.blockstore.has(Cid.decode(cidString));
    }

    /**
     * Remove a block from the local blockstore.
     */
    public CompletableFuture<Boolean> remove(String cidString) {
        return ipfs.blockstore.rm(Cid.decode(cidString));
    }

    /**
     * List all CIDs in the local blockstore.
     */
    public CompletableFuture<List<Cid>> listAll() {
        return ipfs.blockstore.refs(false);
    }

    /**
     * Retrieve a block's optional raw bytes directly from the local store (no network fetch).
     */
    public CompletableFuture<Optional<byte[]>> getLocal(String cidString) {
        return ipfs.blockstore.get(Cid.decode(cidString));
    }
}
