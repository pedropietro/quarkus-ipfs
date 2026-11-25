package org.acme;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.util.Arrays;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;

import javax.net.ssl.SSLException;

import jakarta.inject.Inject;

import org.peergos.EmbeddedIpfs;

import io.quarkiverse.ipfs.runtime.KuboClient;
import io.quarkus.arc.Arc;
import io.quarkus.runtime.QuarkusApplication;
import io.quarkus.runtime.annotations.QuarkusMain;

@QuarkusMain
public class KuboSmokeTest implements QuarkusApplication {
    private static final String EXPECTED_CID = "bafkreiaabc27q6hpnrclwsa5cnjgzv3uv6zbonq7avwsn6bm2ncdi5fbjq";

    @Inject
    KuboClient kubo;

    @Override
    public int run(String... args) throws Exception {
        if (Arc.container().instance(EmbeddedIpfs.class).isAvailable()) {
            throw new IllegalStateException("The embedded IPFS node must be disabled");
        }
        // The configured swarm port must be available throughout the Kubo-only test.
        try (var listener = new ServerSocket(4001, 1, InetAddress.getByName("127.0.0.1"))) {
            byte[] payload = { 'I', 'P', 'F', 'S', 0, (byte) 0xff, '\n' };
            String scenario = System.getenv("KUBO_TEST_EXPECT");
            if (scenario != null && !"success".equals(scenario)) {
                verifyFailure(scenario, payload);
                return 0;
            }

            String cid;
            try (var source = new ByteArrayInputStream(payload)) {
                cid = kubo.addFile(source, false).get(30, TimeUnit.SECONDS);
            }
            if (!EXPECTED_CID.equals(cid)) {
                throw new IllegalStateException("Unexpected CID from Kubo: " + cid);
            }

            var output = new ByteArrayOutputStream();
            long bytes = kubo.writeFile(cid, output).get(30, TimeUnit.SECONDS);
            if (bytes != payload.length || !Arrays.equals(payload, output.toByteArray())) {
                throw new IllegalStateException("Kubo returned different bytes for CID " + cid);
            }

            if ("success".equals(scenario)
                    && !Arrays.equals(payload, kubo.getBlock(cid).get(30, TimeUnit.SECONDS))) {
                throw new IllegalStateException("Kubo returned different raw block bytes for CID " + cid);
            }

            System.out.printf("Authenticated Kubo consumer imported and read %d bytes by CID %s. "
                    + "Embedded node absent and swarm port %d available.%n", bytes, cid, listener.getLocalPort());
            return 0;
        }
    }
    private void verifyFailure(String scenario, byte[] payload) throws Exception {
        try (var input = new ByteArrayInputStream(payload)) {
            assertFailure(scenario, kubo.addFile(input, false));
        }
        assertFailure(scenario, kubo.writeFile(EXPECTED_CID, OutputStream.nullOutputStream()));
        if (!"trailers".equals(scenario)) {
            assertFailure(scenario, kubo.getBlock(EXPECTED_CID));
        }
        System.out.println("Kubo HTTPS check rejected " + scenario + ".");
    }

    private static void assertFailure(String scenario, CompletableFuture<?> operation) throws Exception {
        try {
            operation.get(30, TimeUnit.SECONDS);
        } catch (ExecutionException failure) {
            Throwable cause = failure.getCause();
            boolean expected = switch (scenario) {
                case "redirect" -> cause instanceof KuboClient.KuboRequestException error && error.statusCode() == 307;
                case "trailers" -> cause instanceof UncheckedIOException error
                        && error.getCause().getMessage().contains("X-Stream-Error");
                case "untrusted CA", "hostname mismatch" -> isTlsFailure(cause);
                default -> false;
            };
            if (expected) {
                return;
            }
            throw new IllegalStateException("Unexpected Kubo failure for " + scenario, failure);
        }
        throw new IllegalStateException("Kubo succeeded with " + scenario);
    }

    private static boolean isTlsFailure(Throwable cause) {
        for (Throwable current = cause; current != null; current = current.getCause()) {
            if (current instanceof SSLException) {
                return true;
            }
        }
        return false;
    }
}
