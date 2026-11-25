package org.acme;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.DigestOutputStream;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.Random;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import javax.net.ssl.SSLException;

import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;

import org.peergos.EmbeddedIpfs;

import io.quarkiverse.ipfs.runtime.KuboClient;
import io.quarkus.arc.Arc;
import io.quarkus.runtime.QuarkusApplication;
import io.quarkus.runtime.annotations.QuarkusMain;

@QuarkusMain
public class KuboSmokeTest implements QuarkusApplication {
    private static final String EXPECTED_CID = "bafkreiaabc27q6hpnrclwsa5cnjgzv3uv6zbonq7avwsn6bm2ncdi5fbjq";

    private static final long MIB = 1024 * 1024;

    @Inject
    KuboClient kubo;

    @Inject
    Instance<MetricsCheck> metrics;

    @Inject
    KuboCaRotationCheck rotation;

    @Inject
    Instance<DirectoryCheck> directories;

    interface DirectoryCheck {
        void verify(String scenario) throws Exception;
    }

    interface MetricsCheck {
        void verify(String scenario, boolean enabled);
    }

    @Override
    public int run(String... args) throws Exception {
        if (Arc.container().instance(EmbeddedIpfs.class).isAvailable()) {
            throw new IllegalStateException("The embedded IPFS node must be disabled");
        }
        // The configured swarm port must be available throughout the Kubo-only test.
        try (var listener = new ServerSocket(4001, 1, InetAddress.getByName("127.0.0.1"))) {
            String scenario = System.getenv("KUBO_TEST_EXPECT");
            if (scenario != null && scenario.startsWith("directory")) {
                directories.get().verify(scenario);
                return 0;
            }
            if ("rotation".equals(scenario)) {
                rotation.verify();
                return 0;
            }
            if ("streaming".equals(scenario)) {
                long heap = Runtime.getRuntime().maxMemory();
                if (heap > 64 * MIB) {
                    throw new IllegalStateException("Expected a heap of at most 64 MiB, got " + heap + " bytes");
                }
                System.out.printf("Kubo HTTPS streaming with heap capped at 64 MiB (reported %d bytes). "
                        + "Embedded node absent and swarm port %d available.%n", heap, listener.getLocalPort());
                verifyStreamingFile(0);
                verifyStreamingFile(96 * MIB);
                return 0;
            }

            byte[] payload = { 'I', 'P', 'F', 'S', 0, (byte) 0xff, '\n' };
            if (scenario != null && !"success".equals(scenario)) {
                if ("interrupted".equals(scenario)) {
                    verifyInterrupted();
                } else {
                    verifyFailure(scenario, payload);
                }
                verifyMetrics(scenario);
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

            verifyMetrics(scenario);
            System.out.printf("Authenticated Kubo consumer imported and read %d bytes by CID %s. "
                    + "Embedded node absent and swarm port %d available.%n", bytes, cid, listener.getLocalPort());
            return 0;
        }
    }

    private void verifyStreamingFile(long size) throws Exception {
        Path source = Files.createTempFile("kubo-https-", ".bin");
        try {
            var expectedDigest = MessageDigest.getInstance("SHA-256");
            var random = new Random(42);
            byte[] buffer = new byte[64 * 1024];
            try (var output = new DigestOutputStream(Files.newOutputStream(source), expectedDigest)) {
                for (long remaining = size; remaining > 0;) {
                    random.nextBytes(buffer);
                    int chunk = (int) Math.min(remaining, buffer.length);
                    output.write(buffer, 0, chunk);
                    remaining -= chunk;
                }
            }

            String cid;
            try (var input = Files.newInputStream(source)) {
                cid = kubo.addFile(input, false).get(65, TimeUnit.SECONDS);
            }

            var actualDigest = MessageDigest.getInstance("SHA-256");
            long bytes;
            try (var output = new DigestOutputStream(OutputStream.nullOutputStream(), actualDigest)) {
                bytes = kubo.writeFile(cid, output).get(65, TimeUnit.SECONDS);
            }
            byte[] sha256 = actualDigest.digest();
            if (bytes != size || !Arrays.equals(expectedDigest.digest(), sha256)) {
                throw new IllegalStateException("Kubo HTTPS streaming returned different content for CID " + cid);
            }
            System.out.printf("Authenticated Kubo HTTPS streaming imported and read %d bytes by CID %s (SHA-256 %s).%n",
                    bytes, cid, HexFormat.of().formatHex(sha256));
        } finally {
            Files.deleteIfExists(source);
        }
    }

    private void verifyMetrics(String scenario) {
        String mode = System.getenv("KUBO_EXPECT_METRICS");
        if (mode == null) {
            return;
        }
        boolean hasMicrometer = !"none".equals(mode);
        if (metrics.isResolvable() != hasMicrometer) {
            throw new IllegalStateException("Unexpected Micrometer checker for " + mode);
        }
        if (hasMicrometer) {
            metrics.get().verify(scenario, "enabled".equals(mode));
        } else {
            System.out.println("Kubo consumer verified without Micrometer.");
        }
    }

    private void verifyInterrupted() throws Exception {
        assertFailure("timeout", kubo.writeFile(EXPECTED_CID, OutputStream.nullOutputStream()));
        var cancelled = kubo.writeFile(EXPECTED_CID, OutputStream.nullOutputStream());
        if (!cancelled.cancel(true)) {
            throw new IllegalStateException("Kubo transfer did not cancel");
        }
        try {
            cancelled.join();
            throw new IllegalStateException("Cancelled Kubo transfer succeeded");
        } catch (CancellationException expected) {
            System.out.println("Kubo HTTPS check verified timeout and cancellation.");
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
                case "timeout" -> cause instanceof TimeoutException;
                case "unauthenticated" -> cause instanceof KuboClient.KuboRequestException error
                        && (error.statusCode() == 401 || error.statusCode() == 403);
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

    static boolean isTlsFailure(Throwable cause) {
        for (Throwable current = cause; current != null; current = current.getCause()) {
            if (current instanceof SSLException) {
                return true;
            }
        }
        return false;
    }
}
