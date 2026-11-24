package io.quarkiverse.ipfs.test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import jakarta.inject.Inject;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.peergos.EmbeddedIpfs;

import io.quarkiverse.ipfs.runtime.KuboClient;
import io.quarkus.arc.Arc;
import io.quarkus.test.QuarkusExtensionTest;

class KuboClientTest {
    @RegisterExtension
    static final QuarkusExtensionTest APP = new QuarkusExtensionTest()
            .overrideConfigKey("quarkus.ipfs.enabled", "false")
            .overrideConfigKey("quarkus.ipfs.kubo.enabled", "true");

    @Inject
    KuboClient kubo;

    @Test
    void registersTheKuboClientWithoutAnEmbeddedNode() {
        assertNotNull(kubo);
        assertFalse(Arc.container().instance(EmbeddedIpfs.class).isAvailable());
    }
}
