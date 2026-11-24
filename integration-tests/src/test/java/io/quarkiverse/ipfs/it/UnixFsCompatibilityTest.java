package io.quarkiverse.ipfs.it;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;
import java.util.concurrent.CompletionException;

import jakarta.inject.Inject;

import org.junit.jupiter.api.Test;

import io.ipfs.cid.Cid;
import io.quarkiverse.ipfs.runtime.BlockService;
import io.quarkiverse.ipfs.runtime.DagService;
import io.quarkus.test.junit.QuarkusTest;

@QuarkusTest
class UnixFsCompatibilityTest {

    private static final int CHUNK_SIZE = 256 * 1024;
    // Frozen DAG-PB bytes and CIDs from the 0.2.0 encoding.
    private static final String LEGACY_FILE = "bafybeicqttevwchodivrbpgnxccfevko3lje2eaifsotdsqeu5k4jhksya";
    private static final String LEGACY_DIRECTORY = "bafybeifseu67zvop7j6dnizhjanvqelconpplaveqadpvkk4xwmc44ih3m";
    private static final String FIRST_CHUNK = "0155122031a1f9dea0169551092d05e8bf4a446228c8c3eb4c9b713c66adcb7fd53c89be";
    private static final String LAST_CHUNK = "0155122002638e28fcfa132a6f6e9ae64e7457a09145f45d4d90ac574bb552d2f2173531";

    @Inject
    BlockService blocks;

    @Inject
    DagService dag;

    @Test
    void readsIdentifiableLegacyFileAndDirectoryByTheirExistingCids() {
        byte[] bytes = UnixFsResourceTest.contents(CHUNK_SIZE + 123);
        blocks.put(Arrays.copyOfRange(bytes, 0, CHUNK_SIZE), Cid.Codec.Raw).join();
        blocks.put(Arrays.copyOfRange(bytes, CHUNK_SIZE, bytes.length), Cid.Codec.Raw).join();

        assertEquals(LEGACY_FILE, storeDag("12260a24" + FIRST_CHUNK + "12260a24" + LAST_CHUNK + "0a06080120fb8010"));
        assertEquals(LEGACY_DIRECTORY,
                storeDag("122d0a24" + FIRST_CHUNK + "12057a2e62696e122d0a24" + LAST_CHUNK + "1205612e62696e0a020801"));

        assertArrayEquals(bytes, dag.getFile(LEGACY_FILE).join());
        var streamed = new ByteArrayOutputStream();
        assertEquals((long) bytes.length, dag.writeFile(LEGACY_FILE, streamed).join());
        assertArrayEquals(bytes, streamed.toByteArray());
        assertEquals(List.of("z.bin", "a.bin"), dag.listDirectory(LEGACY_DIRECTORY).join());
        assertThrows(CompletionException.class, () -> dag.getFile(LEGACY_DIRECTORY).join());
        assertThrows(CompletionException.class, () -> dag.listDirectory(LEGACY_FILE).join());
    }

    @Test
    void readsInlineDataAndRejectsMalformedNodes() {
        String inline = storeDag("0a0b0802120568656c6c6f1805");
        assertArrayEquals("hello".getBytes(StandardCharsets.UTF_8), dag.getFile(inline).join());
        String wrongSize = storeDag("0a0b0802120568656c6c6f1806");
        String truncated = storeDag("0a0b0802");
        assertThrows(CompletionException.class, () -> dag.getFile(wrongSize).join());
        assertThrows(CompletionException.class, () -> dag.getFile(truncated).join());
    }

    @Test
    void preservesWriteFailureAndLeavesStreamOpen() {
        byte[] bytes = UnixFsResourceTest.contents(CHUNK_SIZE + 123);
        String cid = dag.addFile("test.bin", new ByteArrayInputStream(bytes)).join();
        var written = new ByteArrayOutputStream();
        var diskFull = new IOException("disk full");
        var output = new OutputStream() {
            @Override
            public void write(int value) {
                throw new AssertionError("Expected a block write");
            }

            @Override
            public void write(byte[] block, int offset, int length) throws IOException {
                if (written.size() != 0) {
                    throw diskFull;
                }
                written.write(block, offset, length);
            }

            @Override
            public void flush() {
                throw new AssertionError("The caller owns flushing");
            }

            @Override
            public void close() {
                throw new AssertionError("The caller owns closing");
            }
        };

        var failure = assertThrows(CompletionException.class, () -> dag.writeFile(cid, output).join());
        assertSame(diskFull, assertInstanceOf(UncheckedIOException.class, failure.getCause()).getCause());
        assertArrayEquals(Arrays.copyOf(bytes, CHUNK_SIZE), written.toByteArray());
    }

    private String storeDag(String hex) {
        return blocks.put(HexFormat.of().parseHex(hex), Cid.Codec.DagProtobuf).join().toString();
    }
}
