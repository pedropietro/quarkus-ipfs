package io.quarkiverse.ipfs.test;

import static org.junit.jupiter.api.Assertions.assertFalse;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.peergos.EmbeddedIpfs;

import io.quarkiverse.ipfs.runtime.BlockService;
import io.quarkiverse.ipfs.runtime.DagService;
import io.quarkiverse.ipfs.runtime.IpfsClient;
import io.quarkiverse.ipfs.runtime.IpfsNodeInfo;
import io.quarkiverse.ipfs.runtime.IpnsService;
import io.quarkiverse.ipfs.runtime.SwarmService;
import io.quarkiverse.ipfs.runtime.health.IpfsHealthCheck;
import io.quarkus.arc.Arc;
import io.quarkus.test.QuarkusExtensionTest;

class IpfsDisabledTest {

    @RegisterExtension
    static final QuarkusExtensionTest APP = new QuarkusExtensionTest()
            .withApplicationRoot(jar -> {
            })
            .overrideConfigKey("quarkus.ipfs.enabled", "false");

    @Test
    void disabledExtensionHasNoIpfsBeans() {
        for (Class<?> type : new Class<?>[] { EmbeddedIpfs.class, IpfsNodeInfo.class, IpfsClient.class,
                BlockService.class, DagService.class, IpnsService.class, SwarmService.class, IpfsHealthCheck.class }) {
            assertFalse(Arc.container().instance(type).isAvailable(), type.getName());
        }
    }
}
