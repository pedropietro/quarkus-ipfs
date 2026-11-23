package io.quarkiverse.ipfs.runtime;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

import jakarta.inject.Inject;

import org.eclipse.microprofile.context.ManagedExecutor;

import com.google.protobuf.ByteString;

import io.ipfs.cid.Cid;
import io.quarkiverse.ipfs.runtime.UnixFs.Link;

/** File and directory operations over raw and UnixFS DAG-PB blocks. */
public class DagService {

    static final int CHUNK_SIZE = 256 * 1024;

    @Inject
    BlockService blockService;

    @Inject
    ManagedExecutor executor;

    /**
     * Store a file and return its root CID. Single-chunk files use the raw codec.
     * The caller owns the input stream.
     */
    public CompletableFuture<String> addFile(String name, InputStream data) {
        return addChunks(data, new ArrayList<>()).thenApply(Cid::toString);
    }

    /** Read a raw or DAG-PB UnixFS file into memory. */
    public CompletableFuture<byte[]> getFile(String cidString) {
        var output = new ByteArrayOutputStream();
        return CompletableFuture.completedFuture(cidString)
                .thenCompose(cid -> appendFile(Cid.decode(cid), output))
                .thenApply(ignored -> output.toByteArray());
    }

    /** Store a directory whose entries link names to existing CIDs. */
    public CompletableFuture<String> makeDirectory(Map<String, String> entries) {
        return CompletableFuture.supplyAsync(() -> UnixFs.encodeDirectory(entries), executor)
                .thenCompose(data -> blockService.put(data, Cid.Codec.DagProtobuf))
                .thenApply(Cid::toString);
    }

    /** Return entry names in the order encoded in the directory. */
    public CompletableFuture<List<String>> listDirectory(String dirCid) {
        return CompletableFuture.completedFuture(dirCid)
                .thenCompose(cid -> dagBytes(Cid.decode(cid)))
                .thenApplyAsync(data -> UnixFs.decode(data).directoryNames(), executor);
    }

    private CompletableFuture<Cid> addChunks(InputStream input, List<Link> chunks) {
        return CompletableFuture.supplyAsync(() -> readChunk(input), executor)
                .thenCompose(chunk -> {
                    if (chunk.length == 0 && !chunks.isEmpty()) {
                        return finishFile(chunks);
                    }
                    return blockService.put(chunk, Cid.Codec.Raw).thenCompose(cid -> {
                        chunks.add(new Link(cid, ByteString.EMPTY, chunk.length));
                        return chunk.length == CHUNK_SIZE ? addChunks(input, chunks) : finishFile(chunks);
                    });
                });
    }

    private CompletableFuture<Cid> finishFile(List<Link> chunks) {
        if (chunks.size() == 1) {
            return CompletableFuture.completedFuture(chunks.getFirst().cid());
        }
        return blockService.put(UnixFs.encodeFile(chunks), Cid.Codec.DagProtobuf);
    }

    private static byte[] readChunk(InputStream input) {
        try {
            return input.readNBytes(CHUNK_SIZE);
        } catch (IOException failure) {
            throw new UncheckedIOException("Failed to read file data", failure);
        }
    }

    private CompletableFuture<Long> appendFile(Cid cid, ByteArrayOutputStream output) {
        if (cid.codec == Cid.Codec.Raw) {
            return blockService.get(cid.toString()).thenApplyAsync(bytes -> {
                output.writeBytes(bytes);
                return (long) bytes.length;
            }, executor);
        }
        return dagBytes(cid).thenComposeAsync(bytes -> {
            var node = UnixFs.decode(bytes);
            List<Long> sizes = node.fileBlockSizes();
            output.writeBytes(node.data());
            CompletableFuture<Long> appended = CompletableFuture.completedFuture((long) node.data().length);
            for (int index = 0; index < sizes.size(); index++) {
                Cid child = node.links().get(index).cid();
                long expected = sizes.get(index);
                appended = appended.thenCompose(total -> appendFile(child, output).thenApply(actual -> {
                    if (actual != expected) {
                        throw new IllegalArgumentException("UnixFS file block size does not match its link");
                    }
                    return total + actual;
                }));
            }
            return appended;
        }, executor);
    }

    private CompletableFuture<byte[]> dagBytes(Cid cid) {
        if (cid.codec != Cid.Codec.DagProtobuf) {
            return CompletableFuture.failedFuture(new IllegalArgumentException("Expected a UnixFS DAG-PB CID"));
        }
        return blockService.get(cid.toString());
    }
}
