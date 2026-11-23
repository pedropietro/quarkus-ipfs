package io.quarkiverse.ipfs.test;

import jakarta.inject.Inject;

import org.jboss.shrinkwrap.api.ShrinkWrap;
import org.jboss.shrinkwrap.api.spec.JavaArchive;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.peergos.EmbeddedIpfs;

import io.quarkiverse.ipfs.runtime.IpfsClient;
import io.quarkus.test.QuarkusExtensionTest;

public class IpfsTest {

    @RegisterExtension
    static final QuarkusExtensionTest unitTest = new QuarkusExtensionTest()
            .setArchiveProducer(() -> ShrinkWrap.create(JavaArchive.class))
            .overrideConfigKey("quarkus.ipfs.async-bootstrap", "false")
            .overrideConfigKey("quarkus.ipfs.run-bitswap", "false");

    @Inject
    EmbeddedIpfs embeddedIpfs;

    @Inject
    IpfsClient ipfsClient;

    @Test
    public void testEmbeddedIpfsIsInjectable() {
        Assertions.assertNotNull(embeddedIpfs, "EmbeddedIpfs bean must be injectable");
    }

    @Test
    public void testIpfsClientIsInjectable() {
        Assertions.assertNotNull(ipfsClient, "IpfsClient bean must be injectable");
        Assertions.assertSame(embeddedIpfs, ipfsClient.getNode());
    }

    @Test
    public void testNodeHasPositiveMaxBlockSize() {
        Assertions.assertTrue(embeddedIpfs.maxBlockSize() > 0,
                "maxBlockSize must be positive");
    }
}
