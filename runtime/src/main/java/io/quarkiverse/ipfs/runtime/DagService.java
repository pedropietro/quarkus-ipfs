package io.quarkiverse.ipfs.runtime;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.RejectedExecutionException;

import jakarta.inject.Inject;

import org.eclipse.microprofile.context.ManagedExecutor;

import io.ipfs.cid.Cid;
import io.quarkiverse.ipfs.runtime.UnixFs.FileLink;

/** File and directory operations over raw and UnixFS DAG-PB blocks. */
public class DagService {

    static final int CHUNK_SIZE = 256 * 1024;
    static final int MAX_FILE_LINKS = 174;

    @Inject
    BlockService blockService;

    @Inject
    ManagedExecutor executor;

    /**
     * Store a file and return its root CID. Single-chunk files use the raw codec.
     * Larger files use UnixFS nodes with at most 174 ordered links per node.
     * The caller owns the input stream.
     */
    public CompletableFuture<String> addFile(String name, InputStream data) {
        var importer = new FileImport(data);
        importer.readNext();
        return importer.result;
    }

    /** Read a raw or DAG-PB UnixFS file into memory. */
    public CompletableFuture<byte[]> getFile(String cidString) {
        var output = new ByteArrayOutputStream();
        return writeFile(cidString, output).thenApply(ignored -> output.toByteArray());
    }

    /**
     * Write a raw or DAG-PB UnixFS file to the caller's stream and return the number of bytes written.
     * Blocks are read and written in file order using the managed executor.
     * The caller keeps the stream open until completion and owns flushing and closing it.
     * On failure, including an {@link UncheckedIOException} from the stream, the output may contain partial data.
     */
    public CompletableFuture<Long> writeFile(String cidString, OutputStream output) {
        return CompletableFuture.completedFuture(cidString)
                .thenCompose(cid -> appendFile(Cid.decode(cid), Objects.requireNonNull(output)));
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

    private final class FileImport {
        private final InputStream input;
        private final CompletableFuture<String> result = new CompletableFuture<>();
        private final List<List<FileLink>> levels = new ArrayList<>();

        private FileImport(InputStream input) {
            this.input = input;
        }

        private void readNext() {
            if (result.isDone()) {
                return;
            }
            try {
                CompletableFuture.supplyAsync(() -> readChunk(input), executor)
                        .thenCompose(this::storeChunk)
                        .whenCompleteAsync((root, failure) -> {
                            if (failure != null) {
                                result.completeExceptionally(failure);
                            } else if (root != null) {
                                result.complete(root.cid().toString());
                            } else {
                                readNext();
                            }
                        }, executor)
                        .exceptionally(failure -> {
                            result.completeExceptionally(failure);
                            return null;
                        });
            } catch (RejectedExecutionException failure) {
                result.completeExceptionally(failure);
            }
        }

        private CompletableFuture<FileLink> storeChunk(byte[] chunk) {
            if (chunk.length == 0 && !levels.isEmpty()) {
                return finish(0);
            }
            int length = chunk.length;
            return blockService.put(chunk, Cid.Codec.Raw)
                    .thenCompose(cid -> append(0, new FileLink(cid, length, length)))
                    .thenCompose(ignored -> length == CHUNK_SIZE ? CompletableFuture.completedFuture(null) : finish(0));
        }

        private CompletableFuture<Void> append(int level, FileLink child) {
            if (level == levels.size()) {
                levels.add(new ArrayList<>());
            }
            var links = levels.get(level);
            if (links.size() < MAX_FILE_LINKS) {
                links.add(child);
                return CompletableFuture.completedFuture(null);
            }

            // Keep each full group until another child arrives, so files within the limit retain their flat root.
            return storeFileNode(links).thenCompose(parent -> {
                links.clear();
                links.add(child);
                return append(level + 1, parent);
            });
        }

        private CompletableFuture<FileLink> finish(int level) {
            var links = levels.get(level);
            if (level == levels.size() - 1) {
                return links.size() == 1 ? CompletableFuture.completedFuture(links.getFirst()) : storeFileNode(links);
            }
            return storeFileNode(links).thenCompose(parent -> append(level + 1, parent))
                    .thenCompose(ignored -> finish(level + 1));
        }

        private CompletableFuture<FileLink> storeFileNode(List<FileLink> links) {
            long fileSize = links.stream().mapToLong(FileLink::fileSize).reduce(0, Math::addExact);
            byte[] data = UnixFs.encodeFile(links, fileSize);
            long dagSize = links.stream().mapToLong(FileLink::dagSize).reduce(data.length, Math::addExact);
            return blockService.put(data, Cid.Codec.DagProtobuf).thenApply(cid -> new FileLink(cid, fileSize, dagSize));
        }
    }

    private static byte[] readChunk(InputStream input) {
        try {
            return input.readNBytes(CHUNK_SIZE);
        } catch (IOException failure) {
            throw new UncheckedIOException("Failed to read file data", failure);
        }
    }

    private CompletableFuture<Long> appendFile(Cid cid, OutputStream output) {
        if (cid.codec == Cid.Codec.Raw) {
            return blockService.get(cid.toString()).thenApplyAsync(bytes -> {
                writeBlock(output, bytes);
                return (long) bytes.length;
            }, executor);
        }
        return dagBytes(cid).thenComposeAsync(bytes -> {
            var node = UnixFs.decode(bytes);
            List<Long> sizes = node.fileBlockSizes();
            writeBlock(output, node.data());
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

    private static void writeBlock(OutputStream output, byte[] bytes) {
        try {
            if (bytes.length != 0) {
                output.write(bytes);
            }
        } catch (IOException failure) {
            throw new UncheckedIOException("Failed to write UnixFS file data", failure);
        }
    }

    private CompletableFuture<byte[]> dagBytes(Cid cid) {
        if (cid.codec != Cid.Codec.DagProtobuf) {
            return CompletableFuture.failedFuture(new IllegalArgumentException("Expected a UnixFS DAG-PB CID"));
        }
        return blockService.get(cid.toString());
    }
}
