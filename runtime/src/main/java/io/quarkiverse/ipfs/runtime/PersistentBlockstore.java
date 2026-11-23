package io.quarkiverse.ipfs.runtime;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

import org.peergos.Hash;
import org.peergos.blockstore.FileBlockstore;

import io.ipfs.cid.Cid;
import io.ipfs.multihash.Multihash;

/** Nabu flat-file layout with atomic writes and observable I/O failures. */
final class PersistentBlockstore extends FileBlockstore {

    private static final String SUFFIX = ".data";
    private final Path blocks;

    PersistentBlockstore(Path root) {
        super(root);
        blocks = root.resolve("blocks");
    }

    @Override
    public CompletableFuture<Cid> put(byte[] data, Cid.Codec codec) {
        Cid cid = new Cid(1, codec, Multihash.Type.sha2_256, Hash.sha256(data));
        Path target = blocks.resolve(getFilePath(cid));
        try {
            Files.createDirectories(target.getParent());
            Path staged = Files.createTempFile(target.getParent(), ".ipfs-", ".tmp");
            try {
                Files.write(staged, data);
                Files.move(staged, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } finally {
                Files.deleteIfExists(staged);
            }
            return CompletableFuture.completedFuture(cid);
        } catch (IOException failure) {
            return CompletableFuture.failedFuture(new UncheckedIOException("Failed to store IPFS block " + cid, failure));
        }
    }

    @Override
    public CompletableFuture<Optional<byte[]>> get(Cid cid) {
        try {
            return CompletableFuture.completedFuture(Optional.of(Files.readAllBytes(blocks.resolve(getFilePath(cid)))));
        } catch (NoSuchFileException missing) {
            return CompletableFuture.completedFuture(Optional.empty());
        } catch (IOException failure) {
            return CompletableFuture.failedFuture(new UncheckedIOException("Failed to read IPFS block " + cid, failure));
        }
    }

    @Override
    public CompletableFuture<Boolean> has(Cid cid) {
        try {
            return CompletableFuture.completedFuture(
                    Files.readAttributes(blocks.resolve(getFilePath(cid)), BasicFileAttributes.class).isRegularFile());
        } catch (NoSuchFileException missing) {
            return CompletableFuture.completedFuture(false);
        } catch (IOException failure) {
            return CompletableFuture.failedFuture(new UncheckedIOException("Failed to check IPFS block " + cid, failure));
        }
    }

    @Override
    public CompletableFuture<Boolean> rm(Cid cid) {
        try {
            return CompletableFuture.completedFuture(Files.deleteIfExists(blocks.resolve(getFilePath(cid))));
        } catch (IOException failure) {
            return CompletableFuture.failedFuture(new UncheckedIOException("Failed to remove IPFS block " + cid, failure));
        }
    }

    @Override
    public CompletableFuture<List<Cid>> refs(boolean useBlockstore) {
        try (var files = Files.find(blocks, 2, (path, attrs) -> attrs.isRegularFile()
                && path.getFileName().toString().endsWith(SUFFIX))) {
            return CompletableFuture.completedFuture(files.map(Path::getFileName).map(Path::toString)
                    .map(name -> keyToHash(name.substring(0, name.length() - SUFFIX.length()))).toList());
        } catch (IOException | UncheckedIOException failure) {
            return CompletableFuture.failedFuture(failure);
        }
    }
}
