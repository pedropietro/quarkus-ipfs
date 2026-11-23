package io.quarkiverse.ipfs.runtime;

import java.util.List;
import java.util.NoSuchElementException;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

import jakarta.inject.Inject;

import org.eclipse.microprofile.context.ManagedExecutor;
import org.peergos.BitswapBlockService;
import org.peergos.EmbeddedIpfs;
import org.peergos.Want;

import io.ipfs.cid.Cid;

/** CDI bean for raw block operations. All failures are reported by the returned future. */
public class BlockService {

    @Inject
    EmbeddedIpfs ipfs;

    @Inject
    ManagedExecutor executor;

    /**
     * Read locally first and fall back to Bitswap when available. Missing blocks fail with
     * {@link NoSuchElementException}. Network failures, including timeouts, are reported as a {@link BlockFetchException}.
     */
    public CompletableFuture<byte[]> get(String cidString) {
        return io(() -> {
            Cid cid = decode(cidString);
            return ipfs.blockstore.get(cid).thenCompose(local -> {
                if (local.isPresent()) {
                    return CompletableFuture.completedFuture(local.get());
                }
                if (!(ipfs.blockRetriever.orElse(null) instanceof BitswapBlockService bitswap)) {
                    return missing(cid);
                }
                return CompletableFuture.supplyAsync(() -> bitswap.getBlock(new Want(cid)), executor)
                        .orTimeout(30, TimeUnit.SECONDS)
                        .exceptionally(failure -> {
                            Throwable cause = failure instanceof CompletionException wrapped ? wrapped.getCause() : failure;
                            throw new BlockFetchException(cid, cause);
                        })
                        .thenComposeAsync(found -> found
                                .map(block -> ipfs.blockstore.put(block.block, block.hash.codec)
                                        .thenApply(stored -> block.block))
                                .orElseGet(() -> missing(cid)), executor);
            });
        });
    }

    public CompletableFuture<Cid> put(byte[] data, Cid.Codec codec) {
        return io(() -> ipfs.blockstore.put(data, codec));
    }

    /** Check only the local blockstore. */
    public CompletableFuture<Boolean> has(String cidString) {
        return io(() -> ipfs.blockstore.has(decode(cidString)));
    }

    public CompletableFuture<Boolean> remove(String cidString) {
        return io(() -> ipfs.blockstore.rm(decode(cidString)));
    }

    public CompletableFuture<List<Cid>> listAll() {
        return io(() -> ipfs.blockstore.refs(false));
    }

    /** Return an empty optional when the local block is absent. */
    public CompletableFuture<Optional<byte[]>> getLocal(String cidString) {
        return io(() -> ipfs.blockstore.get(decode(cidString)));
    }

    private <T> CompletableFuture<T> io(Supplier<CompletableFuture<T>> operation) {
        return CompletableFuture.supplyAsync(operation, executor).thenCompose(result -> result);
    }

    private static Cid decode(String value) {
        try {
            return Cid.decode(value);
        } catch (RuntimeException failure) {
            throw new IllegalArgumentException("Invalid IPFS CID", failure);
        }
    }

    /** A Bitswap or DHT lookup failed before a block could be retrieved. */
    public static final class BlockFetchException extends RuntimeException {
        private BlockFetchException(Cid cid, Throwable cause) {
            super("Failed to fetch IPFS block " + cid, cause);
        }
    }

    private static CompletableFuture<byte[]> missing(Cid cid) {
        return CompletableFuture.failedFuture(new NoSuchElementException("IPFS block not found: " + cid));
    }
}
