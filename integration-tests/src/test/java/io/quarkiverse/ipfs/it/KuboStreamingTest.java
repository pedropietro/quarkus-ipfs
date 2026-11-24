package io.quarkiverse.ipfs.it;

import static io.quarkiverse.ipfs.it.KuboResourceTest.MISSING;
import static io.quarkiverse.ipfs.it.KuboResourceTest.OK;
import static io.quarkiverse.ipfs.it.KuboResourceTest.PAYLOAD;
import static io.quarkiverse.ipfs.it.KuboResourceTest.SLOW;
import static io.quarkiverse.ipfs.it.KuboResourceTest.TRUNCATED;
import static io.quarkiverse.ipfs.it.KuboResourceTest.UNAUTHORIZED;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.util.NoSuchElementException;
import java.util.concurrent.CompletionException;
import java.util.concurrent.TimeoutException;

import jakarta.inject.Inject;

import org.junit.jupiter.api.Test;

import io.quarkiverse.ipfs.runtime.KuboClient;
import io.quarkus.test.common.QuarkusTestResource;
import io.quarkus.test.junit.QuarkusTest;

@QuarkusTest
@QuarkusTestResource(value = KuboResourceTest.Rpc.class, restrictToAnnotatedClass = true)
class KuboStreamingTest {
    @Inject
    KuboClient kubo;

    @Test
    void copiesBytesAndLeavesTheDestinationOpen() {
        var output = new ByteArrayOutputStream() {
            @Override
            public void close() {
                throw new AssertionError("The destination belongs to the caller");
            }
        };
        assertEquals((long) PAYLOAD.length, kubo.writeFile(OK, output).join());
        assertArrayEquals(PAYLOAD, output.toByteArray());
    }

    @Test
    void distinguishesInvalidMissingAndUnauthorizedRequests() {
        var output = new ByteArrayOutputStream();
        assertInstanceOf(IllegalArgumentException.class, failure("invalid-cid", output));
        assertInstanceOf(NoSuchElementException.class, failure(MISSING, output));
        var unauthorized = assertInstanceOf(KuboClient.KuboRequestException.class, failure(UNAUTHORIZED, output));
        assertEquals(401, unauthorized.statusCode());
        assertEquals(0, output.size());
    }

    @Test
    void propagatesDestinationFailure() {
        var error = new IOException("destination failed");
        var output = new OutputStream() {
            @Override
            public void write(int value) throws IOException {
                throw error;
            }
        };
        assertSame(error, assertInstanceOf(UncheckedIOException.class, failure(OK, output)).getCause());
    }

    @Test
    void rejectsAShortChunkedResponse() {
        var output = new ByteArrayOutputStream();
        assertInstanceOf(UncheckedIOException.class, failure(TRUNCATED, output));
        assertEquals(1, output.size());
    }

    @Test
    void timesOutWhileCopyingAStalledBody() {
        assertInstanceOf(TimeoutException.class, failure(SLOW, OutputStream.nullOutputStream()));
    }

    private Throwable failure(String cid, OutputStream output) {
        return assertThrows(CompletionException.class, () -> kubo.writeFile(cid, output).join()).getCause();
    }
}
