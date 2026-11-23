package io.quarkiverse.ipfs.test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import jakarta.inject.Inject;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.peergos.EmbeddedIpfs;

import io.ipfs.cid.Cid;
import io.quarkiverse.ipfs.runtime.BlockService;
import io.quarkiverse.ipfs.runtime.config.IpfsBuildTimeConfig.StorageType;
import io.quarkus.test.QuarkusExtensionTest;

class IpfsPeerExchangeTest {
    @RegisterExtension
    static final QuarkusExtensionTest APP = new QuarkusExtensionTest()
            .withApplicationRoot(jar -> jar.addClass(TestIpfsNode.class));

    @Inject
    EmbeddedIpfs requester;

    @Inject
    BlockService blocks;

    @Test
    void retrievesAndCachesBlockFromConnectedPeer() throws Exception {
        try (var shutdown = new TestIpfsNode()) {
            var provider = shutdown.start(StorageType.RAM, Map.of());
            byte[] payload = "block provided by another peer".getBytes(StandardCharsets.UTF_8);
            Cid cid = provider.blockstore.put(payload, Cid.Codec.Raw).join();
            String key = cid.toString();
            assertFalse(blocks.has(key).get(5, TimeUnit.SECONDS));

            var address = provider.node.listenAddresses().getFirst();
            requester.node.getAddressBook().addAddrs(provider.node.getPeerId(), 0, address).get(5, TimeUnit.SECONDS);
            requester.node.getNetwork().connect(address).get(10, TimeUnit.SECONDS);

            assertArrayEquals(payload, blocks.get(key).get(20, TimeUnit.SECONDS));
            assertTrue(blocks.has(key).get(5, TimeUnit.SECONDS));
            assertArrayEquals(payload, blocks.getLocal(key).get(5, TimeUnit.SECONDS).orElseThrow());
        }
    }
}
