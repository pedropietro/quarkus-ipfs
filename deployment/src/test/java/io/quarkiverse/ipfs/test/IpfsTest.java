package io.quarkiverse.ipfs.test;

import jakarta.inject.Inject;

import org.jboss.shrinkwrap.api.ShrinkWrap;
import org.jboss.shrinkwrap.api.spec.JavaArchive;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.peergos.EmbeddedIpfs;

import io.quarkiverse.ipfs.runtime.IpfsClient;
import io.quarkiverse.ipfs.runtime.KuboClient;
import io.quarkiverse.ipfs.runtime.health.IpfsHealthCheck;
import io.quarkus.arc.Arc;
import io.quarkus.test.QuarkusExtensionTest;

public class IpfsTest {

    @RegisterExtension
    static final QuarkusExtensionTest unitTest = new QuarkusExtensionTest()
            .setArchiveProducer(() -> ShrinkWrap.create(JavaArchive.class))
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
    public void startsWithoutTheOptionalKuboClient() {
        Assertions.assertFalse(Arc.container().instance(KuboClient.class).isAvailable());
        Assertions.assertFalse(embeddedIpfs.node.listenAddresses().isEmpty());
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

    @Test
    public void startsWithoutSmallRyeHealth() {
        Assertions.assertNull(getClass().getClassLoader()
                .getResource("io/quarkus/smallrye/health/runtime/SmallRyeHealthRecorder.class"));
        Assertions.assertFalse(Arc.container().instance(IpfsHealthCheck.class).isAvailable());
        Assertions.assertFalse(embeddedIpfs.node.listenAddresses().isEmpty());
    }
}
