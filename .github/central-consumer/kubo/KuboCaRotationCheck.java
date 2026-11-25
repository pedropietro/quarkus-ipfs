package org.acme;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.FilterInputStream;
import java.io.FilterOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Random;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;

import jakarta.enterprise.event.Event;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;

import io.quarkiverse.ipfs.runtime.KuboClient;
import io.quarkus.tls.CertificateUpdatedEvent;
import io.quarkus.tls.TlsConfigurationRegistry;

@Singleton
class KuboCaRotationCheck {
    private static final Path COORDINATION = Path.of("/rotation");

    @Inject
    KuboClient kubo;

    @Inject
    TlsConfigurationRegistry registry;

    @Inject
    Event<CertificateUpdatedEvent> updates;

    void verify() throws Exception {
        byte[] smallFile = { 'I', 'P', 'F', 'S', 0, (byte) 0xff, '\n' };
        String smallCid = verifyExchange(smallFile);
        byte[] payload = new byte[4 * 1024 * 1024];
        new Random(42).nextBytes(payload);
        String cid;
        try (var source = new ByteArrayInputStream(payload)) {
            cid = kubo.addFile(source, false).get(20, TimeUnit.SECONDS);
        }

        var paused = new CountDownLatch(2);
        var resume = new CountDownLatch(1);
        var downloaded = new ByteArrayOutputStream();
        try (var source = new FilterInputStream(new ByteArrayInputStream(payload)) {
            private boolean first = true;

            @Override
            public int read(byte[] buffer, int offset, int size) throws IOException {
                if (first) {
                    first = false;
                    pause(paused, resume);
                }
                return super.read(buffer, offset, size);
            }
        }; var destination = new FilterOutputStream(downloaded) {
            private boolean first = true;

            @Override
            public void write(byte[] buffer, int offset, int size) throws IOException {
                if (first) {
                    first = false;
                    pause(paused, resume);
                }
                downloaded.write(buffer, offset, size);
            }
        }) {
            var upload = kubo.addFile(source, false);
            var download = kubo.writeFile(cid, destination);
            try {
                if (!paused.await(20, TimeUnit.SECONDS) || upload.isDone() || download.isDone()) {
                    throw new IllegalStateException("Both Kubo transfers must be in flight before rotation");
                }
                signal("active");
                awaitSignal("new-trust");

                var tls = registry.get("kubo").orElseThrow();
                if (!tls.reload()) {
                    throw new IllegalStateException("Could not reload the test trust store");
                }
                // A reload for a different name must not change the Kubo client's trust.
                updates.fire(new CertificateUpdatedEvent("other", tls));
                verifyExchange(smallFile);
                updates.fire(new CertificateUpdatedEvent("kubo", tls));
                verifyRejected(smallCid, smallFile, "the old CA");
                signal("old-rejected");

                awaitSignal("new-proxy");
                verifyExchange(smallFile);
                System.out.println("The same Kubo client accepted the new CA for block/get, cat and add.");
            } finally {
                resume.countDown();
            }

            if (!cid.equals(upload.get(20, TimeUnit.SECONDS))
                    || download.get(20, TimeUnit.SECONDS) != payload.length
                    || !Arrays.equals(payload, downloaded.toByteArray())) {
                throw new IllegalStateException("Kubo CA rotation interrupted or corrupted an active transfer");
            }
        }
        var uploaded = new ByteArrayOutputStream();
        if (kubo.writeFile(cid, uploaded).get(20, TimeUnit.SECONDS) != payload.length
                || !Arrays.equals(payload, uploaded.toByteArray())) {
            throw new IllegalStateException("The upload that spanned Kubo CA rotation returned different content");
        }
        System.out.println("Kubo CA rotation preserved the in-flight 4 MiB upload and download byte for byte.");

        signal("new-accepted");
        awaitSignal("wrong-hostname");
        verifyRejected(smallCid, smallFile, "a wrong hostname signed by the new CA");
    }

    private String verifyExchange(byte[] payload) throws Exception {
        try (var source = new ByteArrayInputStream(payload)) {
            String cid = kubo.addFile(source, false).get(10, TimeUnit.SECONDS);
            var output = new ByteArrayOutputStream();
            if (!Arrays.equals(payload, kubo.getBlock(cid).get(10, TimeUnit.SECONDS))
                    || kubo.writeFile(cid, output).get(10, TimeUnit.SECONDS) != payload.length
                    || !Arrays.equals(payload, output.toByteArray())) {
                throw new IllegalStateException("Kubo returned different content during CA rotation");
            }
            return cid;
        }
    }

    private void verifyRejected(String cid, byte[] payload, String description) throws Exception {
        assertTlsFailure(kubo.getBlock(cid), description);
        assertTlsFailure(kubo.writeFile(cid, OutputStream.nullOutputStream()), description);
        try (var source = new ByteArrayInputStream(payload)) {
            assertTlsFailure(kubo.addFile(source, false), description);
        }
        System.out.println("Kubo rejected " + description + " for block/get, cat and add.");
    }

    private static void assertTlsFailure(CompletableFuture<?> operation, String description) throws Exception {
        try {
            operation.get(10, TimeUnit.SECONDS);
        } catch (ExecutionException failure) {
            if (KuboSmokeTest.isTlsFailure(failure.getCause())) {
                return;
            }
            throw new IllegalStateException("Expected TLS rejection of " + description, failure);
        }
        throw new IllegalStateException("Kubo accepted " + description);
    }

    private static void pause(CountDownLatch paused, CountDownLatch resume) throws IOException {
        paused.countDown();
        try {
            if (!resume.await(40, TimeUnit.SECONDS)) {
                throw new IOException("Timed out waiting to resume the Kubo transfer");
            }
        } catch (InterruptedException failure) {
            Thread.currentThread().interrupt();
            throw new IOException("Kubo transfer was interrupted during CA rotation", failure);
        }
    }

    private static void signal(String name) throws IOException {
        Files.createFile(COORDINATION.resolve(name));
    }

    private static void awaitSignal(String name) throws InterruptedException {
        Path marker = COORDINATION.resolve(name);
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20);
        while (!Files.exists(marker)) {
            if (System.nanoTime() >= deadline) {
                throw new IllegalStateException("Timed out waiting for CA rotation fixture: " + name);
            }
            Thread.sleep(100);
        }
    }
}
