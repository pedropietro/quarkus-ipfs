package io.quarkiverse.ipfs.test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.peergos.HostBuilder;
import org.peergos.RamAddressBook;

import io.ipfs.cid.Cid;
import io.libp2p.core.PeerId;
import io.libp2p.core.crypto.KeyKt;
import io.quarkiverse.ipfs.runtime.config.IpfsBuildTimeConfig.StorageType;

class IpfsBlockstorePersistenceTest {

    @Test
    void reopensFileStoreAndConfiguredIdentity(@TempDir Path directory) throws Exception {
        Path storage = directory.resolve("new-store");
        var key = new HostBuilder(new RamAddressBook()).generateIdentity().getPrivateKey();
        var settings = Map.of("quarkus.ipfs.storage-path", storage.toString(),
                "quarkus.ipfs.identity-private-key", Base64.getEncoder().encodeToString(KeyKt.marshalPrivateKey(key)));
        byte[] data = "persisted IPFS block".getBytes(StandardCharsets.UTF_8);
        Cid cid;
        Cid empty;
        try (var shutdown = new TestIpfsNode()) {
            var node = shutdown.start(StorageType.FILE, settings);
            assertEquals(PeerId.fromPubKey(key.publicKey()), node.node.getPeerId());
            cid = node.blockstore.put(data, Cid.Codec.Raw).join();
            empty = node.blockstore.put(new byte[0], Cid.Codec.Raw).join();
        }
        try (var shutdown = new TestIpfsNode()) {
            var node = shutdown.start(StorageType.FILE, settings);
            assertEquals(PeerId.fromPubKey(key.publicKey()), node.node.getPeerId());
            assertArrayEquals(data, node.blockstore.get(cid).join().orElseThrow());
            assertArrayEquals(new byte[0], node.blockstore.get(empty).join().orElseThrow());
            assertTrue(node.blockstore.refs(false).join().containsAll(java.util.List.of(cid, empty)));
        }
    }

    @Test
    void rejectsMissingPathAndNonDirectory(@TempDir Path directory) throws Exception {
        try (var shutdown = new TestIpfsNode()) {
            var missing = assertThrows(IllegalArgumentException.class, () -> shutdown.start(StorageType.FILE, Map.of()));
            assertTrue(missing.getMessage().contains("quarkus.ipfs.storage-path is required"));
            Path file = Files.createFile(directory.resolve("not-a-directory"));
            var invalid = assertThrows(IllegalArgumentException.class,
                    () -> shutdown.start(StorageType.FILE, Map.of("quarkus.ipfs.storage-path", file.toString())));
            assertTrue(invalid.getMessage().contains("Cannot open FILE blockstore"));
        }
    }
}
